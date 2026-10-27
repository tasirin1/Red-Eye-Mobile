package com.redeye.parentalmonitor.utils

object NumberReply {
    fun parse(text: String): Int? {
        val teks = text.trim()
        if (teks.isEmpty() || teks.length > 6) return null
        if (!teks.all { it.isDigit() }) return null
        return teks.toIntOrNull()
    }

    fun inRange(value: Int, range: String): Boolean {
        val parts = range.split("-")
        if (parts.size != 2) return true
        val min = parts[0].trim().toIntOrNull() ?: return true
        val max = parts[1].trim().toIntOrNull() ?: return true
        return value in min..max
    }
}
