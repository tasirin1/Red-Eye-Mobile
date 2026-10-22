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
        val audioManager = try {
            context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        } catch (_: Exception) {
            null
        }
        val maxVolume = try {
            audioManager?.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM) ?: -1
        } catch (_: Exception) {
            -1
        }
        val currentVolume = try {
            audioManager?.getStreamVolume(android.media.AudioManager.STREAM_ALARM) ?: -1
        } catch (_: Exception) {
            -1
        }
        val usable = previous >= 0 && savedAt > 0L &&
            (age in 0..MAX_AGE_MS || (maxVolume > 0 && currentVolume >= maxVolume))
        if (usable && audioManager != null) {
            try {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, previous, 0)
                restored = true
            } catch (_: Exception) {
            }
        }
        if (restored || !usable) {
            try {
                prefs.clearRingStateSync()
            } catch (_: Exception) {
            }
        }
        return restored
    }
}
