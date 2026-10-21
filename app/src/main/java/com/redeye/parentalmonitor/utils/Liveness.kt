package com.redeye.parentalmonitor.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.receiver.LivenessReceiver
import com.redeye.parentalmonitor.service.MonitoringService

object Liveness {
    const val ACTION_ALARM = "com.redeye.parentalmonitor.action.LIVENESS_ALARM"
    private const val REQ_CODE = 4711
    private const val URGENT_INTERVAL_MS = 3 * 60_000L
    private const val SCREEN_OFF_INTERVAL_MS = 10 * 60_000L
    private const val SCREEN_ON_INTERVAL_MS = 15 * 60_000L
    private const val URGENT_WINDOW_MS = 60 * 60_000L
    private const val KILL_MIN_MS = 3 * 60_000L
    private const val STALE_GAP_MS = 15 * 60_000L
    private const val KILLS_PER_REPORT = 3
    private const val REPORT_COOLDOWN_MS = 6 * 60 * 60_000L
    private const val ALIVE_WRITE_MIN_GAP_MS = 60_000L
    private val lastWriteAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val urgentUntilElapsed = java.util.concurrent.atomic.AtomicLong(0L)
    private val scheduledAtElapsed = java.util.concurrent.atomic.AtomicLong(0L)

    fun intervalMs(context: Context): Long {
        return if (SystemClock.elapsedRealtime() < urgentUntilElapsed.get()) {
            URGENT_INTERVAL_MS
        } else if (isScreenInteractive(context)) {
            SCREEN_ON_INTERVAL_MS
        } else {
            SCREEN_OFF_INTERVAL_MS
        }
    }

    fun intervalText(context: Context): String {
        val ms = intervalMs(context)
        val label = when (ms) {
            URGENT_INTERVAL_MS -> "mode waspada"
            SCREEN_ON_INTERVAL_MS -> "layar nyala"
            else -> "layar mati"
        }
        return (ms / 60_000L).toString() + " mnt (" + label + ")"
    }

    fun intervalTextLocalized(context: Context): String {
        val ms = intervalMs(context)
        val label = when (ms) {
            URGENT_INTERVAL_MS -> context.getString(R.string.liveness_watchdog_urgent)
            SCREEN_ON_INTERVAL_MS -> context.getString(R.string.liveness_watchdog_screen_on)
            else -> context.getString(R.string.liveness_watchdog_screen_off)
        }
        return context.getString(R.string.liveness_watchdog_fmt, ms / 60_000L, label)
    }

    fun noteKillDetected() {
        try {
            urgentUntilElapsed.set(SystemClock.elapsedRealtime() + URGENT_WINDOW_MS)
        } catch (_: Exception) {
        }
    }

    private fun isScreenInteractive(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun allowed(manager: PreferencesManager): Boolean {
        return try {
            manager.isMonitoringEnabled && manager.isConfigured() && !manager.userDisabledMonitoring && manager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    fun schedule(context: Context) {
        scheduleSoon(context, intervalMs(context))
    }

    fun scheduleSoon(context: Context, delayMs: Long) {
        try {
            val now = SystemClock.elapsedRealtime()
            val target = now + delayMs.coerceAtLeast(5_000L)
            val existing = scheduledAtElapsed.get()
            if (existing > now && existing <= target) return
            if (!scheduledAtElapsed.compareAndSet(existing, target)) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                target,
                pending(context)
            )
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context) {
        scheduledAtElapsed.set(0L)
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pending(context))
        } catch (_: Exception) {
        }
    }

    fun markAlive(manager: PreferencesManager, force: Boolean = false) {
        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (!force) {
                val last = lastWriteAt.get()
                if (last != 0L && nowElapsed - last < ALIVE_WRITE_MIN_GAP_MS) return
            }
            lastWriteAt.set(nowElapsed)
            manager.setLastAliveMarker(System.currentTimeMillis(), nowElapsed)
        } catch (_: Exception) {
        }
    }

    fun noteServiceStart(context: Context, manager: PreferencesManager) {
        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val prevElapsed = try { manager.lastAliveElapsed } catch (_: Exception) { 0L }
            val prevWall = try { manager.lastAliveAt } catch (_: Exception) { 0L }
            if (prevElapsed > 0L && nowElapsed > prevElapsed) {
                val downMs = nowElapsed - prevElapsed
                if (downMs >= KILL_MIN_MS) {
                    noteKillDetected()
                    val kills = try { manager.livenessKills } catch (_: Exception) { 0 } + 1
                    val unreported = try { manager.livenessKillsUnreported } catch (_: Exception) { 0 } + 1
                    try { manager.livenessKills = kills } catch (_: Exception) { }
                    try { manager.livenessKillsUnreported = unreported } catch (_: Exception) { }
                    val lastReport = try { manager.livenessReportAt } catch (_: Exception) { 0L }
                    val significant = downMs >= STALE_GAP_MS || unreported >= KILLS_PER_REPORT
                    if (significant && reportDue(lastReport, nowWall)) {
                        val reportPrevWall = prevWall
                        val reportDownMs = downMs
                        val reportUnreported = unreported
                        Background.runCritical {
                            try {
                                val durable = MessageQueue.getInstance(context)
                                    .addMessage(resurrectionText(reportPrevWall, reportDownMs, reportUnreported))
                                try { MessageScheduler.scheduleMessageSend(context) } catch (_: Exception) { }
                                if (durable) {
                                    try { manager.livenessReportAt = nowWall } catch (_: Exception) { }
                                    try { manager.livenessKillsUnreported = 0 } catch (_: Exception) { }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
            markAlive(manager, force = true)
        } catch (_: Exception) {
        }
    }

    fun noteStopped(manager: PreferencesManager) {
        try {
            manager.setLastAliveMarker(0L, 0L)
        } catch (_: Exception) {
        }
    }

    fun handleWakeUp(context: Context) {
        val manager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            schedule(context)
            return
        }
        if (!allowed(manager)) {
            cancel(context)
            return
        }
        if (MonitoringService.isRunning) {
            markAlive(manager)
            schedule(context)
            return
        }
        noteKillDetected()
        schedule(context)
        try {
            if (manager.liveTestPending) manager.liveTestByAlarm = true
        } catch (_: Exception) {
        }
        val started = try {
            val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            try {
                MessageScheduler.scheduleBootRestart(context)
            } catch (_: Exception) {
            }
        }
    }

    fun reportDue(lastReportAt: Long, now: Long): Boolean {
        return lastReportAt == 0L || now < lastReportAt || now - lastReportAt >= REPORT_COOLDOWN_MS
    }

    fun gapText(downMs: Long): String {
        val minutes = (downMs / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60L) "${minutes / 60L} jam ${minutes % 60L} menit" else "$minutes menit"
    }

    fun clockText(ms: Long): String {
        return TimeFmt.short(ms)
    }

    private fun resurrectionText(previousWall: Long, downMs: Long, kills: Int): String {
        val gap = gapText(downMs)
        return if (kills <= 1) {
            "♻️ Monitoring hidup lagi. Sebelumnya berhenti sekitar pukul ${clockText(previousWall)} (±$gap)."
        } else {
            "♻️ Monitoring hidup lagi. Sudah berhenti $kills× sejak laporan terakhir (terakhir sekitar pukul ${clockText(previousWall)}, ±$gap)."
        }
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
