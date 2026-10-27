package com.redeye.parentalmonitor.utils

object SmsNumbers {
    private val emergency = setOf("112", "911", "999", "110", "000", "111", "118", "119")

    fun isEmergency(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        var intl = digits
        if (intl.startsWith("011")) intl = intl.substring(3)
        else if (intl.startsWith("00")) intl = intl.substring(2)
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        return digits in emergency || intl in emergency || local in emergency
    }

    fun isPremium(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        var intl = digits
        if (intl.startsWith("011")) intl = intl.substring(3)
        else if (intl.startsWith("00")) intl = intl.substring(2)
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        if (digits in emergency || intl in emergency || local in emergency) return false
        if (local.startsWith("1900") || local.startsWith("900") || local.startsWith("976")) return true
        if (intl.startsWith("44")) {
            val uk = intl.substring(2)
            if (uk.startsWith("70") || uk.startsWith("90") || uk.startsWith("118") || uk.startsWith("09") || uk.startsWith("087") || uk.startsWith("084")) return true
        }
        if (intl.startsWith("1")) {
            if (intl.substring(1).startsWith("900")) return true
        }
        if (intl.startsWith("900")) return true
        if (local.length <= 6) {
            return true
        }
        return false
    }
}
