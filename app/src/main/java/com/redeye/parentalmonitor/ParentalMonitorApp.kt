package com.redeye.parentalmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.redeye.parentalmonitor.utils.CrashReporter

class ParentalMonitorApp : Application() {

    companion object {
        const val CHANNEL_ID = "monitoring_channel"
        const val CHANNEL_NAME = "Monitoring Service"
        const val RESUME_CHANNEL_ID = "resume_channel"
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (BuildConfig.DEBUG) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_MIN
            }

            val channelName = CHANNEL_NAME
            
            val channel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                importance
            ).apply {
                description = "" // Always empty to hide details
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            try {
                val resume = NotificationChannel(RESUME_CHANNEL_ID, "Resume Monitoring", NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(resume)
            } catch (_: Exception) {
            }
        }
    }
}

