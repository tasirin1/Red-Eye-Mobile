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

    fun nightOverlapMs(startMs: Long, endMs: Long, offsetAt: (Long) -> Int): Long {
        if (endMs <= startMs) return 0L
        var total = 0L
        var cursor = startMs
        while (cursor < endMs) {
            val offsetMs = offsetAt(cursor)
            val hour = localHour(cursor, offsetMs)
            var segmentEnd = minOf(endMs, nextHourBoundary(cursor, offsetMs))
            if (segmentEnd <= cursor) segmentEnd = endMs
            val endOffset = if (segmentEnd > cursor + 1) offsetAt(segmentEnd - 1) else offsetMs
            if (endOffset != offsetMs) {
                val changeAt = firstOffsetChange(cursor, segmentEnd, offsetMs, offsetAt)
                if (changeAt != null) segmentEnd = changeAt
            }
            if (hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR) total += segmentEnd - cursor
            cursor = segmentEnd
        }
        return total
    }

    private fun firstOffsetChange(fromMs: Long, untilMs: Long, baseOffset: Int, offsetAt: (Long) -> Int): Long? {
        var lo = fromMs + 1
        var hi = untilMs - 1
        if (offsetAt(hi) == baseOffset) return null
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (offsetAt(mid) == baseOffset) lo = mid + 1 else hi = mid
        }
        return lo
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

    fun previousDayStartWallMillis(nowWall: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = dayStartWallMillis(nowWall)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    fun splitBoundaryWallMillis(sessionStartWall: Long, todayStartWall: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = dayStartWallMillis(sessionStartWall)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val nextDayStart = cal.timeInMillis
        return if (nextDayStart < todayStartWall) nextDayStart else todayStartWall
    }

    private fun nextHourBoundary(timestamp: Long, offsetMs: Int): Long {
        val hourMs = 3_600_000L
        val shifted = timestamp + offsetMs
        return ((shifted / hourMs) + 1) * hourMs - offsetMs
    }
}
