package com.redeye.parentalmonitor.utils

object NotificationPrivacy {
    private val OTP_WORDS = arrayOf(
        "otp", "kode", "verifikasi", "verification", "otentikasi", "aktivasi", "konfirmasi",
        "token", "password", "kata sandi", "pin", "kode akses", "one time", "one-time",
        "code", "verify"
    )
    private val OTP_PATTERN = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(?:" +
            OTP_WORDS.joinToString("|") { Regex.escape(it) } +
            ")(?![\\p{L}\\p{N}])"
    )
    private val DIGIT_GROUPS = Regex("(?<!\\d)\\d(?:[ .-]?\\d){3,}(?![ .-]?\\d)")

    fun isOtp(text: String): Boolean = OTP_PATTERN.containsMatchIn(text)

    fun mask(text: String): String {
        return DIGIT_GROUPS.replace(text) { match ->
            match.value.map { if (it.isDigit()) '•' else it }.joinToString("")
        }
    }
}
