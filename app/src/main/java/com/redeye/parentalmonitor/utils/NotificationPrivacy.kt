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
            ")(?:(?![\\p{L}\\p{N}])|(?=\\d)|(?:" +
            OTP_WORDS.joinToString("|") { Regex.escape(it) } +
            ")(?:(?![\\p{L}\\p{N}])|(?=\\d)))"
    )
    private val DIGIT_GROUPS = Regex("(?<!\\d)\\d(?:[ .-]*\\d){3,}(?![ .-]*\\d)")
    private val CODE_TOKENS = Regex("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]{4,10}(?![\\p{L}\\p{N}])")

    fun isOtp(text: String): Boolean = OTP_PATTERN.containsMatchIn(text)

    fun hasCode(text: String): Boolean = DIGIT_GROUPS.containsMatchIn(text)

    private val MASK_TOKEN = "••••"

    fun mask(text: String): String {
        val digitsMasked = DIGIT_GROUPS.replace(text, MASK_TOKEN)
        return CODE_TOKENS.replace(digitsMasked) { match ->
            val token = match.value
            if (token.any { it.isLetter() } && token.any { it.isDigit() }) MASK_TOKEN else token
        }
    }
}
