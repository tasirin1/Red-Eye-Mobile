package com.redeye.parentalmonitor.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import java.util.concurrent.TimeUnit

class BootRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val prefs = try {
            PreferencesManager(appContext)
        } catch (_: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
        if (!prefs.isMonitoringEnabled || !prefs.isConfigured()) return Result.success()
        return try {
            val serviceIntent = Intent(appContext, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
            Result.success()
        } catch (_: SecurityException) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (_: IllegalStateException) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "boot_retry_monitoring"

        fun schedule(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<BootRetryWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                android.util.Log.w("BootRetryWorker", "Schedule failed", e)
            }
        }
    }
}
