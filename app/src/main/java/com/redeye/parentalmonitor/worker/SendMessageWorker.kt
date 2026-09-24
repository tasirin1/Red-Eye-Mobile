package com.redeye.parentalmonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import kotlinx.coroutines.delay

class SendMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val messageQueue = MessageQueue(context.applicationContext)
    private val preferencesManager = PreferencesManager.getInstance(context)

    override suspend fun doWork(): Result {
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
        var rateLimited = false
        var capped = false
        var processed = 0

        for (queuedMessage in queue) {
            if (processed >= 20) {
                capped = true
                break
            }
            processed++
            try {
                when (sendMessage(queuedMessage.message)) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        delay(100)
                    }
                    is SendOutcome.RateLimited -> {
                        rateLimited = true
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
                android.util.Log.e("SendMessageWorker", "Exception processing message", e)
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
        var pendingTransient = false
        if (failedIds.isNotEmpty()) {
            val dropped = try {
                messageQueue.registerFailures(failedIds)
            } catch (_: Exception) {
                emptyList()
            }
            pendingTransient = failedIds.size > dropped.size
        }

        if (rateLimited) {
            return Result.retry()
        }
        if ((pendingTransient || capped) && messageQueue.hasMessages()) {
            return Result.retry()
        }
        return Result.success()
    }

    private sealed interface SendOutcome {
        object Sent : SendOutcome
        object Failed : SendOutcome
        object RateLimited : SendOutcome
        object AuthFailed : SendOutcome
    }

    private suspend fun sendMessage(message: String): SendOutcome {
        return try {
            val botToken = preferencesManager.botToken
            val chatId = preferencesManager.chatId
            if (botToken.isEmpty() || chatId.isEmpty()) return SendOutcome.Failed

            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = message, parseMode = "HTML")
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 429) {
                android.util.Log.w("SendMessageWorker", "Rate limited, retrying with backoff")
                SendOutcome.RateLimited
            } else if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                SendOutcome.AuthFailed
            } else {
                SendOutcome.Failed
            }
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
