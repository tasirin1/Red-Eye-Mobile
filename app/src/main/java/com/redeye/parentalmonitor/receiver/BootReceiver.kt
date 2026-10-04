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

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            ACTION_QUICKBOOT_POWERON
        )

        @Volatile
        private var lastHandleAt = 0L
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
        val bootAction = intent.action
        val thread = Thread {
            try {
                handleBoot(appContext, bootAction)
            } catch (_: Exception) {
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }.apply {
            name = "BootReceiver"
            isDaemon = true
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

    private fun handleBoot(context: Context, intentAction: String? = null) {
        val nowBoot = android.os.SystemClock.elapsedRealtime()
        val updated = intentAction == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!updated && nowBoot - lastHandleAt < 10_000L) return
        try {
            val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
            if (!updated && nowBoot - meta.getLong("last_handle_elapsed", 0L) < 10_000L) return
        } catch (_: Exception) {
        }
        if (!updated && MonitoringService.isRunning) {
            lastHandleAt = nowBoot
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_elapsed", nowBoot).apply()
            } catch (_: Exception) {
            }
            return
        }
        val preferencesManager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            lastHandleAt = 0L
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        try {
            val blockedErr = preferencesManager.credentialError
            if (blockedErr.isNotEmpty()) {
                val nowAuth = System.currentTimeMillis()
                val errAt = preferencesManager.credentialErrorAt
                if (nowAuth < errAt || nowAuth - errAt >= 30 * 60_000L) {
                    preferencesManager.credentialError = ""
                    preferencesManager.credentialErrorAt = 0L
                }
            }
        } catch (_: Exception) {
        }
        val isRealBoot = intentAction == Intent.ACTION_BOOT_COMPLETED || intentAction == ACTION_QUICKBOOT_POWERON || intentAction == Intent.ACTION_MY_PACKAGE_REPLACED
        if (isRealBoot) {
            try {
                preferencesManager.photoPausedUntil = 0L
            } catch (_: Exception) {
            }
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            lastHandleAt = nowBoot
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_elapsed", nowBoot).apply()
            } catch (_: Exception) {
            }
            return
        }
        if (!preferencesManager.isConfigured()) {
            lastHandleAt = 0L
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (!tryStartService(context)) {
            lastHandleAt = 0L
            MessageScheduler.scheduleBootRestart(context)
        } else {
            lastHandleAt = nowBoot
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_elapsed", nowBoot).apply()
            } catch (_: Exception) {
            }
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
