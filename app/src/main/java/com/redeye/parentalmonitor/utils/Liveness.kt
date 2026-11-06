package com.redeye.parentalmonitor.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    private val healthyStreak = java.util.concurrent.atomic.AtomicInteger(0)
    private val urgentUntilElapsed = java.util.concurrent.atomic.AtomicLong(0L)
    private val scheduledAtElapsed = java.util.concurrent.atomic.AtomicLong(0L)
    private val alarmLock = Any()

    // Inti murni keputusan interval (issue #1043): diekstrak
    // dari intervalMs agar terkunci uji JVM — dulu menyatu
    // dengan SystemClock + Context sehingga tak teruji sama
    // sekali bersama reportDue dan gapText.
    fun intervalFor(urgent: Boolean, screenOn: Boolean): Long {
        return if (urgent) {
            URGENT_INTERVAL_MS
        } else if (screenOn) {
            SCREEN_ON_INTERVAL_MS
        } else {
            SCREEN_OFF_INTERVAL_MS
        }
    }

    // Peregangan interval alarm mengikuti rentetan sehat
    // (issue #1572): alarm yang berulang kali
    // menemukan layanan hidup menaikkan intervalnya sendiri
    // bertahap (x2 sesudah 6 tembakan sehat, x4 sesudah 12,
    // plafon 60 menit) agar rantai bangun tak membangunkan
    // CPU selamanya pada irama dasar. Murni & teruji JVM.
    fun stretchedIntervalMs(baseMs: Long, streak: Int): Long {
        val factor = when {
            streak >= 12 -> 4L
            streak >= 6 -> 2L
            else -> 1L
        }
        return (baseMs * factor).coerceAtMost(60 * 60_000L)
    }

    fun intervalMs(context: Context): Long {
        return intervalFor(
            SystemClock.elapsedRealtime() < urgentUntilElapsed.get(),
            isScreenInteractive(context)
        )
    }

    fun intervalText(context: Context): String {
        val ms = intervalMs(context)
        val label = when (ms) {
            URGENT_INTERVAL_MS -> "mode waspada"
            SCREEN_ON_INTERVAL_MS -> "layar nyala"
            else -> "layar mati"
        }
        // Kejujuran Doze (issue #1502): alarm while-idle
        // dibatasi platform selama Doze (±1 tembakan per
        // 9 menit), jadi interval waspada 3 menit bisa
        // nyata lebih lama di HP tidur nyenyak — teks
        // menyebutkannya agar tak dibaca sebagai janji.
        val caveat = if (ms == URGENT_INTERVAL_MS) "; saat tidur nyenyak (Doze) sistem bisa memanjangkannya jadi sekitar 9 mnt" else ""
        // Angka yang dilaporkan adalah interval EFEKTIF
        // yang benar-benar dijadwalkan (issue #1822):
        // penjadwalan memakai stretchedIntervalMs atas
        // rentetan sehat (#1572), jadi melaporkan
        // interval dasar saja menilai kecepatan deteksi
        // lebih cepat dari kenyataannya.
        val efektif = stretchedIntervalMs(ms, healthyStreak.get())
        val catatanRegang = if (efektif > ms) "; dasarnya " + (ms / 60_000L) + " mnt, teregang mengikuti rentetan sehat" else ""
        return (efektif / 60_000L).toString() + " mnt (" + label + ")" + catatanRegang + caveat
    }

    fun intervalTextLocalized(context: Context): String {
        val ms = intervalMs(context)
        val label = when (ms) {
            URGENT_INTERVAL_MS -> context.getString(R.string.liveness_watchdog_urgent)
            SCREEN_ON_INTERVAL_MS -> context.getString(R.string.liveness_watchdog_screen_on)
            else -> context.getString(R.string.liveness_watchdog_screen_off)
        }
        // Interval efektif yang dijadwalkan, bukan
        // dasarnya saja (issue #1822): lihat intervalText.
        val efektif = stretchedIntervalMs(ms, healthyStreak.get())
        val dasar = context.getString(R.string.liveness_watchdog_fmt, efektif / 60_000L, label)
        val catatanRegang = if (efektif > ms) context.getString(R.string.liveness_watchdog_stretched, ms / 60_000L) else ""
        // Kejujuran Doze (issue #1502): lihat intervalText.
        return dasar + catatanRegang + if (ms == URGENT_INTERVAL_MS) context.getString(R.string.liveness_watchdog_doze) else 
    }

    fun noteKillDetected() {
        healthyStreak.set(0)
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

    enum class AllowedCheck { ALLOWED, DENIED, READ_FAILED }

    fun allowedCheck(manager: PreferencesManager): AllowedCheck {
        return try {
            if (manager.isMonitoringEnabled && manager.isConfigured() && !manager.userDisabledMonitoring && manager.userConsentedMonitoring && !manager.monitoringPaused) {
                AllowedCheck.ALLOWED
            } else {
                AllowedCheck.DENIED
            }
        } catch (e: Exception) {
            android.util.Log.e("Liveness", "allowed check failed", e)
            AllowedCheck.READ_FAILED
        }
    }

    // True bila satu-satunya alasan allowedCheck menolak adalah
    // keadaan jeda (issue #1276): semua syarat lain terpenuhi.
    private fun pausedOnly(manager: PreferencesManager): Boolean {
        return try {
            manager.isMonitoringEnabled && manager.isConfigured() &&
                !manager.userDisabledMonitoring && manager.userConsentedMonitoring &&
                manager.monitoringPaused
        } catch (_: Exception) {
            false
        }
    }

    fun schedule(context: Context) {
        scheduleSoon(context, intervalMs(context))
    }

    // Pasang alarm hanya bila tak ada yang tertunda (issue
    // #1572): pemanggil penyelamat (watchdog WorkManager)
    // tak boleh meruntuhkan target alarm yang sudah
    // diregangkan rantai sehat menjadi irama dasar lagi.
    fun scheduleIfNone(context: Context) {
        val existing = scheduledAtElapsed.get()
        if (existing > SystemClock.elapsedRealtime()) return
        schedule(context)
    }

    fun scheduleSoon(context: Context, delayMs: Long) {
        synchronized(alarmLock) {
            try {
                val now = SystemClock.elapsedRealtime()
                val target = now + delayMs.coerceAtLeast(5_000L)
                var previous = 0L
                while (true) {
                    val existing = scheduledAtElapsed.get()
                    if (existing > now && existing <= target) return
                    if (scheduledAtElapsed.compareAndSet(existing, target)) {
                        previous = existing
                        break
                    }
                }
                try {
                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        target,
                        pending(context)
                    )
                } catch (_: Exception) {
                    scheduledAtElapsed.compareAndSet(target, previous)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context) {
        synchronized(alarmLock) {
            scheduledAtElapsed.set(0L)
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pending(context))
            } catch (_: Exception) {
            }
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
            if (prevElapsed > 0L && nowElapsed > prevElapsed && BootClock.sameBootOrClockShift(prevWall, prevElapsed, nowWall, nowElapsed)) {
                val downMs = nowElapsed - prevElapsed
                if (downMs >= KILL_MIN_MS) {
                    noteKillDetected()
                    val unreported = try { manager.incrementLivenessKills() } catch (_: Exception) { 0 }
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
                                    try { manager.markLivenessReported(nowWall) } catch (_: Exception) { }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("Liveness", "Failed to queue resurrection report", e)
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

    // Penjaga serial handleWakeUp (issue #1068): sejak pool
    // receiver sungguh sejajar (#1032), alarm liveness dan
    // ACTION_TIME_CHANGED bisa menjalankan handleWakeUp
    // bersamaan dari dua receiver — startForeground dan
    // scheduleBootRestart terpanggil ganda. Dulu keduanya
    // berbagi pool satu benang sehingga terserial sendiri.
    private val wakeLock = Any()

    fun handleWakeUp(context: Context, fromAlarm: Boolean = false, penandaWaktu: Boolean = false) {
        synchronized(wakeLock) { handleWakeUpLocked(context, fromAlarm, penandaWaktu) }
    }

    private fun handleWakeUpLocked(context: Context, fromAlarm: Boolean = false, penandaWaktu: Boolean = false) {
        val manager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (e: Exception) {
            android.util.Log.e("Liveness", "wake-up aborted: preferences unavailable", e)
            schedule(context)
            return
        }
        when (allowedCheck(manager)) {
            AllowedCheck.DENIED -> {
                // Jeda bukan mati (issue #1276): penolakan yang
                // SEMATA karena monitoringPaused tak boleh
                // membatalkan rantai alarm — jeda bersifat
                // sementara dan /resume hanya bisa diterima
                // layanan yang tetap terjaga rantainya. Jadwalkan
                // ulang dan lewati tembakan ini; keadaan mati
                // sungguhan tetap membatalkan seperti biasa.
                if (pausedOnly(manager)) {
                    schedule(context)
                    return
                }
                cancel(context)
                return
            }
            AllowedCheck.READ_FAILED -> {
                schedule(context)
                return
            }
            AllowedCheck.ALLOWED -> {
            }
        }
        if (MonitoringService.isRunning) {
            markAlive(manager)
            // Rantai sehat meregang (issue #1572): mode
            // waspada tak pernah diregangkan; selain itu
            // interval dasar dikali rentetan tembakan sehat
            // berturut. Satu kematian yang terdeteksi
            // mengosongkan rentetan lewat noteKillDetected.
            val streak = healthyStreak.incrementAndGet()
            var urgent = SystemClock.elapsedRealtime() < urgentUntilElapsed.get()
            // Mode waspada berakhir lebih awal bila layanan
            // terbukti stabil (issue #1586): tiga tembakan
            // sehat beruntun pada irama waspada (±9 menit)
            // berarti badai bunuh sudah lewat — sisa jendela
            // 60 menitnya tak perlu dibakar pada irama
            // 3 menit.
            if (urgent && streak >= 3) {
                urgentUntilElapsed.set(0L)
                urgent = false
            }
            if (urgent) {
                schedule(context)
            } else {
                scheduleSoon(context, stretchedIntervalMs(intervalMs(context), streak))
            }
            return
        }
        if (!MonitoringService.tryClaimServiceStart()) {
            // Penghidup lain baru saja mengklaim start
            // (issue #1683): jangan catat sebagai kematian
            // dan jangan kirim start kedua. Jadwalkan
            // ulang Liveness lebih dulu (issue #1705):
            // alarm ini satu-tembak dan semua cabang
            // keluar lain menjadwalkan — tanpa ini rantai
            // watchdog putus bila pengklaim lain lupa.
            schedule(context)
            return
        }
        // Bangun karena jam/zona berubah (issue #1684)
        // BUKAN bukti layanan dibunuh: jangan kosongkan
        // rentetan sehat / nyalakan mode waspada untuknya.
        if (!penandaWaktu) noteKillDetected()
        schedule(context)
        try {
            if (fromAlarm && manager.liveTestPending) manager.liveTestByAlarm = true
        } catch (_: Exception) {
        }
        val started = try {
            val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            ServiceStart.startForeground(context, serviceIntent)
            true
        } catch (e: Exception) {
            android.util.Log.e("Liveness", "wake-up failed to start monitoring service", e)
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
