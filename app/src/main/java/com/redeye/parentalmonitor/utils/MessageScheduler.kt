package com.redeye.parentalmonitor.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.redeye.parentalmonitor.worker.SendMessageWorker
import java.util.concurrent.TimeUnit

object MessageScheduler {
    private const val UNIQUE_WORK = "send_queued_messages"

    fun scheduleMessageSend(context: Context) {
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

        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, sendRequest)
    }
}
