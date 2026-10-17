package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.Liveness
import com.redeye.parentalmonitor.utils.MessageScheduler

class LivenessReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Liveness.ACTION_ALARM) return
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        try {
            Background.runCritical {
                try {
                    handle(appContext)
                } catch (_: Exception) {
                } finally {
                    try {
                        pendingResult?.finish()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            try {
                pendingResult?.finish()
            } catch (_: Exception) {
            }
            try {
                handle(appContext)
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(context: Context) {
        if (MonitoringService.isRunning) {
            Liveness.schedule(context)
            return
        }
        val manager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            Liveness.schedule(context)
            return
        }
        if (!Liveness.allowed(manager)) {
            Liveness.cancel(context)
            return
        }
        Liveness.schedule(context)
        val started = try {
            val serviceIntent = Intent(context, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_START_MONITORING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            true
        } catch (_: Exception) {
            false
        }
        if (!started) {
            try {
                MessageScheduler.scheduleBootRestart(context)
            } catch (_: Exception) {
            }
        }
    }
}
