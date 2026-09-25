package com.redeye.parentalmonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.NetworkUtils
import kotlinx.coroutines.delay

private val tagStripRegex = Regex("<[^>]*>")

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

        if (rateLimitedSecs > 0) {
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, rateLimitedSecs * 1000L)
            } catch (_: Exception) {
            }
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

        if (rejectedIds.isNotEmpty()) {
            try {
                val sample = try {
                    queue.firstOrNull { it.id in rejectedIds }?.message.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                sendDropNotice(rejectedIds.size, runToken, runChatId, sample)
            } catch (_: Exception) {
            }
        }
        if (messageQueue.getQueueSize() > 0) {
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext)
            } catch (_: Exception) {
            }
            return Result.success()
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

    private suspend fun sendPlainFallback(message: String, botToken: String, chatId: String): SendOutcome {
        return try {
            val plain = message.replace(tagStripRegex, "")
            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = plain, parseMode = null)
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 429) {
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
                SendOutcome.RateLimited(retryAfterSecs)
            } else if (response.code() == 401 || response.code() == 403) {
                SendOutcome.AuthFailed(response.code())
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                SendOutcome.Rejected
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }

    private fun splitChunk(text: String, max: Int): Int {
        if (text.length <= max) return text.length
        var cut = max
        if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
        val amp = text.lastIndexOf('&', cut - 1)
        if (amp >= 0 && amp > cut - 12) {
            val semi = text.indexOf(';', amp)
            if (semi < 0 || semi >= cut) {
                val entity = text.substring(amp, cut)
                if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) cut = amp
            }
        }
        val tag = text.lastIndexOf('<', cut - 1)
        if (tag >= 0 && text.indexOf('>', tag) >= cut) cut = tag
        if (cut <= 0) cut = max
        return cut
    }

    private suspend fun sendSingleChunk(chunk: String, botToken: String, chatId: String): SendOutcome {
        return try {
            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = chunk, parseMode = "HTML")
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 400) {
                sendPlainFallback(chunk, botToken, chatId)
            } else if (response.code() == 429) {
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
                SendOutcome.RateLimited(retryAfterSecs)
            } else if (response.code() == 401 || response.code() == 403) {
                SendOutcome.AuthFailed(response.code())
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                SendOutcome.Rejected
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SendOutcome.Failed
        }
    }

    private suspend fun sendChunked(message: String, botToken: String, chatId: String): SendOutcome {
        val parts = mutableListOf<String>()
        var rest = message
        while (rest.length > 4000) {
            val cut = splitChunk(rest, 4000)
            parts.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        parts.add(rest)
        var rateAfter = 0L
        var authCode = 0
        var failed = 0
        var rejected = 0
        for (part in parts) {
            when (val outcome = sendSingleChunk(part, botToken, chatId)) {
                is SendOutcome.Sent -> {
                    delay(500)
                }
                is SendOutcome.RateLimited -> {
                    if (rateAfter == 0L) rateAfter = outcome.retryAfterSecs
                }
                is SendOutcome.AuthFailed -> {
                    if (authCode == 0) authCode = outcome.code
                }
                SendOutcome.Failed -> failed++
                SendOutcome.Rejected -> rejected++
            }
        }
        if (authCode != 0) return SendOutcome.AuthFailed(authCode)
        if (rateAfter > 0L) return SendOutcome.RateLimited(rateAfter)
        if (failed > 0) return SendOutcome.Failed
        if (rejected > 0) return SendOutcome.Rejected
        return SendOutcome.Sent
    }

    private suspend fun sendDropNotice(count: Int, botToken: String, chatId: String, sample: String = "") {
        try {
            val clean = try {
                sample.replace(tagStripRegex, "").trim().take(120)
            } catch (_: Exception) {
                ""
            }
            val text = if (clean.isEmpty()) {
                "⚠️ $count queued message(s) dropped after max retries."
            } else {
                "⚠️ $count queued message(s) dropped (rejected). Sample: $clean"
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = text)
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
            } else if (response.code() == 400 && message.length > 4096) {
                sendChunked(message, botToken, chatId)
            } else if (response.code() == 400) {
                sendPlainFallback(message, botToken, chatId)
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
