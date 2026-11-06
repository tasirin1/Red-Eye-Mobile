package com.redeye.parentalmonitor.utils

import android.content.Context
import android.content.Intent

object ServiceStart {
    fun startForeground(context: Context, intent: Intent) {
        context.startForegroundService(intent)
    }
}
