package com.redeye.parentalmonitor.utils

object TrailStats {

    data class Point(val at: Long, val lat: Double, val lon: Double)

    data class Stay(val lat: Double, val lon: Double, val millis: Long)

    fun distanceMeters(points: List<Point>, maxGapMs: Long = 20 * 60_000L): Float {
        if (points.size < 2) return 0f
        var total = 0.0
        for (i in 1 until points.size) {
            val gap = points[i].at - points[i - 1].at
            if (gap <= 0L || gap > maxGapMs) continue
            total += meters(points[i - 1], points[i])
        }
        return total.toFloat()
    }

    fun longestStay(points: List<Point>, radiusM: Float = 150f, maxGapMs: Long = 20 * 60_000L): Stay? {
        if (points.size < 2) return null
        var best: Stay? = null
        var i = 0
        while (i < points.size - 1) {
            var j = i + 1
            var endAt = points[i].at
            while (j < points.size) {
                if (points[j].at - points[j - 1].at > maxGapMs) break
                if (meters(points[i], points[j]) > radiusM) break
                endAt = points[j].at
                j++
            }
            val millis = endAt - points[i].at
            if (millis > (best?.millis ?: 0L)) best = Stay(points[i].lat, points[i].lon, millis)
            i = if (j > i + 1) j - 1 else i + 1
        }
        return best
    }

    fun millisInside(
        points: List<Point>,
        lat: Double,
        lon: Double,
        radiusM: Float,
        maxGapMs: Long = 20 * 60_000L
    ): Long {
        if (points.size < 2 || radiusM <= 0f) return 0L
        val center = Point(0L, lat, lon)
        var total = 0L
        for (i in 1 until points.size) {
            val gap = points[i].at - points[i - 1].at
            if (gap <= 0L || gap > maxGapMs) continue
            val before = meters(center, points[i - 1])
            val after = meters(center, points[i])
            val beforeInside = before <= radiusM
            val afterInside = after <= radiusM
            if (beforeInside && afterInside) {
                total += gap
            } else if (beforeInside != afterInside) {
                val fraction = ((radiusM - before) / (after - before)).coerceIn(0.0, 1.0)
                total += (gap * (if (beforeInside) fraction else 1.0 - fraction)).toLong()
            }
        }
        return total
    }

    fun kilometers(distanceMeters: Float): Float = distanceMeters / 1000f

    private fun meters(a: Point, b: Point): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(la1) * Math.cos(la2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * earth * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }
}
