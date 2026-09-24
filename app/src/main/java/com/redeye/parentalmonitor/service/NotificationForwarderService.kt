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
import com.redeye.parentalmonitor.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
    private val pkgHits = object : LinkedHashMap<String, ArrayDeque<Long>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<Long>>): Boolean {
            return size > 200
        }
    }
    private val pkgHitsLock = Any()
    private var lastRebindAt = 0L

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                prefsRef = PreferencesManager.getInstance(this@NotificationForwarderService)
                queueRef = MessageQueue(this@NotificationForwarderService)
                prefsRef?.isConfigured()
                queueRef?.hasMessages()
            } catch (_: Exception) {
            }
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
        if (!prefs.isMonitoringEnabled || prefs.monitoringPaused || prefs.userDisabledMonitoring) return
        if (!prefs.notifForwardEnabled || !prefs.isConfigured()) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val key = pkg + "#" + notifId + "\n" + title + "\n" + text
        val now = System.currentTimeMillis()
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
            appendLine("App: ${escapeHtml(appLabel)}")
            if (title.isNotEmpty()) appendLine("Title: ${escapeHtml(title.take(200))}")
            if (text.isNotEmpty()) appendLine("Text: ${escapeHtml(text.take(300))}")
        }
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
        scope.cancel()
        super.onDestroy()
    }

    private fun escapeHtml(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
        }
        return out.toString()
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

    private suspend fun forwardToTelegram(message: String, pkg: String = "") {
        if (inFlight.incrementAndGet() > 4) {
            inFlight.decrementAndGet()
            try {
                (queueRef ?: MessageQueue(this).also { queueRef = it }).addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
            return
        }
        try {
            val prefs = prefsRef ?: try {
                PreferencesManager.getInstance(this).also { prefsRef = it }
            } catch (_: Exception) {
                try {
                    (queueRef ?: MessageQueue(this).also { queueRef = it }).addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return
            }
            val botToken = try { prefs.botToken } catch (_: Exception) { "" }
            val chatId = try { prefs.chatId } catch (_: Exception) { "" }
            if (botToken.isEmpty() || chatId.isEmpty()) return
            if (!NetworkUtils.isNetworkAvailable(this)) {
                (queueRef ?: MessageQueue(this).also { queueRef = it }).addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            if (response.isSuccessful && response.body()?.ok == true) {
                if (pkg.isNotEmpty()) pkgRecord(pkg, System.currentTimeMillis())
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
            (queueRef ?: MessageQueue(this).also { queueRef = it }).addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Forward failed", e)
            try {
                (queueRef ?: MessageQueue(this).also { queueRef = it }).addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }
}
