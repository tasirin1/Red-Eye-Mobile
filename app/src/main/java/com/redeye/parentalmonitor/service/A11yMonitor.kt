package com.redeye.parentalmonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.utils.A11yUsageMath
import com.redeye.parentalmonitor.utils.AppWatch
import com.redeye.parentalmonitor.utils.BootClock
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.NetworkUtils
import com.redeye.parentalmonitor.utils.TamperGuard
import com.redeye.parentalmonitor.utils.TimeFmt

object A11yMonitor {

    data class AppUsage(
        var label: String = "",
        var millis: Long = 0L,
        var nightMillis: Long = 0L,
        var launches: Int = 0
    )

    private const val MAX_SESSION_MS = 4 * 60 * 60_000L
    private const val PERSIST_INTERVAL_MS = 20_000L
    private const val NIGHT_ALERT_GAP_MS = 30 * 60_000L
    private const val TAMPER_ALERT_GAP_MS = 5 * 60_000L
    private const val CONTENT_SCAN_GAP_MS = 3_000L
    private const val KEYWORD_ALERT_GAP_MS = 15 * 60_000L
    private const val MAX_KEYWORD_ALERTS_PER_DAY = 30
    private const val MAX_LABEL_CACHE = 160
    private const val SCAN_TEXT_MAX_CHARS = 512

    private data class ScreenSnapshot(val texts: List<String>, val hasPassword: Boolean)

    private val gson = Gson()
    private val usageType = object : TypeToken<MutableMap<String, AppUsage>>() {}.type
    private val alertType = object : TypeToken<MutableMap<String, Long>>() {}.type
    private val gate = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastTamperAlert = HashMap<String, Long>()
    private var tamperStateLoaded = false
    private val lastContentScan = HashMap<String, Long>()
    // Jejak jendela terakhir untuk cek ulang tamper (issue #922).
    @Volatile
    private var lastWindowPkg = ""
    @Volatile
    private var lastSensitiveCls = ""
    @Volatile
    private var lastTamperRecheckAt = 0L
    private val lastKeywordAlert = HashMap<String, Long>()
    // Cache urai paket pantau + kata kunci (issue #980): jalur
    // pindai a11y berjalan tiap peristiwa jendela/konten, dan
    // dulu set paket dibangun ulang + kata kunci diurai ulang
    // dari string prefs di SETIAP pindaian. Kini diurai hanya
    // bila string mentahnya berubah (pola cache yang sama
    // dengan NotificationForwarderService).
    // @Volatile (issue #1017): cache ini dibaca dari dua
    // benang (benang peristiwa aksesibilitas dan executor
    // redeye-a11y). Penulis menulis cache SEBELUM raw, jadi
    // pembaca yang melihat raw baru pasti melihat cache
    // barunya juga; tanpa penghalang memori pasangan
    // (raw, cache) bisa terbaca tak konsisten sesaat.
    @Volatile
    private var watchPackagesRaw: String? = null
    @Volatile
    private var watchPackagesCache: Set<String> = emptySet()
    @Volatile
    private var watchKeywordsRaw: String? = null
    @Volatile
    private var watchKeywordsCache: List<String> = emptyList()
    private val watchCacheLock = Any()
    private var keywordAlertDay = ""
    private var keywordAlertDayAtMs = 0L
    private var keywordAlertCount = 0
    // Antrean TERBATAS (issue #931): executor lama tak
    // terbatas — satu tugas lambat menumpuk semua event/
    // persist di belakangnya tanpa batas. Tugas yang tertolak
    // saat penuh dibuang diam-diam (pindai/persist memang
    // rancangan lossy dengan jeda; melempar ke thread callback
    // sistem justru bisa mematikan proses).
    private val executor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(64),
        { runnable ->
            Thread(runnable, "redeye-a11y").apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    )

