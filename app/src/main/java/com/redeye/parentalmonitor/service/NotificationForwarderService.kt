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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NotificationForwarderService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appLabelCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > 100
        }
    }
    private val lastSent = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 200
        }
    }
    @Volatile
    private var prefsRef: PreferencesManager? = null
    @Volatile
    private var queueRef: MessageQueue? = null
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    private val pendingPosts = java.util.concurrent.atomic.AtomicInteger(0)
    private val fwdMutex = Mutex()
    private val pkgHits = object : LinkedHashMap<String, ArrayDeque<Long>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean {
            return size > 200
        }
    }
    private val pkgHitsLock = Any()
    private val dropNoticeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var lastRebindAt = 0L
    private var netCheckAt = 0L
    private var netCached = false
    private var cachedFwdToken = ""
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
    private val groupSeen = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 200
        }
    }

    data class NotifRecord(val app: String, val title: String, val text: String, val at: Long)

    companion object {
        private const val MAX_HISTORY = 20
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
                prefsRef?.isConfigured()
                queueRef?.hasMessages()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshFwdCreds() {
        try {
            cachedFwdToken = prefsRef?.botToken.orEmpty()
            cachedFwdChat = prefsRef?.chatId.orEmpty()
        } catch (_: Exception) {
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
            if (!forwardingAllowed()) return
            try {
                val extras = notification.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
                if (title.isNotEmpty() || text.isNotEmpty()) {
                    val nowFb = android.os.SystemClock.elapsedRealtime()
                    val keyFb = pkg + "\n" + title + "\n" + text
                    val dupFb = synchronized(lastSent) {
                        val prev = lastSent[keyFb] ?: 0L
                        if (nowFb - prev < 30_000L) true else {
                            lastSent[keyFb] = nowFb
                            false
                        }
                    }
                    if (dupFb || pkgFull(pkg, nowFb)) return
                    val label = try {
                        val info = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(info).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    record(label, title, text)
                    val message = buildString {
                        appendLine("\uD83D\uDD14 <b>Notification</b>")
                        appendLine("App: ${Html.escape(label)}")
                        if (title.isNotEmpty()) appendLine("Title: ${Html.escape(title.take(200))}")
                        if (text.isNotEmpty()) appendLine("Text: ${Html.escape(text.take(300))}")
                    }
                    val fallbackPkg = pkg
                    val fallbackMsg = message
                    scope.launch {
                        try {
                            forwardToTelegram(fallbackMsg, fallbackPkg)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return
        }
        pendingPosts.incrementAndGet()
        scope.launch {
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
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        } catch (_: Exception) {
            return
        }
        if (title.isEmpty() && text.isEmpty()) return
        val key = pkg + "\n" + title + "\n" + text
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0L) < 30_000L) return
            lastSent[key] = now
        }
        if (pkgFull(pkg, now)) {
            val cachedLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: pkg
            record(cachedLabel, title, text)
            val lastNotice = dropNoticeAt[pkg] ?: 0L
            if (now - lastNotice > 120_000L) {
                dropNoticeAt[pkg] = now
                forwardToTelegram("Spam filter: 10+ updates from " + Html.escape(cachedLabel) + " in 2 min, extras kept in /lastnotif history.", "")
            }
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

    override fun onDestroy() {
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

    private fun isChatMissing(errorBody: String?): Boolean {
        if (errorBody.isNullOrEmpty()) return false
        val lower = errorBody.lowercase(java.util.Locale.ROOT)
        return lower.contains("chat not found") || lower.contains("bot was blocked") || lower.contains("user not found") || lower.contains("group chat was deleted") || lower.contains("group chat was upgraded") || lower.contains("chat_id is empty")
    }

    private suspend fun forwardToTelegram(message: String, pkg: String = "") {
        if (inFlight.incrementAndGet() > 4) {
            inFlight.decrementAndGet()
            try {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return
        }
        try {
            fwdMutex.withLock { forwardLocked(message, pkg) }
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun forwardLocked(message: String, pkg: String) {
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
                if (prefs.credentialError.isNotEmpty()) {
                    val nowAuth = System.currentTimeMillis()
                    if (nowAuth < prefs.credentialErrorAt) {
                        prefs.credentialError = ""
                        prefs.credentialErrorAt = 0L
                    } else if (nowAuth - prefs.credentialErrorAt < 30 * 60_000L) {
                        queue().addMessage(message)
                        MessageScheduler.scheduleMessageSend(this)
                        return
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
                return
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            if (response.isSuccessful && response.body()?.ok == true) {
                if (pkg.isNotEmpty()) pkgRecord(pkg, android.os.SystemClock.elapsedRealtime())
                try {
                    prefs.lastSyncTime = System.currentTimeMillis()
                } catch (_: Exception) {
                }
                return
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
                return
            }
            if (response.code() == 429) {
                val retryAfter = try {
                    NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                } catch (_: Exception) {
                    5L
                }
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
                if (isChatMissing(body)) {
                    try {
                        prefs.credentialError = response.code().toString()
                        prefs.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    queue().addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                    return
                }
                android.util.Log.w("NotifForwarder", "Notification permanently rejected (400), dropping")
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
        }
    }
}
