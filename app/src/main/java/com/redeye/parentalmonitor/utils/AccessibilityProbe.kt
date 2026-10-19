package com.redeye.parentalmonitor.utils

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object AccessibilityProbe {

    fun enabledIn(raw: String?, full: String, short: String): Boolean {
        if (raw.isNullOrEmpty()) return false
        for (part in raw.split(':')) {
            val item = part.trim()
            if (item.isEmpty()) continue
            if (item.equals(full, ignoreCase = true) || item.equals(short, ignoreCase = true)) return true
        }
        return false
    }

    fun isEnabled(context: Context, serviceClass: Class<*>): Boolean {
        return try {
            val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val full = ComponentName(context.applicationContext, serviceClass).flattenToString()
            val short = ComponentName(context.applicationContext, serviceClass).flattenToShortString()
            enabledIn(raw, full, short)
        } catch (_: Exception) {
            false
        }
    }
}
