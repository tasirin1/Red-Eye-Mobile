package com.redeye.parentalmonitor.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.redeye.parentalmonitor.R

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("AdminReceiver", "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("AdminReceiver", "Device Admin disabled!")
        try {
            Toast.makeText(context, context.getString(R.string.admin_disabled), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
        }
        val pending = try { goAsync() } catch (_: Exception) { null }
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    try { com.redeye.parentalmonitor.data.PreferencesManager.refreshInstance(context.applicationContext) } catch (_: Exception) { }
                    val prefs = com.redeye.parentalmonitor.data.PreferencesManager.getInstance(context.applicationContext)
                    if (prefs.isConfigured()) {
                        com.redeye.parentalmonitor.data.MessageQueue.getInstance(context.applicationContext).addMessage("\u26A0\uFE0F Device Admin was disabled on the device. Uninstall protection is OFF.", true)
                        com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(context.applicationContext)
                    }
                } catch (_: Exception) {
                } finally {
                    try { pending?.finish() } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            try { pending?.finish() } catch (_: Exception) { }
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("AdminReceiver", "Attempting to disable Device Admin!")
        val appContext = context.applicationContext
        try {
            val alert = Intent(appContext, com.redeye.parentalmonitor.service.MonitoringService::class.java).apply {
                action = com.redeye.parentalmonitor.service.MonitoringService.ACTION_ADMIN_ALERT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appContext.startForegroundService(alert)
            } else {
                appContext.startService(alert)
            }
        } catch (_: Exception) {
        }
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    com.redeye.parentalmonitor.data.PreferencesManager.refreshInstance(appContext)
                    val prefs = com.redeye.parentalmonitor.data.PreferencesManager.getInstance(appContext)
                    if (prefs.isConfigured()) {
                        com.redeye.parentalmonitor.data.MessageQueue.getInstance(appContext).addMessage(
                            "🚨 <b>Ada percobaan mematikan Device Admin</b>\nProteksi hapus aplikasi sebentar lagi mati. Foto + lokasi saya siapkan.",
                            true
                        )
                        com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(appContext)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return try {
            context.getString(R.string.admin_disable_warning)
        } catch (_: Exception) {
            "Disabling admin will turn off uninstall protection."
        }
    }
}

