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
    private val fwdMutex = Mutex()
    private val pkgHits = object : LinkedHashMap<String, ArrayDeque<Long>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean {
            return size > 200
        }
    }
    private val pkgHitsLock = Any()
    private var lastRebindAt = 0L
    private var netCheckAt = 0L
    private var netCached = false
    private var cachedFwdToken = ""
    private var cachedFwdChat = ""
    private var fwdCredsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var cfgCheckAt = 0L
    private var cfgEnabled = false
    private var cfgForward = true
    private var cfgConfigured = false

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
                queueRef = MessageQueue(this@NotificationForwarderService)
                refreshFwdCreds()
                fwdCredsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "bot_token" || key == "chat_id") refreshFwdCreds()
                }
                try { prefsRef?.registerChangeListener(fwdCredsListener!!) } catch (_: Exception) { }
                prefsRef?.isConfigured()
                queueRef?.hasMessages()
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val notifId = sbn.id
        scope.launch {
            handlePosted(pkg, notifId, notification)
        }
    }

    private suspend fun handlePosted(pkg: String, notifId: Int, notification: Notification) {
        val prefs = prefsRef ?: try {
            PreferencesManager.getInstance(this).also { prefsRef = it }
        } catch (_: Exception) {
            return
        }
        val nowCfg = android.os.SystemClock.elapsedRealtime()
        if (nowCfg - cfgCheckAt > 30_000L) {
            cfgCheckAt = nowCfg
            cfgEnabled = try { prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring } catch (_: Exception) { false }
            cfgForward = try { prefs.notifForwardEnabled } catch (_: Exception) { true }
            cfgConfigured = try { prefs.isConfigured() } catch (_: Exception) { false }
        }
        if (!cfgEnabled) return
        if (!cfgForward || !cfgConfigured) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val key = pkg + "#" + notifId + "\n" + title + "\n" + text
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0L) < 60_000L) return
            lastSent[key] = now
        }
        val appLabel = synchronized(appLabelCache) { appLabelCache[pkg] } ?: try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            "${packageManager.getApplicationLabel(info)}".also { label ->
                synchronized(appLabelCache) { appLabelCache[pkg] = label }
            }
        } catch (_: Exception) {
            pkg
        }
        if (pkgFull(pkg, now)) return
        val message = buildString {
            appendLine("🔔 <b>Notification</b>")
            appendLine("App: ${Html.escape(appLabel)}")
            if (title.isNotEmpty()) appendLine("Title: ${Html.escape(title.take(200))}")
            if (text.isNotEmpty()) appendLine("Text: ${Html.escape(text.take(300))}")
        }
        record(appLabel, title, text)
        forwardToTelegram(message, pkg)
    }

    override fun onListenerConnected() {
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("NotifForwarder", "Notification listener connected")
        scope.launch {
            try {
                val prefs = prefsRef ?: PreferencesManager.getInstance(this@NotificationForwarderService).also { prefsRef = it }
                val queue = queueRef ?: MessageQueue(this@NotificationForwarderService).also { queueRef = it }
                prefs.isConfigured()
                queue.hasMessages()
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
            return q.size >= 5
        }
    }

    private fun pkgRecord(pkg: String, now: Long) {
        synchronized(pkgHitsLock) {
            val q = pkgHits.getOrPut(pkg) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() > 120_000L) q.removeFirst()
            q.addLast(now)
        }
    }

    private fun queue(): MessageQueue {
        queueRef?.let { return it }
        return MessageQueue(this).also { queueRef = it }
    }

    private suspend fun forwardToTelegram(message: String, pkg: String = "") {
        if (inFlight.incrementAndGet() > 4) {
            inFlight.decrementAndGet()
            try {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
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
            if (botToken.isEmpty() || chatId.isEmpty()) return
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
                return
            }
            if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                android.util.Log.e("NotifForwarder", "Auth rejected, dropping notification without queue")
                try {
                    prefs.credentialError = response.code().toString()
                    prefs.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
                return
            }
            queue().addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Forward failed", e)
            try {
                queue().addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        }
    }
}
