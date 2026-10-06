package com.redeye.parentalmonitor.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.MessageScheduler
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
        MessageScheduler.workerRunning = true
        try {
            return doWorkInternal()
        } finally {
            MessageScheduler.workerRunning = false
        }
    }

    private suspend fun doWorkInternal(): Result {
        try {
            messageQueue.tryRestorePersistent()
        } catch (_: Exception) {
        }
        if (!preferencesManager.isConfigured()) {
            try {
                if (messageQueue.getQueueSize() > 0) MessageScheduler.scheduleMessageSendNext(applicationContext, 30 * 60_000L)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            return Result.success()
        }
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, 30 * 60_000L)
            } catch (_: Exception) {
            }
            return Result.success()
        }

        val runGen = messageQueue.queueGeneration()
        val queue = messageQueue.getQueue()
        if (queue.isEmpty()) {
            return Result.success()
        }

        val sentIds = mutableListOf<String>()
        var transientMaxRetry = 0
        val failedIds = mutableListOf<String>()
        val rejectedIds = mutableListOf<String>()
        var authCode = 0
        var rateLimitedSecs = 0L
        var processed = 0
        val runToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
        val runChatId = try { preferencesManager.chatId } catch (_: Exception) { "" }
        if (runToken.isEmpty() || runChatId.isEmpty()) return Result.success()
        var credsChanged = false
        var incrementalFailed = false
        var flushedSent = 0

        var queueCleared = false
        for (queuedMessage in queue) {
            try {
                if (messageQueue.queueGeneration() != runGen) {
                    queueCleared = true
                    break
                }
            } catch (_: Exception) {
            }
            if (processed >= 20) {
                break
            }
            if (processed % 5 == 0) {
                val curToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
                val curChat = try { preferencesManager.chatId } catch (_: Exception) { "" }
                if (curToken.isEmpty() || curChat.isEmpty()) {
                    break
                }
                if (curToken != runToken || curChat != runChatId) {
                    credsChanged = true
                    break
                }
            }
            processed++
            try {
                val outcome = sendMessage(queuedMessage.message, runToken, runChatId)
                when (outcome) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        if (sentIds.size - flushedSent >= 5) {
                            try {
                                messageQueue.removeMessages(sentIds)
                                flushedSent = sentIds.size
                            } catch (_: Exception) {
                                incrementalFailed = true
                            }
                        }
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
                        try {
                            messageQueue.removeMessage(queuedMessage.id)
                        } catch (_: Exception) {
                            incrementalFailed = true
                        }
                    }
                    SendOutcome.Failed -> {
                        failedIds.add(queuedMessage.id)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val detail = (e.message ?: "").let { m -> var out = if (runToken.isNotEmpty()) m.replace(runToken, "***") else m; out = if (runChatId.isNotEmpty()) out.replace(runChatId, "***") else out; out }
                android.util.Log.e("SendMessageWorker", "Exception processing message: $detail")
                failedIds.add(queuedMessage.id)
                continue
            }
        }

        if (credsChanged) {
            if (!queueCleared && sentIds.size > flushedSent) {
                try {
                    messageQueue.removeMessages(sentIds)
                } catch (_: Exception) {
                    incrementalFailed = true
                }
            }
            try {
                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(applicationContext)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        fun credsSame(): Boolean {
            return try {
                preferencesManager.botToken == runToken && preferencesManager.chatId == runChatId
            } catch (_: Exception) {
                false
            }
        }
        if (!queueCleared && sentIds.size > flushedSent) {
            try {
                messageQueue.removeMessages(sentIds)
            } catch (_: Exception) {
                incrementalFailed = true
            }
        }
        try {
            messageQueue.flushSync()
        } catch (_: Exception) {
        }
        if (rejectedIds.isNotEmpty()) {
            if (incrementalFailed) {
                messageQueue.removeMessages(rejectedIds)
            }
            android.util.Log.w("SendMessageWorker", "Dropped ${rejectedIds.size} permanently rejected message(s) (HTTP 4xx)")
        }

        if (authCode != 0) {
            try {
                preferencesManager.credentialError = authCode.toString()
                preferencesManager.credentialErrorAt = System.currentTimeMillis()
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
        val credsSameNow = try {
            preferencesManager.botToken == runToken && preferencesManager.chatId == runChatId
        } catch (_: Exception) {
            false
        }
        if (sentIds.isNotEmpty() && credsSameNow) {
            try {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
            } catch (_: Exception) {
            }
        }
        val dropParts = mutableListOf<String>()
        var dropTotal = 0
        var dropSample = ""
        if (!queueCleared && credsSame()) {
            try {
                val expired = messageQueue.takeExpiredDrops()
                if (expired > 0L) {
                    dropTotal += expired.toInt()
                    dropParts.add("$expired expired (older than 7 days)")
                }
                val overflow = messageQueue.takeOverflowDrops()
                if (overflow > 0L) {
                    dropTotal += overflow.toInt()
                    dropParts.add("$overflow oldest queued (queue full offline)")
                }
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && failedIds.isNotEmpty() && !credsChanged && credsSame()) {
            val online = try {
                NetworkUtils.isNetworkAvailable(applicationContext)
            } catch (_: Exception) {
                true
            }
            if (online) {
                try {
                    val droppedT = messageQueue.registerTransientFailures(failedIds)
                    val dropped = droppedT.first
                    transientMaxRetry = maxOf(transientMaxRetry, droppedT.second)
                    if (dropped.isNotEmpty() && credsSame()) {
                        android.util.Log.w("SendMessageWorker", "Dropped ${dropped.size} message(s) after max retries")
                        dropTotal += dropped.size
                        dropParts.add("${dropped.size} after max retries")
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (!queueCleared && rejectedIds.isNotEmpty() && credsSame()) {
            try {
                dropSample = try {
                    queue.firstOrNull { it.id in rejectedIds }?.message.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                dropTotal += rejectedIds.size
                dropParts.add("${rejectedIds.size} rejected (HTTP 4xx)")
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && dropTotal > 0 && credsSame()) {
            try {
                sendDropNotice(dropTotal, runToken, runChatId, dropSample, dropParts.joinToString("; "))
            } catch (_: Exception) {
            }
        }
        if (messageQueue.getQueueSize() > 0) {
            try {
                val backoffMs = if (failedIds.isNotEmpty() && !queueCleared) (60_000L shl transientMaxRetry.coerceIn(0, 3)).coerceAtMost(900_000L) else 0L
                MessageScheduler.scheduleMessageSendNext(applicationContext, backoffMs)
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


    private suspend fun sendPlainFallback(message: String, botToken: String, chatId: String): SendOutcome {
        return try {
            val plain = message.replace(Html.tagStripRegex, "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = plain, parseMode = null)
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 429) {
                val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(errBody)
                } catch (_: Exception) {
                    5L
                }
                SendOutcome.RateLimited(retryAfterSecs)
            } else if (response.code() == 401 || response.code() == 403) {
                SendOutcome.AuthFailed(response.code())
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else if (response.code() == 400) {
                val body400 = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val chatGone = try { NetworkUtils.isChatMissing(body400) } catch (_: Exception) { false }
                if (chatGone) {
                    if (adoptMigratedChat(body400, chatId)) SendOutcome.Failed else SendOutcome.AuthFailed(response.code())
                }
                else if (NetworkUtils.isRightsLimited(body400)) SendOutcome.Failed
                else SendOutcome.Rejected
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
        return com.redeye.parentalmonitor.utils.TextChunk.safeCut(text, max)
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
                val body400 = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val chatGone = try { NetworkUtils.isChatMissing(body400) } catch (_: Exception) { false }
                if (chatGone) {
                    if (adoptMigratedChat(body400, chatId)) SendOutcome.Failed else SendOutcome.AuthFailed(response.code())
                }
                else if (NetworkUtils.isRightsLimited(body400)) SendOutcome.Failed
                else sendPlainFallback(chunk, botToken, chatId)
            } else if (response.code() == 429) {
                val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(errBody)
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
        var failedKept = false
        var remainderQueued = false
        var rejected = 0
        for ((idx, part) in parts.withIndex()) {
            when (val outcome = sendSingleChunk(part, botToken, chatId)) {
                is SendOutcome.Sent -> {
                    delay(500)
                }
                is SendOutcome.RateLimited -> {
                    if (idx > 0 && !remainderQueued) {
                        try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""))
                            MessageScheduler.scheduleMessageSendNext(applicationContext, outcome.retryAfterSecs * 1000L)
                            remainderQueued = true
                        } catch (_: Exception) {
                        }
                    }
                    if (remainderQueued) break
                    rateAfter = outcome.retryAfterSecs
                    break
                }
                is SendOutcome.AuthFailed -> {
                    if (idx > 0 && !remainderQueued) {
                        try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""))
                            MessageScheduler.scheduleMessageSend(applicationContext)
                            preferencesManager.credentialError = outcome.code.toString()
                            preferencesManager.credentialErrorAt = System.currentTimeMillis()
                            remainderQueued = true
                        } catch (_: Exception) {
                        }
                    }
                    if (remainderQueued) break
                    authCode = outcome.code
                    break
                }
                SendOutcome.Failed -> {
                    failed++
                    if (idx > 0 && !failedKept) {
                        try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""))
                            MessageScheduler.scheduleMessageSend(applicationContext)
                            failedKept = true
                        } catch (_: Exception) {
                        }
                    }
                    break
                }
                SendOutcome.Rejected -> {
                    rejected++
                    break
                }
            }
        }
        if (remainderQueued) return SendOutcome.Sent
        if (authCode != 0) return SendOutcome.AuthFailed(authCode)
        if (rateAfter > 0L) return SendOutcome.RateLimited(rateAfter)
        if (failed > 0) return if (failedKept) SendOutcome.Sent else SendOutcome.Failed
        if (rejected > 0) return SendOutcome.Rejected
        return SendOutcome.Sent
    }

    private suspend fun sendDropNotice(count: Int, botToken: String, chatId: String, sample: String = "", detail: String = "") {
        val clean = try {
            sample.replace(Html.tagStripRegex, "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim().take(120)
        } catch (_: Exception) {
            ""
        }
        val reason = detail.ifEmpty { "after max retries" }
        val text = if (clean.isEmpty()) {
            "⚠️ $count queued message(s) dropped ($reason)."
        } else {
            "⚠️ $count queued message(s) dropped ($reason). Sample: $clean"
        }
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = text, parseMode = null)
            )
            if (response.isSuccessful && response.body()?.ok == true) return
        } catch (_: Exception) {
        }
        if (messageQueue.getQueueSize() >= 90) return
        try {
            messageQueue.addMessage(text, true)
            MessageScheduler.scheduleMessageSend(applicationContext)
        } catch (_: Exception) {
        }
    }

    private fun adoptMigratedChat(body: String?, currentChatId: String): Boolean {
        val migrated = NetworkUtils.extractMigratedChatId(body) ?: return false
        val stored = try { preferencesManager.chatId } catch (_: Exception) { currentChatId }
        if (migrated == stored) {
            try {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
            } catch (_: Exception) {
            }
            return true
        }
        if (migrated == currentChatId) return false
        return try {
            preferencesManager.chatId = migrated
            preferencesManager.credentialError = ""
            preferencesManager.credentialErrorAt = 0L
            try {
                messageQueue.addMessage("\u267B\uFE0F Group upgraded to supergroup \u2014 chat ID updated automatically.", true)
                MessageScheduler.scheduleMessageSend(applicationContext)
            } catch (_: Exception) {
            }
            true
        } catch (_: Exception) {
            false
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
            } else if (response.code() == 400 && message.length > 4000) {
                val body400 = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val chatGone = try { NetworkUtils.isChatMissing(body400) } catch (_: Exception) { false }
                if (chatGone) {
                    if (adoptMigratedChat(body400, chatId)) SendOutcome.Failed else SendOutcome.AuthFailed(response.code())
                }
                else if (NetworkUtils.isRightsLimited(body400)) SendOutcome.Failed
                else sendChunked(message, botToken, chatId)
            } else if (response.code() == 400) {
                val body400 = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val chatGone = try { NetworkUtils.isChatMissing(body400) } catch (_: Exception) { false }
                if (chatGone) {
                    if (adoptMigratedChat(body400, chatId)) SendOutcome.Failed else SendOutcome.AuthFailed(response.code())
                }
                else if (NetworkUtils.isRightsLimited(body400)) SendOutcome.Failed
                else sendPlainFallback(message, botToken, chatId)
            } else if (response.code() == 429) {
                val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                val retryAfterSecs = try {
                    NetworkUtils.parseRetryAfter(errBody)
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
