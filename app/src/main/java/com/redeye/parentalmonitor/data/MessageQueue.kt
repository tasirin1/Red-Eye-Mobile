package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.redeye.parentalmonitor.data.models.QueuedMessage
import com.redeye.parentalmonitor.utils.MessageTrim
import com.redeye.parentalmonitor.utils.QueueCap
import com.redeye.parentalmonitor.utils.QueueFresh

class MessageQueue private constructor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var volatileOnly = false
    private val volatileQueue = mutableListOf<QueuedMessage>()
    @Volatile
    private var sharedPreferences: SharedPreferences? = null
    private val overflowDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val priorityDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val expiredDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val exhaustedDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val rejectedDrops = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var dropsDirty = false
    // clearQueue di mode volatile yang wipe cakramnya GAGAL
    // (issue #1298): memori + baseline sudah ternolkan, tetapi
    // cakram masih memuat hitungan lama; tanpa penanda ini
    // muat aditif saat penyimpanan pulih membangkitkannya
    // sebagai laporan drop hantu. Penanda mati bersama proses
    // (kebangkitan lintas mati-proses = at-least-once yang
    // memang diterima desain volatile).
    private var dropWipePendingVolatile = false

    private var persistedOverflowDrops = 0L
    private var persistedExpiredDrops = 0L
    private var persistedPriorityDrops = 0L
    private var persistedExhaustedDrops = 0L
    private var persistedRejectedDrops = 0L
    @Volatile
    private var restoreFailLogged = false
    private var restoringDrops = false
    private var lastPersistAt = 0L
    @Volatile
    private var persistDirty = false
    private val lock = Any()
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "redeye-queue-persist").apply { isDaemon = true }
    }
    // Penjadwal tunda terpisah (issue #705): jadwal ulang drain sebelumnya
    // Thread.sleep di dalam satu-satunya thread persist, menahan drain
    // lain dan sinkronisasi .bak selama tidur itu.
    private val persistScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "redeye-queue-persist-tunda").apply { isDaemon = true }
    }
    private val persistQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val bakSyncQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val persistMutex = Any()
    private val jsonCache = HashMap<String, Pair<QueuedMessage, String>>()
    @Volatile
    private var persistVersion = 0L
    // Salinan memori JSON antrean terakhir yang berhasil ditulis
    // (issue #1046): rotasi cadangan di writeSnapshot dulu membaca
    // ulang dari prefs — dekripsi penuh seluruh JSON lama — pada
    // SETIAP mutasi antrean. Kini baca dari prefs hanya bila
    // salinan ini kosong (tulis pertama sesudah proses mulai /
    // reset penyimpanan). Dijaga persistMutex.
    private var lastWrittenJson: String? = null
    private var pendingSnapshot: Pair<Long, List<QueuedMessage>>? = null
    private var drainFailCount = 0
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var cached: MutableList<QueuedMessage>? = null
    private val arrayType = Array<QueuedMessage>::class.java

    init {
        // Always try encrypted prefs first; fall back to volatile only if unavailable
        try {
            sharedPreferences = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
            volatileOnly = false
            try {
                loadDropCountsLocked()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using volatile memory", e)
            volatileOnly = true
            sharedPreferences = null
        }
        // Schedule persistent restore attempt if we started in volatile mode
        if (volatileOnly) {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    tryRestorePersistent()
                } catch (e: Exception) {
                    android.util.Log.e("MessageQueue", "Persistent queue restore failed during init", e)
                }
            }
        }
    }
    companion object {
        private const val QUEUE_PREFS_NAME = "encrypted_queue"
        private const val KEY_QUEUE = "queued_messages"
        private const val KEY_QUEUE_BAK = "queued_messages_bak"
        private const val KEY_OVERFLOW_DROPS = "overflow_drops"
        private const val KEY_EXPIRED_DROPS = "expired_drops"
        private const val KEY_PRIORITY_DROPS = "priority_drops"
        private const val KEY_EXHAUSTED_DROPS = "exhausted_drops"
        private const val KEY_REJECTED_DROPS = "rejected_drops"
        // Batas ukuran antrean & SATU pesan (issue #842/#926)
        // kini di utils/QueueCap & utils/MessageTrim agar
        // terkunci uji (issue #977).
        private const val MAX_TRANSIENT_RETRIES = 20
        private const val MAX_DRAIN_RETRIES = 10
        private const val GIVE_UP_RETRY_MS = 300_000L
        private val gson = Gson()


        @Volatile
        private var instance: MessageQueue? = null

        fun getInstance(context: Context): MessageQueue {
            return instance ?: synchronized(this) {
                instance ?: MessageQueue(context.applicationContext).also { instance = it }
            }
        }
    }

    fun tryRestorePersistent(): Boolean {
        synchronized(lock) {
            if (!volatileOnly) return true
            val pending = volatileQueue.toList()
            val previousOverflowDrops = overflowDrops.get()
            val previousExpiredDrops = expiredDrops.get()
            val previousPriorityDrops = priorityDrops.get()
            val previousExhaustedDrops = exhaustedDrops.get()
            val previousRejectedDrops = rejectedDrops.get()
            val previousDropsDirty = dropsDirty
            return try {
                val restored = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                sharedPreferences = restored
                volatileOnly = false
                cached = null
                // Salinan JSON terakhir adalah cermin objek
                // prefs LAMA (issue #1087): objek baru bisa
                // berisi antrean berbeda, jadi nolkan seperti
                // reopenAfterStorageReset — writeSnapshot
                // berikutnya membaca isi utama yang sebenarnya
                // untuk rotasi cadangan, bukan salinan basi.
                synchronized(persistMutex) { lastWrittenJson = null }
                persistVersion++
                pendingSnapshot = null
                persistDirty = false
                if (pending.isNotEmpty()) {
                    val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
                    val freshPending = filterFresh(pending, cutoff)
                    val expired = pending.size - freshPending.size
                    if (expired > 0) {
                        noteExpiredLocked(expired.toLong(), persist = false)
                        android.util.Log.w("MessageQueue", "Dropped $expired expired restore message(s)")
                    }
                    restoringDrops = true
                    val queue = try {
                        readLocked()
                    } finally {
                        restoringDrops = false
                    }.toMutableList()
                    if (volatileOnly) throw IllegalStateException("queue read failed during restore")
                    queue.addAll(freshPending)
                    // Kelonggaran prioritas yang sama dengan jalur
                    // normal (issue #927): batas polos 100 di sini
                    // mengorbankan maks 5 pesan prioritas tertua
                    // yang oleh overCapLocked diberi ruang +5.
                    while (overCapLocked(queue, false)) {
                        evictOldestLocked(queue, persist = false)
                    }
                    if (!persistLocked(queue, force = true, syncOnMain = true)) {
                        throw IllegalStateException("queue write failed during restore")
                    }
                }
                loadDropCountsLocked(add = true)
                persistDropCountsLocked()
                volatileQueue.clear()
                restoreFailLogged = false
                true
            } catch (e: Exception) {
                if (!restoreFailLogged) {
                    restoreFailLogged = true
                    android.util.Log.e("MessageQueue", "Persistent queue restore failed", e)
                }
                overflowDrops.set(previousOverflowDrops)
                expiredDrops.set(previousExpiredDrops)
                priorityDrops.set(previousPriorityDrops)
                exhaustedDrops.set(previousExhaustedDrops)
                rejectedDrops.set(previousRejectedDrops)
                dropsDirty = previousDropsDirty
                volatileQueue.clear()
                volatileQueue.addAll(pending)
                sharedPreferences = null
                volatileOnly = true
                cached = null
                persistVersion++
                pendingSnapshot = null
                persistDirty = false
                false
            }
        }
    }

    fun reopenAfterStorageReset() {
        synchronized(lock) {
            cached = null
            synchronized(persistMutex) { lastWrittenJson = null }
            volatileQueue.clear()
            dropsDirty = false
            persistVersion++
            pendingSnapshot = null
            persistDirty = false
            try {
                sharedPreferences = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                volatileOnly = false
                restoreFailLogged = false
                loadDropCountsLocked(add = false)
            } catch (e: Exception) {
                sharedPreferences = null
                volatileOnly = true
                keepUnpersistedDropsLocked()
                android.util.Log.w("MessageQueue", "Queue reopen failed, using volatile: ${com.redeye.parentalmonitor.utils.Redact.token(e.message)}")
            }
        }
    }

    private fun ensureRestored() {
        if (!volatileOnly) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try { tryRestorePersistent() } catch (e: Exception) {
                    android.util.Log.e("MessageQueue", "Persistent queue restore failed", e)
                }
            }
            return
        }
        try {
            tryRestorePersistent()
        } catch (e: Exception) {
            android.util.Log.e("MessageQueue", "Persistent queue restore failed", e)
        }
    }

    fun isVolatileOnly(): Boolean = volatileOnly

    fun addMessage(message: String, priority: Boolean = false, retryCount: Int = 0): Boolean {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                val item = QueuedMessage(message = potongPesan(message), elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority, bootAt = bootWallAt())
                volatileQueue.add(item)
                while (overCapLocked(volatileQueue, priority)) {
                    evictOldestLocked(volatileQueue)
                }
                return volatileQueue.any { it === item }
            }
            val queue = readLocked().toMutableList()
            if (volatileOnly) {
                // readLocked baru saja gagal total dan membalik
                // mode ke volatile (issue #915): daftar ini kosong
                // semu — mempersistensikannya akan menimpa antrean
                // tersimpan. Ulangi sebagai tambah-volatile
                // (synchronized bersifat reentrant).
                return addMessage(message, priority, retryCount)
            }
            val item = QueuedMessage(message = potongPesan(message), elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority, bootAt = bootWallAt())
            queue.add(item)
            while (overCapLocked(queue, priority)) {
                evictOldestLocked(queue)
            }
            val persisted = persistLocked(queue, force = true, syncOnMain = true)
            return persisted && queue.any { it === item }
        }
    }

    // Kontrak nilai kembali (issue #778): true HANYA bila seluruh batch
    // selamat dari eviksi dan terpersisten. Semantik lama "satu selamat
    // = true" membuat pemanggil menandai seluruh batch berhasil —
    // notifikasi yang tereviksi tertandai sudah diteruskan dan potongan
    // laporan memajukan kursor sumbernya. Pada keberhasilan sebagian
    // pemanggil kini menganggap batch gagal: arah gagal aman (yang
    // selamat bisa terkirim ganda saat dicoba lagi, bukan hilang).
    fun addMessages(messages: Collection<String>, priority: Boolean = false): Boolean {
        if (messages.isEmpty()) return true
        return addMessagesPerItem(messages, priority).all { it }
    }

    // Varian per item dari addMessages (issue #791): hasil per pesan
    // memberi tahu pemanggil salvage pesan mana saja yang benar-benar
    // selamat dari eviksi dan terpersisten, agar penandaan "sudah
    // diteruskan" mengikuti itemnya — bukan all-or-nothing yang pada
    // kegagalan sebagian membuat yang selamat terkirim tanpa tanda
    // (dobel) dan yang tereviksi hilang tanpa tercatat.
    fun addMessagesPerItem(messages: Collection<String>, priority: Boolean = false): List<Boolean> {
        if (messages.isEmpty()) return emptyList()
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                val added = messages.map { QueuedMessage(message = potongPesan(it), elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority, bootAt = bootWallAt()) }
                volatileQueue.addAll(added)
                var evictedVolatile = 0
                while (overCapLocked(volatileQueue, priority)) {
                    evictOldestLocked(volatileQueue, persist = false)
                    evictedVolatile++
                }
                if (evictedVolatile > 0) persistDropCountsLocked()
                return added.map { item -> volatileQueue.any { it === item } }
            }
            val queue = readLocked().toMutableList()
            if (volatileOnly) {
                // Sama seperti addMessage (issue #915): baca gagal
                // total di tengah operasi — ulangi sebagai batch
                // volatile, jangan timpa antrean tersimpan.
                return addMessagesPerItem(messages, priority)
            }
            val addedBatch = messages.map { QueuedMessage(message = potongPesan(it), elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority, bootAt = bootWallAt()) }
            queue.addAll(addedBatch)
            var evictedBatch = 0
            while (overCapLocked(queue, priority)) {
                evictOldestLocked(queue, persist = false)
                evictedBatch++
            }
            if (evictedBatch > 0) persistDropCountsLocked()
            val persistedBatch = persistLocked(queue, force = true, syncOnMain = true)
            return addedBatch.map { item -> persistedBatch && queue.any { it === item } }
        }
    }

    private fun loadDropCountsLocked(add: Boolean = false) {
        try {
            // Tiap kunci dibaca dengan penjaganya sendiri
            // (issue #1109): satu getLong yang melempar
            // (mis. nilai korup beda tipe) dulu menggagalkan
            // SELURUH muat — tak ada setter berjalan, jadi
            // penghitung era lama bertahan dengan
            // dropsDirty=false dan persist berikutnya menulis
            // angka campuran era ke penyimpanan baru sesudah
            // reset. Kini kunci yang gagal terbaca = 0 dan
            // kelima setter selalu berjalan sebagai satu
            // gambar utuh.
            fun bacaKunci(key: String): Long {
                return try { sharedPreferences?.getLong(key, 0L) ?: 0L } catch (_: Exception) { 0L }
            }
            val overflow = bacaKunci(KEY_OVERFLOW_DROPS)
            val expired = bacaKunci(KEY_EXPIRED_DROPS)
            val priority = bacaKunci(KEY_PRIORITY_DROPS)
            val exhausted = bacaKunci(KEY_EXHAUSTED_DROPS)
            val rejected = bacaKunci(KEY_REJECTED_DROPS)
            // Baseline cakram yang SUDAH terwakili di memori,
            // ditangkap sebelum ditimpa (issue #1297): muat
            // aditif hanya boleh menambah KELEBIHAN cakram di
            // atas baseline ini.
            val dasarOverflow = persistedOverflowDrops
            val dasarExpired = persistedExpiredDrops
            val dasarPriority = persistedPriorityDrops
            val dasarExhausted = persistedExhaustedDrops
            val dasarRejected = persistedRejectedDrops
            persistedOverflowDrops = overflow
            persistedExpiredDrops = expired
            persistedPriorityDrops = priority
            persistedExhaustedDrops = exhausted
            persistedRejectedDrops = rejected
            if (add) {
                // Tambah hanya kelebihan cakram di atas
                // baseline yang sudah terwakili di memori
                // (issue #1297, menggantikan potongan
                // takenVolatile #1282 yang salah kolam):
                // volatile-sejak-start baseline-nya 0 sehingga
                // seluruh isi cakram tertambah (kolam cakram
                // tak pernah ikut terambil — pengambil hanya
                // menyentuh kolam memori era volatile);
                // volatile-di-tengah-jalan baseline-nya sama
                // dengan isi cakram (tak ada penulisan selama
                // volatile) sehingga tambahannya 0 dan baseline
                // yang sudah dibawakan keepUnpersistedDrops tak
                // terhitung dua kali.
                if (dropWipePendingVolatile) {
                    // Wipe yang gagal di mode volatile menang
                    // atas isi cakram (issue #1298): jangan
                    // tambah apa pun; dropsDirty di bawah
                    // memastikan keadaan ternolkan tertulis
                    // menimpa hitungan basi di cakram.
                    dropWipePendingVolatile = false
                } else {
                    overflowDrops.addAndGet((overflow - dasarOverflow).coerceAtLeast(0L))
                    expiredDrops.addAndGet((expired - dasarExpired).coerceAtLeast(0L))
                    priorityDrops.addAndGet((priority - dasarPriority).coerceAtLeast(0L))
                    exhaustedDrops.addAndGet((exhausted - dasarExhausted).coerceAtLeast(0L))
                    rejectedDrops.addAndGet((rejected - dasarRejected).coerceAtLeast(0L))
                }
                dropsDirty = true
            } else {
                overflowDrops.set(overflow)
                expiredDrops.set(expired)
                priorityDrops.set(priority)
                exhaustedDrops.set(exhausted)
                rejectedDrops.set(rejected)
                dropsDirty = false
            }
        } catch (_: Exception) {
        }
    }

    private fun persistDropCountsLocked() {
        if (volatileOnly) return
        try {
            val editor = sharedPreferences?.edit()?.putLong(KEY_OVERFLOW_DROPS, overflowDrops.get())?.putLong(KEY_EXPIRED_DROPS, expiredDrops.get())?.putLong(KEY_PRIORITY_DROPS, priorityDrops.get())?.putLong(KEY_EXHAUSTED_DROPS, exhaustedDrops.get())?.putLong(KEY_REJECTED_DROPS, rejectedDrops.get())
            val saved = editor?.commit() == true
            if (saved) {
                persistedOverflowDrops = overflowDrops.get()
                persistedExpiredDrops = expiredDrops.get()
                persistedPriorityDrops = priorityDrops.get()
                persistedExhaustedDrops = exhaustedDrops.get()
                persistedRejectedDrops = rejectedDrops.get()
            }
            dropsDirty = !saved
        } catch (_: Exception) {
            dropsDirty = true
        }
    }

    private fun keepUnpersistedDropsLocked() {
        overflowDrops.set((overflowDrops.get() - persistedOverflowDrops).coerceAtLeast(0L))
        expiredDrops.set((expiredDrops.get() - persistedExpiredDrops).coerceAtLeast(0L))
        priorityDrops.set((priorityDrops.get() - persistedPriorityDrops).coerceAtLeast(0L))
        exhaustedDrops.set((exhaustedDrops.get() - persistedExhaustedDrops).coerceAtLeast(0L))
        rejectedDrops.set((rejectedDrops.get() - persistedRejectedDrops).coerceAtLeast(0L))
        dropsDirty = true
    }

    private fun noteOverflowLocked(by: Long = 1L, persist: Boolean = true) {
        noteDropLocked(overflowDrops, by, persist)
    }

    private fun noteExpiredLocked(by: Long = 1L, persist: Boolean = true) {
        noteDropLocked(expiredDrops, by, persist)
    }

    private fun noteDropLocked(counter: java.util.concurrent.atomic.AtomicLong, by: Long, persist: Boolean) {
        try {
            counter.addAndGet(by)
        } catch (_: Exception) {
        }
        dropsDirty = true
        if (persist && !restoringDrops) persistDropCountsLocked()
    }

    // Batas efektif antrean (issue #842): kelonggaran +5 milik
    // pesan prioritas yang ADA di antrean, bukan bendera pesan yang
    // sedang masuk. Sebelumnya satu pesan biasa yang masuk ke
    // antrean berisi 105 prioritas menyusutkan batas ke 100 dan
    // mengorbankan 5 prioritas tertua.
    private fun overCapLocked(queue: List<QueuedMessage>, incomingPriority: Boolean): Boolean {
        return QueueCap.overCap(queue.size, queue.any { it.priority }, incomingPriority) ||
            QueueCap.overTotalChars(queue.sumOf { it.message.length })
    }

    private fun evictOldestLocked(queue: MutableList<QueuedMessage>, persist: Boolean = true) {
        val idx = queue.indexOfFirst { !it.priority }
        val evicted = if (idx >= 0) queue.removeAt(idx) else queue.removeAt(0)
        try {
            if (evicted.priority) priorityDrops.incrementAndGet()
        } catch (_: Exception) {
        }
        noteOverflowLocked(persist = persist)
        android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
    }

    private fun bootWallAt(): Long = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    // Terapkan batas ukuran per pesan (issue #926) pada titik
    // konstruksi item antrean.
    private fun potongPesan(message: String): String {
        return MessageTrim.trim(message)
    }

    private fun filterFresh(items: List<QueuedMessage>, wallCutoff: Long): List<QueuedMessage> {
        // Hitungan kesegarannya di QueueFresh (logika murni
        // teruji, issue #1028).
        val now = try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            0L
        }
        val bootAt = try {
            bootWallAt()
        } catch (_: Exception) {
            0L
        }
        if (items.size <= 5) return items.filter { QueueFresh.isFresh(it.timestamp, it.elapsedAt, it.bootAt, wallCutoff, now, bootAt) }
        if (items.all { QueueFresh.isFresh(it.timestamp, it.elapsedAt, it.bootAt, wallCutoff, now, bootAt) }) return items
        val fresh = items.filter { QueueFresh.isFresh(it.timestamp, it.elapsedAt, it.bootAt, wallCutoff, now, bootAt) }
        // Penyelamatan "jam melompat" hanya dengan bukti
        // (issue #967): antrean warisan yang sungguh tua
        // memenuhi syarat yang sama persis (mayoritas basi +
        // semua bootAt<=0) dan dulu dipertahankan selamanya.
        // Bukti yang sah: ada pesan bertanggal masa depan.
        val nowWall = System.currentTimeMillis()
        if (QueueFresh.clockJumpRescue(items.size, fresh.size, items.all { it.bootAt <= 0L }, items.any { it.timestamp > nowWall + QueueFresh.FUTURE_PROOF_MS })) {
            android.util.Log.w("MessageQueue", "Clock jump suspected, keeping ${items.size} messages")
            return items
        }
        return fresh
    }

    private fun pruneVolatileLocked() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val before = volatileQueue.size
        val fresh = filterFresh(volatileQueue, cutoff)
        val dropped = before - fresh.size
        if (dropped > 0) {
            volatileQueue.clear()
            volatileQueue.addAll(fresh)
            noteExpiredLocked(dropped.toLong())
            android.util.Log.w("MessageQueue", "Dropped $dropped expired volatile message(s)")
        }
    }

    fun getQueue(): List<QueuedMessage> {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.toList()
            }
            return readLocked().toList()
        }
    }

    fun removeMessages(messageIds: Collection<String>): Boolean {
        if (messageIds.isEmpty()) return true
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                volatileQueue.removeAll { it.id in messageIds }
                return true
            }
            val queue = readLocked().toMutableList()
            if (volatileOnly) {
                // Baca gagal total di tengah operasi (issue #915):
                // penghapusan BELUM terjadi di penyimpanan —
                // laporkan gagal agar pemanggil mencoba lagi,
                // bukan sukses semu yang membuat pesan terkirim
                // bangkit dan terkirim ulang.
                return false
            }
            val removed = queue.removeAll { it.id in messageIds }
            if (!removed && !persistDirty) return true
            val saved = persistWithRetriesLocked(queue)
            if (!saved) android.util.Log.e("MessageQueue", "Failed to persist message removal after retries")
            return saved
        }
    }

    fun registerTransientFailures(messageIds: Collection<String>): Pair<List<String>, Int> {
        return registerWithBudget(messageIds, MAX_TRANSIENT_RETRIES)
    }

    private fun registerWithBudget(messageIds: Collection<String>, budget: Int): Pair<List<String>, Int> {
        if (messageIds.isEmpty()) return Pair(emptyList(), 0)
        synchronized(lock) {
            // Samakan dengan semua mutasi lain (issue #998):
            // coba pulihkan antrean persisten dulu; tanpa ini
            // pendaftaran retry berjalan di salinan volatile
            // walau penyimpanan sudah bisa dibuka lagi.
            ensureRestored()
            var maxRetry = 0
            if (volatileOnly) {
                pruneVolatileLocked()
                val drop = mutableListOf<String>()
                for (i in volatileQueue.indices) {
                    val queued = volatileQueue[i]
                    if (queued.id in messageIds) {
                        val updated = queued.copy(retryCount = queued.retryCount + 1)
                        volatileQueue[i] = updated
                        if (updated.retryCount >= budget) drop.add(updated.id)
                        else if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
                    }
                }
                volatileQueue.removeAll { it.id in drop }
                return Pair(drop, maxRetry)
            }
            val original = readLocked().toMutableList()
            if (volatileOnly) {
                // Baca gagal total di tengah operasi (issue #915):
                // jangan catat percobaan ulang dari daftar kosong
                // semu; batalkan putaran ini dengan aman.
                return Pair(emptyList(), 0)
            }
            val queue = original.toMutableList()
            val drop = mutableListOf<String>()
            for (i in queue.indices) {
                val queued = queue[i]
                if (queued.id in messageIds) {
                    val updated = queued.copy(retryCount = queued.retryCount + 1)
                    queue[i] = updated
                    if (updated.retryCount >= budget) drop.add(updated.id)
                    else if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
                }
            }
            queue.removeAll { it.id in drop }
            if (!persistWithRetriesLocked(queue)) {
                cached = original
                if (!persistWithRetriesLocked(original)) {
                    android.util.Log.e("MessageQueue", "Failed to persist retry rollback after retries")
                }
                return Pair(emptyList(), maxRetry)
            }
            return Pair(drop, maxRetry)
        }
    }

    private fun persistWithRetriesLocked(queue: MutableList<QueuedMessage>, attempts: Int = 3): Boolean {
        repeat(attempts) {
            if (persistLocked(queue, force = true, syncOnMain = true)) return true
        }
        return false
    }

    fun queueGeneration(): Long {
        return generationCounter.get()
    }

    fun clearQueue(): Boolean {
        synchronized(lock) {
            generationCounter.incrementAndGet()
            cached = mutableListOf()
            persistDirty = false
            volatileQueue.clear()
            overflowDrops.set(0L)
            expiredDrops.set(0L)
            priorityDrops.set(0L)
            exhaustedDrops.set(0L)
            rejectedDrops.set(0L)
            persistedOverflowDrops = 0L
            persistedExpiredDrops = 0L
            persistedPriorityDrops = 0L
            persistedExhaustedDrops = 0L
            persistedRejectedDrops = 0L
            dropsDirty = false
            val wipe = if (volatileOnly) {
                try {
                    PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                } catch (_: Exception) {
                    null
                }
            } else {
                sharedPreferences
            }
            val cleared = synchronized(persistMutex) {
                jsonCache.clear()
                lastWrittenJson = null
                persistVersion++
                pendingSnapshot = null
                val removed = try {
                    wipe?.edit()?.remove(KEY_QUEUE)?.remove(KEY_QUEUE_BAK)?.remove(KEY_OVERFLOW_DROPS)?.remove(KEY_EXPIRED_DROPS)?.remove(KEY_PRIORITY_DROPS)?.remove(KEY_EXHAUSTED_DROPS)?.remove(KEY_REJECTED_DROPS)?.commit() == true
                } catch (_: Exception) {
                    false
                }
                removed || try {
                    wipe?.edit()?.putString(KEY_QUEUE, "[]")?.putString(KEY_QUEUE_BAK, "[]")
                        ?.putLong(KEY_OVERFLOW_DROPS, 0L)?.putLong(KEY_EXPIRED_DROPS, 0L)
                        ?.putLong(KEY_PRIORITY_DROPS, 0L)?.putLong(KEY_EXHAUSTED_DROPS, 0L)
                        ?.putLong(KEY_REJECTED_DROPS, 0L)?.commit() == true
                } catch (_: Exception) {
                    false
                }
            }
            if (!cleared) {
                persistDirty = true
                if (volatileOnly) dropWipePendingVolatile = true
            }
            return cleared
        }
    }

    fun flushSync() {
        try {
            val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            if (volatileOnly && !onMain) {
                try {
                    tryRestorePersistent()
                } catch (_: Exception) {
                }
            }
            if (sharedPreferences == null) return
            synchronized(lock) {
                val pending = cached
                if (pending != null) persistLocked(pending, force = true, syncOnMain = true)
                if (dropsDirty) persistDropCountsLocked()
            }
            if (onMain) return
            try {
                persistExecutor.submit(java.lang.Runnable { }).get(5L, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let {
            val cutoffCached = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            val freshCached = filterFresh(it, cutoffCached)
            if (freshCached.size != it.size) {
                noteExpiredLocked((it.size - freshCached.size).toLong())
                android.util.Log.w("MessageQueue", "Dropped ${it.size - freshCached.size} expired message(s)")
                val kept = freshCached.toMutableList()
                persistLocked(kept)
                return kept
            }
            return it
        }
        val json = try {
            sharedPreferences?.getString(KEY_QUEUE, null)
        } catch (e: Exception) {
            android.util.Log.w("MessageQueue", "Queue read failed, falling back to volatile: ${com.redeye.parentalmonitor.utils.Redact.token(e.message)}")
            volatileOnly = true
            keepUnpersistedDropsLocked()
            cached = null
            try {
                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSendNext(appContext, 60_000L)
            } catch (_: Exception) {
            }
            return mutableListOf()
        }
        var parsedOk = false
        val loaded: MutableList<QueuedMessage> = try {
            if (json == null) mutableListOf()
            else {
                val parsed = gson.fromJson(json, arrayType)?.toMutableList() ?: mutableListOf()
                parsedOk = true
                parsed
            }
        } catch (e: Exception) {
            val bakJson = try { sharedPreferences?.getString(KEY_QUEUE_BAK, null) } catch (_: Exception) { null }
            val recovered: MutableList<QueuedMessage> = try {
                if (bakJson == null) mutableListOf()
                else gson.fromJson(bakJson, arrayType)?.toMutableList() ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            if (bakJson != null) {
                android.util.Log.w("MessageQueue", "Queue storage corrupt, recovered ${recovered.size} message(s) from backup")
            } else {
                android.util.Log.w("MessageQueue", "Queue storage corrupt, clearing poison blob: ${com.redeye.parentalmonitor.utils.Redact.token(e.message)}")
            }
            try {
                persistLocked(recovered, force = true, syncOnMain = true, replaceBackup = true)
            } catch (_: Exception) {
            }
            recovered
        }
        if (parsedOk && json != null) {
            try {
                val prefs = sharedPreferences
                if (prefs != null) {
                    synchronized(persistMutex) {
                        val cur = try { prefs.getString(KEY_QUEUE, null) } catch (_: Exception) { null }
                        if (cur != null && prefs.getString(KEY_QUEUE_BAK, null) != cur) {
                            val editor = prefs.edit()?.putString(KEY_QUEUE_BAK, cur)
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                editor?.apply()
                                if (bakSyncQueued.compareAndSet(false, true)) {
                                    try {
                                        persistExecutor.execute {
                                            try {
                                                val live = sharedPreferences
                                                if (live != null) {
                                                    synchronized(persistMutex) {
                                                        val nowMain = try { live.getString(KEY_QUEUE, null) } catch (_: Exception) { null }
                                                        if (nowMain != null && live.getString(KEY_QUEUE_BAK, null) != nowMain) {
                                                            live.edit()?.putString(KEY_QUEUE_BAK, nowMain)?.commit()
                                                        }
                                                    }
                                                }
                                            } catch (_: Exception) {
                                            } finally {
                                                bakSyncQueued.set(false)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        bakSyncQueued.set(false)
                                    }
                                }
                            } else {
                                editor?.commit()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val fresh = filterFresh(loaded, cutoff).toMutableList()
        if (fresh.size != loaded.size) {
            noteExpiredLocked((loaded.size - fresh.size).toLong())
            android.util.Log.w("MessageQueue", "Dropped ${loaded.size - fresh.size} expired message(s)")
            persistLocked(fresh)
            return fresh
        }
        cached = loaded
        return loaded
    }

    private fun persistLocked(queue: MutableList<QueuedMessage>, force: Boolean = false, syncOnMain: Boolean = false, replaceBackup: Boolean = false): Boolean {
        cached = queue
        val prefs = sharedPreferences ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAt < 2000L) {
            val version = ++persistVersion
            pendingSnapshot = version to ArrayList(queue)
            persistDirty = true
            schedulePersistLocked()
            return true
        }
        lastPersistAt = now
        persistDirty = false
        val version = ++persistVersion
        if (!syncOnMain && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            pendingSnapshot = version to ArrayList(queue)
            persistDirty = true
            schedulePersistLocked()
            return true
        }
        val saved = writeSnapshot(prefs, ArrayList(queue), version, replaceBackup)
        if (saved) drainFailCount = 0
        return saved
    }

    private fun jadwalUlangDrain(delayMs: Long): Boolean {
        return try {
            persistScheduler.schedule({
                try {
                    persistExecutor.execute { drainPersist() }
                } catch (_: Exception) {
                }
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun schedulePersistLocked() {
        if (!persistQueued.compareAndSet(false, true)) return
        try {
            persistExecutor.execute { drainPersist() }
        } catch (_: Exception) {
            persistQueued.set(false)
        }
    }

    private fun snapshotJson(snapshot: List<QueuedMessage>): String {
        if (snapshot.isEmpty()) {
            jsonCache.clear()
            return "[]"
        }
        val sb = StringBuilder()
        sb.append('[')
        var first = true
        for (msg in snapshot) {
            if (!first) sb.append(',')
            first = false
            val hit = jsonCache[msg.id]
            if (hit != null && hit.first == msg) {
                sb.append(hit.second)
            } else {
                val json = gson.toJson(msg)
                jsonCache[msg.id] = msg to json
                sb.append(json)
            }
        }
        sb.append(']')
        if (jsonCache.size > snapshot.size * 2 + 64) {
            val ids = snapshot.mapTo(HashSet()) { it.id }
            jsonCache.keys.retainAll(ids)
        }
        return sb.toString()
    }

    private fun writeSnapshot(prefs: SharedPreferences, snapshot: List<QueuedMessage>, version: Long, replaceBackup: Boolean = false): Boolean {
        synchronized(persistMutex) {
            if (persistVersion != version) return true
            val json = snapshotJson(snapshot)
            val saved = try {
                val editor = prefs.edit()
                val old = lastWrittenJson ?: try { prefs.getString(KEY_QUEUE, null) } catch (_: Exception) { null }
                if (editor != null && replaceBackup) {
                    editor.putString(KEY_QUEUE_BAK, json)
                } else if (editor != null && old != null && old != json) {
                    editor.putString(KEY_QUEUE_BAK, old)
                }
                editor?.putString(KEY_QUEUE, json)?.commit() == true
            } catch (_: Exception) {
                false
            }
            if (saved) lastWrittenJson = json
            if (!saved && persistVersion == version) persistDirty = true
            return saved
        }
    }

    private fun drainPersist() {
        while (true) {
            val prefs: SharedPreferences
            val snapshot: List<QueuedMessage>
            val version: Long
            synchronized(lock) {
                val pending = pendingSnapshot
                val currentPrefs = sharedPreferences
                if (pending == null || currentPrefs == null || !persistDirty) {
                    pendingSnapshot = null
                    persistDirty = false
                    persistQueued.set(false)
                    return
                }
                pendingSnapshot = null
                persistDirty = false
                prefs = currentPrefs
                version = pending.first
                snapshot = pending.second
                if (dropsDirty) persistDropCountsLocked()
            }
            if (!writeSnapshot(prefs, snapshot, version)) {
                var delayMs = 0L
                var giveUp = false
                synchronized(lock) {
                    if (pendingSnapshot == null) {
                        pendingSnapshot = version to snapshot
                        persistDirty = true
                    }
                    drainFailCount++
                    if (drainFailCount > MAX_DRAIN_RETRIES) {
                        giveUp = true
                        drainFailCount = 0
                    } else {
                        delayMs = (1000L shl (drainFailCount - 1)).coerceAtMost(60_000L)
                    }
                }
                if (giveUp) {
                    if (!jadwalUlangDrain(GIVE_UP_RETRY_MS)) {
                        persistQueued.set(false)
                    }
                    android.util.Log.e("MessageQueue", "Persist drain retries exhausted; snapshot kept dirty, slow retry scheduled")
                } else if (!jadwalUlangDrain(delayMs)) {
                    persistQueued.set(false)
                }
                return
            }
            synchronized(lock) {
                drainFailCount = 0
                if (pendingSnapshot == null && !persistDirty) {
                    persistQueued.set(false)
                    return
                }
                if (pendingSnapshot == null && persistDirty) {
                    pendingSnapshot = cached?.let { persistVersion to ArrayList(it) }
                    persistDirty = pendingSnapshot != null
                }
            }
        }
    }

    fun hasMessages(): Boolean {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.isNotEmpty()
            }
            return readLocked().isNotEmpty()
        }
    }

    private fun takeDropsLocked(counter: java.util.concurrent.atomic.AtomicLong): Long {
        val n = counter.getAndSet(0L)
        if (n <= 0L) return 0L
        dropsDirty = true
        persistDropCountsLocked()
        return n
    }

    fun takeOverflowDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(overflowDrops)
        }
    }

    fun takePriorityDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(priorityDrops)
        }
    }

    fun takeExpiredDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(expiredDrops)
        }
    }

    fun restoreOverflowDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteOverflowLocked(by)
        }
    }

    fun restorePriorityDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteDropLocked(priorityDrops, by, true)
        }
    }

    fun restoreExpiredDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteExpiredLocked(by)
        }
    }

    fun noteExhaustedDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteDropLocked(exhaustedDrops, by, true)
        }
    }

    fun noteRejectedDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteDropLocked(rejectedDrops, by, true)
        }
    }

    fun takeExhaustedDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(exhaustedDrops)
        }
    }

    fun takeRejectedDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(rejectedDrops)
        }
    }

    fun restoreExhaustedDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteDropLocked(exhaustedDrops, by, true)
        }
    }

    fun restoreRejectedDrops(by: Long) {
        if (by <= 0L) return
        synchronized(lock) {
            noteDropLocked(rejectedDrops, by, true)
        }
    }

    fun getQueueSize(): Int {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.size
            }
            return readLocked().size
        }
    }
}
