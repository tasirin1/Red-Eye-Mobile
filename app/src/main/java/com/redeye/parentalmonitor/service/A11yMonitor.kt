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
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.TamperGuard
import com.redeye.parentalmonitor.utils.TimeFmt
import java.util.concurrent.Executors

object A11yMonitor {

    data class AppUsage(
        var packageName: String = "",
        var label: String = "",
        var millis: Long = 0L,
        var nightMillis: Long = 0L,
        var launches: Int = 0,
        var lastAt: Long = 0L
    )

    private const val MAX_SESSION_MS = 4 * 60 * 60_000L
    private const val PERSIST_INTERVAL_MS = 20_000L
    private const val NIGHT_ALERT_GAP_MS = 30 * 60_000L
    private const val TAMPER_ALERT_GAP_MS = 5 * 60_000L
    private const val CONTENT_SCAN_GAP_MS = 3_000L
    private const val KEYWORD_ALERT_GAP_MS = 15 * 60_000L
    private const val MAX_KEYWORD_ALERTS_PER_DAY = 30
    private const val MAX_LABEL_CACHE = 160

    private data class ScreenSnapshot(val texts: List<String>, val hasPassword: Boolean)

    private val gson = Gson()
    private val usageType = object : TypeToken<MutableMap<String, AppUsage>>() {}.type
    private val alertType = object : TypeToken<MutableMap<String, Long>>() {}.type
    private val gate = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastTamperAlert = HashMap<String, Long>()
    private val lastContentScan = HashMap<String, Long>()
    private val lastKeywordAlert = HashMap<String, Long>()
    private var keywordAlertDay = ""
    private var keywordAlertCount = 0
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "redeye-a11y").apply { isDaemon = true }
    }

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
    private var lastNightAlertElapsed = 0L
    private var suspendedPackage = ""
    private var suspendedLabel = ""
    private var launcherPackage = ""
    private var knownSeeded = false

    fun attach(context: Context) {
        val app = context.applicationContext
        prefs = try { PreferencesManager.getInstance(app) } catch (_: Exception) { null }
        if (context is AccessibilityService) a11yService = context
        executor.execute { ensureLoaded(app) }
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
                val texts = if (TamperGuard.mayBeSensitive(pkg, cls)) collectSnapshot(service).texts else emptyList()
                val app = service.applicationContext
                executor.execute { handleWindow(app, pkg, cls, texts) }
                scanWatchedContent(service, pkg)
                requestPeriodicPersist(app)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = try { event.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
                if (pkg.isNotEmpty()) {
                    scanWatchedContent(service, pkg)
                    requestPeriodicPersist(service.applicationContext)
                }
            }
        }
    }

    private fun scanWatchedContent(service: AccessibilityService, pkg: String) {
        if (pkg.isEmpty() || pkg == service.packageName || AppWatch.isSensitivePackage(pkg)) return
        val manager = prefs ?: return
        if (!manager.a11yAppWatchEnabled) return
        val now = SystemClock.elapsedRealtime()
        val last = lastContentScan[pkg] ?: 0L
        if (last > 0L && now - last < CONTENT_SCAN_GAP_MS) return
        if (lastContentScan.size > 120) lastContentScan.clear()
        lastContentScan[pkg] = now
        if (pkg !in manager.a11yAppWatchPackageSet()) return
        val keywords = AppWatch.parseKeywords(manager.watchKeywords)
        if (keywords.isEmpty()) return
        val snapshot = collectSnapshot(service)
        if (snapshot.hasPassword || snapshot.texts.isEmpty()) return
        val app = service.applicationContext
        executor.execute { handleContent(app, pkg, snapshot.texts, keywords) }
    }

    private fun handleContent(context: Context, pkg: String, texts: List<String>, keywords: List<String>) {
        val manager = prefs ?: return
        if (!manager.a11yAppWatchEnabled || !monitoringActive(manager)) return
        if (pkg !in manager.a11yAppWatchPackageSet() || AppWatch.isSensitivePackage(pkg)) return
        val hit = AppWatch.hit(keywords, texts.joinToString(" ")) ?: return
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
        sendAlert(
            context,
            "\uD83D\uDD0E <b>Kata pantau di aplikasi</b>\nApp: " + Html.escape(label) +
                "\nKata: " + Html.escape(hit) + "\nWaktu: " + TimeFmt.short(nowWall) +
                "\nMatikan: /pantauapp off"
        )
    }

    private fun ensureKeywordState(manager: PreferencesManager, day: String) {
        if (!keywordStateLoaded) {
            keywordStateLoaded = true
            keywordAlertDay = try { manager.a11yKeywordAlertDay } catch (_: Exception) { "" }
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
        if (day != keywordAlertDay) {
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
            manager.setA11yKeywordAlertState(
                keywordAlertDay,
                keywordAlertCount,
                try { gson.toJson(lastKeywordAlert) } catch (_: Exception) { "" }
            )
        } catch (_: Exception) {
        }
    }

    fun flush(context: Context) {
        val app = context.applicationContext
        executor.execute {
            synchronized(gate) {
                lastEventKey = ""
                suspendedPackage = ""
                ensureLoadedLocked(app)
                finishSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                persistLocked(app, true)
            }
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
                suspendedPackage = ""
                val manager = prefs
                if (pkg.isNotEmpty() && manager != null && monitoringActive(manager) && !isKeyguardLocked(app) && isLaunchable(app, pkg)) {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    currentPackage = pkg
                    currentLabel = suspendedLabel.ifEmpty { labelFor(app, pkg) }
                    suspendedLabel = ""
                    currentStartWall = nowWall
                    currentStartElapsed = nowElapsed
                    lastEventKey = ""
                    usage.getOrPut(pkg) { AppUsage(packageName = pkg, label = currentLabel) }.label = currentLabel
                }
            }
        }
    }

    fun currentAppLabel(): String {
        synchronized(gate) { return if (currentPackage.isEmpty()) "" else currentLabel }
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
        if (loaded && previousDay.isNotEmpty() && today != previousDay) {
            finishSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
            saveUsageFor(context, previousDay)
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
                currentStartWall = nowWall
                currentStartElapsed = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun loadUsage(context: Context, today: String): MutableMap<String, AppUsage> {
        val manager = prefs ?: return HashMap()
        return try {
            if (manager.a11yUsageDate != today) return HashMap()
            val raw = manager.a11yUsageJson
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
        synchronized(gate) {
            ensureLoadedLocked(context)
            if (manager.a11yTamperEnabled && protectionActive(manager)) {
                val reason = TamperGuard.reason(pkg, cls, texts, context.packageName, labelFor(context, context.packageName))
                if (reason != null) {
                    blockTamper(context, reason)
                    finishSessionLocked(nowWall, nowElapsed)
                    persistLocked(context, true)
                    return
                }
            }
            if (!monitoringActive(manager)) {
                finishSessionLocked(nowWall, nowElapsed)
                persistLocked(context, true)
                return
            }
            if (pkg == context.packageName || pkg == defaultLauncherPackage(context) || !isLaunchable(context, pkg)) {
                finishSessionLocked(nowWall, nowElapsed)
                persistLocked(context, true)
                return
            }
            if (pkg == currentPackage) return
            finishSessionLocked(nowWall, nowElapsed)
            currentPackage = pkg
            currentLabel = labelFor(context, pkg)
            currentStartWall = nowWall
            currentStartElapsed = nowElapsed
            val row = usage.getOrPut(pkg) { AppUsage(packageName = pkg, label = currentLabel) }
            row.label = currentLabel
            row.launches += 1
            row.lastAt = nowWall
            val seeded = manager.a11yKnownSeeded
            val firstOpen = manager.rememberA11yApp(pkg)
            if (seeded && firstOpen && manager.a11yAppAlert) {
                sendAlert(context, "\uD83D\uDCE6 <b>Aplikasi baru dibuka</b>\n" + Html.escape(currentLabel) + "\n<code>" + pkg + "</code>")
            } else if (manager.a11yAppAlert && isNightHour(nowWall) && nowElapsed - lastNightAlertElapsed >= NIGHT_ALERT_GAP_MS) {
                lastNightAlertElapsed = nowElapsed
                sendAlert(context, "\uD83C\uDF19 <b>Aplikasi dibuka malam</b>\n" + Html.escape(currentLabel) + "\n" + TimeFmt.short(nowWall))
            }
            persistLocked(context, false)
        }
    }

    private fun blockTamper(context: Context, reason: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastTamperAlert[reason] ?: 0L
        mainHandler.post {
            try { a11yService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } catch (_: Exception) { }
        }
        if (now - last < TAMPER_ALERT_GAP_MS) return
        lastTamperAlert[reason] = now
        sendAlert(context, "\uD83D\uDEE1\uFE0F <b>Proteksi tamper</b>\nPercobaan: " + Html.escape(reason) + "\nTindakan: layar ditutup")
    }

    private fun protectionActive(manager: PreferencesManager): Boolean {
        return try {
            manager.isMonitoringEnabled && !manager.userDisabledMonitoring && manager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    private fun collectSnapshot(service: AccessibilityService): ScreenSnapshot {
        val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return ScreenSnapshot(emptyList(), false)
        val out = ArrayList<String>()
        var hasPassword = false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nodes = 0
        while (queue.isNotEmpty() && nodes < 160 && out.size < 80) {
            val node = queue.removeFirst()
            nodes++
            if (try { node.isPassword } catch (_: Exception) { false }) hasPassword = true
            val text = try { node.text?.toString()?.trim() } catch (_: Exception) { null }
            if (!text.isNullOrEmpty() && text.length <= 160) out.add(text)
            val desc = try { node.contentDescription?.toString()?.trim() } catch (_: Exception) { null }
            if (!desc.isNullOrEmpty() && desc != text && desc.length <= 160) out.add(desc)
            val count = try { minOf(node.childCount, 24) } catch (_: Exception) { 0 }
            for (i in 0 until count) {
                try {
                    val child = node.getChild(i)
                    if (child != null) queue.add(child)
                } catch (_: Exception) {
                }
            }
        }
        return ScreenSnapshot(out, hasPassword)
    }

    private fun accrueSessionLocked(nowWall: Long, nowElapsed: Long) {
        if (currentPackage.isEmpty() || currentStartElapsed <= 0L) return
        var remaining = nowElapsed - currentStartElapsed
        if (remaining <= 0L) return
        var wallStart = currentStartWall
        while (remaining > 0L) {
            val chunk = minOf(remaining, MAX_SESSION_MS)
            val row = usage.getOrPut(currentPackage) { AppUsage(packageName = currentPackage, label = currentLabel) }
            row.millis += chunk
            row.nightMillis += minOf(chunk, A11yUsageMath.nightOverlapMs(wallStart, wallStart + chunk, offsetAt(wallStart)))
            row.lastAt = wallStart + chunk
            wallStart += chunk
            remaining -= chunk
        }
        currentStartWall = wallStart
        currentStartElapsed = nowElapsed
    }

    private fun finishSessionLocked(nowWall: Long, nowElapsed: Long) {
        if (currentPackage.isEmpty() || currentStartElapsed <= 0L) {
            currentPackage = ""
            currentStartElapsed = 0L
            return
        }
        accrueSessionLocked(nowWall, nowElapsed)
        currentPackage = ""
        currentStartElapsed = 0L
    }

    private fun requestPeriodicPersist(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveRequestElapsed < PERSIST_INTERVAL_MS) return
        lastSaveRequestElapsed = now
        val app = context.applicationContext
        executor.execute {
            synchronized(gate) {
                ensureLoadedLocked(app)
                accrueSessionLocked(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                persistLocked(app, false)
            }
        }
    }

    private fun saveUsageFor(context: Context, day: String) {
        val manager = prefs ?: return
        try {
            manager.setA11yUsage(day, try { gson.toJson(usage) } catch (_: Exception) { "" })
            lastPersistElapsed = SystemClock.elapsedRealtime()
        } catch (_: Exception) {
        }
    }

    private fun persistLocked(context: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersistElapsed < PERSIST_INTERVAL_MS) return
        val manager = prefs ?: return
        val day = TimeFmt.dayKey(System.currentTimeMillis())
        if (day != loadedDay) return
        try {
            manager.setA11yUsage(day, try { gson.toJson(usage) } catch (_: Exception) { "" })
            lastPersistElapsed = now
        } catch (_: Exception) {
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
        if (launcherPackage.isNotEmpty()) return launcherPackage
        launcherPackage = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return launcherPackage
    }

    private fun isLaunchable(context: Context, pkg: String): Boolean {
        synchronized(labels) {
            launchable[pkg]?.let { return it }
        }
        val ok = try {
            pkg != "com.android.systemui" && context.packageManager.getLaunchIntentForPackage(pkg) != null
        } catch (_: Exception) {
            false
        }
        synchronized(labels) { launchable[pkg] = ok }
        return ok
    }

    private fun labelFor(context: Context, pkg: String): String {
        synchronized(labels) {
            labels[pkg]?.let { return it }
        }
        val label = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString().ifEmpty { pkg }
        } catch (_: Exception) {
            pkg
        }
        synchronized(labels) { labels[pkg] = label }
        return label
    }

    private fun sendAlert(context: Context, text: String) {
        try {
            MessageQueue.getInstance(context).addMessage(text, true)
            MessageScheduler.scheduleMessageSend(context)
        } catch (_: Exception) {
        }
    }
}
