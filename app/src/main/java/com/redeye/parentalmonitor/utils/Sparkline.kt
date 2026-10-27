package com.redeye.parentalmonitor.utils

object Sparkline {
    fun render(hours: IntArray): String {
        return try {
            val bars = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
            val peak = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
            buildString {
                for (h in hours) append(bars[((h * 7) / peak).coerceIn(0, 7)])
            }
        } catch (_: Exception) {
            ""
        }
    }
}
