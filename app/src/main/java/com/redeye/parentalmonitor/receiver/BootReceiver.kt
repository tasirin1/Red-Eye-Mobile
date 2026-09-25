package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.MessageScheduler

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HUAWEI_BOOT_COMPLETED = "huawei.intent.action.BOOTCOMPLETED"
        private const val ACTION_HTC_BOOT_COMPLETED = "com.htc.intent.action.BOOTCOMPLETED"

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HUAWEI_BOOT_COMPLETED,
            ACTION_HTC_BOOT_COMPLETED
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val data = intent.dataString ?: return
            if (!data.contains(context.packageName)) return
        }
        val appContext = context.applicationContext
        MessageScheduler.scheduleWatchdog(appContext)
        val pendingResult = goAsync()
        val thread = Thread {
            try {
                handleBoot(appContext)
            } catch (_: Exception) {
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
        try {
            thread.start()
        } catch (_: Exception) {
            try {
                pendingResult.finish()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleBoot(context: Context) {
        val preferencesManager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        try {
            if (android.os.SystemClock.elapsedRealtime() < preferencesManager.credentialErrorAt) {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
            }
        } catch (_: Exception) {
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) return
        if (!preferencesManager.isConfigured()) {
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (!tryStartService(context)) {
            MessageScheduler.scheduleBootRestart(context)
        }
    }

    private fun tryStartService(context: Context): Boolean {
        val serviceIntent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "Service start blocked after reboot, will retry via worker", e)
            false
        }
    }
}
