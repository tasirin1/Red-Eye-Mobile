package com.redeye.parentalmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class ParentalMonitorApp : Application() {

    companion object {
        const val CHANNEL_ID = "monitoring_channel"
        const val CHANNEL_NAME = "Monitoring Service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = if (BuildConfig.DEBUG) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_MIN
            }

            val channelName = if (BuildConfig.DEBUG) {
                CHANNEL_NAME
            } else {
                "System Service"
            }
            
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
        }
    }
}

