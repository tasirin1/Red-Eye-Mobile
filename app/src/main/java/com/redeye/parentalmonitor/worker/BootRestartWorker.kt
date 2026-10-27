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

class BootRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

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
        return try {
            doWorkInternal()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            if (runAttemptCount < 5) Result.retry() else Result.success()
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
        try {
            Liveness.schedule(appContext)
        } catch (_: Exception) {
        }
        if (MonitoringService.isRunning) {
            MessageScheduler.scheduleMessageSend(appContext)
            return Result.success()
        }
        val prefs = try {
            PreferencesManager.refreshInstance(appContext)
            PreferencesManager.getInstance(appContext)
        } catch (_: Exception) {
            return if (runAttemptCount < 5) Result.retry() else Result.success()
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(prefs)
        } catch (_: Exception) {
        }
        // If auth is blocked, schedule retry instead of giving up
        if (com.redeye.parentalmonitor.utils.NetworkUtils.isAuthBlocked(prefs)) {
            return if (runAttemptCount < 5) Result.retry() else Result.success()
        }
        if (!prefs.isStorageEncrypted) {
            postStorageFallbackReminder(appContext)
            return if (runAttemptCount < 5) Result.retry() else Result.success()
        }
        if (prefs.userDisabledMonitoring || !prefs.userConsentedMonitoring || !prefs.isMonitoringEnabled) {
            MessageScheduler.cancelWatchdog(appContext)
            return Result.success()
        }
        if (!prefs.isConfigured()) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            MessageScheduler.scheduleMessageSend(appContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SecurityException) {
            android.util.Log.w("BootRestartWorker", "FGS start denied, will retry", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else Result.success()
        } catch (e: IllegalStateException) {
            android.util.Log.w("BootRestartWorker", "FGS start blocked by system, will retry", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else Result.success()
        } catch (e: Exception) {
            android.util.Log.w("BootRestartWorker", "Restart attempt failed", e)
            MessageScheduler.scheduleMessageSend(appContext)
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else Result.success()
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
        try {
            val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
            val lastElapsed = meta.getLong(markerKey, 0L)
            if (lastElapsed != 0L && nowResume >= lastElapsed && nowResume - lastElapsed < 3 * 60 * 60_000L) return
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
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong(markerKey, nowResume).apply()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val RESUME_NOTIF_ID = 3
        private const val STORAGE_NOTIF_ID = 4
        private val lastReminderAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }
}
