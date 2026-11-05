package com.redeye.parentalmonitor.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        // Bagian nightMillis yang jatuh 22.00-24.00
        // (issue #1607) — pasangan untuk menyusun total
        // jendela malam yang menyeberangi tengah malam.
        var nightLateMillis: Long = 0L,
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
    private val lastTamperAlert = HashMap<String, Long>()
    private var tamperStateLoaded = false
    private val lastContentScan = HashMap<String, Long>()
    // Jejak jendela terakhir untuk cek ulang tamper (issue #922).
    @Volatile
    private var lastWindowPkg = ""
    // Keempatnya @Volatile (issue #1651): anotasi hanya
    // menempel pada properti tepat di bawahnya, jadi
    // lastWindowCls/lastSensitivePkg sebelumnya polos
    // padahal ditulis utas utama dan dibaca utas IO
    // (onMonitoringResumed) tanpa kunci — kelas
    // visibilitas yang sama dengan issue #1017.
    @Volatile
    private var lastWindowCls = ""
    @Volatile
    private var lastSensitiveCls = ""
    @Volatile
    private var lastSensitivePkg = ""
    @Volatile
    private var contentEventsOn: Boolean? = null
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
    // Sesi yang terpotong jendela transien (issue #1511):
    // paket asal + sejak kapan (elapsed) — kembali ke
    // paket yang sama dalam 10 menit menyambung sesi
    // (celah teratribusi ke asal, tanpa launch baru).
    private var transienPackage = ""
    private var transienSejakElapsed = 0L
    private var launcherPackage = ""
    private var launcherPackageAt = 0L
    private var knownSeeded = false
    private var seedRetryAfter = 0L
    private var seedFailStreak = 0

    fun attach(context: Context) {
        val app = context.applicationContext
        prefs = try { PreferencesManager.getInstance(app) } catch (_: Exception) { null }
        if (context is AccessibilityService) a11yService = context
        // Pemulihan sesi-terbuka tak boleh ikut nasib
        // DiscardPolicy (issue #1323, pola #949 di flush):
        // antrean penuh tepat saat layanan bind ulang sesudah
        // proses mati membuang pemulihan diam-diam dan rentang
        // pemakaian terakhir hilang. Badan tugas digerbang
        // KLAIM atomik (issue #1341): pemeriksaan lama
        // (started + isi antrean) punya dua jendela balapan —
        // lepas-antrean sebelum run, dan serahan firstTask
        // yang tak pernah masuk antrean — sehingga tugas yang
        // sehat bisa jalan dua kali. Kini siapa pun (pekerja
        // atau pemanggil) yang memenangkan klaim yang
        // menjalankan badan; pihak lain langsung kembali,
        // jadi jalan-langsung pemanggil aman untuk semua
        // keadaan tak-terantre, bukan hanya yang terbuang.
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
            ensureLoaded(app)
            synchronized(gate) { restoreOpenSessionLocked(app) }
        }
        executor.execute(task)
        if (!executor.queue.contains(task)) {
            task.run()
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
            val todayStart = A11yUsageMath.dayStartWallMillis(nowWall, java.util.TimeZone.getDefault())
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
                val spanEnd = A11yUsageMath.splitBoundaryWallMillis(startWall, todayStart, java.util.TimeZone.getDefault())
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
            // Penanda hanya dihapus bila kematian sesinya
            // PASTI (issue #1373): jendela depan yang tak
            // terbaca sesaat (root kosong tepat sesudah bind),
            // masih di launcher/aplikasi sendiri, atau permukaan
            // sistem bukan bukti sesi berakhir — menghapus
            // penanda membuang ekor sesi permanen. Kasus tak
            // pasti dibiarkan; percobaan ulang di handleWindow
            // yang memutuskan (kembali ke aplikasi yang sama =
            // sesi lanjut dengan cap asli; pindah ke aplikasi
            // lain yang bisa dibuka = penanda dibersihkan di
            // sana).
            val bolehHapus = when {
                startWall <= 0L -> true
                foreground == pkg -> true
                foreground.isEmpty() -> false
                foreground == context.packageName -> false
                foreground == defaultLauncherPackage(context) -> false
                isLaunchable(context, foreground) -> true
                else -> false
            }
            if (bolehHapus) {
                try { manager.clearA11yOpenSession() } catch (_: Exception) { }
            }
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

    fun onMonitoringResumed(context: Context) {
        // Monitoring jalan lagi (issue #1372): sesi hanya
        // lahir dari transisi jendela dan dedupe lastEventKey
        // menelan peristiwa ulang, jadi aplikasi yang tetap
        // terbuka di jendela yang sama sejak sebelum jeda tak
        // pernah memulai sesi — pemakaiannya hilang dari
        // /screentime dan alert malamnya tak bisa menyala.
        // Putar ulang jendela terakhir lewat jalur
        // handleWindow yang sama (klaim-CAS seperti transisi
        // sungguhan) agar semua gerbangnya berlaku persis.
        // Paket sensitif (Setelan dsb.) dilewati: penilaian
        // tampernya tak boleh diputar ulang di luar peristiwa
        // aslinya (masa tenggang bisa sudah lewat).
        val pkg = lastWindowPkg
        if (pkg.isEmpty() || TamperGuard.mayBeSensitive(pkg)) return
        val cls = lastWindowCls
        val app = context.applicationContext
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
            handleWindow(app, pkg, cls, emptyList())
        }
        executor.execute(task)
        if (!executor.queue.contains(task)) {
            task.run()
        }
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
                lastWindowCls = cls
                val sensitive = TamperGuard.mayBeSensitive(pkg)
                if (sensitive) {
                    lastSensitiveCls = cls
                    lastSensitivePkg = pkg
                }
                val app = service.applicationContext
                // Transisi jendela digerbang KLAIM atomik seperti
                // transisi sesi lain (issue #1358, keluarga
                // #1341/#1342): badan handleWindow tidak idempoten
                // (menutup sesi + akru, membuka sesi baru, menaikkan
                // hitungan buka, menilai tamper) dan penanda dedupe
                // lastEventKey sudah ditulis pemanggil, jadi
                // serahan telanjang yang tertolak DiscardPolicy
                // menghilangkan transisi ini selamanya. Siapa pun
                // yang memenangkan klaim menjalankan badan tepat
                // sekali; tak-terantre berarti pemanggil sendiri.
                val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
                val task = Runnable {
                    if (!claimed.compareAndSet(false, true)) return@Runnable
                    val texts = if (sensitive) collectSnapshot(service, pkg).first.texts else emptyList()
                    handleWindow(app, pkg, cls, texts)
                }
                executor.execute(task)
                if (!executor.queue.contains(task)) {
                    task.run()
                }
                scanWatchedContent(service, pkg)
                requestPeriodicPersist(app)
                refreshEventTypes(service)
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
                    // sensitif terakhir yang tercatat; alert-nya
                    // sendiri tetap dijatah persisten per alasan.
                    // Syaratnya paket SENSITIF terakhir, BUKAN
                    // lastWindowPkg (issue #1387): lastWindowPkg
                    // ditimpa setiap peristiwa jendela paket apa
                    // pun, jadi satu jendela selintas (panel
                    // notifikasi, gelembung chat) melumpuhkan
                    // cek-ulang halaman sensitif yang masih
                    // terbuka secara senyap.
                    if (pkg == lastSensitivePkg && TamperGuard.mayBeSensitive(pkg)) {
                        val nowE = SystemClock.elapsedRealtime()
                        if (nowE - lastTamperRecheckAt >= 10_000L) {
                            lastTamperRecheckAt = nowE
                            val cls = lastSensitiveCls
                            val app = service.applicationContext
                            // Digerbang KLAIM atomik (issue #1359,
                            // keluarga #1341/#1358): serahan
                            // telanjang yang tertolak DiscardPolicy
                            // menghilangkan cek ini DAN cap jatah
                            // 10 detik di atas sudah terbakar,
                            // menunda penilaian TamperGuard halaman
                            // sensitif yang sudah terbuka. Dengan
                            // klaim, tugas selalu berjalan tepat
                            // sekali — di pekerja atau di pemanggil.
                            val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
                            val task = Runnable {
                                if (!claimed.compareAndSet(false, true)) return@Runnable
                                val texts = collectSnapshot(service, pkg).first.texts
                                // Nilai-saja (issue #1404): cek
                                // ulang bisa berasal dari jendela
                                // TAK di depan (split-screen);
                                // penilaian tamper tetap penuh,
                                // tetapi transisi sesi screentime
                                // hanya milik peristiwa jendela
                                // depan sungguhan — tanpa ini sesi
                                // aplikasi depan terpotong dan
                                // hitungan buka paket sensitif
                                // bertambah semu.
                                handleWindow(app, pkg, cls, texts, nilaiSaja = true)
                            }
                            executor.execute(task)
                            if (!executor.queue.contains(task)) {
                                task.run()
                            }
                        }
                    }
                    scanWatchedContent(service, pkg)
                    requestPeriodicPersist(service.applicationContext)
                }
            }
        }
    }

    // Langganan event konten dinamis (issue #1571): XML
    // melanggan TYPE_WINDOW_CONTENT_CHANGED secara permanen,
    // padahal aliran event terderas itu hanya punya dua
    // konsumen — cek-ulang tamper (bermakna hanya selagi
    // halaman sensitif terakhir masih di depan) dan pindai
    // kata pantau (mati secara bawaan). Selama tak satu pun
    // aktif, tipe event itu dimatikan lewat setServiceInfo
    // agar sistem tak mengirimkan ribuan event untuk dibuang.
    // Halaman sensitif dinilai aktif hanya bila ia jendela
    // depan terakhir: lastSensitivePkg sendiri lengket
    // (issue #1387), jadi tanpa syarat ini langganan tak
    // pernah mati sesudah kunjungan pertama. Konsekuensi
    // yang diterima: split-screen dengan fokus di aplikasi
    // lain menghentikan cek-ulang halaman sensitif yang
    // masih tampak, sampai ia kembali ke depan.
    // Gagal menilai kebutuhan = tetap menyala (arah aman);
    // serviceInfo null = jangan sentuh (info kosong akan
    // menghapus kapabilitas dari XML). Dinilai ulang pada
    // koneksi layanan dan tiap transisi jendela, jadi
    // pengaktifan pantau berlaku mulai transisi berikutnya.
    fun refreshEventTypes(service: AccessibilityService) {
        val manager = prefs ?: return
        val watchActive = try {
            manager.a11yAppWatchEnabled && watchedPackages(manager).isNotEmpty() && watchedKeywords(manager).isNotEmpty()
        } catch (_: Exception) {
            true
        }
        val tamperActive = lastSensitivePkg.isNotEmpty() && lastWindowPkg == lastSensitivePkg && TamperGuard.mayBeSensitive(lastSensitivePkg)
        val need = watchActive || tamperActive
        if (contentEventsOn == need) return
        try {
            val info: AccessibilityServiceInfo = service.serviceInfo ?: return
            info.eventTypes = if (need) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            } else {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            }
            service.setServiceInfo(info)
            contentEventsOn = need
        } catch (_: Exception) {
        }
    }

    // Lepas cache label saat tekanan memori (issue #1598):
    // kedua peta dibangun ulang malas dari PackageManager.
    fun releaseCaches() {
        try { synchronized(labels) { labels.clear() } } catch (_: Exception) { }
        try { synchronized(labels) { launchable.clear() } } catch (_: Exception) { } // monitor SAMA dengan semua akses operasional launchable (issue #1667): dulu synchronized(launchable) sendiri, tak saling mengecualikan dengan isLaunchable/invalidatePackageCache
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
        // Pemangkasan ditaruh SEBELUM gerbang (issue #1270):
        // di posisi lama blok ini hanya terjangkau bila pantau
        // app menyala, sehingga pada keadaan bawaan peta tumbuh
        // monoton tanpa pernah dipangkas.
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
        if (AppWatch.isSensitivePackage(pkg)) return
        val manager = prefs ?: return
        if (!manager.a11yAppWatchEnabled) return
        if (pkg !in watchedPackages(manager)) return
        val keywords = watchedKeywords(manager)
        if (keywords.isEmpty()) return
        val app = service.applicationContext
        // Pola klaim seperti transisi satu-tembakan lain
        // (issue #1608): serahan telanjang yang tertolak
        // DiscardPolicy dulu hilang senyap padahal cap
        // throttle-nya sudah terbakar (#1014) — kini tugas
        // yang tak masuk antrean berjalan tepat satu kali
        // di pemanggil; CAS menjaga tak ada jalan ganda.
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
            val (snapshot, fullHit) = collectSnapshot(service, pkg, keywords)
            if (!snapshot.hasPassword && snapshot.texts.isNotEmpty()) {
                handleContent(app, pkg, snapshot.texts, keywords, fullHit)
            }
        }
        executor.execute(task)
        if (!executor.queue.contains(task)) {
            task.run()
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
        val key = pkg + "|" + hit
        var last = 0L
        // Seluruh urutan baca-ubah-simpan state kata pantau
        // di dalam gate yang sama (issue #1652): pola
        // serah-tugas bisa menjalankan dua handleContent
        // sejajar (utas utama sebaris + utas executor),
        // dan HashMap + penghitung polos ini sebelumnya
        // berlomba tanpa kunci — iterasi toJson di
        // saveKeywordState bisa menabrak ubahan sejajar
        // dan peta jeda persisten tersimpan kosong.
        synchronized(gate) {
            ensureKeywordState(manager, day)
            if (keywordAlertCount >= MAX_KEYWORD_ALERTS_PER_DAY) return
            last = lastKeywordAlert[key] ?: 0L
            if (last > 0L && nowWall - last < KEYWORD_ALERT_GAP_MS) return
            lastKeywordAlert[key] = nowWall
            keywordAlertCount++
            saveKeywordState(manager)
        }
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
            // kejadian yang sama bisa dilaporkan lagi. Di dalam
            // gate yang sama dengan penulisannya (issue #1652).
            synchronized(gate) {
                if (last > 0L) lastKeywordAlert[key] = last else lastKeywordAlert.remove(key)
                keywordAlertCount--
                saveKeywordState(manager)
            }
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
        // peta jeda dimulai ulang, bukan diwarisi. Batas basi
        // memakai varian zona 50 jam (issue #1182, kelas yang
        // sama dengan #1055): cap harian ini tak terikat jendela
        // jam, dan satu tanggal lokal bisa berumur >24 jam epoch
        // sesudah terbang ke barat lintas zona — batas ketat
        // 24 jam me-reset hitungan pada tanggal yang sama.
        val pasanganSah = day == keywordAlertDay &&
            com.redeye.parentalmonitor.utils.DayStamp.blocks(keywordAlertDay, keywordAlertDayAtMs, day, System.currentTimeMillis(), com.redeye.parentalmonitor.utils.DayStamp.STALE_MS_ZONA)
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
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
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
        // hilang tanpa pernah terpersisten. Bila tugas tak ada
        // di antrean, pemanggil mencobanya langsung — klaim
        // atomik di badan (issue #1341) memastikan badan tetap
        // jalan tepat satu kali walau pekerja ternyata sudah
        // melepasnya dari antrean atau menerimanya sebagai
        // firstTask: yang kalah klaim langsung kembali.
        if (!executor.queue.contains(task)) {
            task.run()
        }
    }

    fun screenOff(context: Context) {
        val app = context.applicationContext
        // Finalisasi sesi saat layar mati tak boleh ikut nasib
        // DiscardPolicy (issue #1324, pola #949 di flush): bila
        // terbuang, sesi tak tertutup dan rentang layar-mati
        // (termasuk jam malam) ikut terakru ke statistik
        // pemakaian berikutnya. Bila tugas tak mulai dan tak
        // ada di antrean, ia terbuang — jalankan langsung.
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
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
        executor.execute(task)
        // Pola anti-buang yang sama dengan flush (issue
        // #1324), kini digerbang klaim atomik (issue #1341):
        // tepat satu pihak menjalankan badan.
        if (!executor.queue.contains(task)) {
            task.run()
        }
    }

    fun screenOn(context: Context) {
        val app = context.applicationContext
        // Lanjut-sesi saat layar menyala tak boleh ikut nasib
        // DiscardPolicy (issue #1342, pola #949/#1324):
        // tugas ini satu-satunya transisi sesi satu-tembakan
        // yang dulu diserahkan telanjang — bila antrean jenuh
        // tepat saat layar menyala, kelanjutan sesi tersuspensi
        // terbuang diam-diam dan rentang pemakaian itu hilang
        // dari statistik. Kini pola anti-buang yang sama
        // dengan screenOff, digerbang klaim atomik (#1341).
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = Runnable {
            if (!claimed.compareAndSet(false, true)) return@Runnable
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
        executor.execute(task)
        if (!executor.queue.contains(task)) {
            task.run()
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

    // Baris pemakaian KEMARIN dari slot persisten
    // (issue #1607): laporan menyusun total jendela malam
    // 22.00-06.00 terakhir dari paruh akhir kemarin +
    // paruh awal hari ini.
    fun yesterdayUsage(context: Context): List<AppUsage> {
        val app = context.applicationContext
        ensurePrefs(app)
        synchronized(gate) {
            ensureLoadedLocked(app)
            // Kemarin kalender, bukan now-24 jam tetap
            // (issue #1660): sesudah hari 23 jam (DST/geser
            // offset ke timur), pada 00.00-01.00 kunci
            // now-86_400_000 menunjuk hari sebelum kemarin,
            // sehingga paruh akhir malam terakhir terbaca
            // dari slot yang salah.
            val yesterday = TimeFmt.dayKey(A11yUsageMath.previousDayStartWallMillis(System.currentTimeMillis(), java.util.TimeZone.getDefault()))
            return loadUsage(app, yesterday).values.map { it.copy() }
                .filter { it.millis > 0L || it.launches > 0 }
                .sortedByDescending { it.millis }
        }
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
        // Seed yang gagal dicoba lagi berkala (issue #1355):
        // tanpa ini kegagalan sesaat (kueri launcher melempar/
        // kosong saat proses mulai) membekukan status belum-
        // seeded sampai ganti hari atau proses dimulai ulang.
        if (!knownSeeded && SystemClock.elapsedRealtime() >= seedRetryAfter) {
            // Backoff kegagalan seed (issue #1592): gerbang
            // 60 detik #1355 menjaga seed yang gagal sesaat
            // tetap dicoba lagi, tetapi bila kueri paket
            // TERUS gagal (prune/seed tak pernah mengunci
            // penandanya), enumerasi ratusan paket berulang
            // pada irama penuh selamanya. Kegagalan tanpa
            // kemajuan apa pun kini menggandakan jeda coba
            // lagi (60 dtk -> s.d. 16 mnt); kemajuan apa pun
            // (satu penanda terkunci) mengembalikan ke 60 dtk.
            val seededBefore = knownSeeded
            val reconciledBefore = knownReconciled
            seedKnownAppsLocked(context)
            if (knownSeeded != seededBefore || knownReconciled != reconciledBefore) {
                seedFailStreak = 0
                seedRetryAfter = SystemClock.elapsedRealtime() + 60_000L
            } else {
                seedFailStreak = (seedFailStreak + 1).coerceAtMost(4)
                seedRetryAfter = SystemClock.elapsedRealtime() + 60_000L * (1L shl seedFailStreak)
            }
        }
        if (loaded && today == loadedDay) return
        val previousDay = loadedDay
        val activePackage = currentPackage
        val activeLabel = currentLabel
        var resumeWall = 0L
        var resumeElapsed = 0L
        if (loaded && previousDay.isNotEmpty() && today != previousDay) {
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val dayStart = A11yUsageMath.dayStartWallMillis(nowWall, java.util.TimeZone.getDefault())
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
                val boundaries = A11yUsageMath.splitBoundariesWallMillis(currentStartWall, dayStart, java.util.TimeZone.getDefault())
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
            // Calon slot bertanggal hari ini, urut utama →
            // cadangan (issue #1037). Parse dicoba satu per
            // satu (issue #1524): dulu slot dipilih berdasar
            // tanggal SEBELUM parse — JSON hari ini yang
            // rusak menghasilkan peta kosong tanpa mencoba
            // slot cadangan bertanggal sama, dan persist
            // berikutnya menimpa statistik satu hari senyap.
            val kandidat = ArrayList<String>(3)
            if (manager.a11yUsageDate == today) kandidat.add(manager.a11yUsageJson)
            if (manager.a11yUsagePrevDate == today) kandidat.add(manager.a11yUsagePrevJson)
            if (manager.a11yUsagePrev2Date == today) kandidat.add(manager.a11yUsagePrev2Json)
            for (raw in kandidat) {
                if (raw.isEmpty()) continue
                try {
                    val parsed: MutableMap<String, AppUsage>? = gson.fromJson(raw, usageType)
                    if (parsed != null) return parsed
                } catch (_: Exception) {
                }
            }
            HashMap()
        } catch (_: Exception) {
            HashMap()
        }
    }

    private var knownReconciled = false

    private fun seedKnownAppsLocked(context: Context) {
        // Rekonsiliasi sekali per proses (issue #1277), sebelum
        // jalan pintas seeded: entri dikenal untuk aplikasi yang
        // tercopot selama layanan mati dibuang agar pasang-ulang
        // kembali memicu alert aplikasi baru.
        if (!knownReconciled) {
            val managerAwal = prefs
            if (managerAwal != null) {
                // Terkunci true hanya bila rekonsiliasi
                // SUNGGUH selesai (issue #1301, diluruskan
                // #1316, dituntaskan #1334): prune kini
                // melaporkan hasilnya, jadi dua saluran senyap
                // terakhir ikut tertutup — kueri paket gagal
                // (daftar kosong membuat prune melewatkan
                // pekerjaannya) dan kegagalan di dalam prune
                // sama-sama membiarkan penanda terbuka agar
                // dicoba lagi pada muat berikutnya.
                val terpasang = try {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        context.packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)).map { it.packageName }
                    } else {
                        @Suppress("DEPRECATION")
                        context.packageManager.getInstalledPackages(0).map { it.packageName }
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                if (managerAwal.pruneA11yKnownApps(terpasang)) {
                    knownReconciled = true
                }
            }
        }
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

    private fun handleWindow(context: Context, pkg: String, cls: String, texts: List<String>, nilaiSaja: Boolean = false) {
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
            // Bendera panas dijepret sekali per transisi
            // (issue #1581): monitoringActive menyusun 4 get
            // prefs terenkripsi dan dulu dinilai dua kali
            // dalam satu transisi (jalur tamper + jalur
            // berhenti), a11yAppAlert dua kali juga. Nilai
            // tak bisa berubah di tengah blok gate yang
            // sama, jadi jepretan ini setia.
            val tamperOn = try { manager.a11yTamperEnabled } catch (_: Exception) { false }
            val activeNow = monitoringActive(manager)
            val appAlertOn = try { manager.a11yAppAlert } catch (_: Exception) { false }
            // Percobaan ulang pemulihan sesi (issue #1373):
            // restore di attach bisa gagal menilai karena
            // jendela depan sesaat tak cocok/tak terbaca;
            // peristiwa jendela berikutnya menilai lagi
            // SEBELUM transisinya diproses, selagi tak ada
            // sesi yang sedang terbuka.
            if (currentPackage.isEmpty()) {
                try { restoreOpenSessionLocked(context) } catch (_: Exception) { }
            }
            if (tamperOn && activeNow) {
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
            if (!done && !nilaiSaja && !activeNow) {
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
            if (!done && !nilaiSaja && (pkg == context.packageName || pkg == launcherPkg || !launchable)) {
                // Jendela transien murni (lembar berbagi,
                // billing Play, systemui): sesi asal
                // disimpan untuk penyambungan (issue #1511).
                // Jendela milik sendiri/peluncur adalah
                // akhir sesi yang sah — stash dibersihkan.
                if (!launchable && pkg != context.packageName && pkg != launcherPkg) {
                    if (currentPackage.isNotEmpty()) {
                        transienPackage = currentPackage
                        transienSejakElapsed = nowElapsed
                    }
                } else {
                    transienPackage = ""
                    transienSejakElapsed = 0L
                }
                val terakru = finishSessionLocked(nowWall, nowElapsed)
                persistSnapshot = persistSnapshotLocked(terakru)
                done = true
            }
            // Penyambungan transien (issue #1511): kembali
            // ke paket asal dalam 10 menit — sesi tersambung
            // TANPA launch tambahan; celah yang DIAKRU ke
            // aplikasi asal dibatasi 60 detik (issue #1610):
            // jendela transien nyata berskala detik, jadi
            // menit di luar itu (termasuk menit malamnya)
            // tak lagi terhitung sebagai pemakaian asal.
            var sambungTransien = false
            if (!done && !nilaiSaja && pkg != currentPackage &&
                transienPackage.isNotEmpty() && pkg == transienPackage &&
                transienSejakElapsed > 0L && nowElapsed - transienSejakElapsed in 0..600_000L) {
                currentPackage = pkg
                val celahTerakru = minOf(nowElapsed - transienSejakElapsed, 60_000L)
                currentStartElapsed = nowElapsed - celahTerakru
                currentStartWall = nowWall - celahTerakru
                accrueSessionLocked(nowWall, nowElapsed)
                currentPackage = ""
                currentStartElapsed = 0L
                sambungTransien = true
            }
            if (transienPackage.isNotEmpty() && pkg != transienPackage) {
                transienPackage = ""
                transienSejakElapsed = 0L
            }
            if (!done && !nilaiSaja && pkg != currentPackage) {
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
                if (!sambungTransien) row.launches += 1
                val seeded = manager.a11yKnownSeeded
                // Belum seeded -> JANGAN mengingat (issue #1355):
                // mengingat pada jendela seed-gagal menelan
                // aplikasi itu selamanya (seed hanya menambah,
                // tak pernah memicu alert untuk yang sudah
                // dikenal) — ia masuk himpunan tanpa kabar dan
                // tak akan pernah memicu alert aplikasi baru.
                val firstOpen = if (seeded && !sambungTransien) manager.rememberA11yApp(pkg) else false
                if (seeded && firstOpen && appAlertOn) {
                    if (isNightHour(nowWall)) {
                        if (nightUndo == null) nightUndo = captureNightStamp(manager, pkg, nowElapsed)
                        stampNightAlert(manager, pkg, nowWall, nowElapsed)
                    }
                    alertText = "\uD83D\uDCE6 <b>Aplikasi baru dibuka</b>\n" + Html.escape(currentLabel) + "\n<code>" + pkg + "</code>"
                } else if (!sambungTransien && appAlertOn && isNightHour(nowWall) && nightAlertAgeMs(manager, nowWall, nowElapsed) >= NIGHT_ALERT_GAP_MS) {
                    if (nightUndo == null) nightUndo = captureNightStamp(manager, pkg, nowElapsed)
                    stampNightAlert(manager, pkg, nowWall, nowElapsed)
                    alertText = "\uD83C\uDF19 <b>Aplikasi dibuka malam</b>\n" + Html.escape(currentLabel) + "\n<code>" + pkg + "</code>\n" + TimeFmt.short(nowWall)
                }
                persistSnapshot = persistSnapshotLocked(terakru)
            }
            // Snapshot ditulis DI DALAM gate (issue #1280):
            // penulisan di luar gate bisa kalah urut dari
            // persist flush() yang berjalan di dalam gate pada
            // benang executor — snapshot basi menimpa keadaan
            // yang lebih baru dan akru sesi terakhir hilang
            // permanen bila proses mati di jendela itu.
            writePersistSnapshot(persistSnapshot)
        }
        tamperReason?.let { blockTamper(context, it) }
        alertText?.let {
            // Antre menolak (issue #811): cap malam dikembalikan.
            if (!sendAlert(context, it)) {
                try { nightUndo?.invoke() } catch (_: Exception) { }
            }
        }
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
        // Hasil aksi pulang kini diperiksa (issue #1438):
        // dulu aksi dibuang hasilnya (dan ditunda lewat
        // post) sementara alert selalu mengklaim layar
        // ditutup — bila layanan sedang ter-unbind, klaim
        // itu tak terbukti. Aksi dipanggil langsung dari
        // alur peristiwa (utas utama) dan teks alert
        // mengikuti hasilnya.
        val layarDitutup = try {
            a11yService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false
        } catch (_: Exception) {
            false
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
        val queued = sendAlert(context, "\uD83D\uDEE1\uFE0F <b>Proteksi tamper</b>\nPercobaan: " + Html.escape(reason) + if (layarDitutup) "\nTindakan: layar ditutup" else "\nTindakan: perintah menutup layar GAGAL dijalankan (layanan aksesibilitas mungkin sedang terputus)")
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

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (android.os.Build.VERSION.SDK_INT >= 33) return
        try { node.recycle() } catch (_: Exception) { }
    }

    // Pasangan kedua: kena kata pantau dari teks UTUH node (issue
    // #830), di luar potongan snapshot.
    private fun collectSnapshot(service: AccessibilityService, expectedPackage: String, keywords: List<String> = emptyList()): Pair<ScreenSnapshot, String?> {
        var dariJendelaLain = false
        var terpilih: AccessibilityNodeInfo? = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return ScreenSnapshot(emptyList(), false) to null
        val rootPackage = try { terpilih?.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
        if (rootPackage != expectedPackage) {
            terpilih?.let { recycleNode(it) }
            terpilih = null
            // Jendela sensitif yang TERLIHAT tetapi tak aktif
            // (issue #1397): di split-screen halaman sensitif
            // bisa tampil penuh di panel tak-aktif sementara
            // jendela aktif milik aplikasi lain — snapshot
            // hanya dari rootInActiveWindow membuatnya tak
            // pernah dinilai berbasis teks. Cari akar paket
            // yang sama di daftar jendela layanan.
            val daftar = try { service.windows } catch (_: Exception) { null }
            if (daftar != null) {
                for (jendela in daftar) {
                    val calon = try { jendela.root } catch (_: Exception) { null } ?: continue
                    val pkgCalon = try { calon.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                    if (pkgCalon == expectedPackage) {
                        terpilih = calon
                        dariJendelaLain = true
                        break
                    }
                    recycleNode(calon)
                }
            }
            if (terpilih == null) return ScreenSnapshot(emptyList(), false) to null
        }
        // Ikat ke val non-null untuk seluruh penelusuran di
        // bawahnya (smart-cast atas var yang ditugaskan ulang
        // dalam cabang tak berlaku).
        val root: AccessibilityNodeInfo = terpilih!!
        // Sidik jendela di awal penelusuran (issue #929): node
        // adalah objek hidup — navigasi cepat di tengah
        // penelusuran mencampur teks dua layar (satu paket,
        // beda halaman) sehingga alasan tamper berbasis teks
        // bisa hilang/salah. Di akhir, jendela aktif diverifikasi
        // masih yang sama; bila sudah berganti, snapshot dibuang.
        // Sidik jendela hanya untuk akar AKTIF; akar dari
        // jendela lain (split-screen) tak punya pembanding
        // aktif yang sahih — lewati pemeriksaan #929 untuknya.
        val windowIdAwal = if (dariJendelaLain) -1 else try { root.windowId } catch (_: Exception) { -1 }
        val out = ArrayList<String>()
        var hasPassword = false
        var fullHit: String? = null
        // Teks yang sudah dipindai kata pantau dalam snapshot
        // ini (issue #1582): baris berulang di daftar (label
        // dan cap waktu yang sama) dulu membayar lowercase +
        // pindai penuh yang sama berkali-kali — hasil untuk
        // teks yang identik selalu sama, jadi cukup sekali.
        val sudahPindai = HashSet<String>()
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
                        if (fullHit == null && keywords.isNotEmpty() && sudahPindai.add(text)) {
                            fullHit = try { AppWatch.hit(keywords, text) } catch (_: Exception) { null }
                        }
                        out.add(text.take(SCAN_TEXT_MAX_CHARS))
                    }
                    val desc = try { node.contentDescription?.toString()?.trim() } catch (_: Exception) { null }
                    if (!desc.isNullOrEmpty() && desc != text) {
                        if (fullHit == null && keywords.isNotEmpty() && sudahPindai.add(desc)) {
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
            row.nightLateMillis += minOf(chunk, A11yUsageMath.nightLateOverlapMs(wallStart, wallStart + chunk) { ts -> offsetAt(ts) })
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
        // Pasangan (paket, cap waktu) dibaca dan ditulis atomik
        // di bawah monitor labels (issue #1668): dulu tanpa
        // kunci dan tanpa @Volatile sama sekali, dan satu
        // pemanggil (handleWindow) berada di luar gate — paket
        // resolusi satu utas bisa berpasangan dengan cap waktu
        // utas lain dan mengunci launcher yang salah 60 detik.
        synchronized(labels) {
            if (launcherPackage.isNotEmpty() && System.currentTimeMillis() - launcherPackageAt < 60_000L) return launcherPackage
        }
        val resolved = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        synchronized(labels) {
            launcherPackage = resolved
            launcherPackageAt = System.currentTimeMillis()
        }
        return resolved
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
