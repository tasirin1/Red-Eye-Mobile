package com.redeye.parentalmonitor.utils

object ReportFormat {
    fun durationText(millis: Long): String {
        val minutes = (millis / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            days > 0 -> "$days d ${hours % 24} h"
            hours > 0 -> "$hours h ${minutes % 60} m"
            else -> "$minutes m"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        return try {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, out)
            out[0]
        } catch (_: Exception) {
            -1f
        }
    }

    fun isCameraPolicyError(reason: String?): Boolean {
        if (reason == null) return false
        return reason.contains("CAMERA_DISABLED", ignoreCase = true) ||
            reason.contains("disabled by policy", ignoreCase = true)
    }
}
