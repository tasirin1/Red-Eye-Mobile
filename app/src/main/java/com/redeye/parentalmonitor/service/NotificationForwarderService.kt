package com.redeye.parentalmonitor.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.AppWatch
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.NetworkUtils
import com.redeye.parentalmonitor.utils.NotificationPrivacy
import com.redeye.parentalmonitor.utils.TelegramSendPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.redeye.parentalmonitor.utils.ServiceStart

class NotificationForwarderService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fwdSerial = Dispatchers.IO.limitedParallelism(1)
    private val appLabelCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 100
        }
    }
    // Nilai: waktu kirim + postTime notifikasi sumber.
    // postTime membedakan kirim-ulang notifikasi yang SAMA
    // dari pesan BARU berisi identik (issue #1457).
    private val lastSent = object : LinkedHashMap<String, Pair<Long, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Long>>): Boolean {
            return size > 100
        }
    }
    private val forwardedIdsSeeded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val forwardedIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > MAX_FORWARDED_IDS
        }
    }
    private val groupForwardedSeeded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val groupForwarded = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > MAX_GROUP_FORWARDED
        }
    }
    @Volatile
    private var forwardedIdsView: Set<String> = emptySet()
    @Volatile
    private var groupForwardedView: Map<String, Long> = emptyMap()
    // Kunci khusus tulis-disk penanda (issue #1018): commit
    // sinkron tidak lagi menahan kunci dua peta penanda.
    private val markerPersistLock = Any()
    @Volatile
    private var prefsRef: PreferencesManager? = null
    @Volatile
    private var queueRef: MessageQueue? = null
    private val pendingPosts = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingOverflow = java.util.concurrent.atomic.AtomicInteger(0)
    private val pkgHits = object : LinkedHashMap<String, ArrayDeque<Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean {
            return size > 100
        }
    }
    private val pkgHitsLock = Any()
    private val dropNoticeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dropNoticeCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val credsListenerAttached = java.util.concurrent.atomic.AtomicBoolean(false)
    private val dropNoticeSeeded = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun attachCredsListener() {
        val listener = fwdCredsListener ?: return
        val prefs = prefsRef ?: return
        if (!credsListenerAttached.compareAndSet(false, true)) return
        try {
            prefs.registerChangeListener(listener)
        } catch (_: Exception) {
            credsListenerAttached.set(false)
        }
    }

    private fun adoptPrefs(prefs: PreferencesManager): PreferencesManager {
        prefsRef = prefs
        attachCredsListener()
        if (!dropNoticeSeeded.get()) {
            try {
                val seeded = (prefs.pendingNotifDrops).coerceAtLeast(0)
                if (dropNoticeSeeded.compareAndSet(false, true)) {
                    dropNoticeCount.set(seeded)
                    if (seeded > 0) {
                        val since = try { prefs.pendingNotifDropsSince } catch (_: Exception) { 0L }
                        synchronized(dropNoticeLock) { dropNoticeFirstAt = since }
                    }
                    val batchPending = try { prefs.dropNoticeBatch.isNotEmpty() } catch (_: Exception) { false }
                    if (batchPending) {
                        dropBatchWasPending = true
                        if (dropNoticeBatchReconciled.compareAndSet(false, true)) {
                            try { reconcileDropNoticeBatch() } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        if (!forwardedIdsSeeded.get()) {
            try {
                val stored = prefs.forwardedNotifIds
                if (forwardedIdsSeeded.compareAndSet(false, true)) {
                    synchronized(forwardedIds) {
                        for (id in stored) forwardedIds[id] = true
                        forwardedIdsView = LinkedHashSet(forwardedIds.keys)
                    }
                }
            } catch (_: Exception) { }
        }
        if (!groupForwardedSeeded.get()) {
            try {
                val storedGroups = prefs.notifGroupForwarded
                if (groupForwardedSeeded.compareAndSet(false, true)) {
                    synchronized(groupForwarded) {
                        for (entry in storedGroups) {
                            val sep = entry.lastIndexOf(':')
                            if (sep > 0) {
                                val ts = entry.substring(sep + 1).toLongOrNull() ?: 0L
                                if (ts > 0L) groupForwarded[entry.substring(0, sep)] = ts
                            }
                        }
                        groupForwardedView = LinkedHashMap(groupForwarded)
                    }
                }
            } catch (_: Exception) { }
        }
        return prefs
    }
    private var dropNoticeJob: Job? = null
    private var dropPersistJob: Job? = null
    private val dropNoticeLock = Any()
    private var dropNoticeInFlight = 0
    private var dropNoticeFirstAt = 0L
    private val dropNoticeBatchReconciled = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var dropBatchWasPending = false
    @Volatile
    private var watchKeywordsRaw = ""
    @Volatile
    private var watchKeywordsList: List<String> = emptyList()
    private var rebindAttempt = 0
    private var rebindNoticeSent = false
    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebindRunnable = Runnable { runRebind() }
    private val lastReviveAt = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile
    private var lastWakeStartAt = 0L
    private var wakeJob: Job? = null
    @Volatile
    private var netCheckAt = 0L
    @Volatile
    private var netCached = false
    @Volatile
    private var cachedFwdCreds: Pair<String, String> = "" to ""
    // Pasangan kredensial SATU snapshot atomik (issue #1125,
    // pola #1099): dua field terpisah yang ditulis berurutan
    // dari benang listener dan dibaca terpisah di dispatcher
    // bisa sobek — satu kiriman memakai token baru + chat lama.
    private var fwdCredsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var cfgCacheAt = 0L
    @Volatile
    private var cfgCacheFilled = false
    @Volatile
    private var cfgCacheEnabled = false
    @Volatile
    private var cfgCacheForward = true
    @Volatile
    private var cfgCacheConfigured = false
    private val groupSeen = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 100
        }
    }

    data class NotifRecord(val app: String, val title: String, val text: String, val at: Long)

    companion object {
        private const val MAX_FORWARDED_IDS = 100
        private const val MAX_GROUP_FORWARDED = 50
        private const val GROUP_SUMMARY_HOLD_MS = 30 * 60_000L
        private const val MAX_HISTORY = 20
        private const val MAX_QUEUED = 64
        private const val MAX_OVERFLOW_QUEUE = 8
        private const val MAX_BATCH = 32
        private const val NORMAL_RATE_LIMIT = 10
        private const val PRIORITY_RATE_LIMIT = 20
        private val REBIND_DELAYS_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L, 1_800_000L)
        private const val WAKE_PING_COOLDOWN_MS = 120_000L
        private val PRIORITY_CODE_REGEX = Regex("(?<!\\d)(?:\\d[ .-]?){3,11}\\d(?![ .-]?\\d)")
        private val history = ArrayDeque<NotifRecord>()
        private val historyLock = Any()

        fun requestRebindFrom(context: android.content.Context) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(context, NotificationForwarderService::class.java)
                )
            } catch (_: Exception) {
            }
        }

        fun record(app: String, title: String, text: String) {
            synchronized(historyLock) {
                history.addLast(NotifRecord(app, title, text, System.currentTimeMillis()))
                while (history.size > MAX_HISTORY) history.removeFirst()
            }
        }

        private const val DROP_SUMMARY_APP = "(gugur)"
        private const val DROP_SUMMARY_WINDOW_MS = 60_000L
        private var dropSummaryCount = 0
        private var dropSummaryAt = 0L

        fun recordDrop() {
            synchronized(historyLock) {
                val now = System.currentTimeMillis()
                if (dropSummaryAt > 0L && now - dropSummaryAt < DROP_SUMMARY_WINDOW_MS) {
                    dropSummaryCount += 1
                } else {
                    dropSummaryCount = 1
                    // Jangkar jendela dipasang di gugur PERTAMA
                    // rantai ini dan tidak digeser oleh gugur
                    // berikutnya (issue #1178): sebelumnya jendela
                    // diukur dari gugur terakhir sehingga rantai
                    // berjeda <60 dtk menumpuk tanpa batas di bawah
                    // label "60 detik terakhir".
                    dropSummaryAt = now
                }
                val summaryIndex = history.indexOfLast { it.app == DROP_SUMMARY_APP }
                if (summaryIndex >= 0) history.removeAt(summaryIndex)
                history.addLast(NotifRecord(DROP_SUMMARY_APP, "", "$dropSummaryCount notifikasi gugur dalam 60 detik terakhir (antrean penuh)", now))
                while (history.size > MAX_HISTORY) history.removeFirst()
            }
        }

        fun history(): List<NotifRecord> {
            synchronized(historyLock) {
                return history.toList()
            }
        }

        fun clearHistory() {
            synchronized(historyLock) {
                history.clear()
            }
        }

        private val dropWriteLock = Any()

        @Volatile
        private var dropResetGeneration = 0L

        @Volatile
        private var activeInstance: NotificationForwarderService? = null

        fun resetDropCounters(prefs: PreferencesManager) {
            synchronized(dropWriteLock) {
                dropResetGeneration++
                val inst = activeInstance
                if (inst != null) {
                    synchronized(inst.dropNoticeLock) {
                        inst.dropNoticeCount.set(0)
                        inst.dropNoticeFirstAt = 0L
                    }
                }
                try { prefs.setPendingNotifDropsSync(0, 0L) } catch (_: Exception) { }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        // Initialize synchronously to avoid race with onNotificationPosted
        try {
            adoptPrefs(PreferencesManager.getInstance(this@NotificationForwarderService))
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Preferences init failed in onCreate, will retry lazily", e)
        }
        try {
            queueRef = MessageQueue.getInstance(this@NotificationForwarderService)
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Queue init failed in onCreate, will retry lazily", e)
        }
        fwdCredsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PreferencesManager.KEY_BOT_TOKEN || key == PreferencesManager.KEY_CHAT_ID) { refreshFwdCreds(); cfgCacheAt = 0L; cfgCacheFilled = false }
            if (key == PreferencesManager.KEY_NOTIF_FORWARD || key == PreferencesManager.KEY_MONITORING_ENABLED || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_USER_DISABLED || key == PreferencesManager.KEY_USER_CONSENTED) { cfgCacheAt = 0L; cfgCacheFilled = false }
        }
        attachCredsListener()
        scope.launch {
            try {
                refreshFwdCreds()
                try { queueRef?.tryRestorePersistent() } catch (_: Exception) { }
                try {
                    if (dropNoticeCount.get() > 0) {
                        synchronized(dropNoticeLock) {
                            if (dropNoticeJob?.isActive != true) {
                                val startDelay = if (dropBatchWasPending) 0L else 30_000L
                                dropNoticeJob = scope.launch {
                                    try {
                                        delay(startDelay)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        flushDropNotice()
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                queueRef?.hasMessages()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        startWakeLoop()
    }

    private fun refreshFwdCreds() {
        try {
            cachedFwdCreds = prefsRef?.botToken.orEmpty() to prefsRef?.chatId.orEmpty()
        } catch (_: Exception) {
        }
    }

    private fun recordOverflow(pkg: String, title: String, text: String) {
        try {
            noteDroppedNotification()
        } catch (_: Exception) {
        }
        val cached = try { synchronized(appLabelCache) { appLabelCache[pkg] } } catch (_: Exception) { null }
        if (cached != null) {
            try { record(cached, title, text) } catch (_: Exception) { }
            return
        }
        scope.launch {
            try { record(overflowLabel(pkg), title, text) } catch (_: Exception) { }
        }
    }

    private fun overflowLabel(pkg: String): String {
        val cached = synchronized(appLabelCache) { appLabelCache[pkg] }
        if (cached != null) return cached
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString().also { resolved ->
                synchronized(appLabelCache) { appLabelCache[pkg] = resolved }
            }
        } catch (_: Exception) {
            pkg
        }
    }

    private fun refreshCfgCache(prefs: PreferencesManager, now: Long) {
        cfgCacheEnabled = try {
            prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
        cfgCacheForward = try {
            prefs.notifForwardEnabled
        } catch (_: Exception) {
            true
        }
        cfgCacheConfigured = try {
            prefs.isConfigured()
        } catch (_: Exception) {
            false
        }
        cfgCacheAt = now
        cfgCacheFilled = true
    }

    private fun ensureCfgCache(prefs: PreferencesManager) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!cfgCacheFilled || now - cfgCacheAt >= 10_000L) refreshCfgCache(prefs, now)
    }

    private fun forwardingAllowed(): Boolean {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { adoptPrefs(it) }
        } catch (_: Exception) {
            return false
        }
        ensureCfgCache(prefs)
        return cfgCacheEnabled && cfgCacheForward && cfgCacheConfigured
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        reviveMonitoringIfNeeded()
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val groupKey = try {
            sbn.groupKey ?: pkg
        } catch (_: Exception) {
            pkg
        }
        val sbnKey = try { sbn.key ?: "" } catch (_: Exception) { "" }
        val sbnPostTime = try { sbn.postTime } catch (_: Exception) { 0L }
        if (pendingPosts.incrementAndGet() > MAX_QUEUED) {
            pendingPosts.decrementAndGet()
            if (pendingOverflow.incrementAndGet() > MAX_OVERFLOW_QUEUE) {
                pendingOverflow.decrementAndGet()
                try { noteDroppedNotification() } catch (_: Exception) { }
                try { recordDrop() } catch (_: Exception) { }
                return
            }
            // Jalur luapan ikut antrean serial yang sama dengan jalur
            // normal (issue #703) agar urutan & gerbang grup tak berlomba.
            scope.launch(fwdSerial) {
                try {
                    val nf = notifTitleText(notification)
                    if (nf.title.isNotEmpty() || nf.text.isNotEmpty()) {
                        val squeezedJoined = nf.rawTitle + "\n" + nf.rawBody
                        // Izin dinilai lebih dulu (issue #1123):
                        // pindai prioritas hanya dibayar bila
                        // penerusan memang diperbolehkan.
                        val allowed = forwardingAllowed()
                        val squeezedPriority = if (allowed) try { isPriorityNotif(pkg, nf.rawTitle, nf.rawBody, squeezedJoined.lowercase(java.util.Locale.ROOT)) } catch (_: Exception) { false } else false
                        if (squeezedPriority && allowed) {
                            val squeezedLabel = try { synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg } catch (_: Exception) { pkg }
                            emitPost(pkg, groupKey, squeezedLabel, nf.title, nf.text, android.os.SystemClock.elapsedRealtime(), !isSummary && groupKey.isNotEmpty(), nf.rawTitle, nf.rawBody, sbnKey, sbnPostTime, isSummary, squeezedJoined)
                        } else if (allowed) recordOverflow(pkg, nf.title, nf.text)
                        // Bila penerusan memang mati/nonaktif
                        // (issue #993): notifikasi ini bukan
                        // "gugur antrean" — jangan dicatat
                        // sebagai luapan, agar hitungan dan
                        // jurnal gugur tak menggelembung palsu
                        // selama penerusan nonaktif.
                    }
                } catch (_: Exception) {
                } finally {
                    pendingOverflow.decrementAndGet()
                }
            }
            return
        }
        scope.launch(fwdSerial) {
            try {
                handlePosted(pkg, notification, groupKey, isSummary, sbnKey, sbnPostTime)
            } finally {
                pendingPosts.decrementAndGet()
            }
        }
    }

    private data class NotifText(val title: String, val text: String, val rawTitle: String, val rawBody: String)
    // Gabungan tumpukan MessagingStyle terakhir per kunci
    // notifikasi (issue #1472): pesan baru hanya meneruskan
    // ekor barunya, bukan seluruh tumpukan berulang.
    // kunciTumpukanAktif diisi handlePosted (utas serial
    // yang sama) tepat sebelum notifTitleText dipanggil.
    private val tumpukanDulu = HashMap<String, String>()
    private var kunciTumpukanAktif = ""

    private fun notifTitleText(notification: Notification): NotifText {
        return try {
            val extras = notification.extras
            // Tiap medan dibaca dengan penjaganya sendiri
            // (issue #932): dulu satu try menyeluruh — satu
            // extra rusak (Bundle tak bisa diurai) membuang
            // SELURUH notifikasi walau medan lain sehat.
            fun teks(key: String): String = try {
                val mentah = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
                // Potong sejak di sumber (issue #940): teks
                // raksasa (mis. log yang ditempel sebagai
                // EXTRA_TEXT) dulu diproses utuh oleh masking,
                // lowercase, dan hash sebelum titik potong akhir
                // 200/300 — 20 rb aksara jauh di atas kebutuhan.
                if (mentah.length > 20_000) {
                    mentah.take(com.redeye.parentalmonitor.utils.TextChunk.safeCut(mentah, 20_000))
                } else {
                    mentah
                }
            } catch (_: Exception) {
                ""
            }
            var tumpukanGabung = ""
            val title = teks(Notification.EXTRA_TITLE)
            var body = teks(Notification.EXTRA_TEXT)
            if (body.isEmpty()) {
                body = teks(Notification.EXTRA_BIG_TEXT)
            }
            if (body.isEmpty()) {
                try {
                    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.asSequence()?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }?.toList()
                    if (!lines.isNullOrEmpty()) {
                        // Gabung dengan batas sejak awal (issue
                        // #958, sisa #940): joinToString utuh
                        // mewujudkan seluruh string gabungan (bisa
                        // sangat besar dari EXTRA_TEXT_LINES
                        // aplikasi chat) sebelum potongan 20 rb
                        // berlaku. Akumulasi berhenti di batas;
                        // safeCut akhir menjaga batas surrogate.
                        val sb = StringBuilder()
                        for (line in lines) {
                            if (sb.length >= 20_000) break
                            if (sb.isNotEmpty()) sb.append('\n')
                            val sisa = 20_000 - sb.length
                            if (line.length <= sisa) sb.append(line) else sb.append(line.substring(0, sisa))
                        }
                        val gabung = sb.toString().trim()
                        body = if (gabung.length >= 20_000) gabung.take(com.redeye.parentalmonitor.utils.TextChunk.safeCut(gabung, 20_000)) else gabung
                    }
                } catch (_: Exception) {
                }
            }
            // Tumpukan MessagingStyle (issue #1458):
            // EXTRA_MESSAGES tak pernah dibaca — pesan yang
            // menumpuk dalam satu notifikasi hanya tertangkap
            // teks terakhirnya. Bila gabungan pesannya lebih
            // kaya dari isi semasa, pakai gabungan itu.
            try {
                @Suppress("DEPRECATION")
                val pesanArr = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (pesanArr != null && pesanArr.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (item in pesanArr) {
                        val b = item as? android.os.Bundle ?: continue
                        val t = try { b.getCharSequence("text")?.toString()?.trim() } catch (_: Exception) { null }
                        if (t.isNullOrEmpty()) continue
                        if (sb.length >= 20_000) break
                        if (sb.isNotEmpty()) sb.append('\n')
                        val sisa = 20_000 - sb.length
                        if (t.length <= sisa) sb.append(t) else sb.append(t.substring(0, sisa))
                    }
                    val gabungPesan = sb.toString().trim()
                    if (gabungPesan.length > body.length) {
                        body = gabungPesan
                        tumpukanGabung = gabungPesan
                    }
                }
            } catch (_: Exception) {
            }
            if (body.isEmpty()) {
                body = teks(Notification.EXTRA_SUB_TEXT)
            }
            // Potong ekor tumpukan (issue #1472): bila
            // gabungan tumpukan ini meneruskan gabungan
            // terakhir untuk notifikasi yang sama, body
            // menjadi sisa barunya saja (penopengan di
            // bawah tetap berlaku atas sisa itu).
            if (tumpukanGabung.isNotEmpty() && kunciTumpukanAktif.isNotEmpty()) {
                try {
                    val kunci = kunciTumpukanAktif
                    val prev = synchronized(tumpukanDulu) { tumpukanDulu[kunci] }
                    synchronized(tumpukanDulu) {
                        if (tumpukanDulu.size > 100) tumpukanDulu.clear()
                        tumpukanDulu[kunci] = tumpukanGabung
                    }
                    if (prev != null && prev.isNotEmpty() && tumpukanGabung.startsWith(prev)) {
                        val sisa = tumpukanGabung.removePrefix(prev).removePrefix("\n")
                        if (sisa.isNotEmpty()) body = sisa
                    }
                } catch (_: Exception) {
                }
            }
            var safeTitle = title
            var safeBody = body
            val mask = try {
                (prefsRef ?: PreferencesManager.getInstance(this)).otpMaskEnabled
            } catch (_: Exception) {
                true
            }
            if (mask && NotificationPrivacy.isOtp(title + "\n" + body)) {
                safeTitle = NotificationPrivacy.mask(title)
                safeBody = NotificationPrivacy.mask(body)
            }
            NotifText(safeTitle, safeBody, title, body)
        } catch (_: Exception) {
            NotifText("", "", "", "")
        }
    }

    private suspend fun handlePosted(pkg: String, notification: Notification, groupKey: String = "", isSummary: Boolean = false, sbnKey: String = "", sbnPostTime: Long = 0L) {
        var prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { adoptPrefs(it) }
        } catch (_: Exception) {
            return
        }
        if (!prefs.isStorageEncrypted) {
            try {
                if (PreferencesManager.refreshInstance(this)) {
                    prefs = PreferencesManager.getInstance(this)
                    prefsRef = prefs
                }
            } catch (_: Exception) {
            }
        }
        ensureCfgCache(prefs)
        val cfgEnabled = cfgCacheEnabled
        val cfgForward = cfgCacheForward
        val cfgConfigured = cfgCacheConfigured
        if (!cfgEnabled) {
            return
        }
        kunciTumpukanAktif = sbnKey
        val nf = try { notifTitleText(notification) } finally { kunciTumpukanAktif = "" }
        val title = nf.title
        val text = nf.text
        if (title.isEmpty() && text.isEmpty()) return
        val appLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            "${packageManager.getApplicationLabel(info)}".also { label ->
                synchronized(appLabelCache) { appLabelCache[pkg] = label }
            }
        } catch (_: Exception) {
            pkg
        }
        if (!cfgForward || !cfgConfigured) {
            record(appLabel, title, text)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        emitPost(pkg, groupKey, appLabel, title, text, now, trackGroup = !isSummary && groupKey.isNotEmpty(), rawTitle = nf.rawTitle, rawBody = nf.rawBody, sbnKey = sbnKey, sbnPostTime = sbnPostTime, isSummary = isSummary, keyJoined = nf.rawTitle + "\n" + nf.rawBody)
    }

    private fun notifIdHash(sbnKey: String, postTime: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(sbnKey.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(postTime.toString().toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
    }

    private fun isForwardedId(idHash: String): Boolean {
        return forwardedIdsView.contains(idHash)
    }

    private fun groupKeyHash(groupKey: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(groupKey.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
    }

    private fun groupForwardedAt(groupKey: String): Long {
        val hash = try { groupKeyHash(groupKey) } catch (_: Exception) { return 0L }
        return groupForwardedView[hash] ?: 0L
    }

    private fun markGroupAndIdForwarded(groupKey: String, idHash: String) {
        markMarkersForwarded(
            if (groupKey.isEmpty()) emptyList() else listOf(groupKey),
            if (idHash.isEmpty()) emptyList() else listOf(idHash)
        )
    }

    // Penanda gabungan banyak grup + banyak id dalam SATU
    // tulis commit (issue #979): jalur batch sebelumnya
    // memanggil penanda id dan penanda grup terpisah, jadi
    // tiap potongan membayar dua commit yang masing-masing
    // menulis ulang snapshot peta penuh.
    private fun markMarkersForwarded(groupKeys: Collection<String>, idHashes: Collection<String>) {
        if (groupKeys.isEmpty() && idHashes.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(groupForwarded) {
            synchronized(forwardedIds) {
                for (groupKey in groupKeys) {
                    if (groupKey.isEmpty()) continue
                    val hash = try { groupKeyHash(groupKey) } catch (_: Exception) { null }
                    if (hash != null) groupForwarded[hash] = now
                }
                for (idHash in idHashes) {
                    if (idHash.isNotEmpty()) forwardedIds[idHash] = true
                }
                groupForwardedView = LinkedHashMap(groupForwarded)
                forwardedIdsView = LinkedHashSet(forwardedIds.keys)
            }
        }
        // Commit disk di LUAR kunci peta (issue #1018): dulu
        // tulis sinkron prefs terenkripsi berjalan sambil
        // menahan dua kunci, menahan penulis lain (seeding
        // adoptPrefs, flush lain). Yang ditulis adalah view
        // terbaru yang dibaca di bawah kunci persist, jadi
        // tulisan yang lebih baru tidak tertimpa yang lama.
        synchronized(markerPersistLock) {
            val groupSnapshot = groupForwardedView.entries.map { it.key + ":" + it.value }
            val idSnapshot = forwardedIdsView.toList()
            try {
                (prefsRef ?: PreferencesManager.getInstance(this)).setForwardedMarkersSync(groupSnapshot, idSnapshot)
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun emitPost(
        pkg: String,
        groupKey: String,
        label: String,
        title: String,
        text: String,
        now: Long,
        trackGroup: Boolean,
        rawTitle: String = "",
        rawBody: String = "",
        sbnKey: String = "",
        sbnPostTime: Long = 0L,
        isSummary: Boolean = false,
        keyJoined: String = ""
    ) {
        if (isSummary && groupKey.isNotEmpty()) {
            // Cek dan penandaan groupSeen dalam SATU blok synchronized
            // (issue #703): blok terpisah membuat dua ringkasan grup yang
            // sama bisa lolos bersamaan.
            val seenAt = synchronized(groupSeen) { groupSeen[groupKey] }
            if (seenAt != null && now - seenAt < 120_000L) {
                record(label, title, text)
                return
            }
            val childAt = groupForwardedAt(groupKey)
            if (childAt > 0L && System.currentTimeMillis() - childAt < GROUP_SUMMARY_HOLD_MS) {
                record(label, title, text)
                return
            }
            // Klaim slot ringkasan secara atomik: periksa ulang di dalam
            // kunci agar dua ringkasan bersamaan tak sama-sama lolos.
            val claimed = synchronized(groupSeen) {
                val prev = groupSeen[groupKey]
                if (prev != null && now - prev < 120_000L) {
                    false
                } else {
                    groupSeen[groupKey] = now
                    true
                }
            }
            if (!claimed) {
                record(label, title, text)
                return
            }
        }
        val keyTitle = if (rawTitle.isEmpty() && rawBody.isEmpty()) title else rawTitle
        val keyBody = if (rawTitle.isEmpty() && rawBody.isEmpty()) text else rawBody
        if (trackGroup && groupKey.isNotEmpty()) {
            try {
                synchronized(groupSeen) { groupSeen[groupKey] = now }
            } catch (_: Exception) {
            }
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (part in arrayOf(pkg, keyTitle, keyBody)) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        val key = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
        val joined = if (rawTitle.isEmpty() && rawBody.isEmpty()) keyTitle + "\n" + keyBody else keyJoined.ifEmpty { keyTitle + "\n" + keyBody }
        val idHash = if (sbnKey.isNotEmpty()) try { notifIdHash(sbnKey, sbnPostTime) } catch (_: Exception) { "" } else ""
        // Cap dedup ditulis SESUDAH penanganan terkonfirmasi
        // (issue #934): dulu cap ditulis di sini, sebelum kirim —
        // kirim yang gugur menelan kiriman ulang <10 detik.
        val capTerkirim = {
            synchronized(lastSent) { lastSent[key] = now to sbnPostTime }
        }
        val alreadyHandled = synchronized(lastSent) {
            val prev = lastSent[key]
            // Duplikat konten hanya bila posting yang SAMA
            // (postTime sama): dua pesan "ok" berurutan adalah
            // posting berbeda dan harus sama-sama sampai
            // (issue #1457). postTime tak dikenal (0) kembali
            // ke perilaku lama berbasis waktu saja.
            val dupContent = prev != null && now - prev.first < 10_000L &&
                (sbnPostTime == 0L || prev.second == 0L || prev.second == sbnPostTime)
            val dupId = idHash.isNotEmpty() && isForwardedId(idHash)
            dupContent || dupId
        }
        if (alreadyHandled) {
            record(label, title, text)
            return
        }
        // Lowercase teks utuh + pindai prioritas baru dihitung
        // SESUDAH gerbang dedup (issue #1123): hasilnya tak
        // dipakai pada jalur buang, dan jalur ini terpanas —
        // notifikasi chat yang sama diperbarui berkali-kali
        // dan mayoritas berakhir duplikat.
        val lowerJoined = joined.lowercase(java.util.Locale.ROOT)
        val maskedLower = (title + "\n" + text).lowercase(java.util.Locale.ROOT)
        val priority = try { isPriorityNotif(pkg, keyTitle, keyBody, lowerJoined) } catch (_: Exception) { false }
        if (pkgShouldDrop(pkg, now, if (priority) PRIORITY_RATE_LIMIT else NORMAL_RATE_LIMIT)) {
            if (priority) {
                record(label, title, text)
                var queued = false
                try {
                    queued = queue().addMessage("❗ " + buildNotifMessage(label, title, text, maskedLower), true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                if (queued) {
                    capTerkirim()
                    markGroupAndIdForwarded(if (trackGroup) groupKey else "", idHash)
                } else {
                    // Gugur di cabang ini tetap tercatat
                    // (issue #1325): penolakan antrean durable
                    // dulu senyap — satu-satunya situs penolakan
                    // tanpa akuntansi; situs luapan lain sudah
                    // mencatat ke penghitung gugur yang sama
                    // (pola #1271). Cap terkirim memang TIDAK
                    // dipasang di sini (pesan tak terantre).
                    try { noteDroppedNotification() } catch (_: Exception) { }
                }
                return
            }
            val spamLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg
            val lastNotice = dropNoticeAt[pkg] ?: 0L
            if (now - lastNotice > 120_000L) {
                if (dropNoticeAt.size > 64) {
                    val cutoff = now - 3_600_000L
                    dropNoticeAt.entries.removeIf { it.value < cutoff }
                }
                dropNoticeAt[pkg] = now
                forwardToTelegram("Penyaring spam: 10+ pembaruan dari " + Html.escape(spamLabel) + " dalam 2 menit, sisanya disimpan di riwayat /lastnotify.")
            }
            // Terbuang SENGAJA oleh penyaring: tetap dicap agar
            // bukaannya tidak mengulang pemberitahuan spam.
            capTerkirim()
            record(label, title, text)
            return
        }
        val message = buildNotifMessage(label, title, text, maskedLower)
        record(label, title, text)
        if (priority) {
            val delivered = try {
                forwardLocked("❗ " + message, 1, priority = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                true
            } catch (_: Exception) {
                false
            }
            if (delivered) {
                capTerkirim()
                markGroupAndIdForwarded(if (trackGroup) groupKey else "", idHash)
            }
            return
        }
        try {
            if (forwardToTelegram(message, idHash, if (trackGroup) groupKey else "")) {
                capTerkirim()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildNotifMessage(label: String, title: String, text: String, lowerJoined: String): String {
        return buildString {
            appendLine("🔔 <b>Notifikasi</b>")
            try {
                val raw = (prefsRef ?: PreferencesManager.getInstance(this@NotificationForwarderService)).watchKeywords
                if (raw.isNotEmpty()) {
                    if (raw != watchKeywordsRaw) {
                        watchKeywordsList = AppWatch.parseKeywords(raw)
                        watchKeywordsRaw = raw
                    }
                    // Bahan pantau isi-saja (issue #1459),
                    // selaras isPriorityNotif.
                    val bahanPantau = if (text.isNotEmpty()) text.lowercase(java.util.Locale.ROOT) else lowerJoined
                    val hit = AppWatch.hitLowered(watchKeywordsList, bahanPantau)
                    if (hit != null) appendLine("⚠️ Watch: " + Html.escape(hit))
                }
            } catch (_: Exception) {
            }
            appendLine("Apl: ${Html.escape(label)}")
            if (title.isNotEmpty()) appendLine("Judul: ${Html.escape(title).let { it.take(com.redeye.parentalmonitor.utils.TextChunk.safeCut(it, 200)) }}")
            if (text.isNotEmpty()) appendLine("Isi: ${Html.escape(text).let { it.take(com.redeye.parentalmonitor.utils.TextChunk.safeCut(it, 300)) }}")
        }
    }

    override fun onListenerConnected() {
        reviveMonitoringIfNeeded()
        try {
            rebindAttempt = 0
            rebindNoticeSent = false
            rebindHandler.removeCallbacks(rebindRunnable)
        } catch (_: Exception) {
        }
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("NotifForwarder", "Notification listener connected")
        scope.launch {
            try {
                prefsRef = prefsRef ?: adoptPrefs(PreferencesManager.getInstance(this@NotificationForwarderService))
                queueRef = queueRef ?: MessageQueue.getInstance(this@NotificationForwarderService)
                queueRef?.hasMessages()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            // Pindai notifikasi aktif saat tersambung
            // (issue #1460): dulu bergantung penuh pada
            // kirim-ulang platform — notifikasi yang
            // terposting lalu terhapus selama listener
            // lepas hilang tanpa jejak. Kini yang masih
            // aktif dilewatkan jalur masuk yang sama;
            // dedupe id + konten sadar-postTime menahan
            // yang sudah pernah diteruskan.
            try {
                val aktif = activeNotifications
                if (aktif != null) {
                    for (sbn in aktif) {
                        try { onNotificationPosted(sbn) } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onListenerDisconnected() {
        android.util.Log.w("NotifForwarder", "Notification listener disconnected")
        scheduleRebind()
    }

    private fun scheduleRebind() {
        try {
            val exhausted = rebindAttempt >= REBIND_DELAYS_MS.size
            val delayMs = REBIND_DELAYS_MS[rebindAttempt.coerceIn(0, REBIND_DELAYS_MS.size - 1)]
            rebindAttempt++
            rebindHandler.removeCallbacks(rebindRunnable)
            rebindHandler.postDelayed(rebindRunnable, delayMs)
            if (exhausted && !rebindNoticeSent) {
                rebindNoticeSent = true
                scope.launch {
                    try {
                        queue().addMessage("⚠️ Akses notifikasi terputus dan belum pulih walau sudah dicoba berulang. Buka aplikasi → Setup → izinkan akses notifikasi, atau kirim /recover. Aplikasi tetap mencoba menyambung tiap 30 menit.", true)
                        MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun runRebind() {
        try {
            requestRebind(ComponentName(this, NotificationForwarderService::class.java))
        } catch (_: Exception) {
        }
        scheduleRebind()
    }

    private fun startWakeLoop() {
        try {
            wakeJob?.cancel()
        } catch (_: Exception) {
        }
        wakeJob = scope.launch {
            while (isActive) {
                try {
                    if (!MonitoringService.isRunning) pollWakeOnce()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                try {
                    val pausedLoop = try {
                        (prefsRef ?: PreferencesManager.getInstance(this@NotificationForwarderService)).monitoringPaused
                    } catch (_: Exception) {
                        false
                    }
                    delay(if (pausedLoop) 300_000L else 60_000L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun pollWakeOnce() {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { adoptPrefs(it) }
        } catch (_: Exception) {
            return
        }
        val resumeOk = try {
            prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && !prefs.monitoringPaused
        } catch (_: Exception) {
            false
        }
        if (!resumeOk) return
        if (MonitoringService.isRunning) return
        val nowWake = android.os.SystemClock.elapsedRealtime()
        if (nowWake - lastWakeStartAt < WAKE_PING_COOLDOWN_MS) return
        lastWakeStartAt = nowWake
        var started = false
        try {
            val restart = android.content.Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            ServiceStart.startForeground(this, restart)
            started = true
        } catch (_: Exception) {
            android.util.Log.w("NotifForwarder", "Wake restart failed, retry via worker")
        }
        if (!started) {
            try {
                MessageScheduler.scheduleBootRestart(this)
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        }
    }

    private fun reviveMonitoringIfNeeded() {
        try {
            if (MonitoringService.isRunning) return
            // Klaim jatah 60 detik via CAS (issue #1104):
            // cek-lalu-tulis telanjang atas @Volatile bisa
            // dilolosi dua pemanggil bersamaan — tepat satu
            // yang memenangkan cap yang berhak meluncurkan.
            val now = android.os.SystemClock.elapsedRealtime()
            while (true) {
                val last = lastReviveAt.get()
                if (now - last < 60_000L) return
                if (lastReviveAt.compareAndSet(last, now)) break
            }
            scope.launch {
                try {
                    val prefs = prefsRef ?: try {
                        PreferencesManager.getInstance(this@NotificationForwarderService).also { adoptPrefs(it) }
                    } catch (_: Exception) {
                        return@launch
                    }
                    val resume = try {
                        prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && !prefs.monitoringPaused
                    } catch (_: Exception) {
                        false
                    }
                    if (!resume) return@launch
                    try {
                        val restart = android.content.Intent(this@NotificationForwarderService, MonitoringService::class.java).apply {
                            action = MonitoringService.ACTION_START_MONITORING
                        }
                        ServiceStart.startForeground(this@NotificationForwarderService, restart)
                    } catch (_: Exception) {
                        android.util.Log.w("NotifForwarder", "Monitoring restart failed, retry via worker")
                        try {
                            MessageScheduler.scheduleBootRestart(this@NotificationForwarderService)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun salvageBatch() {
        try {
            val leftover = synchronized(batchLock) {
                val items = batchBuf.toList()
                batchBuf.clear()
                items
            }
            if (leftover.isNotEmpty()) {
                // Penandaan per item (issue #791): buffer sudah terlanjur
                // dikuras, jadi item yang selamat wajib ditandai walau
                // sebagian batch tereviksi — pola all-or-nothing lama
                // membuat yang selamat terkirim tanpa tanda (dobel saat
                // replay) dan yang tereviksi hilang tanpa tercatat.
                var results: List<Boolean> = emptyList()
                try {
                    results = queue().addMessagesPerItem(leftover.map { it.first })
                    queue().flushSync()
                } catch (_: Exception) {
                }
                val survived = leftover.filterIndexed { idx, _ -> results.getOrElse(idx) { false } }
                if (survived.isNotEmpty()) {
                    markMarkersForwarded(
                        survived.map { it.third }.filter { it.isNotEmpty() }.distinct(),
                        survived.map { it.second }.filter { it.isNotEmpty() }
                    )
                }
                try {
                    MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onTrimMemory(level: Int) {
        try {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) salvageBatch()
        } catch (_: Exception) {
        }
        try { super.onTrimMemory(level) } catch (_: Exception) { }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        try { salvageBatch() } catch (_: Exception) { }
        try { super.onTaskRemoved(rootIntent) } catch (_: Exception) { }
    }

    private fun salvageInFlight() {
        try {
            val sisa = synchronized(batchLock) {
                if (flushSalvageClaimed) {
                    emptyList()
                } else {
                    flushSalvageClaimed = true
                    val items = flushInFlight
                    flushInFlight = emptyList()
                    items
                }
            }
            if (sisa.isNotEmpty()) {
                try {
                    queue().addMessages(sisa.map { it.first })
                    queue().flushSync()
                } catch (_: Exception) {
                }
                try {
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        // Selamatkan sisa flush yang sedang berjalan SEBELUM scope.cancel()
        // (issue #704) — jalur tangkap asinkron tak selalu sempat jalan
        // bila proses mati tepat sesudah onDestroy.
        try { salvageInFlight() } catch (_: Exception) { }
        try {
            synchronized(dropWriteLock) {
                val pending = synchronized(dropNoticeLock) {
                    (dropNoticeCount.get() + dropNoticeInFlight) to dropNoticeFirstAt
                }
                prefsRef?.setPendingNotifDropsSync(pending.first, pending.second)
            }
        } catch (_: Exception) {
        }
        try {
            salvageBatch()
        } catch (_: Exception) {
        }
        try { wakeJob?.cancel() } catch (_: Exception) { }
        try { rebindHandler.removeCallbacks(rebindRunnable) } catch (_: Exception) { }
        try { fwdCredsListener?.let { prefsRef?.unregisterChangeListener(it) } } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }


    private fun pkgShouldDrop(pkg: String, now: Long, limit: Int): Boolean {
        if (pkg.isEmpty()) return false
        synchronized(pkgHitsLock) {
            val q = pkgHits.getOrPut(pkg) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() > 120_000L) q.removeFirst()
            if (q.size >= limit) {
                q.addLast(now)
                while (q.size > 30) q.removeFirst()
                return true
            }
            q.addLast(now)
            while (q.size > 30) q.removeFirst()
            return false
        }
    }

    private fun redactToken(value: String?): String {
        val token = try {
            prefsRef?.botToken ?: PreferencesManager.getInstance(this).botToken
        } catch (_: Exception) {
            ""
        }
        val chat = try {
            prefsRef?.chatId ?: PreferencesManager.getInstance(this).chatId
        } catch (_: Exception) {
            ""
        }
        val pin = try { prefsRef?.accessPin ?: PreferencesManager.getInstance(this).accessPin } catch (_: Exception) { "" }
        return com.redeye.parentalmonitor.utils.Redact.token(value, token, chat, pin)
    }

    private fun queue(): MessageQueue {
        queueRef?.let { return it }
        return MessageQueue.getInstance(this).also { queueRef = it }
    }

    // Klasifikasi paket prioritas kini di
    // utils/PriorityPackages agar terkunci uji (issue #977).
    private fun pkgPrioritas(lowPkg: String): Boolean {
        return com.redeye.parentalmonitor.utils.PriorityPackages.isPriority(lowPkg)
    }

    private fun isPriorityNotif(pkg: String, title: String, text: String, lowText: String): Boolean {
        return try {
            val lowPkg = pkg.lowercase(java.util.Locale.ROOT)
            try {
                val raw = (prefsRef ?: PreferencesManager.getInstance(this)).watchKeywords
                if (raw.isNotEmpty()) {
                    if (raw != watchKeywordsRaw) {
                        watchKeywordsList = AppWatch.parseKeywords(raw)
                        watchKeywordsRaw = raw
                    }
                    // Kata pantau dinilai atas ISI saja
                    // (issue #1459): judul notifikasi chat
                    // adalah nama grup/pengirim — kecocokan
                    // di sana menandai SEMUA pesan grup itu
                    // selamanya. Isi kosong -> judul jadi
                    // satu-satunya bahan (perilaku lama).
                    val bahanPantau = if (text.isNotEmpty()) text.lowercase(java.util.Locale.ROOT) else lowText
                    if (AppWatch.hitLowered(watchKeywordsList, bahanPantau) != null) return true
                }
            } catch (_: Exception) {
            }
            if (lowText.contains("missed call") || lowText.contains("panggilan tak terjawab") || lowText.contains("tak terjawab") || lowText.contains("missed video call")) return true
            if (pkgPrioritas(lowPkg)) return true
            if (PRIORITY_CODE_REGEX.containsMatchIn(title + " " + text) && (lowText.contains("otp") || lowText.contains("kode") || lowText.contains("verif") || lowText.contains("pin") || lowText.contains("token") || lowText.contains("auth"))) return true
            false
        } catch (_: Exception) {
            false
        }
    }


    private val batchLock = Any()
    private val batchBuf = ArrayDeque<Triple<String, String, String>>()
    private var batchJob: Job? = null
    // Sisa item yang sedang dipegang flushBatch di daftar lokalnya (issue
    // #704): batchBuf sudah kosong untuk item ini, jadi salvageBatch biasa
    // tak menjangkaunya. flushBatch memperbarui daftar ini seiring item
    // terkirim; onDestroy menyelamatkannya secara sinkron.
    private var flushInFlight: List<Triple<String, String, String>> = emptyList()
    private var flushSalvageClaimed = false

    private fun scheduleBatchLocked() {
        if (batchJob?.isActive == true) return
        batchJob = scope.launch {
            try {
                delay(5_000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            try {
                kotlinx.coroutines.withContext(fwdSerial) { flushBatch() }
            } catch (_: Exception) {
            }
            synchronized(batchLock) {
                batchJob = null
                if (batchBuf.isNotEmpty()) scheduleBatchLocked()
            }
        }
    }

    // Hasil: true bila pesan terbuffer atau terantre; false bila
    // luapan batch DAN antrean menolak — pesan hilang dan WAJIB
    // tercatat (issue #1271). Pemanggil memakai hasil ini untuk
    // memutuskan menulis cap dedup: cap tanpa pesan yang sampai
    // membuang posting ulang yang sah sebagai "duplikat".
    private suspend fun forwardToTelegram(message: String, idHash: String = "", groupKey: String = ""): Boolean {
        var overflowed = false
        synchronized(batchLock) {
            if (batchBuf.size >= MAX_BATCH) {
                overflowed = true
            } else {
                batchBuf.addLast(Triple(message, idHash, groupKey))
                scheduleBatchLocked()
            }
        }
        if (!overflowed) return true
        var terantre = false
        try {
            if (queue().addMessage(message)) {
                markGroupAndIdForwarded(groupKey, idHash)
                terantre = true
            }
            MessageScheduler.scheduleMessageSend(this)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (!terantre) {
            try { noteDroppedNotification() } catch (_: Exception) {
            }
            try { recordDrop() } catch (_: Exception) {
            }
        }
        return terantre
    }

    private suspend fun flushBatch() {
        val items: List<Triple<String, String, String>>
        synchronized(batchLock) {
            if (batchBuf.isEmpty()) return
            items = batchBuf.toList()
            batchBuf.clear()
            flushInFlight = items
            flushSalvageClaimed = false
        }
        var cur = StringBuilder()
        var curCount = 0
        var curHashes = ArrayList<String>()
        var curGroups = ArrayList<String>()
        var index = 0
        // Item yang sedang diproses dan apakah ia sudah tercakup wadah
        // penyelamatan (cur/splitRest) — issue #758: pengecualian di
        // dalam flushCur() (dipanggil sebelum item semasa ditambahkan
        // ke cur) sebelumnya membuat item itu hilang dari penyelamatan
        // catch, karena index sudah terlanjur melewatinya.
        var currentItem: Triple<String, String, String>? = null
        var currentCovered = false
        var splitRest: String? = null
        var splitHash = ""
        var splitGroup = ""
        suspend fun flushCur() {
            if (curCount <= 0) return
            val delivered = forwardLocked(cur.toString(), curCount, queueOnCancel = false)
            if (delivered) {
                markMarkersForwarded(curGroups.distinct(), curHashes)
            }
            cur = StringBuilder()
            curCount = 0
            curHashes = ArrayList()
            curGroups = ArrayList()
        }
        fun syncSplitRegistry(item: Triple<String, String, String>, restText: String) {
            synchronized(batchLock) {
                flushInFlight = if (restText.isEmpty()) items.drop(index) else listOf(Triple(restText, item.second, item.third)) + items.drop(index)
            }
        }
        try {
            while (index < items.size) {
                val item = items[index]
                index++
                currentItem = item
                currentCovered = false
                val msg = item.first
                if (msg.length > 4000) {
                    flushCur()
                    var rest = msg
                    var splitDelivered = true
                    splitRest = rest
                    currentCovered = true
                    splitHash = item.second
                    splitGroup = item.third
                    syncSplitRegistry(item, rest)
                    while (rest.length > 4000) {
                        var cut = rest.lastIndexOf("\n\n", 4000)
                        if (cut <= 0) cut = 4000
                        cut = com.redeye.parentalmonitor.utils.TextChunk.safeCut(rest, cut)
                        splitRest = rest
                        if (!forwardLocked(rest.substring(0, cut), 1, queueOnCancel = false)) splitDelivered = false
                        rest = rest.substring(cut).trimStart('\n')
                        splitRest = rest
                        syncSplitRegistry(item, rest)
                        if (rest.isEmpty()) break
                    }
                    if (rest.isNotEmpty()) {
                        splitRest = rest
                        if (!forwardLocked(rest, 1, queueOnCancel = false)) splitDelivered = false
                    }
                    splitRest = null
                    splitHash = ""
                    splitGroup = ""
                    if (splitDelivered) markMarkersForwarded(listOf(item.third), listOf(item.second))
                    synchronized(batchLock) { flushInFlight = items.drop(index) }
                    continue
                }
                if (curCount > 0 && cur.length + 2 + msg.length > 4000) flushCur()
                if (curCount > 0) cur.append("\n\n")
                cur.append(msg)
                curCount++
                if (item.second.isNotEmpty()) curHashes.add(item.second)
                if (item.third.isNotEmpty()) curGroups.add(item.third)
                currentCovered = true
                synchronized(batchLock) {
                    val sisa = items.drop(index)
                    flushInFlight = if (curCount > 0) listOf(Triple(cur.toString(), "", "")) + sisa else sisa
                }
            }
            flushCur()
            synchronized(batchLock) { flushInFlight = emptyList() }
        } catch (t: Throwable) {
            val skipSalvage = synchronized(batchLock) {
                if (flushSalvageClaimed) {
                    true
                } else {
                    flushSalvageClaimed = true
                    flushInFlight = emptyList()
                    false
                }
            }
            if (!skipSalvage) {
            val restItems = ArrayList<Triple<String, String, String>>()
            while (index < items.size) restItems.add(items[index++])
            if (t is kotlinx.coroutines.CancellationException) {
                // Daftar hash/grup kini sejajar per teks (issue #791)
                // agar penandaan bisa per item seperti salvageBatch.
                val texts = ArrayList<String>()
                val hashLists = ArrayList<List<String>>()
                val groupLists = ArrayList<List<String>>()
                if (curCount > 0) {
                    texts.add(cur.toString())
                    hashLists.add(ArrayList(curHashes))
                    groupLists.add(ArrayList(curGroups))
                }
                val pendingSplit = splitRest
                if (!pendingSplit.isNullOrEmpty()) {
                    texts.add(pendingSplit)
                    hashLists.add(if (splitHash.isNotEmpty()) listOf(splitHash) else emptyList())
                    groupLists.add(if (splitGroup.isNotEmpty()) listOf(splitGroup) else emptyList())
                }
                val curItemCe = currentItem
                if (curItemCe != null && !currentCovered) {
                    texts.add(curItemCe.first)
                    hashLists.add(if (curItemCe.second.isNotEmpty()) listOf(curItemCe.second) else emptyList())
                    groupLists.add(if (curItemCe.third.isNotEmpty()) listOf(curItemCe.third) else emptyList())
                }
                for (item in restItems) {
                    texts.add(item.first)
                    hashLists.add(if (item.second.isNotEmpty()) listOf(item.second) else emptyList())
                    groupLists.add(if (item.third.isNotEmpty()) listOf(item.third) else emptyList())
                }
                if (texts.isNotEmpty()) {
                    var results: List<Boolean> = emptyList()
                    try {
                        results = queue().addMessagesPerItem(texts)
                        queue().flushSync()
                    } catch (_: Exception) {
                    }
                    val okHashes = ArrayList<String>()
                    val okGroups = ArrayList<String>()
                    for (idx in texts.indices) {
                        if (results.getOrElse(idx) { false }) {
                            okHashes.addAll(hashLists[idx])
                            okGroups.addAll(groupLists[idx])
                        }
                    }
                    markMarkersForwarded(okGroups.distinct(), okHashes)
                    try {
                        MessageScheduler.scheduleMessageSend(this)
                    } catch (_: Exception) {
                    }
                }
            } else {
                synchronized(batchLock) {
                    for (i in restItems.indices.reversed()) batchBuf.addFirst(restItems[i])
                    val pendingSplit = splitRest
                    if (!pendingSplit.isNullOrEmpty()) batchBuf.addFirst(Triple(pendingSplit, splitHash, splitGroup))
                    val curItem = currentItem
                    if (curItem != null && !currentCovered) batchBuf.addFirst(curItem)
                    if (curCount > 0) {
                        val onlyHash = if (curHashes.size == 1) curHashes[0] else ""
                        val distinctGroups = curGroups.distinct()
                        val onlyGroup = if (distinctGroups.size == 1) distinctGroups[0] else ""
                        batchBuf.addFirst(Triple(cur.toString(), onlyHash, onlyGroup))
                    }
                }
            }
            }
            throw t
        }
    }

    private fun persistDropCountFinal(expectedGeneration: Long) {
        val prefs = prefsRef ?: return
        for (attempt in 1..3) {
            val written = try {
                synchronized(dropWriteLock) {
                    if (dropResetGeneration != expectedGeneration) return
                    val snap = synchronized(dropNoticeLock) { (dropNoticeCount.get() + dropNoticeInFlight) to dropNoticeFirstAt }
                    prefs.setPendingNotifDropsSync(snap.first, snap.second)
                }
            } catch (_: Exception) {
                false
            }
            if (written) return
        }
    }

    private fun noteDroppedNotification(count: Int = 1) {
        synchronized(dropNoticeLock) {
            val add = count.coerceAtLeast(0)
            if (add > 0 && dropNoticeFirstAt == 0L && dropNoticeCount.get() + dropNoticeInFlight == 0) {
                dropNoticeFirstAt = System.currentTimeMillis()
            }
            dropNoticeCount.addAndGet(add)
            if (dropNoticeJob?.isActive != true) {
                dropNoticeJob = scope.launch {
                    try {
                        delay(30_000L)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    try {
                        flushDropNotice()
                    } catch (_: Exception) {
                    }
                }
            }
            if (dropPersistJob?.isActive != true) {
                val genAtSchedule = dropResetGeneration
                dropPersistJob = scope.launch {
                    try {
                        var lastWritten = -1
                        var done = false
                        var firstPass = true
                        while (!done) {
                            if (firstPass) {
                                firstPass = false
                            } else {
                                delay(1_000L)
                            }
                            synchronized(dropWriteLock) {
                                val snap = synchronized(dropNoticeLock) {
                                    (dropNoticeCount.get() + dropNoticeInFlight) to dropNoticeFirstAt
                                }
                                val snapshot = snap.first
                                if (snapshot != lastWritten) {
                                    val prefs = prefsRef
                                    val written = if (prefs == null) true else try { prefs.setPendingNotifDropsSync(snapshot, snap.second) } catch (_: Exception) { false }
                                    if (written) lastWritten = snapshot
                                } else {
                                    synchronized(dropNoticeLock) {
                                        val nowTotal = dropNoticeCount.get() + dropNoticeInFlight
                                        if (nowTotal == lastWritten) {
                                            dropPersistJob = null
                                            done = true
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        persistDropCountFinal(genAtSchedule)
                        synchronized(dropNoticeLock) { dropPersistJob = null }
                        throw e
                    } catch (_: Exception) {
                        persistDropCountFinal(genAtSchedule)
                        synchronized(dropNoticeLock) { dropPersistJob = null }
                    }
                }
            }
        }
    }

    private fun dropNoticeText(count: Int, firstAt: Long): String {
        val base = if (count == 1) "Total 1 notifikasi dibuang (ditolak/antrean penuh)."
            else "Total $count notifikasi dibuang (ditolak/antrean penuh)."
        if (firstAt <= 0L) return base
        return base + " Terakumulasi sejak " +
            com.redeye.parentalmonitor.utils.TimeFmt.full(firstAt) + "."
    }

    private fun reconcileDropNoticeBatch() {
        val prefs = prefsRef ?: return
        val batch = try { prefs.dropNoticeBatch } catch (_: Exception) { "" }
        if (batch.isEmpty()) return
        val firstSep = batch.indexOf(':')
        val n = (if (firstSep >= 0) batch.substring(0, firstSep) else batch).toIntOrNull() ?: 0
        val rest = if (firstSep >= 0) batch.substring(firstSep + 1) else ""
        val secondSep = rest.indexOf(':')
        val ts = (if (secondSep >= 0) rest.substring(0, secondSep) else rest).toLongOrNull() ?: 0L
        val batchFirstAt = (if (secondSep >= 0) rest.substring(secondSep + 1) else "").toLongOrNull() ?: 0L
        val found = n > 0 && try {
            val expected = dropNoticeText(n, batchFirstAt)
            queue().getQueue().any {
                it.priority && it.message == expected &&
                    it.timestamp >= ts - 5_000L && it.timestamp <= ts + 300_000L
            }
        } catch (_: Exception) {
            false
        }
        synchronized(dropWriteLock) {
            var applied = false
            var oldFirstAt = 0L
            val state = synchronized(dropNoticeLock) {
                oldFirstAt = dropNoticeFirstAt
                if (found) {
                    dropNoticeCount.set((dropNoticeCount.get() - n).coerceAtLeast(0))
                    applied = true
                    if (dropNoticeCount.get() + dropNoticeInFlight == 0) {
                        dropNoticeFirstAt = 0L
                    } else if (dropNoticeFirstAt > 0L && dropNoticeFirstAt <= ts) {
                        dropNoticeFirstAt = ts
                    }
                }
                (dropNoticeCount.get() + dropNoticeInFlight) to dropNoticeFirstAt
            }
            val ok = try {
                prefs.setDropNoticeStateSync(state.first, "", state.second)
            } catch (_: Exception) {
                false
            }
            if (!ok && applied) {
                synchronized(dropNoticeLock) {
                    dropNoticeCount.addAndGet(n)
                    dropNoticeFirstAt = oldFirstAt
                }
            }
        }
    }

    private fun flushDropNotice() {
        synchronized(dropWriteLock) {
            flushDropNoticeLocked()
        }
    }

    private fun flushDropNoticeLocked() {
        if (dropNoticeBatchReconciled.compareAndSet(false, true)) {
            try { reconcileDropNoticeBatch() } catch (_: Exception) { }
        }
        var firstAt = 0L
        val count = synchronized(dropNoticeLock) {
            val taken = dropNoticeCount.getAndSet(0).also { dropNoticeInFlight += it }
            firstAt = dropNoticeFirstAt
            taken
        }
        if (count <= 0) return
        val batchTs = System.currentTimeMillis()
        val text = dropNoticeText(count, firstAt)
        try {
            prefsRef?.setDropNoticeBatchSync("$count:$batchTs:$firstAt")
        } catch (_: Exception) {
        }
        val queued = try {
            queue().addMessage(text, true)
        } catch (_: Exception) {
            false
        }
        val state = synchronized(dropNoticeLock) {
            dropNoticeInFlight = (dropNoticeInFlight - count).coerceAtLeast(0)
            if (!queued) {
                dropNoticeCount.addAndGet(count)
            } else if (dropNoticeCount.get() > 0) {
                dropNoticeFirstAt = batchTs
            } else {
                dropNoticeFirstAt = 0L
            }
            dropNoticeCount.get() to dropNoticeFirstAt
        }
        try {
            prefsRef?.setDropNoticeStateSync(state.first, "", state.second)
        } catch (_: Exception) {
        }
        if (queued) {
            MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
        } else {
            synchronized(dropNoticeLock) {
                dropNoticeJob = scope.launch {
                    try {
                        delay(60_000L)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    try {
                        flushDropNotice()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun adoptMigratedChat(prefs: PreferencesManager, errorBody: String?, currentChatId: String): Boolean {
        return NetworkUtils.adoptMigratedChat(
            prefs,
            errorBody,
            currentChatId,
            refresh = { refreshFwdCreds() },
            announce = {
                queue().addMessage(NetworkUtils.MIGRATED_CHAT_NOTICE, true)
                MessageScheduler.scheduleMessageSend(this)
            }
        )
    }

    private fun classifyForward(response: retrofit2.Response<com.redeye.parentalmonitor.network.TelegramResponse>, prefs: PreferencesManager, chatId: String): TelegramSendPolicy.Outcome {
        val code = response.code()
        val body = if (code == 429 || code == 400) {
            try { response.errorBody()?.string() } catch (_: Exception) { null }
        } else {
            // Sama seperti jalur utama (issue #1337):
            // errorBody yang tak dibaca ditutup agar koneksi
            // OkHttp kembali ke pool (pola poll #950).
            try { response.errorBody()?.close() } catch (_: Exception) { }
            null
        }
        val migrated = if (code == 400) adoptMigratedChat(prefs, body, chatId) else false
        return TelegramSendPolicy.classify(response.isSuccessful, response.body()?.ok == true, code, body, migrated)
    }

    private suspend fun forwardPlainFallback(url: String, botToken: String, chatId: String, original: String, plain: String, prefs: PreferencesManager, priority: Boolean = false): Boolean {
        val fallbackResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
        val outcome = classifyForward(fallbackResp, prefs, chatId)
        return when (outcome.kind) {
            TelegramSendPolicy.Kind.SENT -> {
                try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, "", 0L) } catch (_: Exception) { }
                true
            }
            TelegramSendPolicy.Kind.AUTH_FAILED -> {
                try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis()) } catch (_: Exception) { }
                val queued = queue().addMessage(original, priority)
                MessageScheduler.scheduleMessageSend(this)
                queued
            }
            TelegramSendPolicy.Kind.RATE_LIMITED -> {
                NetworkUtils.noteRateLimited(outcome.retryAfterSecs)
                val queued = queue().addMessage(original, priority)
                MessageScheduler.scheduleMessageSendNext(this, outcome.retryAfterSecs * 1000L)
                queued
            }
            TelegramSendPolicy.Kind.TRANSIENT, TelegramSendPolicy.Kind.MIGRATED, TelegramSendPolicy.Kind.RIGHTS_LIMITED -> {
                val queued = queue().addMessage(original, priority)
                MessageScheduler.scheduleMessageSend(this)
                queued
            }
            TelegramSendPolicy.Kind.CHAT_MISSING -> {
                try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis()) } catch (_: Exception) { }
                val queued = queue().addMessage(original, priority)
                MessageScheduler.scheduleMessageSend(this)
                queued
            }
            TelegramSendPolicy.Kind.PARSE_ENTITIES -> false
            // REJECTED hanya lahir dari 400 persis (aturan
            // #966 ditegakkan di TelegramSendPolicy, issue
            // #1336): selalu penolakan permanen di sini.
            TelegramSendPolicy.Kind.REJECTED -> false
        }
    }


    // Nilai kembali (issue #779): true hanya bila pesan benar-benar
    // terkirim ATAU selamat terantre. Cabang antre sebelumnya
    // mengabaikan hasil addMessage, sehingga eviksi-diri (antrean
    // tepat penuh berisi prioritas) membuat pesan hilang tetapi
    // pemanggil menandainya sudah diteruskan. Berlaku juga untuk
    // forwardPlainFallback di atas.
    // priority diteruskan ke SEMUA antre-cadangan di dalam
    // (issue #1391): notifikasi prioritas yang terantre tanpa
    // bendera kehilangan jalurnya — dikuras paling akhir dan
    // tereviksi lebih dulu saat antrean penuh.
    private suspend fun forwardLocked(message: String, msgCount: Int = 1, queueOnCancel: Boolean = true, priority: Boolean = false): Boolean {
        try {
            val prefs = prefsRef ?: try {
                PreferencesManager.getInstance(this).also { adoptPrefs(it) }
            } catch (_: Exception) {
                var queued = false
                try {
                    queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return queued
            }
            if (prefs.accessPin.isNotEmpty() && prefs.ownerUserId == 0L) {
                // Antrekan seperti cabang jeda/kredensial kosong: sebelumnya
                // batch terbuang diam-diam (hanya tersisa di /lastnotify).
                // SendMessageWorker sendiri menahan pengiriman selama
                // owner belum login, jadi tak ada yang keluar pra-login
                // (issue #675).
                val queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSend(this)
                return queued
            }
            if (prefs.userDisabledMonitoring || !prefs.userConsentedMonitoring || !prefs.isMonitoringEnabled) return false
            if (prefs.monitoringPaused) {
                val queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSend(this)
                return queued
            }
            if (cachedFwdCreds.first.isEmpty() || cachedFwdCreds.second.isEmpty()) refreshFwdCreds()
            val (botToken, chatId) = cachedFwdCreds
            if (botToken.isEmpty() || chatId.isEmpty()) {
                val queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSend(this)
                return queued
            }
            try {
                NetworkUtils.sweepAuthBlock(prefs)
                if (prefs.credentialError.isNotEmpty()) {
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                    return queued
                }
            } catch (_: Exception) {
            }
            val rateRemain = NetworkUtils.rateLimitedRemainMs()
            if (rateRemain > 0L) {
                val queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSendNext(this, rateRemain)
                return queued
            }
            val nowNet = android.os.SystemClock.elapsedRealtime()
            if (nowNet - netCheckAt > 20_000L) {
                netCheckAt = nowNet
                netCached = NetworkUtils.isNetworkAvailable(this)
            }
            if (!netCached) {
                val queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSend(this)
                return queued
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            val outcome = classifyForward(response, prefs, chatId)
            when (outcome.kind) {
                TelegramSendPolicy.Kind.SENT -> {
                    try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, "", 0L) } catch (_: Exception) { }
                    return true
                }
                TelegramSendPolicy.Kind.AUTH_FAILED -> {
                    android.util.Log.e("NotifForwarder", "Send rejected (${outcome.code}), queuing notification")
                    try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis()) } catch (_: Exception) { }
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                    return queued
                }
                TelegramSendPolicy.Kind.RATE_LIMITED -> {
                    NetworkUtils.noteRateLimited(outcome.retryAfterSecs)
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSendNext(this, outcome.retryAfterSecs * 1000L)
                    return queued
                }
                TelegramSendPolicy.Kind.MIGRATED, TelegramSendPolicy.Kind.RIGHTS_LIMITED -> {
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                    return queued
                }
                TelegramSendPolicy.Kind.CHAT_MISSING -> {
                    try { prefs.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis()) } catch (_: Exception) { }
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                    return queued
                }
                TelegramSendPolicy.Kind.PARSE_ENTITIES, TelegramSendPolicy.Kind.REJECTED -> {
                    // Keduanya hanya lahir dari 400 persis
                    // (issue #1336): kode tak terdaftar datang
                    // sebagai TRANSIENT dan terantre di cabangnya.
                    val plain = Html.unescape(message)
                    if (plain != message || outcome.kind == TelegramSendPolicy.Kind.PARSE_ENTITIES) {
                        if (forwardPlainFallback(url, botToken, chatId, message, plain, prefs, priority)) return true
                    }
                    android.util.Log.w("NotifForwarder", "Notification permanently rejected (400), dropping")
                    try { noteDroppedNotification(msgCount) } catch (_: Exception) { }
                    return false
                }
                TelegramSendPolicy.Kind.TRANSIENT -> {
                    val queued = queue().addMessage(message, priority)
                    MessageScheduler.scheduleMessageSend(this)
                    return queued
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (queueOnCancel) {
                // Hasil penyelamatan menentukan vonis (issue
                // #1369): dulu hasil addMessage dibuang dan
                // pemanggil jalur prioritas memetakan batal ->
                // delivered=true tanpa syarat, jadi notifikasi
                // yang GAGAL diselamatkan tetap dicap terkirim
                // + diteruskan persisten — hilang permanen.
                // Kini seperti cabang TRANSIENT: antrean adalah
                // pipa pengiriman yang tahan, jadi rescued=true
                // berarti terkirim-ke-pipa; gagal = false agar
                // pemanggil tak memasang cap.
                var rescued = false
                try {
                    rescued = queue().addMessage(message, priority)
                    if (rescued) MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return rescued
            }
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Forward failed: ${redactToken(e.message)}")
            var queued = false
            try {
                queued = queue().addMessage(message, priority)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
            return queued
        }
    }
}
