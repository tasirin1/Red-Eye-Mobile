package com.redeye.parentalmonitor.utils

import android.content.Context
import com.redeye.parentalmonitor.data.PreferencesManager

object RingVolume {
    private const val MAX_AGE_MS = 12 * 60 * 60_000L

    fun restoreStuck(context: Context, prefs: PreferencesManager): Boolean {
        val previous = try { prefs.ringPrevVolume } catch (_: Exception) { -1 }
        val savedAt = try { prefs.ringSavedAt } catch (_: Exception) { 0L }
        val age = System.currentTimeMillis() - savedAt
        var restored = false
        if (previous >= 0 && savedAt > 0L && age < MAX_AGE_MS) {
            try {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, previous, 0)
                restored = true
            } catch (_: Exception) {
            }
        }
        try {
            prefs.clearRingStateSync()
        } catch (_: Exception) {
        }
        return restored
    }
}
