package com.redeye.parentalmonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.redeye.parentalmonitor.utils.CrashReporter

class ParentalMonitorApp : Application() {

    companion object {
        const val CHANNEL_ID = "monitoring_channel"
        const val RESUME_CHANNEL_ID = "resume_channel"
    }

    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Exception) {
            android.util.Log.e("ParentalMonitorApp", "super.onCreate failed", e)
        }
        try {
            CrashReporter.install(this)
        } catch (e: Exception) {
            android.util.Log.e("ParentalMonitorApp", "CrashReporter install failed", e)
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.init(this)
        } catch (e: Exception) {
            android.util.Log.e("ParentalMonitorApp", "NetworkUtils init failed", e)
        }
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            android.util.Log.e("ParentalMonitorApp", "Notification channel creation failed", e)
        }
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    com.redeye.parentalmonitor.data.PreferencesManager.refreshInstance(this@ParentalMonitorApp)
                } catch (e: Exception) {
                    android.util.Log.e("ParentalMonitorApp", "PreferencesManager refresh failed", e)
                }
                try {
                    com.redeye.parentalmonitor.data.PreferencesManager.getInstance(this@ParentalMonitorApp)
                } catch (e: Exception) {
                    android.util.Log.e("ParentalMonitorApp", "PreferencesManager init failed", e)
                }
                try {
                    com.redeye.parentalmonitor.data.MessageQueue.getInstance(this@ParentalMonitorApp).tryRestorePersistent()
                } catch (e: Exception) {
                    android.util.Log.e("ParentalMonitorApp", "MessageQueue restore failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ParentalMonitorApp", "Background init dispatch failed", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                importance
            ).apply {
                description = getString(R.string.channel_desc)
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
                val resume = NotificationChannel(RESUME_CHANNEL_ID, getString(R.string.channel_name_alerts), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                }
                notificationManager.createNotificationChannel(resume)
            } catch (_: Exception) {
            }
        }
    }
}

