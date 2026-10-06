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

        private val handleLock = Any()
        @Volatile
        private var lastHandleAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val updatedPart = intent.data?.schemeSpecificPart
            if (updatedPart != null && updatedPart != context.packageName) return
        }
        if (intent.action == Intent.ACTION_USER_UNLOCKED) {
            val nowUnlock = android.os.SystemClock.elapsedRealtime()
            synchronized(handleLock) { if (lastHandleAt != 0L && nowUnlock - lastHandleAt < 60_000L) return }
            if (MonitoringService.isRunning) {
                lastHandleAt = nowUnlock
                return
            }
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
        val nowWall = System.currentTimeMillis()
        val updated = intentAction == Intent.ACTION_MY_PACKAGE_REPLACED
        val freshBoot = nowBoot <= 120_000L
        synchronized(handleLock) { if (!updated && !freshBoot && lastHandleAt != 0L && nowBoot - lastHandleAt < 60_000L) return }
        try {
            val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
            var lastWall = meta.getLong("last_handle_wall", 0L)
            if (lastWall == 0L) lastWall = meta.getLong("last_handle_elapsed", 0L)
            if (!updated && !freshBoot && lastWall != 0L && nowWall >= lastWall && nowWall - lastWall < 60_000L) return
        } catch (_: Exception) {
        }
        if (!updated && MonitoringService.isRunning) {
            lastHandleAt = nowBoot
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_wall", nowWall).apply()
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
            val stuckVolume = preferencesManager.ringPrevVolume
            val stuckAt = preferencesManager.ringSavedAt
            val ringAge = System.currentTimeMillis() - stuckAt
            if (stuckVolume >= 0 && stuckAt > 0L && ringAge > 60_000L && ringAge < 12 * 60 * 60_000L) {
                try {
                    val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckVolume, 0)
                } catch (_: Exception) {
                }
                try {
                    preferencesManager.clearRingStateSync()
                } catch (_: Exception) {
                }
            } else if (stuckVolume >= 0 || stuckAt > 0L) {
                try {
                    preferencesManager.clearRingStateSync()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        val bootClear = intentAction == Intent.ACTION_BOOT_COMPLETED || intentAction == ACTION_QUICKBOOT_POWERON || intentAction == Intent.ACTION_USER_UNLOCKED
        if (!updated && bootClear) {
            try {
                preferencesManager.clearScreenshotConsentSync()
            } catch (_: Exception) {
            }
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(preferencesManager)
        } catch (_: Exception) {
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            lastHandleAt = nowBoot
            try {
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_wall", nowWall).apply()
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
                context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit().putLong("last_handle_wall", nowWall).apply()
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
