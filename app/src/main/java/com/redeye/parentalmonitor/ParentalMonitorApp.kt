package com.redeye.parentalmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.redeye.parentalmonitor.utils.CrashReporter

class ParentalMonitorApp : Application() {

    companion object {
        const val CHANNEL_ID = "monitoring_channel"
        const val CHANNEL_NAME = "Monitoring"
        const val RESUME_CHANNEL_ID = "resume_channel"
    }

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (_: Exception) {
        }
        try {
            CrashReporter.install(this)
        } catch (_: Exception) {
        }
        try {
            createNotificationChannel()
        } catch (_: Exception) {
        }
        try {
            Thread {
                try {
                    com.redeye.parentalmonitor.data.PreferencesManager.refreshInstance(this)
                } catch (_: Exception) {
                }
                try {
                    com.redeye.parentalmonitor.data.PreferencesManager.getInstance(this)
                } catch (_: Exception) {
                }
                try {
                    com.redeye.parentalmonitor.data.MessageQueue.getInstance(this).tryRestorePersistent()
                } catch (_: Exception) {
                }
            }.start()
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_MIN
            val channelName = CHANNEL_NAME
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = "Monitoring status"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val notificationManager = try {
                getSystemService(NotificationManager::class.java)
            } catch (_: Exception) {
                null
            } ?: return
            try {
                notificationManager.createNotificationChannel(channel)
            } catch (_: Exception) {
            }
            try {
                val resume = NotificationChannel(RESUME_CHANNEL_ID, "Monitoring Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(resume)
            } catch (_: Exception) {
            }
        }
    }
}

