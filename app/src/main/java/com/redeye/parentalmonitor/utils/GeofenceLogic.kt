package com.redeye.parentalmonitor.utils

object GeofenceLogic {
    data class Transition(val writeState: Int?, val alert: Boolean)

    fun configured(latE6: Long, lonE6: Long, radiusM: Int): Boolean {
        return latE6 != Long.MIN_VALUE && lonE6 != Long.MIN_VALUE && radiusM > 0
    }

    fun jemputHit(jemputRadiusM: Int, distM: Float, accuracyM: Float): Boolean {
        return jemputRadiusM > 0 && distM <= jemputRadiusM && (accuracyM < 0f || accuracyM <= jemputRadiusM.toFloat())
    }

    fun statusText(latE6: Long, lonE6: Long, radiusM: Int): String {
        if (latE6 == Long.MIN_VALUE || radiusM <= 0) return "mati"
        return "${latE6 / 1_000_000.0},${lonE6 / 1_000_000.0} ±${radiusM}m"
    }

    fun transition(state: Int, inside: Boolean, accuracyM: Float, radiusM: Int): Transition {
        val now = if (inside) 1 else 2
        return when {
            state !in 1..2 -> Transition(now, false)
            state != now && (accuracyM < 0f || accuracyM <= radiusM.toFloat()) -> Transition(now, true)
            else -> Transition(null, false)
        }
    }
}
