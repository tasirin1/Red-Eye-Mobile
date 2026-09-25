package com.redeye.parentalmonitor.worker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.redeye.parentalmonitor.utils.MessageScheduler

class BootRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val tap = android.app.PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, com.redeye.parentalmonitor.ui.SpeedMonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.notification_title))
            .setContentText(applicationContext.getString(R.string.notification_text))
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        try {
            setForeground(getForegroundInfo())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        try {
            CrashReporter.flushPending(appContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        val prefs = try {
            PreferencesManager.refreshInstance(appContext)
            PreferencesManager.getInstance(appContext)
        } catch (_: Exception) {
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
        try {
            if (android.os.SystemClock.elapsedRealtime() < prefs.credentialErrorAt) {
                prefs.credentialError = ""
                prefs.credentialErrorAt = 0L
            }
        } catch (_: Exception) {
        }
        if (prefs.userDisabledMonitoring || !prefs.userConsentedMonitoring || !prefs.isMonitoringEnabled) {
            return Result.success()
        }
        if (!prefs.isConfigured()) {
            return if (runAttemptCount < 5) Result.retry() else Result.success()
        }
        MessageScheduler.scheduleWatchdog(appContext)
        if (MonitoringService.isRunning || MonitoringService.heartbeatFresh(appContext)) {
            MessageScheduler.scheduleMessageSend(appContext)
            return Result.success()
        }
        return try {
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
            postResumeReminder(appContext)
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    private fun postResumeReminder(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val tap = android.app.PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.redeye.parentalmonitor.ui.SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, ParentalMonitorApp.RESUME_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.resume_title))
                .setContentText(context.getString(R.string.resume_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(RESUME_NOTIF_ID, notification)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val NOTIF_ID = 2
        private const val RESUME_NOTIF_ID = 3
    }
}
