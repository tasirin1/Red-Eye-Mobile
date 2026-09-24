package com.redeye.parentalmonitor.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.redeye.parentalmonitor.worker.BootRestartWorker
import com.redeye.parentalmonitor.worker.SendMessageWorker
import java.util.concurrent.TimeUnit

object MessageScheduler {
    private const val UNIQUE_WORK = "send_queued_messages"
    private const val BOOT_RESTART_WORK = "boot_restart_monitoring"

    fun scheduleMessageSend(context: Context): Boolean {
        return try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val sendRequest = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, sendRequest)
            true
        } catch (e: Exception) {
            android.util.Log.w("MessageScheduler", "Schedule send failed", e)
            false
        }
    }

    fun scheduleBootRestart(context: Context): Boolean {
        return try {
            val restartRequest = OneTimeWorkRequestBuilder<BootRestartWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(BOOT_RESTART_WORK, ExistingWorkPolicy.KEEP, restartRequest)
            true
        } catch (e: Exception) {
            android.util.Log.w("MessageScheduler", "Schedule reboot restart failed", e)
            false
        }
    }
}