    private val labels = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_LABEL_CACHE
    }
    private val launchable = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean = size > MAX_LABEL_CACHE
    }

    @Volatile
    private var prefs: PreferencesManager? = null

    @Volatile
    private var a11yService: AccessibilityService? = null

    @Volatile
    private var lastEventKey = ""

    @Volatile
    private var lastSaveRequestElapsed = 0L

    private var keywordStateLoaded = false

    private var loaded = false
    private var loadedDay = ""
    private var usage: MutableMap<String, AppUsage> = HashMap()
    private var currentPackage = ""
    private var currentLabel = ""
    private var currentStartWall = 0L
    private var currentStartElapsed = 0L
    private var lastPersistElapsed = 0L
    private var persistFailStreak = 0
    private var lastPersistFailElapsed = 0L
    private var lastNightAlertElapsed = 0L
    private var nightAlertedPackage = ""
    private var suspendedPackage = ""
    private var suspendedLabel = ""
    private var launcherPackage = ""
    private var launcherPackageAt = 0L
    private var knownSeeded = false

    fun attach(context: Context) {
        val app = context.applicationContext
        prefs = try { PreferencesManager.getInstance(app) } catch (_: Exception) { null }
        if (context is AccessibilityService) a11yService = context
        executor.execute {
            ensureLoaded(app)
            synchronized(gate) { restoreOpenSessionLocked(app) }
        }
    }

    private fun refreshOpenSessionMarkerLocked(manager: PreferencesManager) {
        try {
            if (currentPackage.isNotEmpty() && currentStartElapsed > 0L) {
                manager.setA11yOpenSession(currentPackage, currentStartWall, currentStartElapsed)
            } else {
                manager.clearA11yOpenSession()
            }
        } catch (_: Exception) {
        }
    }

    private fun restoreOpenSessionLocked(context: Context) {
        if (currentPackage.isNotEmpty()) return
        val service = a11yService ?: return
        val manager = prefs ?: return
        val pkg = try { manager.a11yOpenSessionPkg } catch (_: Exception) { "" }
        if (pkg.isEmpty()) return
        val startWall = try { manager.a11yOpenSessionStartWall } catch (_: Exception) { 0L }
        val startElapsed = try { manager.a11yOpenSessionStartElapsed } catch (_: Exception) { 0L }
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        var foreground = ""
        try {
            val root = service.rootInActiveWindow
            if (root != null) {
                foreground = try { root.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                recycleNode(root)
            }
        } catch (_: Exception) {
        }
        val recoverable = startWall > 0L && startElapsed > 0L &&
            TimeFmt.dayKey(startWall) == TimeFmt.dayKey(nowWall) &&
            BootClock.sameBootOrClockShift(startWall, startElapsed, nowWall, nowElapsed) &&
            foreground == pkg &&
            monitoringActive(manager) &&
            isLaunchable(context, pkg)
        if (!recoverable && startWall > 0L && foreground == pkg && monitoringActive(manager) && isLaunchable(context, pkg)) {
            // Sesi terbuka lintas tengah malam (issue #735): sebelumnya
            // sesi semalam dibuang begitu saja saat service hidup lagi
            // keesokan hari. Bila slot pemakaian masih memegang hari
            // mulai sesi, akru porsi sebelum tengah malam ke hari itu,
            // lalu lanjutkan sesi mulai tengah malam ini.
            val startDay = TimeFmt.dayKey(startWall)
            val todayKey = TimeFmt.dayKey(nowWall)
            val todayStart = A11yUsageMath.dayStartWallMillis(nowWall)
            val slotDay = try { manager.a11yUsageDate } catch (_: Exception) { "" }
            // Slot cadangan ikut sah (issue #1037): loadUsage
            // di bawah memulihkan dari cadangan bila tanggal
            // utamanya bukan hari yang diminta.
            val slotDayPrev = try { manager.a11yUsagePrevDate } catch (_: Exception) { "" }
            val slotDayPrev2 = try { manager.a11yUsagePrev2Date } catch (_: Exception) { "" }
            if (startDay != todayKey && todayStart > startWall && loaded && loadedDay == todayKey && (slotDay == startDay || slotDayPrev == startDay || slotDayPrev2 == startDay)) {
                // Akru hanya sampai tengah malam PERTAMA sesudah sesi
                // mulai (issue #780): sebelumnya sampai tengah malam
                // hari ini, sehingga sesi yang mulai ≥2 hari lalu
                // menagih seluruh hari antaranya ke hari mulai.
                val spanEnd = A11yUsageMath.splitBoundaryWallMillis(startWall, todayStart)
                val span = spanEnd - startWall
                val todayUsage = usage
                usage = loadUsage(context, startDay)
                currentPackage = pkg
                currentLabel = labelFor(context, pkg)
                currentStartWall = startWall
                currentStartElapsed = 1L
                accrueSessionLocked(spanEnd, 1L + span)
                saveUsageFor(context, startDay)
                usage = todayUsage
                currentPackage = pkg
                currentLabel = labelFor(context, pkg)
                currentStartWall = todayStart
                // Jangkar elapsed tengah malam hanya ada bila perangkat
                // boot sebelum tengah malam; bila boot sesudahnya nilainya
                // negatif — sesi dilanjutkan dihitung dari saat restore
                // ini (issue #747). Koersi lama ke 1 ms membuat akru
                // berikutnya menagih seluruh uptime sebagai pemakaian
                // hari ini.
                val midnightElapsed = nowElapsed - (nowWall - todayStart)
                currentStartElapsed = if (midnightElapsed >= 1L) midnightElapsed else nowElapsed
                usage.getOrPut(pkg) { AppUsage(label = currentLabel) }.label = currentLabel
                try { manager.setA11yOpenSession(currentPackage, currentStartWall, currentStartElapsed) } catch (_: Exception) { }
                return
            }
        }
        if (!recoverable) {
            try { manager.clearA11yOpenSession() } catch (_: Exception) { }
            return
        }
        currentPackage = pkg
        currentLabel = labelFor(context, pkg)
        currentStartWall = startWall
        currentStartElapsed = startElapsed
        usage.getOrPut(pkg) { AppUsage(label = currentLabel) }.label = currentLabel
    }

    fun unbind(service: AccessibilityService? = null) {
        if (service == null || a11yService === service) a11yService = null
    }

    fun noteEnabled(context: Context) {
        try {
            val manager = prefs ?: PreferencesManager.getInstance(context.applicationContext).also { prefs = it }
            manager.accessibilityEverEnabled = true
        } catch (_: Exception) {
        }
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = try { event.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                if (pkg.isEmpty()) return
                val cls = try { event.className?.toString().orEmpty() } catch (_: Exception) { "" }
                val key = pkg + "|" + cls
                if (key == lastEventKey) return
                lastEventKey = key
                lastWindowPkg = pkg
                val sensitive = TamperGuard.mayBeSensitive(pkg)
                if (sensitive) lastSensitiveCls = cls
                val app = service.applicationContext
                executor.execute {
                    val texts = if (sensitive) collectSnapshot(service, pkg).first.texts else emptyList()
                    handleWindow(app, pkg, cls, texts)
                }
                scanWatchedContent(service, pkg)
                requestPeriodicPersist(app)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = try { event.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                if (pkg.isNotEmpty()) {
                    // Cek ulang tamper untuk halaman sensitif yang
                    // SUDAH terbuka (issue #922): halaman Settings
                    // yang terbuka sebelum proteksi aktif/service
                    // bind tak pernah memicu state-changed lagi,
                    // jadi tanpa ini tak pernah dinilai TamperGuard.
                    // Dijatah 10 detik & hanya untuk jendela
                    // terakhir yang tercatat sensitif; alert-nya
                    // sendiri tetap dijatah persisten per alasan.
                    if (pkg == lastWindowPkg && TamperGuard.mayBeSensitive(pkg)) {
                        val nowE = SystemClock.elapsedRealtime()
                        if (nowE - lastTamperRecheckAt >= 10_000L) {
                            lastTamperRecheckAt = nowE
                            val cls = lastSensitiveCls
                            val app = service.applicationContext
                            executor.execute {
                                val texts = collectSnapshot(service, pkg).first.texts
                                handleWindow(app, pkg, cls, texts)
                            }
                        }
                    }
                    scanWatchedContent(service, pkg)
                    requestPeriodicPersist(service.applicationContext)
                }
            }
        }
    }

    private fun watchedPackages(manager: PreferencesManager): Set<String> {
        // Pasangan (raw, cache) dinilai+ditulis atomik
        // (issue #1100): dua benang (peristiwa + executor)
        // dulu bisa menyilangkan tulisannya sehingga keadaan
        // akhir menyatakan raw lama dengan cache baru atau
        // sebaliknya. Satu kunci untuk kedua pasangan.
        synchronized(watchCacheLock) {
            val raw = try { manager.a11yAppWatchPackages } catch (_: Exception) { "" }
            if (raw != watchPackagesRaw) {
                watchPackagesCache = try { manager.a11yAppWatchPackageSet() } catch (_: Exception) { emptySet() }
                watchPackagesRaw = raw
            }
            return watchPackagesCache
        }
    }

    private fun watchedKeywords(manager: PreferencesManager): List<String> {
        synchronized(watchCacheLock) {
            val raw = try { manager.watchKeywords } catch (_: Exception) { "" }
            if (raw != watchKeywordsRaw) {
                watchKeywordsCache = AppWatch.parseKeywords(raw)
                watchKeywordsRaw = raw
            }
            return watchKeywordsCache
        }
    }

    private fun scanWatchedContent(service: AccessibilityService, pkg: String) {
        if (pkg.isEmpty() || pkg == service.packageName) return
        val now = SystemClock.elapsedRealtime()
        val last = lastContentScan[pkg] ?: 0L
        if (last > 0L && now - last < CONTENT_SCAN_GAP_MS) return
        // Cap throttle ditulis SEBELUM gerbang (issue #1014):
        // dulu cap hanya ditulis sesudah semua gerbang lolos,
        // jadi pada keadaan bawaan (pantau mati) dan paket
        // sensitif jatah 3 dtk tak pernah berlaku dan pra-cek
        // penuh jalan di setiap peristiwa konten.
        lastContentScan[pkg] = now
        if (AppWatch.isSensitivePackage(pkg)) return
        val manager = prefs ?: return
        if (!manager.a11yAppWatchEnabled) return
        if (lastContentScan.size > 120) {
            // Pangkas yang TERTUA saja (issue #930): clear()
            // total mencabut throttle semua paket sekaligus
            // sehingga pemindaian meledak serentak.
            val urut = lastContentScan.entries.sortedBy { it.value }
            var buang = lastContentScan.size - 60
            for (entri in urut) {
                if (buang <= 0) break
                lastContentScan.remove(entri.key)
                buang--
            }
        }
        if (pkg !in watchedPackages(manager)) return
        val keywords = watchedKeywords(manager)
        if (keywords.isEmpty()) return
        val app = service.applicationContext
        executor.execute {
            val (snapshot, fullHit) = collectSnapshot(service, pkg, keywords)
            if (snapshot.hasPassword || snapshot.texts.isEmpty()) return@execute
            handleContent(app, pkg, snapshot.texts, keywords, fullHit)
        }
    }

    private fun handleContent(context: Context, pkg: String, texts: List<String>, keywords: List<String>, fullTextHit: String? = null) {
        val manager = prefs ?: return
        if (!manager.a11yAppWatchEnabled || !monitoringActive(manager)) return
        if (pkg !in watchedPackages(manager) || AppWatch.isSensitivePackage(pkg)) return
        // Kena dari teks UTUH node (issue #830) didahulukan: kata
        // pantau di luar potongan 512 karakter snapshot sebelumnya
        // tak pernah terdeteksi sama sekali.
        val hit = fullTextHit ?: AppWatch.hit(keywords, texts.joinToString(" ")) ?: return
        val nowWall = System.currentTimeMillis()
        val day = TimeFmt.dayKey(nowWall)
        ensureKeywordState(manager, day)
        if (keywordAlertCount >= MAX_KEYWORD_ALERTS_PER_DAY) return
        val key = pkg + "|" + hit
        val last = lastKeywordAlert[key] ?: 0L
        if (last > 0L && nowWall - last < KEYWORD_ALERT_GAP_MS) return
        lastKeywordAlert[key] = nowWall
        keywordAlertCount++
        saveKeywordState(manager)
        val label = labelFor(context, pkg)
        val snippet = try {
            texts.firstNotNullOfOrNull { AppWatch.contextSnippet(hit, it) }
        } catch (_: Exception) {
            null
        }
        val queued = sendAlert(
            context,
            "\uD83D\uDD0E <b>Kata pantau di aplikasi</b>\nApp: " + Html.escape(label) +
                "\nKata: " + Html.escape(hit) +
                (if (snippet != null) "\nKonteks: " + Html.escape(snippet) else "") +
                "\nWaktu: " + TimeFmt.short(nowWall) +
                "\nMatikan: /appwatch off"
        )
        if (!queued) {
            // Antre menolak (issue #811): kembalikan cap jeda +
            // hitungan harian yang telanjur ditulis di atas, agar
            // kejadian yang sama bisa dilaporkan lagi.
            if (last > 0L) lastKeywordAlert[key] = last else lastKeywordAlert.remove(key)
            keywordAlertCount--
            saveKeywordState(manager)
        }
    }

    private fun ensureKeywordState(manager: PreferencesManager, day: String) {
        if (!keywordStateLoaded) {
            keywordStateLoaded = true
            keywordAlertDay = try { manager.a11yKeywordAlertDay } catch (_: Exception) { "" }
            keywordAlertDayAtMs = try { manager.a11yKeywordAlertDayAtMs } catch (_: Exception) { 0L }
            keywordAlertCount = try { manager.a11yKeywordAlertCount } catch (_: Exception) { 0 }
            try {
                val parsed: MutableMap<String, Long>? = gson.fromJson(manager.a11yKeywordAlertJson, alertType)
                if (parsed != null) {
                    lastKeywordAlert.clear()
                    lastKeywordAlert.putAll(parsed)
                }
            } catch (_: Exception) {
            }
        }
        // Pasangan (hari, epoch) dinilai penilai yang sama
        // dengan cap harian lain (issue #1053): hari cocok
        // tetapi epoch penulisan sudah basi berarti state ini
        // sisa excursi jam ke tanggal yang sama — anggaran dan
        // peta jeda dimulai ulang, bukan diwarisi.
        val pasanganSah = day == keywordAlertDay &&
            com.redeye.parentalmonitor.utils.DayStamp.blocks(keywordAlertDay, keywordAlertDayAtMs, day, System.currentTimeMillis())
        if (!pasanganSah) {
            keywordAlertDay = day
            keywordAlertCount = 0
            lastKeywordAlert.clear()
            saveKeywordState(manager)
            return
        }
        if (lastKeywordAlert.size > 200) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
            try { lastKeywordAlert.entries.removeIf { it.value < cutoff } } catch (_: Exception) { }
        }
    }

    private fun saveKeywordState(manager: PreferencesManager) {
        try {
            keywordAlertDayAtMs = System.currentTimeMillis()
            manager.setA11yKeywordAlertState(
                keywordAlertDay,
                keywordAlertCount,
                try { gson.toJson(lastKeywordAlert) } catch (_: Exception) { "" },
                keywordAlertDayAtMs
            )
        } catch (_: Exception) {
        }
    }

    fun flush(context: Context) {
        val app = context.applicationContext
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            started.set(true)
            synchronized(gate) {
                lastEventKey = ""
                suspendedPackage = ""
                ensureLoadedLocked(app)
                finishSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                persistLocked(app, true)
            }
        }
        executor.execute(task)
        // Flush akhir tak boleh ikut nasib DiscardPolicy (issue
        // #949): antrean penuh tepat saat layanan dibongkar
        // membuang flush diam-diam dan sesi pemakaian terakhir
        // hilang tanpa pernah terpersisten. DiscardPolicy
        // membuang secara sinkron di dalam execute(), jadi bila
        // tugas tak mulai dan tak ada di antrean, ia terbuang —
        // jalankan langsung di thread pemanggil (badan kecil,
        // terkurung gate yang sama, aman dijalankan dua kali).
        if (!started.get() && !executor.queue.contains(task)) {
            task.run()
        }
    }

    fun screenOff(context: Context) {
        val app = context.applicationContext
        executor.execute {
            synchronized(gate) {
                ensureLoadedLocked(app)
                if (currentPackage.isNotEmpty()) {
                    suspendedPackage = currentPackage
                    suspendedLabel = currentLabel
                }
                finishSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                lastEventKey = ""
                persistLocked(app, true)
            }
        }
    }

    fun screenOn(context: Context) {
        val app = context.applicationContext
        executor.execute {
            synchronized(gate) {
                ensureLoadedLocked(app)
                val pkg = suspendedPackage
                val manager = prefs
                // Verifikasi aplikasi terdepan sungguhan (issue #739):
                // sesi lama hanya dilanjutkan bila jendela aktif memang
                // paket itu; bila pengguna membuka aplikasi lain dari
                // notifikasi/pintasan kunci, sesi lama tidak dilanjutkan
                // (event jendela aplikasi baru yang akan mencatatnya).
                var foreground = ""
                val service = a11yService
                if (service != null) {
                    try {
                        val root = service.rootInActiveWindow
                        if (root != null) {
                            foreground = try { root.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                            recycleNode(root)
                        }
                    } catch (_: Exception) {
                    }
                }
                // Pembersihan karena beda terdepan hanya sah bila layar
                // sudah terbuka (issue #760): pada SCREEN_ON yang masih
                // terkunci, jendela aktif bisa terbaca sebagai SystemUI/
                // layar kunci — cabang lanjutkan di bawah sudah menunggu
                // kunci terbuka lewat isKeyguardLocked, tetapi cabang
                // bersih ini sebelumnya menghapus sesi tersuspensi lebih
                // dulu sehingga tak ada lagi yang bisa dilanjutkan.
                if (pkg.isNotEmpty() && foreground.isNotEmpty() && foreground != pkg && !isKeyguardLocked(app)) {
                    suspendedPackage = ""
                    suspendedLabel = ""
                }
                if (pkg.isNotEmpty() && manager != null && monitoringActive(manager) && !isKeyguardLocked(app) && isLaunchable(app, pkg) && (foreground.isEmpty() || foreground == pkg)) {
                    suspendedPackage = ""
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    currentPackage = pkg
                    currentLabel = suspendedLabel.ifEmpty { labelFor(app, pkg) }
                    suspendedLabel = ""
                    currentStartWall = nowWall
                    currentStartElapsed = nowElapsed
                    try { manager.setA11yOpenSession(pkg, nowWall, nowElapsed) } catch (_: Exception) { }
                    lastEventKey = ""
                    usage.getOrPut(pkg) { AppUsage(label = currentLabel) }.label = currentLabel
                }
            }
        }
    }

    fun currentAppLabel(): String {
        synchronized(gate) { return if (currentPackage.isEmpty()) "" else currentLabel }
    }

    fun lastRecordedLabel(context: Context, pkg: String): String {
        val manager = try { PreferencesManager.getInstance(context) } catch (_: Exception) { return "" }
        val raw = try { manager.a11yUsageJson } catch (_: Exception) { "" }
        if (raw.isEmpty()) return ""
        val parsed: MutableMap<String, AppUsage>? = try { gson.fromJson(raw, usageType) } catch (_: Exception) { null }
        return parsed?.get(pkg)?.label?.trim().orEmpty()
    }

    fun todayUsage(context: Context): List<AppUsage> {
        val app = context.applicationContext
        ensurePrefs(app)
        synchronized(gate) {
            ensureLoadedLocked(app)
            accrueSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            return usage.values.map { it.copy() }
                .filter { it.millis > 0L || it.launches > 0 }
                .sortedByDescending { it.millis }
        }
    }

    private fun ensurePrefs(context: Context) {
        if (prefs == null) {
            try { prefs = PreferencesManager.getInstance(context) } catch (_: Exception) { }
        }
    }

    private fun ensureLoaded(context: Context) {
        ensurePrefs(context)
        synchronized(gate) { ensureLoadedLocked(context) }
    }

    private fun ensureLoadedLocked(context: Context) {
        val today = TimeFmt.dayKey(System.currentTimeMillis())
        if (loaded && today == loadedDay) return
        val previousDay = loadedDay
        val activePackage = currentPackage
        val activeLabel = currentLabel
        var resumeWall = 0L
        var resumeElapsed = 0L
        if (loaded && previousDay.isNotEmpty() && today != previousDay) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val dayStart = A11yUsageMath.dayStartWallMillis(nowWall)
            var splitAtMidnight = false
            var segmentsSaved = false
            // Penjaga lompatan jam dinding (issue #761): pemecahan per
            // hari memetakan batas dinding ke elapsed dengan asumsi
            // keduanya maju bersama. Bila jam dinding melompat maju jauh
            // di tengah sesi (diubah manual), rentang dinding jauh lebih
            // besar dari rentang elapsed dan sesi akan terpotong ke
            // hari-hari yang tidak pernah dialami — lewati pemecahan dan
            // biarkan jalur selesai biasa yang menanganinya.
            val wallSpan = nowWall - currentStartWall
            val elapsedSpan = nowElapsed - currentStartElapsed
            val clockJumped = wallSpan - elapsedSpan > 3_600_000L
            if (currentPackage.isNotEmpty() && currentStartElapsed > 0L && currentStartWall in 1L until dayStart && dayStart <= nowWall && !clockJumped) {
                val boundaries = A11yUsageMath.splitBoundariesWallMillis(currentStartWall, dayStart)
                val elapsedAtFirst = nowElapsed - (nowWall - boundaries.first())
                if (elapsedAtFirst > currentStartElapsed) {
                    var segDay = previousDay
                    for (boundary in boundaries) {
                        val elapsedAtBoundary = nowElapsed - (nowWall - boundary)
                        accrueSessionLocked(boundary, elapsedAtBoundary)
                        saveUsageFor(context, segDay)
                        if (boundary >= dayStart) break
                        segDay = TimeFmt.dayKey(boundary)
                        usage = loadUsage(context, segDay)
                    }
                    segmentsSaved = true
                    resumeWall = dayStart
                    resumeElapsed = nowElapsed - (nowWall - dayStart)
                    splitAtMidnight = true
                }
            }
            if (splitAtMidnight) {
                currentPackage = ""
                currentStartElapsed = 0L
            } else {
                finishSessionLocked(nowWall, nowElapsed)
            }
            if (!segmentsSaved) saveUsageFor(context, previousDay)
        }
        loadedDay = today
        loaded = true
        usage = loadUsage(context, today)
        seedKnownAppsLocked(context)
        if (activePackage.isNotEmpty() && (previousDay.isEmpty() || today != previousDay)) {
            val manager = prefs
            if (manager != null && monitoringActive(manager) && isLaunchable(context, activePackage)) {
                val nowWall = System.currentTimeMillis()
                currentPackage = activePackage
                currentLabel = activeLabel.ifEmpty { labelFor(context, activePackage) }
                currentStartWall = if (resumeWall > 0L) resumeWall else nowWall
                currentStartElapsed = if (resumeElapsed > 0L) resumeElapsed else SystemClock.elapsedRealtime()
                try { manager.setA11yOpenSession(currentPackage, currentStartWall, currentStartElapsed) } catch (_: Exception) { }
            }
        }
    }

    private fun loadUsage(context: Context, today: String): MutableMap<String, AppUsage> {
        val manager = prefs ?: return HashMap()
        return try {
            // Slot cadangan lebih dulu (issue #1037): bila
            // tanggal slot utama bukan hari ini tetapi tanggal
            // cadangan adalah hari ini, slot utama tertimpa
            // persist bertanggal palsu selama excursi jam —
            // data hari ini yang sah ada di cadangan.
            val raw = when {
                manager.a11yUsageDate == today -> manager.a11yUsageJson
                manager.a11yUsagePrevDate == today -> manager.a11yUsagePrevJson
                manager.a11yUsagePrev2Date == today -> manager.a11yUsagePrev2Json
                else -> return HashMap()
            }
            if (raw.isEmpty()) return HashMap()
            val parsed: MutableMap<String, AppUsage>? = gson.fromJson(raw, usageType)
            parsed ?: HashMap()
        } catch (_: Exception) {
            HashMap()
        }
    }

    private fun seedKnownAppsLocked(context: Context) {
        if (knownSeeded) return
        val manager = prefs ?: return
        if (manager.a11yKnownSeeded) {
            knownSeeded = true
            return
        }
        val packages = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val infos = if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                context.packageManager.queryIntentActivities(intent, 0)
            }
            infos.mapNotNull { it.activityInfo?.packageName }.filter { it.isNotEmpty() }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
        if (packages.isNotEmpty()) {
            manager.seedA11yKnownApps(packages)
            knownSeeded = true
        }
    }

    private fun handleWindow(context: Context, pkg: String, cls: String, texts: List<String>) {
        ensurePrefs(context)
        val manager = prefs ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val selfLabel = labelFor(context, context.packageName)
        val pkgLabel = labelFor(context, pkg)
        val launcherPkg = defaultLauncherPackage(context)
        val launchable = isLaunchable(context, pkg)
        var persistSnapshot: Triple<String, String, Long>? = null
        var alertText: String? = null
        var nightUndo: (() -> Unit)? = null
        var tamperReason: String? = null
        var done = false
        synchronized(gate) {
            ensureLoadedLocked(context)
            if (manager.a11yTamperEnabled && protectionActive(manager)) {
                val reason = TamperGuard.reason(pkg, cls, texts, context.packageName, selfLabel)
                // Tenggang hanya dinilai bila ada alasan tamper
                // (issue #1064): dulu inSetupGrace dievaluasi
                // penuh — sembilan baca prefs terenkripsi plus
                // penghitung boot — untuk SETIAP peristiwa
                // jendela walau hasilnya dibuang.
                val setupGrace = if (reason != null) TamperGuard.inSetupGrace(reason, nowWall, manager.a11ySettingsGraceUntil, manager.a11yAdminGraceUntil, nowElapsed, manager.a11ySettingsGraceElapsedUntil, manager.a11yAdminGraceElapsedUntil,
                    com.redeye.parentalmonitor.receiver.BootReceiver.bootCount(context),
                    try { manager.a11ySettingsGraceBootCount } catch (_: Exception) { 0L },
                    try { manager.a11yAdminGraceBootCount } catch (_: Exception) { 0L }) else false
                if (reason != null) {
                    if (!setupGrace) {
                        tamperReason = reason
                        finishSessionLocked(nowWall, nowElapsed)
                        persistSnapshot = persistSnapshotLocked(true)
                    }
                    done = true
                }
            }
            if (!done && !monitoringActive(manager)) {
                val terakru = finishSessionLocked(nowWall, nowElapsed)
                // Persist mengikuti jatah normal (issue #1010):
                // paksa di sini membuat SETIAP transisi activity
                // menulis ulang seluruh JSON pemakaian walau
                // monitoring mati dan tak ada data baru. Kecuali
                // bila finish baru saja mengakru sesi (issue
                // #1036): akru itu kini hanya di memori dan
                // penanda sesi-terbukanya sudah terhapus, jadi
                // persist dipaksa agar tak hilang bila proses
                // mati sebelum jatah berikutnya.
                persistSnapshot = persistSnapshotLocked(terakru)
                done = true
            }
            if (!done && (pkg == context.packageName || pkg == launcherPkg || !launchable)) {
                val terakru = finishSessionLocked(nowWall, nowElapsed)
                persistSnapshot = persistSnapshotLocked(terakru)
                done = true
            }
            if (!done && pkg != currentPackage) {
                // Akru sesi yang baru selesai wajib ikut memaksa
                // persist (issue #1050, pola #1036): nilainya dulu
                // dibuang dan persistSnapshotLocked(false) membuat
                // akru hanya di memori sementara penanda sesi-
                // terbukanya sudah terhapus — proses mati sebelum
                // jatah berikutnya menghilangkan akru permanen.
                val terakru = finishSessionLocked(nowWall, nowElapsed)
                currentPackage = pkg
                currentLabel = pkgLabel
                currentStartWall = nowWall
                currentStartElapsed = nowElapsed
                try { manager.setA11yOpenSession(pkg, nowWall, nowElapsed) } catch (_: Exception) { }
                val row = usage.getOrPut(pkg) { AppUsage(label = currentLabel) }
                row.label = currentLabel
                row.launches += 1
                val seeded = manager.a11yKnownSeeded
                val firstOpen = manager.rememberA11yApp(pkg)
                if (seeded && firstOpen && manager.a11yAppAlert) {
                    if (isNightHour(nowWall)) {
                        if (nightUndo == null) nightUndo = captureNightStamp(manager, pkg, nowElapsed)
                        stampNightAlert(manager, pkg, nowWall, nowElapsed)
                    }
                    alertText = "\uD83D\uDCE6 <b>Aplikasi baru dibuka</b>\n" + Html.escape(currentLabel) + "\n<code>" + pkg + "</code>"
                } else if (manager.a11yAppAlert && isNightHour(nowWall) && nightAlertAgeMs(manager, nowWall, nowElapsed) >= NIGHT_ALERT_GAP_MS) {
                    if (nightUndo == null) nightUndo = captureNightStamp(manager, pkg, nowElapsed)
                    stampNightAlert(manager, pkg, nowWall, nowElapsed)
                    alertText = "\uD83C\uDF19 <b>Aplikasi dibuka malam</b>\n" + Html.escape(currentLabel) + "\n<code>" + pkg + "</code>\n" + TimeFmt.short(nowWall)
                }
                persistSnapshot = persistSnapshotLocked(terakru)
            }
        }
        tamperReason?.let { blockTamper(context, it) }
        alertText?.let {
            // Antre menolak (issue #811): cap malam dikembalikan.
            if (!sendAlert(context, it)) {
                try { nightUndo?.invoke() } catch (_: Exception) { }
            }
        }
        writePersistSnapshot(persistSnapshot)
    }

    private fun persistSnapshotLocked(force: Boolean): Triple<String, String, Long>? {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastPersistElapsed < PERSIST_INTERVAL_MS || persistBackoffActive(now))) return null
        val day = TimeFmt.dayKey(System.currentTimeMillis())
        if (day != loadedDay) return null
        val json = try { gson.toJson(usage) } catch (_: Exception) { "" }
        return Triple(day, json, now)
    }

    private fun writePersistSnapshot(snapshot: Triple<String, String, Long>?) {
        if (snapshot == null) return
        val manager = prefs ?: return
        try {
            manager.setA11yUsage(snapshot.first, snapshot.second)
            notePersistSuccess(snapshot.third)
        } catch (_: Exception) {
            notePersistFailure()
        }
    }

    private fun persistBackoffActive(now: Long): Boolean {
        if (persistFailStreak <= 0) return false
        val backoff = PERSIST_INTERVAL_MS * (1L shl (persistFailStreak - 1).coerceIn(0, 4))
        return now - lastPersistFailElapsed < backoff
    }

    private fun notePersistFailure() {
        synchronized(gate) {
            persistFailStreak += 1
            lastPersistFailElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun notePersistSuccess(atElapsed: Long) {
        synchronized(gate) {
            lastPersistElapsed = atElapsed
            persistFailStreak = 0
        }
    }

    private fun blockTamper(context: Context, reason: String) {
        // Jam dinding + peta persisten (issue #799): jeda anti-dobel
        // alert tamper sebelumnya hanya di memori, jadi restart proses
        // oleh sistem mengulang alert yang sama. Pola meniru peta
        // alert kata kunci yang sudah persisten di berkas ini.
        val now = System.currentTimeMillis()
        mainHandler.post {
            try { a11yService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) { }
        }
        val manager = try { prefs ?: PreferencesManager.getInstance(context.applicationContext).also { prefs = it } } catch (_: Exception) { null }
        var prevTamper = -1L
        val allowed = synchronized(lastTamperAlert) {
            if (!tamperStateLoaded) {
                tamperStateLoaded = true
                if (manager != null) {
                    try {
                        val parsed: MutableMap<String, Long>? = gson.fromJson(manager.a11yTamperAlertJson, alertType)
                        if (parsed != null) {
                            lastTamperAlert.putAll(parsed.filterValues { now - it < TAMPER_ALERT_GAP_MS })
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            val last = lastTamperAlert[reason] ?: 0L
            if (last > 0L && now - last >= 0L && now - last < TAMPER_ALERT_GAP_MS) false
            else {
                prevTamper = last
                lastTamperAlert[reason] = now
                if (manager != null) {
                    try { manager.a11yTamperAlertJson = gson.toJson(HashMap(lastTamperAlert)) } catch (_: Exception) { }
                }
                true
            }
        }
        if (!allowed) return
        val queued = sendAlert(context, "\uD83D\uDEE1\uFE0F <b>Proteksi tamper</b>\nPercobaan: " + Html.escape(reason) + "\nTindakan: layar ditutup")
        if (!queued) {
            // Antre menolak (issue #811): kembalikan entri jeda yang
            // telanjur dicap + dipersisten, agar percobaan berikutnya
            // tetap dilaporkan.
            synchronized(lastTamperAlert) {
                if (prevTamper > 0L) lastTamperAlert[reason] = prevTamper else lastTamperAlert.remove(reason)
                if (manager != null) {
                    try { manager.a11yTamperAlertJson = gson.toJson(HashMap(lastTamperAlert)) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun protectionActive(manager: PreferencesManager): Boolean {
        return monitoringActive(manager)
    }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        try { node.recycle() } catch (_: Exception) { }
    }

    // Pasangan kedua: kena kata pantau dari teks UTUH node (issue
    // #830), di luar potongan snapshot.
    private fun collectSnapshot(service: AccessibilityService, expectedPackage: String, keywords: List<String> = emptyList()): Pair<ScreenSnapshot, String?> {
        val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return ScreenSnapshot(emptyList(), false) to null
        val rootPackage = try { root.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
        if (rootPackage != expectedPackage) {
            recycleNode(root)
            return ScreenSnapshot(emptyList(), false) to null
        }
        // Sidik jendela di awal penelusuran (issue #929): node
        // adalah objek hidup — navigasi cepat di tengah
        // penelusuran mencampur teks dua layar (satu paket,
        // beda halaman) sehingga alasan tamper berbasis teks
        // bisa hilang/salah. Di akhir, jendela aktif diverifikasi
        // masih yang sama; bila sudah berganti, snapshot dibuang.
        val windowIdAwal = try { root.windowId } catch (_: Exception) { -1 }
        val out = ArrayList<String>()
        var hasPassword = false
        var fullHit: String? = null
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nodes = 0
        try {
            while (queue.isNotEmpty() && nodes < 160 && out.size < 80) {
                val node = queue.removeFirst()
                nodes++
                try {
                    if (try { node.isPassword } catch (_: Exception) { false }) hasPassword = true
                    val text = try { node.text?.toString()?.trim() } catch (_: Exception) { null }
                    if (!text.isNullOrEmpty()) {
                        // Uji kata pantau atas teks UTUH sebelum
                        // dipotong untuk snapshot (issue #830).
                        if (fullHit == null && keywords.isNotEmpty()) {
                            fullHit = try { AppWatch.hit(keywords, text) } catch (_: Exception) { null }
                        }
                        out.add(text.take(SCAN_TEXT_MAX_CHARS))
                    }
                    val desc = try { node.contentDescription?.toString()?.trim() } catch (_: Exception) { null }
                    if (!desc.isNullOrEmpty() && desc != text) {
                        if (fullHit == null && keywords.isNotEmpty()) {
                            fullHit = try { AppWatch.hit(keywords, desc) } catch (_: Exception) { null }
                        }
                        out.add(desc.take(SCAN_TEXT_MAX_CHARS))
                    }
                    val count = try { minOf(node.childCount, 24) } catch (_: Exception) { 0 }
                    for (i in 0 until count) {
                        if (queue.size >= 160) break
                        try {
                            val child = node.getChild(i)
                            if (child != null) queue.add(child)
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    recycleNode(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) recycleNode(queue.removeFirst())
        }
        if (windowIdAwal >= 0) {
            val rootKini = try { service.rootInActiveWindow } catch (_: Exception) { null }
            val windowIdKini = try { rootKini?.windowId ?: -1 } catch (_: Exception) { -1 }
            if (rootKini != null) recycleNode(rootKini)
            if (windowIdKini >= 0 && windowIdKini != windowIdAwal) {
                return ScreenSnapshot(emptyList(), false) to null
            }
        }
        return ScreenSnapshot(out, hasPassword) to fullHit
    }

    private fun accrueSessionLocked(nowWall: Long, nowElapsed: Long) {
        if (currentPackage.isEmpty() || currentStartElapsed <= 0L) return
        var remaining = nowElapsed - currentStartElapsed
        if (remaining <= 0L) return
        // Jendela dinding dijangkar ke jam dinding NYATA (issue #823):
        // sebelumnya awal dinding sesi + durasi elapsed diproyeksikan
        // maju, jadi transisi DST / lompatan jam manual di tengah sesi
        // menggeser atribusi nightMillis dan geserannya terbawa ke
        // akru berikutnya. Ujung jendela kini selalu nowWall.
        // Awal jendela tak boleh mundur melewati titik yang sudah
        // diakru (issue #834): jam yang DIMUNDURKAN membuat jendela
        // baru tumpang tindih dengan jendela akru sebelumnya dan
        // irisan malamnya terhitung dua kali.
        var wallStart = maxOf(currentStartWall, nowWall - remaining)
        while (remaining > 0L) {
            val chunk = minOf(remaining, MAX_SESSION_MS)
            val row = usage.getOrPut(currentPackage) { AppUsage(label = currentLabel) }
            row.millis += chunk
            row.nightMillis += minOf(chunk, A11yUsageMath.nightOverlapMs(wallStart, wallStart + chunk) { ts -> offsetAt(ts) })
            wallStart += chunk
            remaining -= chunk
        }
        currentStartWall = wallStart
        currentStartElapsed = nowElapsed
    }

    // Jeda alert malam persisten (issue #782): cap waktu + paket
    // disimpan di prefs agar restart proses (LMK) tidak meng-nol-kan
    // jeda 30 menit dan menggandakan alert dalam satu malam. Bidang
    // memori tetap sumber utama selama proses hidup; prefs hanya
    // dibaca bila bidang masih kosong (baru restart).
    // Penangkap keadaan cap malam sebelum ditulis (issue #811):
    // kembalikan lewat lambda bila pengantrean alertnya gagal.
    // Pemulihan dijaga (issue #833): lambda hanya memulihkan bila
    // cap saat ini MASIH cap yang ditulis situs ini — cap yang lebih
    // baru dari jalur lain tak boleh tertimpa — dan seluruh
    // periksa+pulih berjalan di dalam gate yang sama dengan
    // penulisan cap, karena pemanggilnya berada di luar kunci.
    private fun captureNightStamp(manager: PreferencesManager, writtenPkg: String, writtenElapsed: Long): () -> Unit {
        val memElapsed = lastNightAlertElapsed
        val memPkg = nightAlertedPackage
        val wPkg = try { manager.a11yNightAlertPkg } catch (_: Exception) { "" }
        val wAt = try { manager.a11yNightAlertAt } catch (_: Exception) { 0L }
        val wEl = try { manager.a11yNightAlertElapsed } catch (_: Exception) { 0L }
        return {
            synchronized(gate) {
                if (lastNightAlertElapsed == writtenElapsed && nightAlertedPackage == writtenPkg) {
                    lastNightAlertElapsed = memElapsed
                    nightAlertedPackage = memPkg
                    try { manager.setA11yNightAlert(wPkg, wAt, wEl) } catch (_: Exception) { }
                }
            }
        }
    }

    private fun stampNightAlert(manager: PreferencesManager, pkg: String, nowWall: Long, nowElapsed: Long) {
        lastNightAlertElapsed = nowElapsed
        nightAlertedPackage = pkg
        try { manager.setA11yNightAlert(pkg, nowWall, nowElapsed) } catch (_: Exception) { }
    }

    private fun nightAlertAgeMs(manager: PreferencesManager, nowWall: Long, nowElapsed: Long): Long {
        if (lastNightAlertElapsed > 0L) return nowElapsed - lastNightAlertElapsed
        val atWall = try { manager.a11yNightAlertAt } catch (_: Exception) { 0L }
        val atElapsed = try { manager.a11yNightAlertElapsed } catch (_: Exception) { 0L }
        if (atWall <= 0L && atElapsed <= 0L) return Long.MAX_VALUE
        val age = NetworkUtils.blockAgeMs(atWall, atElapsed, nowWall, nowElapsed)
        return if (age < 0L) 0L else age
    }

    private fun nightAlertPkg(manager: PreferencesManager): String {
        if (nightAlertedPackage.isNotEmpty()) return nightAlertedPackage
        return try { manager.a11yNightAlertPkg } catch (_: Exception) { "" }
    }

    // Mengembalikan true bila ada akru sesi nyata yang baru
    // masuk peta memori (issue #1036): pemanggil di cabang
    // bertrottle memakai nilai ini untuk memaksa persist,
    // karena penanda sesi-terbuka persisten sudah dihapus di
    // sini — akru yang hanya di memori hilang permanen bila
    // proses mati sebelum persist jatah berikutnya.
    private fun finishSessionLocked(nowWall: Long, nowElapsed: Long): Boolean {
        // Pembersih hanya berjalan bila memang ada cap tersimpan
        // (issue #1010): dulu setiap panggilan membayar dua
        // apply() prefs walau tidak ada sesi/cap yang berubah.
        val nightStored = nightAlertedPackage.isNotEmpty() ||
            try { prefs?.a11yNightAlertPkg?.isNotEmpty() == true } catch (_: Exception) { false }
        nightAlertedPackage = ""
        if (nightStored) {
            try { prefs?.clearA11yNightAlertPkg() } catch (_: Exception) { }
        }
        if (currentPackage.isEmpty() || currentStartElapsed <= 0L) {
            currentPackage = ""
            currentStartElapsed = 0L
            val sessionStored = try { prefs?.a11yOpenSessionPkg?.isNotEmpty() == true } catch (_: Exception) { false }
            if (sessionStored) {
                try { prefs?.clearA11yOpenSession() } catch (_: Exception) { }
            }
            return false
        }
        val terakru = nowElapsed > currentStartElapsed
        accrueSessionLocked(nowWall, nowElapsed)
        currentPackage = ""
        currentStartElapsed = 0L
        try { prefs?.clearA11yOpenSession() } catch (_: Exception) { }
        return terakru
    }

    private fun requestPeriodicPersist(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveRequestElapsed < PERSIST_INTERVAL_MS) return
        lastSaveRequestElapsed = now
        val app = context.applicationContext
        executor.execute {
            var crossAlert: String? = null
            var nightUndo: (() -> Unit)? = null
            synchronized(gate) {
                ensureLoadedLocked(app)
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                accrueSessionLocked(nowWall, nowElapsed)
                val manager = prefs
                if (manager != null && currentPackage.isNotEmpty() && currentPackage != nightAlertPkg(manager) &&
                    monitoringActive(manager) && manager.a11yAppAlert && isNightHour(nowWall) &&
                    nightAlertAgeMs(manager, nowWall, nowElapsed) >= NIGHT_ALERT_GAP_MS
                ) {
                    nightUndo = captureNightStamp(manager, currentPackage, nowElapsed)
                    stampNightAlert(manager, currentPackage, nowWall, nowElapsed)
                    crossAlert = "\uD83C\uDF19 <b>Aplikasi masih dipakai malam</b>\n" + Html.escape(labelFor(app, currentPackage)) + "\n<code>" + currentPackage + "</code>\n" + TimeFmt.short(nowWall)
                }
                persistLocked(app, false)
            }
            crossAlert?.let {
                // Antre menolak (issue #811): cap malam dikembalikan.
                if (!sendAlert(app, it)) {
                    try { nightUndo?.invoke() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun saveUsageFor(context: Context, day: String) {
        val manager = prefs ?: return
        try {
            manager.setA11yUsage(day, try { gson.toJson(usage) } catch (_: Exception) { "" })
            notePersistSuccess(SystemClock.elapsedRealtime())
        } catch (_: Exception) {
            notePersistFailure()
        }
    }

    private fun persistLocked(context: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (now - lastPersistElapsed < PERSIST_INTERVAL_MS || persistBackoffActive(now))) return
        val manager = prefs ?: return
        val day = TimeFmt.dayKey(System.currentTimeMillis())
        if (day != loadedDay) return
        try {
            manager.setA11yUsage(day, try { gson.toJson(usage) } catch (_: Exception) { "" })
            notePersistSuccess(now)
            refreshOpenSessionMarkerLocked(manager)
        } catch (_: Exception) {
            notePersistFailure()
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        return try {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)?.isKeyguardLocked ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun monitoringActive(manager: PreferencesManager): Boolean {
        return try {
            manager.isMonitoringEnabled && !manager.monitoringPaused && !manager.userDisabledMonitoring && manager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    private fun isNightHour(timestamp: Long): Boolean {
        val hour = A11yUsageMath.localHour(timestamp, offsetAt(timestamp))
        return hour >= A11yUsageMath.NIGHT_START_HOUR || hour < A11yUsageMath.NIGHT_END_HOUR
    }

    private fun offsetAt(timestamp: Long): Int {
        return try { java.util.TimeZone.getDefault().getOffset(timestamp) } catch (_: Exception) { 0 }
    }

    private fun defaultLauncherPackage(context: Context): String {
        // Masa cache 60 detik (issue #832): 10 menit sebelumnya
        // membuat launcher BARU sesudah ganti launcher default
        // tercatat sebagai aplikasi biasa (ikut akru + penilaian
        // malam) selama sisa cache. Resolusi HOME murah dan fungsi
        // ini hanya dipanggil pada pergantian jendela.
        if (launcherPackage.isNotEmpty() && System.currentTimeMillis() - launcherPackageAt < 60_000L) return launcherPackage
        launcherPackage = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        launcherPackageAt = System.currentTimeMillis()
        return launcherPackage
    }

    private fun isLaunchable(context: Context, pkg: String): Boolean {
        synchronized(labels) {
            launchable[pkg]?.let { return it }
        }
        var resolved = false
        val ok = try {
            val result = pkg != "com.android.systemui" && context.packageManager.getLaunchIntentForPackage(pkg) != null
            resolved = true
            result
        } catch (_: Exception) {
            false
        }
        if (resolved) {
            synchronized(labels) { launchable[pkg] = ok }
        }
        return ok
    }

    private fun labelFor(context: Context, pkg: String): String {
        synchronized(labels) {
            labels[pkg]?.let { return it }
        }
        var resolved = false
        val label = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            val result = context.packageManager.getApplicationLabel(info).toString().ifEmpty { pkg }
            resolved = true
            result
        } catch (_: Exception) {
            pkg
        }
        if (resolved) {
            synchronized(labels) { labels[pkg] = label }
        }
        return label
    }

    fun invalidatePackageCache(pkg: String) {
        try {
            synchronized(labels) {
                labels.remove(pkg)
                launchable.remove(pkg)
            }
        } catch (_: Exception) {
        }
    }

    // Hasil antre dikembalikan (issue #811): pemanggil menulis cap
    // jeda/hitungannya di sekitar panggilan ini, jadi kegagalan antre
    // harus terlihat agar cap bisa dibatalkan — sebelumnya hasil
    // dibuang dan alert hilang sementara jedanya tetap terpakai.
    private fun sendAlert(context: Context, text: String): Boolean {
        return try {
            val queued = MessageQueue.getInstance(context).addMessage(text, true)
            if (queued) MessageScheduler.scheduleMessageSend(context)
            queued
        } catch (_: Exception) {
            false
        }
    }
}
