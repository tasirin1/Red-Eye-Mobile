package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.Liveness

class LivenessReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Liveness.ACTION_ALARM) return
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        Background.runReceiver(pendingResult) {
            Liveness.handleWakeUp(appContext)
        }
    }
}
