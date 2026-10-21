package com.redeye.parentalmonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.utils.A11yUsageMath
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.MessageScheduler
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
    private const val MAX_LABEL_CACHE = 160

    private val gson = Gson()
    private val usageType = object : TypeToken<MutableMap<String, AppUsage>>() {}.type
    private val gate = Any()
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
    private var appContext: Context? = null

    @Volatile
    private var prefs: PreferencesManager? = null

    @Volatile
    private var lastEventPackage = ""

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
        appContext = app
        prefs = try { PreferencesManager.getInstance(app) } catch (_: Exception) { null }
        executor.execute { ensureLoaded(app) }
    }

    fun noteEnabled(context: Context) {
        try {
            val manager = prefs ?: PreferencesManager.getInstance(context.applicationContext).also { prefs = it }
            manager.accessibilityEverEnabled = true
        } catch (_: Exception) {
        }
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = try { event.packageName?.toString().orEmpty() } catch (_: Exception) { "" }
        if (pkg.isEmpty() || pkg == lastEventPackage) return
        lastEventPackage = pkg
        val app = service.applicationContext
        executor.execute { handleWindow(app, pkg) }
    }

    fun flush(context: Context) {
        val app = context.applicationContext
        executor.execute {
            synchronized(gate) {
                lastEventPackage = ""
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
                lastEventPackage = ""
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
                if (pkg.isNotEmpty() && manager != null && monitoringActive(manager) && isLaunchable(app, pkg)) {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = SystemClock.elapsedRealtime()
                    currentPackage = pkg
                    currentLabel = suspendedLabel.ifEmpty { labelFor(app, pkg) }
                    suspendedLabel = ""
                    currentStartWall = nowWall
                    currentStartElapsed = nowElapsed
                    lastEventPackage = pkg
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
            val nowWall = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val rows = usage.values.map { it.copy() }.toMutableList()
            if (currentPackage.isNotEmpty() && currentStartElapsed > 0L) {
                val extra = (nowElapsed - currentStartElapsed).coerceIn(0L, MAX_SESSION_MS)
                val row = rows.firstOrNull { it.packageName == currentPackage }
                    ?: AppUsage(packageName = currentPackage, label = currentLabel).also { rows.add(it) }
                row.millis += extra
                row.nightMillis += minOf(extra, A11yUsageMath.nightOverlapMs(currentStartWall, nowWall, offsetAt(currentStartWall)))
                row.lastAt = nowWall
            }
            return rows.filter { it.millis > 0L || it.launches > 0 }.sortedByDescending { it.millis }
        }
    }

    private fun ensurePrefs(context: Context) {
        if (prefs == null) {
            try { prefs = PreferencesManager.getInstance(context) } catch (_: Exception) { }
        }
        if (appContext == null) appContext = context
    }

    private fun ensureLoaded(context: Context) {
        ensurePrefs(context)
        synchronized(gate) { ensureLoadedLocked(context) }
    }

    private fun ensureLoadedLocked(context: Context) {
        val today = TimeFmt.dayKey(System.currentTimeMillis())
        if (loaded && today == loadedDay) return
        loadedDay = today
        loaded = true
        usage = loadUsage(context, today)
        seedKnownAppsLocked(context)
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

    private fun handleWindow(context: Context, pkg: String) {
        ensurePrefs(context)
        val manager = prefs ?: return
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(gate) {
            ensureLoadedLocked(context)
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

    private fun finishSessionLocked(nowWall: Long, nowElapsed: Long) {
        if (currentPackage.isEmpty() || currentStartElapsed <= 0L) {
            currentPackage = ""
            currentStartElapsed = 0L
            return
        }
        val elapsed = (nowElapsed - currentStartElapsed).coerceIn(0L, MAX_SESSION_MS)
        if (elapsed > 0L) {
            val row = usage.getOrPut(currentPackage) { AppUsage(packageName = currentPackage, label = currentLabel) }
            row.millis += elapsed
            row.nightMillis += minOf(elapsed, A11yUsageMath.nightOverlapMs(currentStartWall, nowWall, offsetAt(currentStartWall)))
            row.lastAt = nowWall
        }
        currentPackage = ""
        currentStartElapsed = 0L
    }

    private fun persistLocked(context: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPersistElapsed < PERSIST_INTERVAL_MS) return
        val manager = prefs ?: return
        val day = TimeFmt.dayKey(System.currentTimeMillis())
        if (day != loadedDay) return
        manager.a11yUsageDate = day
        manager.a11yUsageJson = try { gson.toJson(usage) } catch (_: Exception) { "" }
        lastPersistElapsed = now
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
