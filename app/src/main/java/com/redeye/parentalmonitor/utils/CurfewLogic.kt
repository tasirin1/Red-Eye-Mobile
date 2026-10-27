package com.redeye.parentalmonitor.utils

object CurfewLogic {
    private val TIME_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun inWindow(curfew: String, hour: Int, minute: Int): Boolean {
        if (!TIME_REGEX.matches(curfew)) return false
        val parts = curfew.split(":")
        val ch = parts[0].toIntOrNull() ?: -1
        val cm = parts[1].toIntOrNull() ?: -1
        val startMin = ch * 60 + cm
        val nowMin = hour * 60 + minute
        val morningEnd = 5 * 60
        return ch in 0..23 && cm in 0..59 && if (startMin >= 12 * 60) {
            nowMin >= startMin || nowMin < morningEnd
        } else {
            startMin < morningEnd && nowMin >= startMin && nowMin < morningEnd
        }
    }

    fun schoolHours(dayOfWeek: Int, hour: Int): Boolean {
        return dayOfWeek != java.util.Calendar.SATURDAY && dayOfWeek != java.util.Calendar.SUNDAY && hour in 8..15
    }
}
