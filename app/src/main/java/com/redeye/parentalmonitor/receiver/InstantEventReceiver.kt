package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService

class InstantEventReceiver : BroadcastReceiver() {

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
        val active = try {
            val prefs = PreferencesManager.getInstance(appContext)
            prefs.isConfigured() && prefs.isMonitoringEnabled && !prefs.userDisabledMonitoring
        } catch (_: Exception) {
            false
        }
        if (!active) return
        try {
            val service = Intent(appContext, MonitoringService::class.java).apply { action = target }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(service)
            } else {
                appContext.startService(service)
            }
        } catch (_: Exception) {
        }
    }
}
