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
    private val preferencesManager = PreferencesManager.getInstance(context)

    override suspend fun doWork(): Result {
        if (!preferencesManager.isConfigured()) {
            return Result.failure()
        }

        val queue = messageQueue.getQueue()
        if (queue.isEmpty()) {
            return Result.success()
        }

        val sentIds = mutableListOf<String>()
        val dropIds = mutableListOf<String>()
        var rateLimitedAfter: Long = 0L

        for (queuedMessage in queue) {
            try {
                when (val outcome = sendMessage(queuedMessage.message)) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        delay(200)
                    }
                    is SendOutcome.RateLimited -> {
                        rateLimitedAfter = outcome.retryAfter
                        break
                    }
                    SendOutcome.Failed -> {
                        val retries = messageQueue.incrementRetry(queuedMessage.id)
                        if (retries >= MessageQueue.MAX_RETRIES || retries < 0) {
                            dropIds.add(queuedMessage.id)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SendMessageWorker", "Exception processing message", e)
            }
        }

        if (sentIds.isNotEmpty()) {
            messageQueue.removeMessages(sentIds)
        }
        if (dropIds.isNotEmpty()) {
            messageQueue.removeMessages(dropIds)
        }

        if (rateLimitedAfter > 0) {
            delay(rateLimitedAfter * 1000L)
            return Result.retry()
        }
        return Result.success()
    }

    private sealed interface SendOutcome {
        object Sent : SendOutcome
        object Failed : SendOutcome
        data class RateLimited(val retryAfter: Long) : SendOutcome
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
                val retryAfter = NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                android.util.Log.w("SendMessageWorker", "Rate limited, retry after ${retryAfter}s")
                SendOutcome.RateLimited(retryAfter)
            } else {
                SendOutcome.Failed
            }
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
