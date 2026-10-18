package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.Liveness

class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        Background.runCritical {
            try {
                Liveness.handleWakeUp(appContext)
            } catch (_: Exception) {
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {
                }
            }
        }
    }
}
