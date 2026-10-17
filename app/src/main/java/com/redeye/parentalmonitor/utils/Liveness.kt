package com.redeye.parentalmonitor.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.receiver.LivenessReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Liveness {
    const val ACTION_ALARM = "com.redeye.parentalmonitor.action.LIVENESS_ALARM"
    private const val REQ_CODE = 4711
    private const val INTERVAL_MS = 10 * 60_000L
    private const val STALE_GAP_MS = 15 * 60_000L
    private const val REPORT_COOLDOWN_MS = 6 * 60 * 60_000L
    private const val ALIVE_WRITE_MIN_GAP_MS = 60_000L
    private val lastWriteAt = java.util.concurrent.atomic.AtomicLong(0L)

    fun allowed(manager: PreferencesManager): Boolean {
        return try {
            manager.isMonitoringEnabled && manager.isConfigured() && !manager.userDisabledMonitoring && manager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    fun schedule(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pending(context)
            )
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pending(context))
        } catch (_: Exception) {
        }
    }

    fun markAlive(manager: PreferencesManager, force: Boolean = false) {
        try {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (!force) {
                val last = lastWriteAt.get()
                if (last != 0L && nowElapsed - last < ALIVE_WRITE_MIN_GAP_MS) return
            }
            lastWriteAt.set(nowElapsed)
            manager.lastAliveAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
    }

    fun noteServiceStart(context: Context, manager: PreferencesManager) {
        try {
            val now = System.currentTimeMillis()
            val previous = try { manager.lastAliveAt } catch (_: Exception) { 0L }
            val lastReport = try { manager.livenessReportAt } catch (_: Exception) { 0L }
            if (isKill(previous, now) && reportDue(lastReport, now)) {
                val kills = try { manager.livenessKills } catch (_: Exception) { 0 }
                try { manager.livenessKills = kills + 1 } catch (_: Exception) { }
                try { manager.livenessReportAt = now } catch (_: Exception) { }
                try {
                    MessageQueue.getInstance(context).addMessage(resurrectionText(previous, now))
                    MessageScheduler.scheduleMessageSend(context)
                } catch (_: Exception) {
                }
            }
            markAlive(manager, force = true)
        } catch (_: Exception) {
        }
    }

    fun noteStopped(manager: PreferencesManager) {
        try {
            manager.lastAliveAt = 0L
        } catch (_: Exception) {
        }
    }

    fun isKill(previousActivityAt: Long, now: Long): Boolean {
        return previousActivityAt > 0L && now > previousActivityAt && now - previousActivityAt >= STALE_GAP_MS
    }

    fun reportDue(lastReportAt: Long, now: Long): Boolean {
        return lastReportAt == 0L || now < lastReportAt || now - lastReportAt >= REPORT_COOLDOWN_MS
    }

    fun gapText(previous: Long, now: Long): String {
        val minutes = ((now - previous) / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60L) "${minutes / 60L} jam ${minutes % 60L} menit" else "$minutes menit"
    }

    fun clockText(ms: Long): String {
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        } catch (_: Exception) {
            "-"
        }
    }

    private fun resurrectionText(previous: Long, now: Long): String {
        return "♻️ Monitoring hidup lagi. Sebelumnya berhenti sekitar pukul ${clockText(previous)} (±${gapText(previous, now)})."
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, LivenessReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(
            context,
            REQ_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
