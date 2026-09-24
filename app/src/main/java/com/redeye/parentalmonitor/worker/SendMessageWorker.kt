package com.redeye.parentalmonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.NetworkUtils
import kotlinx.coroutines.delay

class SendMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val messageQueue = MessageQueue(context.applicationContext)
    private val preferencesManager: PreferencesManager by lazy {
        try { PreferencesManager.refreshInstance(context.applicationContext) } catch (_: Exception) { }
        PreferencesManager.getInstance(context.applicationContext)
    }

    override suspend fun doWork(): Result {
        try {
            messageQueue.tryRestorePersistent()
        } catch (_: Exception) {
        }
        if (!preferencesManager.isConfigured()) {
            return Result.success()
        }

        val queue = messageQueue.getQueue()
        if (queue.isEmpty()) {
            return Result.success()
        }

        val sentIds = mutableListOf<String>()
        val failedIds = mutableListOf<String>()
        val authFailedIds = mutableListOf<String>()
        var rateLimitedSecs = 0L
        var processed = 0
        val runToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
        val runChatId = try { preferencesManager.chatId } catch (_: Exception) { "" }
        if (runToken.isEmpty() || runChatId.isEmpty()) return Result.success()

        for (queuedMessage in queue) {
            if (processed >= 20) {
                break
            }
            processed++
            try {
                val outcome = sendMessage(queuedMessage.message, runToken, runChatId)
                when (outcome) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        delay(100)
                    }
                    is SendOutcome.RateLimited -> {
                        rateLimitedSecs = outcome.retryAfterSecs
                        break
                    }
                    is SendOutcome.AuthFailed -> {
                        authFailedIds.add(queuedMessage.id)
                    }
                    SendOutcome.Failed -> {
                        failedIds.add(queuedMessage.id)
                    }
                }
            } catch (e: Exception) {
                val detail = (e.message ?: "").let { m -> if (runToken.isNotEmpty()) m.replace(runToken, "***") else m }
                android.util.Log.e("SendMessageWorker", "Exception processing message: $detail", e)
                failedIds.add(queuedMessage.id)
            }
        }

        if (sentIds.isNotEmpty()) {
            messageQueue.removeMessages(sentIds)
            try {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
            } catch (_: Exception) {
            }
        }
        if (authFailedIds.isNotEmpty()) {
            messageQueue.removeMessages(authFailedIds)
            try {
                preferencesManager.credentialError = "401"
                preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
            android.util.Log.e("SendMessageWorker", "Auth rejected, dropped ${authFailedIds.size} message(s) without retry")
        }
        if (failedIds.isNotEmpty()) {
            try {
                messageQueue.registerFailures(failedIds)
            } catch (_: Exception) {
            }
        }

        if (rateLimitedSecs > 0) {
            try {
                delay(rateLimitedSecs.coerceIn(1, 60) * 1000L)
            } catch (_: Exception) {
            }
            return Result.retry()
        }
        if (messageQueue.hasMessages() && (sentIds.isNotEmpty() || runAttemptCount < 3)) {
            return Result.retry()
        }
        return Result.success()
    }

    private sealed interface SendOutcome {
        object Sent : SendOutcome
        object Failed : SendOutcome
        class RateLimited(val retryAfterSecs: Long) : SendOutcome
        object AuthFailed : SendOutcome
    }

    private suspend fun sendMessage(message: String, botToken: String, chatId: String): SendOutcome {
        return try {
            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = message, parseMode = "HTML")
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 429) {
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
                android.util.Log.w("SendMessageWorker", "Rate limited, retrying after ${retryAfterSecs}s")
                SendOutcome.RateLimited(retryAfterSecs)
            } else if (response.code() == 401 || response.code() == 403) {
                SendOutcome.AuthFailed
            } else {
                SendOutcome.Failed
            }
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
