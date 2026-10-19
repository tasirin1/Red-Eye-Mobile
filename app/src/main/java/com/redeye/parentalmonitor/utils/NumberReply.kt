package com.redeye.parentalmonitor.utils

object NumberReply {
    fun parse(text: String): Int? {
        val teks = text.trim()
        if (teks.isEmpty() || teks.length > 6) return null
        if (!teks.all { it.isDigit() }) return null
        return teks.toIntOrNull()
    }
}
