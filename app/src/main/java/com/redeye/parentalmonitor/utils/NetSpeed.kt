package com.redeye.parentalmonitor.utils

import android.net.TrafficStats
import java.util.Locale

object NetSpeed {

    fun totals(): Pair<Long, Long> {
        var rx = TrafficStats.getTotalRxBytes()
        var tx = TrafficStats.getTotalTxBytes()
        if (rx < 0 || tx < 0) {
            val uid = android.os.Process.myUid()
            rx = TrafficStats.getUidRxBytes(uid)
            tx = TrafficStats.getUidTxBytes(uid)
        }
        return rx to tx
    }

    fun splitRate(bytesPerSec: Long): Pair<String, String> {
        val v = bytesPerSec.coerceAtLeast(0).toDouble()
        return when {
            v < 1024 -> v.toInt().toString() to "B/s"
            v < 1024 * 1024 -> String.format(Locale.US, "%.1f", v / 1024) to "KB/s"
            else -> String.format(Locale.US, "%.2f", v / (1024 * 1024)) to "MB/s"
        }
    }

    fun formatRate(bytesPerSec: Long): String {
        val parts = splitRate(bytesPerSec)
        return parts.first + " " + parts.second
    }

    fun formatTotal(bytes: Long): String {
        val v = bytes.coerceAtLeast(0).toDouble()
        return when {
            v < 1024 -> v.toInt().toString() + " B"
            v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024)
            v < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024 * 1024))
            else -> String.format(Locale.US, "%.2f GB", v / (1024 * 1024 * 1024))
        }
    }
}
