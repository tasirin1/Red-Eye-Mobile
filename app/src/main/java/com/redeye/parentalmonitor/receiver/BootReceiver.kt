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
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_PACKAGE_RESTARTED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT
        )

        private val handleLock = Any()
        @Volatile
        private var lastHandleAt = 0L

        private fun markHandled(context: android.content.Context, at: Long, full: Boolean = true) {
            synchronized(handleLock) { lastHandleAt = at }
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("last_handle_elapsed", at)
                    .apply()
            } catch (_: Exception) {
            }
            if (!full) return
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("last_full_boot_elapsed", at)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val updatedPart = intent.data?.schemeSpecificPart
            if (updatedPart != null && updatedPart != context.packageName) return
        }
        if (intent.action == Intent.ACTION_PACKAGE_RESTARTED) {
            val restartedPart = intent.data?.schemeSpecificPart
            if (restartedPart != null && restartedPart != context.packageName) return
        }
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            val nowUnlock = android.os.SystemClock.elapsedRealtime()
            synchronized(handleLock) { if (lastHandleAt != 0L && nowUnlock - lastHandleAt < 60_000L) return }
            if (MonitoringService.isRunning) {
                markHandled(context, nowUnlock)
                try {
                    MessageScheduler.scheduleWatchdog(context.applicationContext)
                } catch (_: Exception) {
                }
                return
            }
        }
        val appContext = context.applicationContext
        MessageScheduler.scheduleWatchdog(appContext)
        try {
            com.redeye.parentalmonitor.utils.Liveness.schedule(appContext)
        } catch (_: Exception) {
        }
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            return
        }
        val bootAction = intent.action
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    handleBoot(appContext, bootAction)
                } catch (_: Exception) {
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {
                    }
                }
            }
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
        val freshBoot = nowBoot <= 120_000L
        synchronized(handleLock) { if (!updated && !freshBoot && lastHandleAt != 0L && nowBoot - lastHandleAt < 60_000L) return }
        try {
            val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
            val lastElapsed = meta.getLong("last_handle_elapsed", 0L)
            if (!updated && !freshBoot && lastElapsed != 0L && nowBoot >= lastElapsed && nowBoot - lastElapsed < 60_000L) return
            // Additional guard: if we've handled a boot in the last 10 minutes AND service runs, skip (catches OEM duplicate >60s apart)
            val lastFullBoot = meta.getLong("last_full_boot_elapsed", 0L)
            if (!updated && MonitoringService.isRunning && lastFullBoot != 0L && nowBoot - lastFullBoot < 10 * 60_000L) return
        } catch (_: Exception) {
        }
        if (!updated && MonitoringService.isRunning) {
            markHandled(context, nowBoot)
            return
        }
        val preferencesManager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        try {
            val stuckVolume = preferencesManager.ringPrevVolume
            val stuckAt = preferencesManager.ringSavedAt
            val ringAge = System.currentTimeMillis() - stuckAt
            if (stuckVolume >= 0 && stuckAt > 0L && ringAge >= 0L && (ringAge > 60_000L && ringAge < 12 * 60 * 60_000L)) {
                var restored = false
                try {
                    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckVolume, 0)
                    restored = true
                } catch (_: Exception) {
                }
                if (restored) {
                    try {
                        preferencesManager.clearRingStateSync()
                    } catch (_: Exception) {
                    }
                }
            } else if (ringAge >= 0L && (stuckVolume >= 0 || stuckAt > 0L)) {
                try {
                    preferencesManager.clearRingStateSync()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(preferencesManager)
        } catch (_: Exception) {
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            markHandled(context, nowBoot)
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_elapsed", nowBoot).apply()
            } catch (_: Exception) {
            }
            return
        }
        if (!preferencesManager.isConfigured()) {
            markHandled(context, 0L)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (!tryStartService(context)) {
            markHandled(context, 0L)
            MessageScheduler.scheduleBootRestart(context)
            try {
                val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
                val lastNotice = meta.getLong("reboot_notice_elapsed", 0L)
                if (lastNotice == 0L || nowBoot < lastNotice || nowBoot - lastNotice >= 3_600_000L) {
                    meta.edit().putLong("reboot_notice_elapsed", nowBoot).apply()
                    com.redeye.parentalmonitor.data.MessageQueue.getInstance(context).addMessage("🔄 Monitoring coba jalan lagi. Bila sepi, buka aplikasi kalkulator.")
                    MessageScheduler.scheduleMessageSend(context)
                }
            } catch (_: Exception) {
            }
        } else {
            markHandled(context, nowBoot)
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
