package com.redeye.parentalmonitor.utils

object A11yUsageMath {

    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6

    fun localHour(timestamp: Long, offsetMs: Int): Int {
        val dayMs = 24L * 60L * 60_000L
        val shifted = timestamp + offsetMs
        val inDay = ((shifted % dayMs) + dayMs) % dayMs
        return (inDay / 3_600_000L).toInt()
    }

    fun nightOverlapMs(startMs: Long, endMs: Long, offsetMs: Int): Long {
        if (endMs <= startMs) return 0L
        var total = 0L
        var cursor = startMs
        while (cursor < endMs) {
            val hour = localHour(cursor, offsetMs)
            val next = nextHourBoundary(cursor, offsetMs)
            val segmentEnd = if (next <= cursor) endMs else minOf(endMs, next)
            if (hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR) total += segmentEnd - cursor
            cursor = segmentEnd
        }
        return total
    }

    fun dayStartWallMillis(nowWall: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowWall
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nextHourBoundary(timestamp: Long, offsetMs: Int): Long {
        val hourMs = 3_600_000L
        val shifted = timestamp + offsetMs
        return ((shifted / hourMs) + 1) * hourMs - offsetMs
    }
}
