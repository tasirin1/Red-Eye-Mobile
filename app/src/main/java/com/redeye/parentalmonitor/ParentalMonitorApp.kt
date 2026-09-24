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
        migrateLegacyConsent()
        createNotificationChannel()
    }

    private fun migrateLegacyConsent() {
        try {
            val prefs = com.redeye.parentalmonitor.data.PreferencesManager.getInstance(this)
            if (prefs.isMonitoringEnabled && !prefs.userDisabledMonitoring && !prefs.userConsentedMonitoring) {
                prefs.userConsentedMonitoring = true
            }
        } catch (_: Exception) {
        }
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
        }
    }
}

