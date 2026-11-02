package com.redeye.parentalmonitor.utils

object GeofenceLogic {
    data class Transition(val writeState: Int?, val alert: Boolean)

    fun configured(latE6: Long, lonE6: Long, radiusM: Int): Boolean {
        return latE6 != Long.MIN_VALUE && lonE6 != Long.MIN_VALUE && radiusM > 0
    }

    fun jemputHit(jemputRadiusM: Int, distM: Float, accuracyM: Float): Boolean {
        return jemputRadiusM > 0 && distM <= jemputRadiusM && (accuracyM < 0f || accuracyM <= jemputRadiusM.toFloat())
    }

    fun judgedOutside(distM: Float, radiusM: Int, accuracyM: Float): Boolean {
        return distM >= 0f && distM > radiusM && (accuracyM < 0f || accuracyM <= radiusM.toFloat())
    }

    fun statusText(latE6: Long, lonE6: Long, radiusM: Int): String {
        if (latE6 == Long.MIN_VALUE || lonE6 == Long.MIN_VALUE || radiusM <= 0) return "mati"
        return "${latE6 / 1_000_000.0},${lonE6 / 1_000_000.0} ±${radiusM}m"
    }

    fun insideWithHysteresis(state: Int, distM: Float, radiusM: Int): Boolean {
        val exitMargin = maxOf(30f, radiusM * 0.15f)
        return if (state == 1) distM <= radiusM + exitMargin else distM <= radiusM
    }

    fun transition(state: Int, inside: Boolean, accuracyM: Float, radiusM: Int): Transition {
        val now = if (inside) 1 else 2
        val adopting = state !in 1..2
        val accurate = accuracyM < 0f || accuracyM <= (if (adopting) radiusM else radiusM / 2).toFloat()
        return when {
            adopting -> if (accurate) Transition(now, false) else Transition(null, false)
            state != now && accurate -> Transition(now, true)
            else -> Transition(null, false)
        }
    }
}
