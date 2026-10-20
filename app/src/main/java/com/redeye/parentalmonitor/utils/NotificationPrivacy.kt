package com.redeye.parentalmonitor.utils

object NotificationPrivacy {
    private val OTP_WORDS = arrayOf(
        "otp", "kode", "verifikasi", "verification", "otentikasi", "aktivasi", "konfirmasi",
        "token", "password", "kata sandi", "pin", "kode akses", "one time", "one-time"
    )
    private val DIGIT_GROUPS = Regex("\\b\\d{4,8}\\b")

    fun isOtp(text: String): Boolean {
        val low = text.lowercase()
        return OTP_WORDS.any { low.contains(it) }
    }

    fun mask(text: String): String {
        return DIGIT_GROUPS.replace(text) { match -> "•".repeat(match.value.length) }
    }
}
