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
    private val lastSent = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val prefs = try {
            PreferencesManager(this)
        } catch (_: Exception) {
            return
        }
        if (!prefs.isMonitoringEnabled || prefs.monitoringPaused || prefs.userDisabledMonitoring) return
        if (!prefs.notifForwardEnabled || !prefs.isConfigured()) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val key = sbn.packageName + "\n" + title + "\n" + text
        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            if (now - (lastSent[key] ?: 0L) < 5 * 60_000L) return
            if (lastSent.size > 200) lastSent.clear()
            lastSent[key] = now
        }
        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info)?.toString() ?: sbn.packageName
        } catch (_: Exception) {
            sbn.packageName
        }
        val message = buildString {
            appendLine("🔔 <b>Notification</b>")
            appendLine("App: ${escapeHtml(appLabel)}")
            if (title.isNotEmpty()) appendLine("Title: ${escapeHtml(title.take(200))}")
            if (text.isNotEmpty()) appendLine("Text: ${escapeHtml(text.take(300))}")
        }
        scope.launch {
            forwardToTelegram(message)
        }
    }

    override fun onListenerConnected() {
        android.util.Log.i("NotifForwarder", "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        android.util.Log.w("NotifForwarder", "Notification listener disconnected")
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

    private suspend fun forwardToTelegram(message: String) {
        try {
            val prefs = PreferencesManager(this)
            val botToken = prefs.botToken
            val chatId = prefs.chatId
            if (botToken.isEmpty() || chatId.isEmpty()) return
            if (!NetworkUtils.isNetworkAvailable(this)) {
                MessageQueue(this).addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = message))
            if (response.isSuccessful && response.body()?.ok == true) return
            MessageQueue(this).addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        } catch (e: Exception) {
            android.util.Log.e("NotifForwarder", "Forward failed", e)
            try {
                MessageQueue(this).addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        }
    }
}
