package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val preferencesManager = try {
                PreferencesManager(context)
            } catch (_: Exception) {
                return
            }
            if (preferencesManager.isMonitoringEnabled && preferencesManager.isConfigured() && !preferencesManager.userDisabledMonitoring) {
                val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_START_MONITORING
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BootReceiver", "Service start blocked after reboot, scheduling queue drain", e)
                    com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(context)
                }
            }
        }
    }
}
