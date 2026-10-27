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

    companion object {
        private val activeRuns = java.util.concurrent.atomic.AtomicInteger(0)

        fun isRunning(): Boolean = activeRuns.get() > 0
    }

    private val messageQueue: MessageQueue by lazy { MessageQueue.getInstance(applicationContext) }
    private val preferencesManager: PreferencesManager by lazy {
        try { PreferencesManager.refreshInstance(context.applicationContext) } catch (_: Exception) { }
        PreferencesManager.getInstance(context.applicationContext)
    }

    override suspend fun doWork(): Result {
        activeRuns.incrementAndGet()
        try {
            return try {
                doWorkInternal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    MessageScheduler.scheduleMessageSendNext(applicationContext, 60_000L)
                } catch (_: Exception) {
                }
                Result.success()
            }
        } finally {
            try {
                activeRuns.updateAndGet { if (it > 0) it - 1 else 0 }
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.onSendRunFinished()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun doWorkInternal(): Result {
        guardPreconditions()?.let { return it }

        val runGen = messageQueue.queueGeneration()
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
        var credsChanged = false
        var flushedSent = 0

        var queueCleared = false
        val runDeadlineAt = android.os.SystemClock.elapsedRealtime() + 8 * 60_000L
        for (queuedMessage in queue) {
            try {
                if (messageQueue.queueGeneration() != runGen) {
                    queueCleared = true
                    break
                }
            } catch (_: Exception) {
            }
            if (preferencesManager.accessPin.isNotEmpty() && preferencesManager.ownerUserId == 0L) {
                break
            }
            if (processed >= 20 || android.os.SystemClock.elapsedRealtime() >= runDeadlineAt) {
                break
            }
            val curToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
            val curChat = try { preferencesManager.chatId } catch (_: Exception) { "" }
            if (curToken.isEmpty() || curChat.isEmpty()) {
                break
            }
            if (curToken != runToken || curChat != runChatId) {
                credsChanged = true
                break
            }
            processed++
            try {
                val outcome = sendMessage(queuedMessage.message, runToken, runChatId, queuedMessage.retryCount, queuedMessage.id, runDeadlineAt)
                when (outcome) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        noteSendOk()
                        if (sentIds.size - flushedSent >= 10) {
                            try {
                                if (messageQueue.removeMessages(sentIds)) {
                                    flushedSent = sentIds.size
                                }
                            } catch (_: Exception) {
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
                    SendOutcome.Rejected -> {
                        rejectedIds.add(queuedMessage.id)
                    }
                    SendOutcome.Failed -> {
                        failedIds.add(queuedMessage.id)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (sentIds.size > flushedSent) {
                    try {
                        messageQueue.removeMessages(sentIds)
                        messageQueue.flushSync()
                    } catch (_: Exception) {
                    }
                }
                throw e
            } catch (e: Exception) {
                val detail = (e.message ?: "").let { m ->
                    var out = if (runToken.isNotEmpty()) m.replace(runToken, "***") else m
                    out = if (runChatId.isNotEmpty()) out.replace(runChatId, "***") else out
                    out
                }
                android.util.Log.e("SendMessageWorker", "Exception processing message: $detail")
                failedIds.add(queuedMessage.id)
                continue
            }
        }

        if (credsChanged) {
            if (!queueCleared && sentIds.size > flushedSent) {
                try {
                    messageQueue.removeMessages(sentIds)
                    messageQueue.flushSync()
                } catch (_: Exception) {
                }
            }
            try {
                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(applicationContext)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        if (!queueCleared && sentIds.size > flushedSent) {
            try {
                messageQueue.removeMessages(sentIds)
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && rejectedIds.isNotEmpty() && credsSame(runToken, runChatId)) {
            try {
                messageQueue.removeMessages(rejectedIds)
            } catch (_: Exception) {
            }
            android.util.Log.w("SendMessageWorker", "Dropped ${rejectedIds.size} permanently rejected message(s) (HTTP 4xx)")
        } else if (rejectedIds.isNotEmpty()) {
            android.util.Log.w("SendMessageWorker", "Keeping ${rejectedIds.size} rejected message(s) for retry under fresh credentials")
        }
        try {
            messageQueue.flushSync()
        } catch (_: Exception) {
        }

        handleAuthOrRate(authCode, rateLimitedSecs, failedIds, queueCleared, runToken, runChatId)?.let { return it }
        val credsSameNow = credsSame(runToken, runChatId)
        if (sentIds.isNotEmpty() && credsSameNow) {
            preferencesManager.setCredentialErrorIfCredentialsMatch(runToken, runChatId, "", 0L)
        }
        val transientMaxRetry = reportDrops(failedIds, rejectedIds, queueCleared, queue, runToken, runChatId)
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

    private fun guardPreconditions(): Result? {
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
        if (preferencesManager.accessPin.isNotEmpty() && preferencesManager.ownerUserId == 0L) {
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
        val rateRemain = NetworkUtils.rateLimitedRemainMs()
        if (rateRemain > 0L) {
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, rateRemain)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        return null
    }

    private fun handleAuthOrRate(
        authCode: Int,
        rateLimitedSecs: Long,
        failedIds: List<String>,
        queueCleared: Boolean,
        runToken: String,
        runChatId: String
    ): Result? {
        var authDropped = 0
        if ((authCode != 0 || rateLimitedSecs > 0) && !queueCleared && failedIds.isNotEmpty() && credsSame(runToken, runChatId)) {
            try {
                if (NetworkUtils.isNetworkAvailable(applicationContext)) {
                    authDropped = messageQueue.registerTransientFailures(failedIds).first.size
                }
            } catch (_: Exception) {
            }
        }
        if (authCode != 0) {
            val marked = preferencesManager.setCredentialErrorIfCredentialsMatch(
                runToken,
                runChatId,
                authCode.toString(),
                System.currentTimeMillis()
            )
            if (!marked) {
                MessageScheduler.scheduleMessageSend(applicationContext)
                return Result.success()
            }
            android.util.Log.e("SendMessageWorker", "Auth rejected ($authCode), keeping queued messages until credentials are fixed")
            if (authDropped > 0 && !queueCleared && credsSame(runToken, runChatId)) {
                try {
                    messageQueue.addMessage("⚠️ $authDropped pesan dalam antrean dibuang (sudah mentok percobaan).", true)
                    MessageScheduler.scheduleMessageSend(applicationContext)
                } catch (_: Exception) {
                }
            }
            return Result.success()
        }

        if (rateLimitedSecs > 0) {
            NetworkUtils.noteRateLimited(rateLimitedSecs)
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, rateLimitedSecs * 1000L)
            } catch (_: Exception) {
            }
            if (authDropped > 0 && !queueCleared && credsSame(runToken, runChatId)) {
                try {
                    messageQueue.addMessage("⚠️ $authDropped pesan dalam antrean dibuang (sudah mentok percobaan).", true)
                    MessageScheduler.scheduleMessageSend(applicationContext)
                } catch (_: Exception) {
                }
            }
            return Result.success()
        }
        return null
    }

    private suspend fun reportDrops(
        failedIds: List<String>,
        rejectedIds: List<String>,
        queueCleared: Boolean,
        queue: List<com.redeye.parentalmonitor.data.models.QueuedMessage>,
        runToken: String,
        runChatId: String
    ): Int {
        var transientMaxRetry = 0
        val dropParts = mutableListOf<String>()
        var dropTotal = 0
        var dropSample = ""
        if (!queueCleared && credsSame(runToken, runChatId)) {
            try {
                val expired = messageQueue.takeExpiredDrops()
                if (expired > 0L) {
                    dropTotal += expired.toInt()
                    dropParts.add("$expired kedaluwarsa (lebih dari 7 hari)")
                }
                val overflow = messageQueue.takeOverflowDrops()
                if (overflow > 0L) {
                    dropTotal += overflow.toInt()
                    val prio = try { messageQueue.takePriorityDrops() } catch (_: Exception) { 0L }
                    dropParts.add("$overflow paling lama di antrean (antrean penuh saat offline)" + if (prio > 0L) ", termasuk $prio prioritas" else "")
                }
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && failedIds.isNotEmpty() && credsSame(runToken, runChatId)) {
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
                    if (dropped.isNotEmpty() && credsSame(runToken, runChatId)) {
                        android.util.Log.w("SendMessageWorker", "Dropped ${dropped.size} message(s) after max retries")
                        dropTotal += dropped.size
                        dropParts.add("${dropped.size} setelah percobaan maksimal")
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (!queueCleared && rejectedIds.isNotEmpty() && credsSame(runToken, runChatId)) {
            try {
                dropSample = try {
                    queue.firstOrNull { it.id in rejectedIds }?.message.orEmpty()
                } catch (_: Exception) {
                    ""
                }
                dropTotal += rejectedIds.size
                dropParts.add("${rejectedIds.size} ditolak (HTTP 4xx)")
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && dropTotal > 0 && credsSame(runToken, runChatId)) {
            try {
                sendDropNotice(dropTotal, runToken, runChatId, dropSample, dropParts.joinToString("; "))
            } catch (_: Exception) {
            }
        }
        return transientMaxRetry
    }

    private fun credsSame(token: String, chatId: String): Boolean {
        return try {
            preferencesManager.botToken == token && preferencesManager.chatId == chatId
        } catch (_: Exception) {
            false
        }
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
            val plain = Html.unescape(message)
            val url = sendMessageUrl(botToken)
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = plain, parseMode = null)
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 429) {
                rateLimited(response)
            } else if (response.code() == 401) {
                SendOutcome.AuthFailed(401)
            } else if (response.code() == 403) {
                classify403(response, chatId)
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else if (response.code() == 400) {
                classify400(response, chatId) ?: SendOutcome.Rejected
            } else {
                SendOutcome.Failed
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
            val url = sendMessageUrl(botToken)
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = chunk, parseMode = "HTML")
            )
            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 400) {
                classify400(response, chatId) ?: sendPlainFallback(chunk, botToken, chatId)
            } else if (response.code() == 429) {
                rateLimited(response)
            } else if (response.code() == 401) {
                SendOutcome.AuthFailed(401)
            } else if (response.code() == 403) {
                classify403(response, chatId)
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                SendOutcome.Failed
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SendOutcome.Failed
        }
    }

    private suspend fun sendChunked(message: String, botToken: String, chatId: String, retryCount: Int = 0, origId: String? = null, deadlineAt: Long = Long.MAX_VALUE): SendOutcome {
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
            if (android.os.SystemClock.elapsedRealtime() >= deadlineAt) {
                if (idx == 0) return SendOutcome.Failed
                val queued = try {
                    messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""), retryCount = retryCount + 1)
                } catch (_: Exception) {
                    false
                }
                if (!queued) return SendOutcome.Failed
                try {
                    if (origId != null) messageQueue.removeMessages(listOf(origId))
                } catch (_: Exception) {
                }
                try {
                    MessageScheduler.scheduleMessageSend(applicationContext)
                } catch (_: Exception) {
                }
                return SendOutcome.Sent
            }
            when (val outcome = sendSingleChunk(part, botToken, chatId)) {
                is SendOutcome.Sent -> {
                    noteSendOk()
                    try {
                        delay(500)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        if (idx + 1 < parts.size) {
                            try {
                                messageQueue.addMessage(parts.subList(idx + 1, parts.size).joinToString(""), retryCount = retryCount + 1)
                            } catch (_: Exception) {
                            }
                            try {
                                if (origId != null) messageQueue.removeMessages(listOf(origId))
                            } catch (_: Exception) {
                            }
                            try {
                                MessageScheduler.scheduleMessageSend(applicationContext)
                            } catch (_: Exception) {
                            }
                        } else {
                            try {
                                if (origId != null) messageQueue.removeMessages(listOf(origId))
                            } catch (_: Exception) {
                            }
                        }
                        throw e
                    }
                }
                is SendOutcome.RateLimited -> {
                    if (idx > 0 && !remainderQueued) {
                        remainderQueued = try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""), retryCount = retryCount + 1)
                        } catch (_: Exception) {
                            false
                        }
                        if (remainderQueued) {
                            try {
                                MessageScheduler.scheduleMessageSendNext(applicationContext, outcome.retryAfterSecs * 1000L)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (remainderQueued) {
                        try {
                            NetworkUtils.noteRateLimited(outcome.retryAfterSecs)
                        } catch (_: Exception) {
                        }
                        break
                    }
                    rateAfter = outcome.retryAfterSecs
                    break
                }
                is SendOutcome.AuthFailed -> {
                    if (idx > 0 && !remainderQueued) {
                        remainderQueued = try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""), retryCount = retryCount + 1)
                        } catch (_: Exception) {
                            false
                        }
                        if (remainderQueued) {
                            try {
                                MessageScheduler.scheduleMessageSend(applicationContext)
                                preferencesManager.setCredentialErrorIfCredentialsMatch(
                                    botToken,
                                    chatId,
                                    outcome.code.toString(),
                                    System.currentTimeMillis()
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (remainderQueued) break
                    authCode = outcome.code
                    break
                }
                SendOutcome.Failed -> {
                    failed++
                    if (idx > 0 && !failedKept) {
                        failedKept = try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""), retryCount = retryCount + 1)
                        } catch (_: Exception) {
                            false
                        }
                        if (failedKept) {
                            try {
                                MessageScheduler.scheduleMessageSend(applicationContext)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    break
                }
                SendOutcome.Rejected -> {
                    rejected++
                    if (idx > 0 && !failedKept) {
                        failedKept = try {
                            messageQueue.addMessage(parts.subList(idx, parts.size).joinToString(""), retryCount = retryCount + 1)
                        } catch (_: Exception) {
                            false
                        }
                        if (failedKept) {
                            try {
                                MessageScheduler.scheduleMessageSend(applicationContext)
                            } catch (_: Exception) {
                            }
                        }
                    }
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
            Html.unescape(sample).trim().take(120)
        } catch (_: Exception) {
            ""
        }
        val reason = detail.ifEmpty { "setelah percobaan maksimal" }
        val text = if (clean.isEmpty()) {
            "⚠️ $count pesan dalam antrean dibuang ($reason). Bisa ada celah di riwayat SMS/panggilan; cek ulang dengan /lastsms, /lastcalls, atau /history."
        } else {
            "⚠️ $count pesan dalam antrean dibuang ($reason). Contoh: $clean"
        }
        try {
            val url = sendMessageUrl(botToken)
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = text, parseMode = null)
            )
            if (response.isSuccessful && response.body()?.ok == true) return
        } catch (_: Exception) {
        }
        try {
            messageQueue.addMessage(text, true)
            MessageScheduler.scheduleMessageSend(applicationContext)
        } catch (_: Exception) {
        }
    }

    private fun adoptMigratedChat(body: String?, currentChatId: String): Boolean {
        return NetworkUtils.adoptMigratedChat(
            preferencesManager,
            body,
            currentChatId,
            refresh = { },
            announce = {
                try {
                    messageQueue.addMessage(NetworkUtils.MIGRATED_CHAT_NOTICE, true)
                    MessageScheduler.scheduleMessageSend(applicationContext)
                } catch (_: Exception) {
                }
            }
        )
    }

    private fun sendMessageUrl(botToken: String): String = "https://api.telegram.org/bot${botToken}/sendMessage"

    private fun rateLimited(response: retrofit2.Response<*>): SendOutcome.RateLimited {
        val errBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
        val retryAfterSecs = try {
            NetworkUtils.parseRetryAfter(errBody)
        } catch (_: Exception) {
            5L
        }
        android.util.Log.w("SendMessageWorker", "Rate limited, retrying after ${retryAfterSecs}s")
        return SendOutcome.RateLimited(retryAfterSecs)
    }

    private fun classify400(response: retrofit2.Response<*>, chatId: String): SendOutcome? {
        val body400 = try { response.errorBody()?.string() } catch (_: Exception) { null }
        val chatGone = try { NetworkUtils.isChatMissing(body400) } catch (_: Exception) { false }
        if (chatGone) {
            return if (adoptMigratedChat(body400, chatId)) SendOutcome.Failed else SendOutcome.AuthFailed(response.code())
        }
        if (NetworkUtils.isRightsLimited(body400)) return SendOutcome.Failed
        return null
    }

    private fun classify403(response: retrofit2.Response<*>, chatId: String): SendOutcome {
        val body = try { response.errorBody()?.string() } catch (_: Exception) { null }
        val adopted = try {
            NetworkUtils.isChatMissing(body) && adoptMigratedChat(body, chatId)
        } catch (_: Exception) {
            false
        }
        return if (adopted) SendOutcome.Failed else SendOutcome.AuthFailed(403)
    }

    @Volatile
    private var lastSendOkNoteAt = 0L
    private var healStageCleared = false

    private fun noteSendOk() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSendOkNoteAt >= 30_000L) {
            lastSendOkNoteAt = now
            try {
                preferencesManager.setLastSendOkMarker(now)
            } catch (_: Exception) {
            }
        }
        if (!healStageCleared) {
            try {
                if (preferencesManager.silentHealStage != 0) preferencesManager.silentHealStage = 0
                healStageCleared = true
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendMessage(message: String, botToken: String, chatId: String, retryCount: Int = 0, origId: String? = null, deadlineAt: Long = Long.MAX_VALUE): SendOutcome {
        return try {
            val url = sendMessageUrl(botToken)
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = message, parseMode = "HTML")
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 400) {
                classify400(response, chatId) ?: if (message.length > 4000) sendChunked(message, botToken, chatId, retryCount, origId, deadlineAt) else sendPlainFallback(message, botToken, chatId)
            } else if (response.code() == 429) {
                rateLimited(response)
            } else if (response.code() == 401) {
                SendOutcome.AuthFailed(401)
            } else if (response.code() == 403) {
                classify403(response, chatId)
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                SendOutcome.Failed
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
