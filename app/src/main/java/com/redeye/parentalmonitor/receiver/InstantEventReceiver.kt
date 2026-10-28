package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.ServiceStart

class InstantEventReceiver : BroadcastReceiver() {

    companion object {
        private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val target = when (intent.action) {
            "android.provider.Telephony.SMS_RECEIVED" -> MonitoringService.ACTION_SMS_RECEIVED
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state != TelephonyManager.EXTRA_STATE_IDLE) return
                MonitoringService.ACTION_CALL_STATE
            }
            else -> return
        }
        val appContext = context.applicationContext
        val pending = try { goAsync() } catch (_: Exception) { null }
        try {
            executor.execute {
                try {
                    try { PreferencesManager.refreshInstance(appContext) } catch (_: Exception) { }
                    val active = try {
                        val prefs = PreferencesManager.getInstance(appContext)
                        prefs.isConfigured() && prefs.isMonitoringEnabled && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && !prefs.monitoringPaused
                    } catch (_: Exception) {
                        false
                    }
                    if (active) {
                        try {
                            val service = Intent(appContext, MonitoringService::class.java).apply { action = target }
                            ServiceStart.startForeground(appContext, service)
                        } catch (_: Exception) {
                            try {
                                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleBootRestart(appContext)
                            } catch (_: Exception) {
                            }
                        }
                    }
                } finally {
                    try { pending?.finish() } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            try { pending?.finish() } catch (_: Exception) {
            }
        }
    }
}
