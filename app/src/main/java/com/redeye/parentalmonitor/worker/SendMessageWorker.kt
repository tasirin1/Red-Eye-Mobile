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

    private val messageQueue = MessageQueue(context)
    private val preferencesManager = PreferencesManager(context)

    override suspend fun doWork(): Result {
        if (!preferencesManager.isConfigured()) {
            return Result.failure()
        }

        val queue = messageQueue.getQueue()
        if (queue.isEmpty()) {
            return Result.success()
        }

        var successCount = 0
        var failCount = 0

        for (queuedMessage in queue) {
            try {
                when (sendMessage(queuedMessage.message)) {
                    SendOutcome.SENT -> {
                        messageQueue.removeMessage(queuedMessage.id)
                        successCount++
                        delay(200)
                    }
                    SendOutcome.RATE_LIMITED -> {
                        failCount++
                        return Result.retry()
                    }
                    SendOutcome.FAILED -> {
                        val retries = messageQueue.incrementRetry(queuedMessage.id)
                        if (retries >= MessageQueue.MAX_RETRIES || retries < 0) {
                            messageQueue.removeMessage(queuedMessage.id)
                        }
                        failCount++
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SendMessageWorker", "Exception processing message", e)
                failCount++
            }
        }

        return if (failCount == 0) {
            Result.success()
        } else if (runAttemptCount >= 5) {
            Result.failure()
        } else {
            Result.retry()
        }
    }

    private enum class SendOutcome { SENT, FAILED, RATE_LIMITED }

    private suspend fun sendMessage(message: String): SendOutcome {
        return try {
            val botToken = preferencesManager.botToken
            val chatId = preferencesManager.chatId
            if (botToken.isEmpty() || chatId.isEmpty()) return SendOutcome.FAILED

            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = message, parseMode = "HTML")
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.SENT
            } else if (response.code() == 429) {
                val retryAfter = NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                android.util.Log.w("SendMessageWorker", "Rate limited, retry after ${retryAfter}s")
                SendOutcome.RATE_LIMITED
            } else {
                SendOutcome.FAILED
            }
        } catch (e: Exception) {
            SendOutcome.FAILED
        }
    }
}
