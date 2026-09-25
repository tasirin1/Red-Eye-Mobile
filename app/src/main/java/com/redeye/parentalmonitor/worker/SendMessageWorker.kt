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

    private val messageQueue = MessageQueue.getInstance(context.applicationContext)
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
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            return Result.success()
        }
        if (authBlocked()) {
            return Result.success()
        }

        val queue = messageQueue.getQueue()
        if (queue.isEmpty()) {
            return Result.success()
        }

        val sentIds = mutableListOf<String>()
        val failedIds = mutableListOf<String>()
        val rejectedIds = mutableListOf<String>()
        var authCode = 0
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
                        authCode = outcome.code
                        break
                    }
                    is SendOutcome.Rejected -> {
                        rejectedIds.add(queuedMessage.id)
                    }
                    SendOutcome.Failed -> {
                        failedIds.add(queuedMessage.id)
                    }
                }
            } catch (e: Exception) {
                val detail = (e.message ?: "").let { m -> if (runToken.isNotEmpty()) m.replace(runToken, "***") else m }
                android.util.Log.e("SendMessageWorker", "Exception processing message: $detail")
                failedIds.add(queuedMessage.id)
            }
        }

        if (sentIds.isNotEmpty()) {
            messageQueue.removeMessages(sentIds)
        }
        if (rejectedIds.isNotEmpty()) {
            messageQueue.removeMessages(rejectedIds)
            android.util.Log.w("SendMessageWorker", "Dropped ${rejectedIds.size} permanently rejected message(s) (HTTP 4xx)")
        }

        if (authCode != 0) {
            try {
                preferencesManager.credentialError = authCode.toString()
                preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
            android.util.Log.e("SendMessageWorker", "Auth rejected ($authCode), keeping queued messages until credentials are fixed")
            return Result.success()
        }

        if (sentIds.isNotEmpty()) {
            try {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
            } catch (_: Exception) {
            }
        }
        if (failedIds.isNotEmpty()) {
            try {
                val dropped = messageQueue.registerFailures(failedIds)
                if (dropped.isNotEmpty()) {
                    android.util.Log.w("SendMessageWorker", "Dropped ${dropped.size} message(s) after max retries")
                    sendDropNotice(dropped.size, runToken, runChatId)
                }
            } catch (_: Exception) {
            }
        }

        if (rateLimitedSecs > 0) {
            delay(rateLimitedSecs * 1000L)
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
        class AuthFailed(val code: Int) : SendOutcome
        object Rejected : SendOutcome
    }

    private fun authBlocked(): Boolean {
        return try {
            val err = preferencesManager.credentialError
            if (err.isEmpty()) return false
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < preferencesManager.credentialErrorAt) {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
                return false
            }
            now - preferencesManager.credentialErrorAt < 30 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun sendDropNotice(count: Int, botToken: String, chatId: String) {
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = "⚠️ $count queued message(s) dropped after max retries.")
            )
        } catch (_: Exception) {
        }
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
                SendOutcome.AuthFailed(response.code())
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                SendOutcome.Rejected
            }
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
