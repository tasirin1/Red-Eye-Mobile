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

    fun nightOverlapMs(startMs: Long, endMs: Long, offsetAt: (Long) -> Int): Long =
        nightOverlapInternal(startMs, endMs, offsetAt, lateOnly = false)

    // Bagian malam yang jatuh pada 22.00-24.00 SAJA
    // (issue #1607): jendela malam 22.00-06.00 menyeberangi
    // tengah malam, jadi total satu malam utuh = bagian
    // akhir hari kemarin + bagian awal (00.00-06.00) hari
    // ini. Tanpa pemisahan ini laporan pagi hanya memuat
    // paruh sesudah tengah malam.
    fun nightLateOverlapMs(startMs: Long, endMs: Long, offsetAt: (Long) -> Int): Long =
        nightOverlapInternal(startMs, endMs, offsetAt, lateOnly = true)

    private fun nightOverlapInternal(startMs: Long, endMs: Long, offsetAt: (Long) -> Int, lateOnly: Boolean): Long {
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
            if (if (lateOnly) hour >= NIGHT_START_HOUR else (hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR)) total += segmentEnd - cursor
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

    // Zona waktu diteruskan pemanggil (issue #1619):
    // fungsi batas hari dulu membaca zona GLOBAL
    // untuk cap waktu lampau — kini eksplisit seperti
    // fungsi malam yang berparameter offset, dan uji
    // tak lagi mengubah TimeZone.setDefault global.
    fun dayStartWallMillis(nowWall: Long, zone: java.util.TimeZone): Long {
        val cal = java.util.Calendar.getInstance(zone)
        cal.timeInMillis = nowWall
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun previousDayStartWallMillis(nowWall: Long, zone: java.util.TimeZone): Long {
        val cal = java.util.Calendar.getInstance(zone)
        cal.timeInMillis = dayStartWallMillis(nowWall, zone)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    fun splitBoundaryWallMillis(sessionStartWall: Long, todayStartWall: Long, zone: java.util.TimeZone): Long {
        val cal = java.util.Calendar.getInstance(zone)
        cal.timeInMillis = dayStartWallMillis(sessionStartWall, zone)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        val nextDayStart = cal.timeInMillis
        return if (nextDayStart < todayStartWall) nextDayStart else todayStartWall
    }

    fun splitBoundariesWallMillis(sessionStartWall: Long, todayStartWall: Long, zone: java.util.TimeZone): List<Long> {
        val out = ArrayList<Long>()
        var cursor = sessionStartWall
        while (true) {
            val boundary = splitBoundaryWallMillis(cursor, todayStartWall, zone)
            out.add(boundary)
            if (boundary >= todayStartWall) break
            cursor = boundary
        }
        return out
    }

    private fun nextHourBoundary(timestamp: Long, offsetMs: Int): Long {
        val hourMs = 3_600_000L
        val shifted = timestamp + offsetMs
        return ((shifted / hourMs) + 1) * hourMs - offsetMs
    }
}
