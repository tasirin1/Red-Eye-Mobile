package com.redeye.parentalmonitor.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService

class BootRestartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val prefs = try {
            PreferencesManager.getInstance(appContext)
        } catch (_: Exception) {
            return if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
        if (!prefs.isMonitoringEnabled || !prefs.isConfigured() || prefs.userDisabledMonitoring || !prefs.userConsentedMonitoring) {
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
            Result.success()
        } catch (e: SecurityException) {
            android.util.Log.w("BootRestartWorker", "FGS start denied, surrendering", e)
            Result.success()
        } catch (e: IllegalStateException) {
            android.util.Log.w("BootRestartWorker", "FGS start blocked by system, surrendering", e)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("BootRestartWorker", "Restart attempt failed", e)
            if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
    }
}
