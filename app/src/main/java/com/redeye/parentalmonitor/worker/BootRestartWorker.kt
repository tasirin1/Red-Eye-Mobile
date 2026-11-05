package com.redeye.parentalmonitor.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.ParentalMonitorApp
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.CrashReporter
import com.redeye.parentalmonitor.utils.FgsTypes
import com.redeye.parentalmonitor.utils.Liveness
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.ServiceStart

class BootRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    // Menyerah dengan jaring pengaman (issue #928): titik-titik
    // menyerah sesudah 5x retry dulu berakhir Result.success()
    // polos — bila watchdog periodik belum terpasang pada boot
    // ini, layanan bisa tertidur sampai pemicu eksternal.
    // Menyerah kini selalu memasang watchdog (kebijakan KEEP:
    // tak mengganggu yang sudah terpasang; worker-nya sendiri
    // yang kelak membatalkan bila pemantauan dimatikan owner).
    private fun menyerahDenganJaring(): Result {
        try {
            com.redeye.parentalmonitor.utils.MessageScheduler.scheduleWatchdog(applicationContext)
        } catch (_: Exception) {
        }
        // Jaring menyerah juga memasang Liveness (issue #1259):
        // jalan keluar awal (prefs gagal/blokir auth/penyimpanan)
        // berhenti sebelum titik pasang Liveness normal, padahal
        // ketiganya keadaan sementara yang tak dinilai
        // allowedCheck sebagai penolakan — tanpa ini rantai
        // alarm bisa absen total sesudah menyerah. Keadaan mati
        // sengaja tak sampai sini (dicek lebih dulu) dan
        // Liveness tetap menilai ulang saat menembak.
        try {
            Liveness.schedule(applicationContext)
        } catch (_: Exception) {
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return try {
            buildForegroundInfo()
        } catch (_: Exception) {
            val fallback = NotificationCompat.Builder(applicationContext, ParentalMonitorApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.notification_title))
                .setOngoing(true)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(NOTIF_ID, fallback, FgsTypes.base(Build.VERSION.SDK_INT))
            } else {
                ForegroundInfo(NOTIF_ID, fallback)
            }
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val tap = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, com.redeye.parentalmonitor.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(applicationContext.getString(R.string.notification_text))
            .setContentIntent(tap)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, FgsTypes.base(Build.VERSION.SDK_INT))
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        if (activeRuns.getAndIncrement() > 0) {
            activeRuns.decrementAndGet()
            return Result.success()
        }
        try {
            return try {
                doWorkInternal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                android.util.Log.e("BootRestartWorker", "doWork failed", t)
                if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
            }
        } finally {
            activeRuns.decrementAndGet()
        }
    }

    private suspend fun doWorkInternal(): Result {
        val appContext = applicationContext
        try {
            CrashReporter.flushPending(appContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (MonitoringService.isRunning) {
            // Rantai sehat (issue #1572): alarm Liveness hanya
            // diperbaiki bila hilang (jadwalnya yang
            // diregangkan jangan diruntuhkan), dan watchdog
            // ini sendiri diregangkan ke 45 menit. Awal
            // layanan berikutnya memulihkannya ke 15 menit.
            try {
                Liveness.scheduleIfNone(appContext)
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.scheduleMessageSend(appContext)
            } catch (_: Exception) {
            }
            try {
                MessageScheduler.scheduleWatchdogStretched(appContext)
            } catch (_: Exception) {
            }
            return Result.success()
        }
        val prefs = try {
            PreferencesManager.refreshInstance(appContext)
            PreferencesManager.getInstance(appContext)
        } catch (_: Exception) {
            return if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(prefs)
        } catch (_: Exception) {
        }
        // If auth is blocked, schedule retry instead of giving up
        if (com.redeye.parentalmonitor.utils.NetworkUtils.isAuthBlocked(prefs)) {
            return if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        }
        if (!prefs.isStorageEncrypted) {
            postStorageFallbackReminder(appContext)
            return if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        }
        if (prefs.userDisabledMonitoring || !prefs.userConsentedMonitoring || !prefs.isMonitoringEnabled) {
            MessageScheduler.cancelWatchdog(appContext)
            // Alarm Liveness ikut dibatalkan (issue #1563):
            // cabang ini dulu hanya membatalkan watchdog —
            // alarm sisa menembak sekali dalam <=15 menit
            // membangunkan proses di perangkat yang sengaja
            // dimatikan. BootReceiver (#1274) dan jalur
            // berhenti Setup membatalkan keduanya.
            try { Liveness.cancel(appContext) } catch (_: Exception) { }
            return Result.success()
        }
        // Liveness dipasang SESUDAH cek nonaktif di atas (issue
        // #1245): run sisa pekerjaan boot-restart yang menembak
        // sesudah pemilik mematikan pemantauan tak boleh memasang
        // ulang alarm Liveness lewat prolognya sebelum tahu
        // pemantauan sudah mati.
        try {
            Liveness.schedule(appContext)
        } catch (_: Exception) {
        }
        if (!prefs.isConfigured()) {
            // Tak terkonfigurasi = tak ada yang bisa dihidupkan:
            // watchdog periodik ikut dibatalkan (issue #1275).
            // Tanpa ini, watchdog yang terlanjur terpasang (mis.
            // dijadwalkan ulang onDestroy saat hapus kredensial
            // sedang berjalan) hidup selamanya membangunkan
            // proses tiap ±15 menit tanpa pernah bisa memulai
            // pemantauan — cabang nonaktif di atas sudah
            // membatalkannya, cabang ini tertinggal.
            try {
                MessageScheduler.cancelWatchdog(appContext)
            } catch (_: Exception) {
            }
            // Liveness yang baru dipasang di atas ikut
            // dibatalkan (issue #1563): perangkat belum
            // terkonfigurasi tak punya apa pun untuk
            // dijaga tetap hidup.
            try { Liveness.cancel(appContext) } catch (_: Exception) { }
            return Result.success()
        }
        try {
            setForeground(getForegroundInfo())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        MessageScheduler.scheduleWatchdog(appContext)
        val startResult: Result = try {
            val serviceIntent = Intent(appContext, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            ServiceStart.startForeground(appContext, serviceIntent)
            MessageScheduler.scheduleMessageSend(appContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            android.util.Log.w("BootRestartWorker", "FGS start denied, will retry", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        } catch (e: IllegalStateException) {
            android.util.Log.w("BootRestartWorker", "FGS start blocked by system, will retry", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        } catch (e: Exception) {
            android.util.Log.w("BootRestartWorker", "Restart attempt failed", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else menyerahDenganJaring()
        }
        return startResult
    }

    private fun postResumeReminder(context: Context) {
        postSetupReminder(context, R.string.resume_title, R.string.resume_text, "last_resume_elapsed", RESUME_NOTIF_ID)
    }

    private fun postStorageFallbackReminder(context: Context) {
        postSetupReminder(context, R.string.storage_fallback_title, R.string.storage_fallback_text, "last_storage_warn_elapsed", STORAGE_NOTIF_ID)
    }

    private fun postSetupReminder(context: Context, titleRes: Int, textRes: Int, markerKey: String, notifId: Int) {
        val nowResume = android.os.SystemClock.elapsedRealtime()
        val lastAt = lastReminderAt[markerKey] ?: 0L
        if (lastAt != 0L && nowResume - lastAt < 3 * 60 * 60_000L) return
        val nowWall = System.currentTimeMillis()
        try {
            val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val lastWall = meta.getLong(markerKey, 0L)
            val sinceLast = nowWall - lastWall
            if (lastWall != 0L && sinceLast > -3 * 60 * 60_000L && sinceLast < 3 * 60 * 60_000L) return
        } catch (_: Exception) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
        } catch (_: Exception) {
        }
        try {
            val tap = android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.redeye.parentalmonitor.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, ParentalMonitorApp.RESUME_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(titleRes))
                .setContentText(context.getString(textRes))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(notifId, notification)
            lastReminderAt[markerKey] = nowResume
            try {
                context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().putLong(markerKey, nowWall).apply()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private val activeRuns = java.util.concurrent.atomic.AtomicInteger(0)
        private const val NOTIF_ID = 2
        private const val RESUME_NOTIF_ID = 3
        private const val STORAGE_NOTIF_ID = 4
        private val lastReminderAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
