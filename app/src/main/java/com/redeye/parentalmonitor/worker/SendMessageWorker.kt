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

        @Volatile
        private var lastSendOkNoteAt = 0L

        fun isRunning(): Boolean = activeRuns.get() > 0
    }

    private val credsTouched = java.util.concurrent.atomic.AtomicBoolean(true)
    private val credListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> credsTouched.set(true) }

    private val messageQueue: MessageQueue by lazy { MessageQueue.getInstance(applicationContext) }
    private val preferencesManager: PreferencesManager by lazy {
        try { PreferencesManager.refreshInstance(context.applicationContext) } catch (_: Exception) { }
        PreferencesManager.getInstance(context.applicationContext)
    }

    override suspend fun doWork(): Result {
        // Penjaga run-tunggal (issue #1103): pola yang sama
        // dengan BootRestartWorker. Tanpa ini, ekor run lama
        // yang dibatalkan REPLACE bisa berjalan bersama run
        // pengganti dari snapshot antrean yang sama — pesan
        // terkirim dua kali dan kegagalan terhitung dua kali.
        // Run yang ditolak selesai sebagai sukses senyap;
        // run yang sedang berjalan tetap menguras antrean.
        if (activeRuns.getAndIncrement() > 0) {
            activeRuns.decrementAndGet()
            // Jaring pengaman antrean (issue #1294): asumsi
            // "run yang berjalan tetap menguras" runtuh bila
            // run itu ekor yang SEDANG dibatalkan (jalur heal
            // /clearqueue cancel->enqueue: ekor lama bisa masih
            // flushSync sampai 5 dtk saat pengganti tiba di
            // sini, lalu ekor itu pergi lewat jalur batal yang
            // tak menjadwalkan apa pun). Sisakan satu run
            // tertunda APPEND_OR_REPLACE: pada run sehat ia
            // hanya menemukan antrean kosong, pada ekor sekarat
            // ia yang menyelamatkan antrean dari menunggu
            // watchdog +-15 menit.
            // Lepaskan slot outstandingSends milik run ini
            // (issue #1313): reservasi dibuat saat penjadwalan
            // dan jalur lolos penjaga melepaskannya di finally —
            // cabang tertolak kembali TANPA finally itu, jadi
            // tiap penolakan membocorkan satu slot permanen dan
            // ±3 penolakan menjenuhkan MAX_OUTSTANDING_SEND,
            // meredam semua penjadwalan kirim per jendela
            // self-heal. Invariant kini: setiap doWork
            // melepaskan tepat satu slot (di sini, atau finally).
            try {
                MessageScheduler.onSendRunFinished()
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, 15_000L)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        try { preferencesManager.registerChangeListener(credListener) } catch (_: Exception) { }
        try {
            return try {
                doWorkInternal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Jalur batal ikut meninggalkan jadwal (issue
                // #1294): pembatalan REPLACE menjanjikan run
                // pengganti, tetapi pengganti itu bisa ditolak
                // penjaga run-tunggal oleh ekor run INI — jadwal
                // tertunda di sini (APPEND_OR_REPLACE, tak
                // membatalkan siapa pun) menutup celahnya.
                try {
                    MessageScheduler.scheduleMessageSendNext(applicationContext, 15_000L)
                } catch (_: Exception) {
                }
                throw e
            } catch (e: Exception) {
                // Catat kegagalan total (issue #965): pola lama
                // menelannya senyap sebagai sukses — kegagalan
                // deterministik berputar tiap ±60 detik tanpa
                // jejak apa pun untuk didiagnosis.
                android.util.Log.e("SendMessageWorker", "doWork gagal total, jadwal ulang 60 dtk: ${com.redeye.parentalmonitor.utils.Redact.token(e.message)}", e)
                try {
                    MessageScheduler.scheduleMessageSendNext(applicationContext, 60_000L)
                } catch (_: Exception) {
                }
                Result.success()
            }
        } finally {
            try { preferencesManager.unregisterChangeListener(credListener) } catch (_: Exception) { }
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
        // Prioritas jalan lebih dulu dalam putaran ini (issue #846):
        // sebelumnya urutan kirim murni FIFO sehingga pesan prioritas
        // di posisi 100 menunggu lima putaran worker. Urutan di dalam
        // kelas yang sama tetap FIFO (pengurutan stabil).
        val queue = messageQueue.getQueue().sortedByDescending { it.priority }
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
        var pausedMidLoop = false
        val runDeadlineAt = android.os.SystemClock.elapsedRealtime() + 8 * 60_000L
        for (queuedMessage in queue) {
            try {
                if (messageQueue.queueGeneration() != runGen) {
                    queueCleared = true
                    break
                }
            } catch (_: Exception) {
            }
            if (ownerLoginPending()) {
                break
            }
            if (try { preferencesManager.monitoringPaused } catch (_: Exception) { false }) {
                pausedMidLoop = true
                rescheduleIn30Min(true)
                break
            }
            val rateRemainMs = try { NetworkUtils.rateLimitedRemainMs() } catch (_: Exception) { 0L }
            if (rateRemainMs > 0L) {
                rateLimitedSecs = (rateRemainMs + 999L) / 1000L
                break
            }
            if (processed >= 20 || android.os.SystemClock.elapsedRealtime() >= runDeadlineAt) {
                break
            }
            if (credsTouched.getAndSet(false)) {
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
                val outcome = sendMessage(queuedMessage.message, runToken, runChatId, queuedMessage.retryCount, queuedMessage.id, runDeadlineAt, queuedMessage.priority, runGen)
                when (outcome) {
                    is SendOutcome.Sent -> {
                        sentIds.add(queuedMessage.id)
                        noteSendOk()
                        // Hapus dari antrean per kelompok, bukan
                        // per pesan (issue #946): satu penghapusan
                        // memaksa tulis sinkron SELURUH antrean ke
                        // disk, jadi drain 20 pesan dulu membayar
                        // 20 tulis penuh. Checkpoint tiap 10 pesan
                        // + titik flush batch yang sudah ada di
                        // bawah membereskan sisanya; bila proses
                        // mati di tengah drain, paling banyak 9
                        // pesan terkirim yang terkirim ulang
                        // (arah gagal aman: ganda, bukan hilang).
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
                // Redaksi standar (issue #1170): Redact.token
                // juga menangkap bentuk token dari pola, bukan
                // hanya snapshot berjalan.
                val detail = com.redeye.parentalmonitor.utils.Redact.token(e.message, runToken, runChatId)
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
            val removedRejected = try {
                messageQueue.removeMessages(rejectedIds)
            } catch (_: Exception) {
                false
            }
            if (removedRejected) {
                try {
                    messageQueue.noteRejectedDrops(rejectedIds.size.toLong())
                } catch (_: Exception) {
                }
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
        val pausedAtTail = try { preferencesManager.monitoringPaused } catch (_: Exception) { false }
        val transientMaxRetry = if (pausedAtTail) 0 else reportDrops(failedIds, rejectedIds, queueCleared, queue, runToken, runChatId)
        if (messageQueue.getQueueSize() > 0) {
            if (!pausedMidLoop) {
                try {
                    val backoffMs = if (failedIds.isNotEmpty() && !queueCleared) (60_000L shl transientMaxRetry.coerceIn(0, 3)).coerceAtMost(900_000L) else 0L
                    MessageScheduler.scheduleMessageSendNext(applicationContext, backoffMs)
                } catch (_: Exception) {
                }
            }
            return Result.success()
        }
        return Result.success()
    }

    private fun ownerLoginPending(): Boolean {
        return preferencesManager.accessPin.isNotEmpty() && preferencesManager.ownerUserId == 0L
    }

    private fun rescheduleIn30Min(onlyIfQueued: Boolean) {
        try {
            if (!onlyIfQueued || messageQueue.getQueueSize() > 0) {
                MessageScheduler.scheduleMessageSendNext(applicationContext, 30 * 60_000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun guardPreconditions(): Result? {
        try {
            messageQueue.tryRestorePersistent()
        } catch (_: Exception) {
        }
        if (!preferencesManager.isConfigured()) {
            rescheduleIn30Min(true)
            return Result.success()
        }
        if (ownerLoginPending()) {
            rescheduleIn30Min(true)
            return Result.success()
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            return Result.success()
        }
        if (try { preferencesManager.monitoringPaused } catch (_: Exception) { false }) {
            rescheduleIn30Min(true)
            return Result.success()
        }
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            rescheduleIn30Min(false)
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

    // Pemberitahuan "pesan dibuang" sesudah mentok percobaan
    // (issue #984): dulu blok ini tersalin persis dua kali —
    // di jalur kode auth dan di jalur rate-limit.
    private fun notifyExhaustedDrops(authDropped: Int) {
        try {
            messageQueue.noteExhaustedDrops(authDropped.toLong())
        } catch (_: Exception) {
        }
        val queued = try {
            messageQueue.addMessage("⚠️ $authDropped pesan dalam antrean dibuang (sudah mentok percobaan).", true)
        } catch (_: Exception) {
            false
        }
        if (queued) {
            try {
                val taken = messageQueue.takeExhaustedDrops()
                if (taken > authDropped) messageQueue.restoreExhaustedDrops(taken - authDropped)
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.scheduleMessageSend(applicationContext)
            } catch (_: Exception) {
            }
        }
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
        val sameCreds = credsSame(runToken, runChatId)
        if ((authCode != 0 || rateLimitedSecs > 0) && !queueCleared && failedIds.isNotEmpty() && sameCreds) {
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
            if (authDropped > 0 && !queueCleared && sameCreds) {
                notifyExhaustedDrops(authDropped)
            }
            return Result.success()
        }

        if (rateLimitedSecs > 0) {
            NetworkUtils.noteRateLimited(rateLimitedSecs)
            try {
                MessageScheduler.scheduleMessageSendNext(applicationContext, rateLimitedSecs * 1000L)
            } catch (_: Exception) {
            }
            if (authDropped > 0 && !queueCleared && sameCreds) {
                notifyExhaustedDrops(authDropped)
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
        var expiredTaken = 0L
        var overflowTaken = 0L
        var prioTaken = 0L
        var exhaustedTaken = 0L
        var rejectedTaken = 0L
        val sameCreds = credsSame(runToken, runChatId)
        // Generasi antrean saat pengambilan (issue #822): bila
        // /clearqueue berjalan di tengah laporan ini, restore di
        // bawah tak boleh membangkitkan angka pra-pembersihan.
        val dropGen = try { messageQueue.queueGeneration() } catch (_: Exception) { -1L }
        if (!queueCleared && sameCreds) {
            try {
                val expired = messageQueue.takeExpiredDrops()
                if (expired > 0L) {
                    expiredTaken = expired
                    dropTotal += expired.toInt()
                    dropParts.add("$expired kedaluwarsa (lebih dari 7 hari)")
                }
                val overflow = messageQueue.takeOverflowDrops()
                if (overflow > 0L) {
                    overflowTaken = overflow
                    dropTotal += overflow.toInt()
                    val prio = try { messageQueue.takePriorityDrops() } catch (_: Exception) { 0L }
                    prioTaken = prio
                    dropParts.add("$overflow paling lama di antrean (antrean penuh saat offline)" + if (prio > 0L) ", termasuk $prio prioritas" else "")
                }
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && failedIds.isNotEmpty() && sameCreds) {
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
                    if (dropped.isNotEmpty() && sameCreds) {
                        android.util.Log.w("SendMessageWorker", "Dropped ${dropped.size} message(s) after max retries")
                        try {
                            messageQueue.noteExhaustedDrops(dropped.size.toLong())
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (!queueCleared && sameCreds) {
            try {
                val exhausted = messageQueue.takeExhaustedDrops()
                if (exhausted > 0L) {
                    exhaustedTaken = exhausted
                    dropTotal += exhausted.toInt()
                    dropParts.add("$exhausted setelah percobaan maksimal")
                }
            } catch (_: Exception) {
            }
        }

        if (!queueCleared && sameCreds) {
            try {
                val rejected = messageQueue.takeRejectedDrops()
                if (rejected > 0L) {
                    rejectedTaken = rejected
                    if (rejectedIds.isNotEmpty()) {
                        dropSample = try {
                            queue.firstOrNull { it.id in rejectedIds }?.message.orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    dropTotal += rejected.toInt()
                    dropParts.add("$rejected ditolak (HTTP 4xx)")
                }
            } catch (_: Exception) {
            }
        }
        if (!queueCleared && dropTotal > 0 && sameCreds) {
            val reported = try {
                sendDropNotice(dropTotal, runToken, runChatId, dropSample, dropParts.joinToString("; "))
            } catch (_: Exception) {
                false
            }
            if (!reported) {
                // Lewati restore bila antrean dibersihkan sesudah
                // pengambilan (issue #822): angka lama akan bangkit
                // sebagai laporan drop hantu pada run berikutnya.
                val genSama = try { messageQueue.queueGeneration() == dropGen } catch (_: Exception) { true }
                if (genSama) {
                try {
                    messageQueue.restoreExpiredDrops(expiredTaken)
                } catch (_: Exception) {
                }
                try {
                    messageQueue.restoreOverflowDrops(overflowTaken)
                } catch (_: Exception) {
                }
                try {
                    messageQueue.restorePriorityDrops(prioTaken)
                } catch (_: Exception) {
                }
                try {
                    messageQueue.restoreExhaustedDrops(exhaustedTaken)
                } catch (_: Exception) {
                }
                try {
                    messageQueue.restoreRejectedDrops(rejectedTaken)
                } catch (_: Exception) {
                }
                }
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
                // Kode tak terdaftar (404/409/413/422, ...) BUKAN
                // penolakan permanen (issue #966): hanya 400/
                // 401/403 yang terbukti permanen di kode ini.
                // Perlakukan sebagai gagal sementara — pesan
                // dipertahankan dan dicoba lagi lewat anggaran
                // retry, bukan dihapus permanen.
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
                // Sama seperti sendPlainFallback (issue #966):
                // kode tak terdaftar = gagal sementara, bukan
                // penolakan permanen.
                SendOutcome.Failed
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            SendOutcome.Failed
        }
    }

    private suspend fun sendChunked(message: String, botToken: String, chatId: String, retryCount: Int = 0, origId: String? = null, deadlineAt: Long = Long.MAX_VALUE, priority: Boolean = false, runGen: Long = -1L): SendOutcome {
        val parts = mutableListOf<String>()
        var rest = message
        while (rest.length > 4000) {
            val cut = splitChunk(rest, 4000)
            parts.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        parts.add(rest)
        // Generasi antrean berubah = antrean dibersihkan/diganti di tengah
        // kirim (issue #692): potongan sisa TIDAK boleh diantrekan ulang
        // dan pengiriman pesan ini harus berhenti.
        fun genStale(): Boolean = runGen >= 0L && (try { messageQueue.queueGeneration() } catch (_: Exception) { runGen } != runGen)
        fun queueRemainder(fromIdx: Int): Boolean {
            if (genStale()) return false
            return try {
                messageQueue.addMessage(parts.subList(fromIdx, parts.size).joinToString(""), priority, retryCount = retryCount + 1)
            } catch (_: Exception) {
                false
            }
        }
        var rateAfter = 0L
        var authCode = 0
        var failed = 0
        var failedKept = false
        var remainderQueued = false
        var rejected = 0
        for ((idx, part) in parts.withIndex()) {
            if (genStale()) return SendOutcome.Failed
            // Kredensial berganti di tengah pesan panjang (issue #693):
            // sisa potongan TIDAK diteruskan ke bot/chat snapshot lama —
            // antrekan ulang agar run berikutnya memakainya dengan
            // kredensial baru, sama seperti jalur tenggat. Berlaku juga
            // untuk potongan pertama (issue #722); nilai kosong dari baca
            // prefs yang gagal sesaat (prefString mengembalikan "") TIDAK
            // dianggap pergantian — pengosongan sungguhan (logout/Setup)
            // menaikkan generasi antrean dan tertangkap genStale().
            val curToken = try { preferencesManager.botToken } catch (_: Exception) { botToken }
            val curChat = try { preferencesManager.chatId } catch (_: Exception) { chatId }
            if ((curToken.isNotEmpty() && curToken != botToken) || (curChat.isNotEmpty() && curChat != chatId)) {
                if (!queueRemainder(idx)) return SendOutcome.Failed
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
            if (android.os.SystemClock.elapsedRealtime() >= deadlineAt) {
                if (idx == 0) return SendOutcome.Failed
                val queued = queueRemainder(idx)
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
                            val queued = queueRemainder(idx + 1)
                            if (queued) {
                                try {
                                    if (origId != null) messageQueue.removeMessages(listOf(origId))
                                } catch (_: Exception) {
                                }
                                try {
                                    MessageScheduler.scheduleMessageSend(applicationContext)
                                } catch (_: Exception) {
                                }
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
                        remainderQueued = queueRemainder(idx)
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
                        try {
                            if (origId != null) messageQueue.removeMessages(listOf(origId))
                        } catch (_: Exception) {
                        }
                        rateAfter = outcome.retryAfterSecs
                        break
                    }
                    rateAfter = outcome.retryAfterSecs
                    break
                }
                is SendOutcome.AuthFailed -> {
                    if (idx > 0 && !remainderQueued) {
                        remainderQueued = queueRemainder(idx)
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
                    if (remainderQueued) {
                        // Hasil akhir harus AuthFailed, bukan Sent (issue
                        // #745): penanda yang baru dipasang di atas dihapus
                        // lagi oleh ekor doWorkInternal bila run ini keluar
                        // sebagai Sent. handleAuthOrRate memasang ulang
                        // penanda yang sama. Pesan asal dibuang di sini
                        // karena sisanya sudah terantre sebagai pesan baru.
                        try {
                            if (origId != null) messageQueue.removeMessages(listOf(origId))
                        } catch (_: Exception) {
                        }
                        authCode = outcome.code
                        break
                    }
                    authCode = outcome.code
                    break
                }
                SendOutcome.Failed -> {
                    failed++
                    if (idx > 0 && !failedKept) {
                        failedKept = queueRemainder(idx)
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
                        failedKept = queueRemainder(idx)
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
        if (rateAfter > 0L) return SendOutcome.RateLimited(rateAfter)
        if (authCode != 0) return SendOutcome.AuthFailed(authCode)
        if (remainderQueued) return SendOutcome.Sent
        if (failed > 0) return if (failedKept) SendOutcome.Sent else SendOutcome.Failed
        if (rejected > 0) return if (failedKept) SendOutcome.Sent else SendOutcome.Rejected
        return SendOutcome.Sent
    }

    private suspend fun sendDropNotice(count: Int, botToken: String, chatId: String, sample: String = "", detail: String = ""): Boolean {
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
            if (response.isSuccessful && response.body()?.ok == true) return true
        } catch (_: Exception) {
        }
        return try {
            val queued = messageQueue.addMessage(text, true)
            if (queued) {
                try {
                    MessageScheduler.scheduleMessageSend(applicationContext)
                } catch (_: Exception) {
                }
            }
            queued
        } catch (_: Exception) {
            false
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

    private fun noteSendOk() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSendOkNoteAt >= 30_000L) {
            lastSendOkNoteAt = now
            try {
                preferencesManager.setLastSendOkMarker(now)
            } catch (_: Exception) {
            }
        }
        try {
            if (preferencesManager.silentHealStage != 0) preferencesManager.silentHealStage = 0
        } catch (_: Exception) {
        }
    }

    private suspend fun sendMessage(message: String, botToken: String, chatId: String, retryCount: Int = 0, origId: String? = null, deadlineAt: Long = Long.MAX_VALUE, priority: Boolean = false, runGen: Long = -1L): SendOutcome {
        return try {
            val url = sendMessageUrl(botToken)
            val response = TelegramClient.api.sendMessage(
                url,
                TelegramMessage(chatId = chatId, text = message, parseMode = "HTML")
            )

            if (response.isSuccessful && response.body()?.ok == true) {
                SendOutcome.Sent
            } else if (response.code() == 400) {
                classify400(response, chatId) ?: if (message.length > 4000) sendChunked(message, botToken, chatId, retryCount, origId, deadlineAt, priority, runGen) else sendPlainFallback(message, botToken, chatId)
            } else if (response.code() == 429) {
                rateLimited(response)
            } else if (response.code() == 401) {
                SendOutcome.AuthFailed(401)
            } else if (response.code() == 403) {
                classify403(response, chatId)
            } else if (response.code() == 408 || response.code() >= 500) {
                SendOutcome.Failed
            } else {
                // Situs ketiga pola yang sama (issue #966):
                // kode tak terdaftar = gagal sementara.
                SendOutcome.Failed
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed
        }
    }
}
