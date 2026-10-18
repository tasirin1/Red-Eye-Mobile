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
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NotificationForwarderService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val fwdSerial = Dispatchers.IO.limitedParallelism(1)
    private val appLabelCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 100
        }
    }
    private val lastSent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 100
        }
    }
    @Volatile
    private var prefsRef: PreferencesManager? = null
    @Volatile
    private var queueRef: MessageQueue? = null
    private val pendingPosts = java.util.concurrent.atomic.AtomicInteger(0)
    private val pkgHits = object : LinkedHashMap<String, ArrayDeque<Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean {
            return size > 100
        }
    }
    private val pkgHitsLock = Any()
    private val dropNoticeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val dropNoticeCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var dropNoticeJob: Job? = null
    private val dropNoticeLock = Any()
    @Volatile
    private var watchKeywordsRaw = ""
    @Volatile
    private var watchKeywordsList: List<String> = emptyList()
    private var rebindAttempt = 0
    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rebindRunnable = Runnable { runRebind() }
    @Volatile
    private var lastReviveAt = 0L
    @Volatile
    private var lastWakeStartAt = 0L
    private var wakeJob: Job? = null
    @Volatile
    private var netCheckAt = 0L
    @Volatile
    private var netCached = false
    @Volatile
    private var cachedFwdToken = ""
    @Volatile
    private var cachedFwdChat = ""
    private var fwdCredsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var cfgCacheAt = 0L
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
        private const val MAX_HISTORY = 20
        private const val MAX_QUEUED = 64
        private const val MAX_BATCH = 32
        private val REBIND_DELAYS_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L, 1_800_000L)
        private const val WAKE_PING_COOLDOWN_MS = 120_000L
        private val PRIORITY_CODE_REGEX = Regex("\\b\\d{4,8}\\b")
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

        fun history(): List<NotifRecord> {
            synchronized(historyLock) {
                return history.toList()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize synchronously to avoid race with onNotificationPosted
        prefsRef = PreferencesManager.getInstance(this@NotificationForwarderService)
        queueRef = MessageQueue.getInstance(this@NotificationForwarderService)
        scope.launch {
            try {
                refreshFwdCreds()
                fwdCredsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PreferencesManager.KEY_BOT_TOKEN || key == PreferencesManager.KEY_CHAT_ID) { refreshFwdCreds(); cfgCacheAt = 0L }
                    if (key == PreferencesManager.KEY_NOTIF_FORWARD || key == PreferencesManager.KEY_MONITORING_ENABLED || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_USER_DISABLED || key == PreferencesManager.KEY_USER_CONSENTED) cfgCacheAt = 0L
                }
                try { fwdCredsListener?.let { prefsRef?.registerChangeListener(it) } } catch (_: Exception) { }
                try { queueRef?.tryRestorePersistent() } catch (_: Exception) { }
                try {
                    val pending = prefsRef?.pendingNotifDrops ?: 0
                    if (pending > 0) {
                        try {
                            prefsRef?.setPendingNotifDropsSync(0)
                        } catch (_: Exception) {
                        }
                        dropNoticeCount.addAndGet(pending)
                        synchronized(dropNoticeLock) {
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
                        }
                    }
                } catch (_: Exception) {
                }
                prefsRef?.isConfigured()
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
            cachedFwdToken = prefsRef?.botToken.orEmpty()
            cachedFwdChat = prefsRef?.chatId.orEmpty()
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
    }

    private fun ensureCfgCache(prefs: PreferencesManager) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cfgCacheAt >= 10_000L) refreshCfgCache(prefs, now)
    }

    private fun forwardingAllowed(): Boolean {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { prefsRef = it }
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
        if (pendingPosts.get() > MAX_QUEUED) {
            val (fbTitle, fbText) = notifTitleText(notification)
            if (fbTitle.isEmpty() && fbText.isEmpty()) return
            recordOverflow(pkg, fbTitle, fbText)
            return
        }

        if (pendingPosts.incrementAndGet() > MAX_QUEUED) {
            pendingPosts.decrementAndGet()
            try {
                val (t, x) = notifTitleText(notification)
                if (t.isNotEmpty() || x.isNotEmpty()) {
                    val squeezedPriority = try { isPriorityNotif(pkg, t, x) } catch (_: Exception) { false }
                    if (squeezedPriority && forwardingAllowed()) {
                        val squeezedLabel = try { synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg } catch (_: Exception) { pkg }
                        try { record(squeezedLabel, t, x) } catch (_: Exception) { }
                        val squeezedMsg = try { buildNotifMessage(squeezedLabel, t, x) } catch (_: Exception) { "" }
                        scope.launch {
                            try {
                                if (squeezedMsg.isNotEmpty()) queue().addMessage(squeezedMsg)
                                queue().flushSync()
                                MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
                            } catch (_: Exception) {
                            }
                        }
                    } else recordOverflow(pkg, t, x)
                }
            } catch (_: Exception) { }
            return
        }
        scope.launch(fwdSerial) {
            try {
                handlePosted(pkg, notification, groupKey, isSummary)
            } finally {
                pendingPosts.decrementAndGet()
            }
        }
    }

    private fun notifTitleText(notification: Notification): Pair<String, String> {
        return try {
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            var body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (body.isEmpty()) {
                body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            }
            if (body.isEmpty()) {
                try {
                    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.asSequence()?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }?.toList()
                    if (!lines.isNullOrEmpty()) body = lines.joinToString("\n").trim()
                } catch (_: Exception) {
                }
            }
            if (body.isEmpty()) {
                body = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            }
            Pair(title, body)
        } catch (_: Exception) {
            Pair("", "")
        }
    }

    private suspend fun handlePosted(pkg: String, notification: Notification, groupKey: String = "", isSummary: Boolean = false) {
        var prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { prefsRef = it }
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
        val (title, text) = notifTitleText(notification)
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
        if (isSummary && groupKey.isNotEmpty()) {
            val seenAt = synchronized(groupSeen) { groupSeen[groupKey] } ?: 0L
            if (now - seenAt < 120_000L) {
                record(appLabel, title, text)
                return
            }
        }
        emitPost(pkg, groupKey, appLabel, title, text, now, trackGroup = !isSummary && groupKey.isNotEmpty())
    }

    private suspend fun emitPost(pkg: String, groupKey: String, label: String, title: String, text: String, now: Long, trackGroup: Boolean) {
        if (trackGroup && groupKey.isNotEmpty()) {
            try {
                synchronized(groupSeen) { groupSeen[groupKey] = now }
            } catch (_: Exception) {
            }
        }
        val key = pkg + "\n" + title + "\n" + text
        val priority = try { isPriorityNotif(pkg, title, text) } catch (_: Exception) { false }
        val dup = synchronized(lastSent) {
            val prev = lastSent[key] ?: 0L
            if (!priority && now - prev < 10_000L) true else {
                lastSent[key] = now
                false
            }
        }
        if (dup) {
            record(label, title, text)
            return
        }
        if (pkgTryAcquire(pkg, now)) {
            if (priority) {
                record(label, title, text)
                try {
                    queue().addMessage(buildNotifMessage(label, title, text))
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
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
                forwardToTelegram("Penyaring spam: 10+ pembaruan dari " + Html.escape(spamLabel) + " dalam 2 menit, sisanya disimpan di riwayat /lastnotif.")
            }
            record(label, title, text)
            return
        }
        val message = buildNotifMessage(label, title, text)
        record(label, title, text)
        if (priority) {
            try {
                forwardLocked("❗ " + message, 1)
            } catch (_: Exception) {
            }
            return
        }
        try {
            forwardToTelegram(message)
        } catch (_: Exception) {
        }
    }

    private fun watchKeywordList(raw: String): List<String> {
        if (raw == watchKeywordsRaw) return watchKeywordsList
        val parsed = ArrayList<String>()
        val token = StringBuilder()
        for (ch in raw) {
            if (ch == ',' || ch == ' ' || ch == ';') {
                if (token.isNotEmpty()) {
                    val t = token.toString().trim().lowercase(java.util.Locale.ROOT)
                    if (t.length >= 3) parsed.add(t)
                    token.setLength(0)
                }
            } else {
                token.append(ch)
            }
        }
        if (token.isNotEmpty()) {
            val t = token.toString().trim().lowercase(java.util.Locale.ROOT)
            if (t.length >= 3) parsed.add(t)
        }
        watchKeywordsRaw = raw
        watchKeywordsList = parsed
        return parsed
    }

    private fun buildNotifMessage(label: String, title: String, text: String): String {
        return buildString {
            appendLine("🔔 <b>Notifikasi</b>")
            try {
                val raw = (prefsRef ?: PreferencesManager.getInstance(this@NotificationForwarderService)).watchKeywords
                if (raw.isNotEmpty()) {
                    val low = (title + "\n" + text).lowercase(java.util.Locale.ROOT)
                    val hit = watchKeywordList(raw).firstOrNull { low.contains(it) }
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
            rebindHandler.removeCallbacks(rebindRunnable)
        } catch (_: Exception) {
        }
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("NotifForwarder", "Notification listener connected")
        scope.launch {
            try {
                val prefs = prefsRef ?: PreferencesManager.getInstance(this@NotificationForwarderService).also { prefsRef = it }
                val queue = queueRef ?: MessageQueue.getInstance(this@NotificationForwarderService).also { queueRef = it }
                prefs.isConfigured()
                queue.hasMessages()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            if (rebindAttempt >= REBIND_DELAYS_MS.size) return
            val delayMs = REBIND_DELAYS_MS[rebindAttempt.coerceIn(0, REBIND_DELAYS_MS.size - 1)]
            rebindAttempt++
            rebindHandler.removeCallbacks(rebindRunnable)
            rebindHandler.postDelayed(rebindRunnable, delayMs)
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
                    delay(if (pausedLoop) 300_000L else 25_000L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun pollWakeOnce() {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { prefsRef = it }
        } catch (_: Exception) {
            return
        }
        val resumeOk = try {
            prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
        if (!resumeOk) return
        if (MonitoringService.isRunning) return
        val nowWake = android.os.SystemClock.elapsedRealtime()
        if (nowWake - lastWakeStartAt < WAKE_PING_COOLDOWN_MS) return
        lastWakeStartAt = nowWake
        try {
            val restart = android.content.Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(restart)
            } else {
                startService(restart)
            }
        } catch (_: Exception) {
            android.util.Log.w("NotifForwarder", "Wake restart failed, retry via worker")
        }
        try {
            MessageScheduler.scheduleBootRestart(this)
        } catch (_: Exception) {
        }
        try {
            MessageScheduler.scheduleMessageSend(this)
        } catch (_: Exception) {
        }
    }

    private fun reviveMonitoringIfNeeded() {
        try {
            if (MonitoringService.isRunning) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastReviveAt < 60_000L) return
            lastReviveAt = now
            scope.launch {
                try {
                    val prefs = prefsRef ?: try {
                        PreferencesManager.getInstance(this@NotificationForwarderService).also { prefsRef = it }
                    } catch (_: Exception) {
                        return@launch
                    }
                    val resume = try {
                        prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
                    } catch (_: Exception) {
                        false
                    }
                    if (!resume) return@launch
                    try {
                        val restart = android.content.Intent(this@NotificationForwarderService, MonitoringService::class.java).apply {
                            action = MonitoringService.ACTION_START_MONITORING
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            startForegroundService(restart)
                        } else {
                            startService(restart)
                        }
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
                try {
                    queue().addMessages(leftover)
                    queue().flushSync()
                } catch (_: Exception) {
                }
                try {
                    MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        try { salvageBatch() } catch (_: Exception) { }
        try { super.onTaskRemoved(rootIntent) } catch (_: Exception) { }
    }

    override fun onDestroy() {
        try {
            val drops = dropNoticeCount.getAndSet(0)
            if (drops > 0) {
                try {
                    val text = if (drops == 1) "1 notifikasi dibuang (ditolak/antrean penuh)."
                        else "$drops notifikasi dibuang (ditolak/antrean penuh)."
                    queue().addMessage(text, true)
                    queue().flushSync()
                    try {
                        prefsRef?.setPendingNotifDropsSync(0)
                    } catch (_: Exception) {
                    }
                    try {
                        MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                    dropNoticeCount.addAndGet(drops)
                    try {
                        prefsRef?.setPendingNotifDropsSync(dropNoticeCount.get())
                    } catch (_: Exception) {
                    }
                }
            } else {
                try {
                    prefsRef?.setPendingNotifDropsSync(0)
                } catch (_: Exception) {
                }
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


    private fun pkgTryAcquire(pkg: String, now: Long): Boolean {
        if (pkg.isEmpty()) return false
        synchronized(pkgHitsLock) {
            val q = pkgHits.getOrPut(pkg) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() > 120_000L) q.removeFirst()
            if (q.size >= 10) {
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
        return com.redeye.parentalmonitor.utils.Redact.token(value, token, chat)
    }

    private fun queue(): MessageQueue {
        queueRef?.let { return it }
        return MessageQueue.getInstance(this).also { queueRef = it }
    }

    private fun isPriorityNotif(pkg: String, title: String, text: String): Boolean {
        return try {
            val lowPkg = pkg.lowercase(java.util.Locale.ROOT)
            val lowText = (title + "\n" + text).lowercase(java.util.Locale.ROOT)
            try {
                val raw = (prefsRef ?: PreferencesManager.getInstance(this)).watchKeywords
                if (raw.isNotEmpty()) {
                    val hit = watchKeywordList(raw).firstOrNull { lowText.contains(it) }
                    if (hit != null) return true
                }
            } catch (_: Exception) {
            }
            if (lowText.contains("missed call") || lowText.contains("panggilan tak terjawab") || lowText.contains("tak terjawab") || lowText.contains("missed video call")) return true
            if (lowPkg.contains("bank") || lowPkg.contains("brimo") || lowPkg.contains("livin") || lowPkg.contains("bca") || lowPkg.contains("mandiri") || lowPkg.contains("bni") || lowPkg.contains("bri") || lowPkg.contains("btn") || lowPkg.contains("ovo") || lowPkg.contains("dana") || lowPkg.contains("gopay") || lowPkg.contains("shopeepay") || lowPkg.contains("mbanking") || lowPkg.contains("m-banking") || lowPkg.contains("wallet") || lowPkg.contains("pay")) return true
            if (PRIORITY_CODE_REGEX.containsMatchIn(title + " " + text) && (lowText.contains("otp") || lowText.contains("kode") || lowText.contains("verif") || lowText.contains("pin") || lowText.contains("token") || lowText.contains("auth"))) return true
            false
        } catch (_: Exception) {
            false
        }
    }


    private val batchLock = Any()
    private val batchBuf = ArrayDeque<String>()
    private var batchJob: Job? = null

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

    private suspend fun forwardToTelegram(message: String) {
        var overflowed = false
        synchronized(batchLock) {
            if (batchBuf.size >= MAX_BATCH) {
                overflowed = true
            } else {
                batchBuf.addLast(message)
                scheduleBatchLocked()
            }
        }
        if (overflowed) {
            try {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun flushBatch() {
        val items: List<String>
        synchronized(batchLock) {
            if (batchBuf.isEmpty()) return
            items = batchBuf.toList()
            batchBuf.clear()
        }
        var cur = StringBuilder()
        var curCount = 0
        var index = 0
        suspend fun flushCur() {
            if (curCount <= 0) return
            forwardLocked(cur.toString(), curCount)
            cur = StringBuilder()
            curCount = 0
        }
        try {
            while (index < items.size) {
                val msg = items[index]
                index++
                if (msg.length > 4000) {
                    flushCur()
                    var rest = msg
                    while (rest.length > 4000) {
                        var cut = rest.lastIndexOf("\n\n", 4000)
                        if (cut <= 0) cut = 4000
                        cut = com.redeye.parentalmonitor.utils.TextChunk.safeCut(rest, cut)
                        forwardLocked(rest.substring(0, cut), 1)
                        rest = rest.substring(cut).trimStart('\n')
                        if (rest.isEmpty()) break
                    }
                    if (rest.isNotEmpty()) forwardLocked(rest, 1)
                    continue
                }
                if (curCount > 0 && cur.length + 2 + msg.length > 4000) flushCur()
                if (curCount > 0) cur.append("\n\n")
                cur.append(msg)
                curCount++
            }
            flushCur()
        } catch (t: Throwable) {
            val leftover = ArrayList<String>()
            if (curCount > 0) leftover.add(cur.toString())
            while (index < items.size) leftover.add(items[index++])
            if (leftover.isNotEmpty()) {
                if (t is kotlinx.coroutines.CancellationException) {
                    try {
                        queue().addMessages(leftover)
                        queue().flushSync()
                    } catch (_: Exception) {
                    }
                    try {
                        MessageScheduler.scheduleMessageSend(this)
                    } catch (_: Exception) {
                    }
                } else {
                    synchronized(batchLock) {
                        for (i in leftover.indices.reversed()) batchBuf.addFirst(leftover[i])
                    }
                }
            }
            throw t
        }
    }

    private fun noteDroppedNotification(count: Int = 1) {
        dropNoticeCount.addAndGet(count.coerceAtLeast(0))
        try {
            prefsRef?.setPendingNotifDropsSync(dropNoticeCount.get())
        } catch (_: Exception) {
        }
        synchronized(dropNoticeLock) {
            if (dropNoticeJob?.isActive == true) return
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
    }

    private fun flushDropNotice() {
        val count = dropNoticeCount.getAndSet(0)
        try {
            prefsRef?.setPendingNotifDropsSync(0)
        } catch (_: Exception) {
        }
        if (count <= 0) return
        try {
            val text = if (count == 1) "1 notifikasi dibuang (ditolak/antrean penuh)."
                else "$count notifikasi dibuang (ditolak/antrean penuh)."
            queue().addMessage(text, true)
            MessageScheduler.scheduleMessageSend(this@NotificationForwarderService)
        } catch (_: Exception) {
            dropNoticeCount.addAndGet(count)
            try {
                prefsRef?.setPendingNotifDropsSync(dropNoticeCount.get())
            } catch (_: Exception) {
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

    private suspend fun forwardLocked(message: String, msgCount: Int = 1) {
        try {
            val prefs = prefsRef ?: try {
                PreferencesManager.getInstance(this).also { prefsRef = it }
            } catch (_: Exception) {
                try {
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return
            }
            if (cachedFwdToken.isEmpty() || cachedFwdChat.isEmpty()) refreshFwdCreds()
            val botToken = cachedFwdToken
            val chatId = cachedFwdChat
            if (botToken.isEmpty() || chatId.isEmpty()) {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }
            try {
                NetworkUtils.sweepAuthBlock(prefs)
                if (prefs.credentialError.isNotEmpty()) {
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return
                }
            } catch (_: Exception) {
            }
            val rateRemain = NetworkUtils.rateLimitedRemainMs()
            if (rateRemain > 0L) {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSendNext(this, rateRemain)
                return
            }
            val nowNet = android.os.SystemClock.elapsedRealtime()
            if (nowNet - netCheckAt > 20_000L) {
                netCheckAt = nowNet
                netCached = NetworkUtils.isNetworkAvailable(this)
            }
            if (!netCached) {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            if (response.isSuccessful && response.body()?.ok == true) {
                return
            }
            if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("NotifForwarder", "Send rejected (${response.code()}), queuing notification")
                try {
                    prefs.credentialError = response.code().toString()
                    prefs.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }
            if (response.code() == 429) {
                val retryAfter = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
                NetworkUtils.noteRateLimited(retryAfter)
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                return
            }
            if (response.code() == 400) {
                val body = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                if (adoptMigratedChat(prefs, body, chatId)) {
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return
                }
                if (NetworkUtils.isChatMissing(body)) {
                    try {
                        prefs.credentialError = response.code().toString()
                        prefs.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return
                }
                if (NetworkUtils.isRightsLimited(body)) {
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return
                }
                val plain = Html.unescape(message)
                if (plain != message) {
                    try {
                        val fallbackResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                        if (fallbackResp.isSuccessful && fallbackResp.body()?.ok == true) {
                            return
                        }
                        if (fallbackResp.code() == 401 || fallbackResp.code() == 403) {
                            try {
                                prefs.credentialError = fallbackResp.code().toString()
                                prefs.credentialErrorAt = System.currentTimeMillis()
                            } catch (_: Exception) {
                            }
                            queue().addMessage(message)
                            MessageScheduler.scheduleMessageSend(this)
                            return
                        }
                        if (fallbackResp.code() == 429) {
                            val retryAfter = try {
                                NetworkUtils.parseRetryAfter(fallbackResp.errorBody()?.string())
                            } catch (_: Exception) {
                                5L
                            }
                            NetworkUtils.noteRateLimited(retryAfter)
                            queue().addMessage(message)
                            MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                            return
                        }
                        if (fallbackResp.code() == 408 || fallbackResp.code() >= 500) {
                            queue().addMessage(message)
                            MessageScheduler.scheduleMessageSend(this)
                            return
                        }
                        if (fallbackResp.code() == 400) {
                            val fallbackBody = try {
                                fallbackResp.errorBody()?.string()
                            } catch (_: Exception) {
                                null
                            }
                            if (adoptMigratedChat(prefs, fallbackBody, chatId)) {
                                queue().addMessage(message)
                                MessageScheduler.scheduleMessageSend(this)
                                return
                            }
                            if (NetworkUtils.isChatMissing(fallbackBody)) {
                                try {
                                    prefs.credentialError = fallbackResp.code().toString()
                                    prefs.credentialErrorAt = System.currentTimeMillis()
                                } catch (_: Exception) {
                                }
                                queue().addMessage(message)
                                MessageScheduler.scheduleMessageSend(this)
                                return
                            }
                            if (NetworkUtils.isRightsLimited(fallbackBody)) {
                                queue().addMessage(message)
                                MessageScheduler.scheduleMessageSend(this)
                                return
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                android.util.Log.w("NotifForwarder", "Notification permanently rejected (400), dropping")
                try {
                    noteDroppedNotification(msgCount)
                } catch (_: Exception) {
                }
                return
            }
            queue().addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Forward failed: ${redactToken(e.message)}")
            try {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
            return
        }
    }
}
