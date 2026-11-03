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
    // Pemisah grup mencakup koma/garis-miring/titik-dua (issue
    // #912): format "123,456", "123/456", "123:456" dari bank/
    // aplikasi dulu lolos karena kelas hanya [ .-]. Garis-bawah
    // ditambahkan pada issue #955: "123_456" juga format OTP
    // nyata yang dulu lolos utuh. Konsekuensi yang diterima:
    // jam "12:30"/tanggal "12/05/2026" di dalam notifikasi
    // konteks-OTP ikut tertopeng (arah aman privasi).
    private val DIGIT_GROUPS = Regex("(?<!\\d)\\d(?:[ ,./:_-]*\\d){3,}(?![ ,./:_-]*\\d)")
    private val CODE_TOKENS = Regex("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]{4,16}(?![\\p{L}\\p{N}])")
    // Kode alfanumerik bergrup-strip (AB-12-CD-34): potongan per grup terlalu
    // pendek untuk kedua pola di atas sehingga kode lolos utuh (issue #660).
    // Pemisah diperluas ke titik/garis-bawah/titik-dua/campuran (issue #694).
    private val GROUPED_CODES = Regex("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]{2,8}(?:[-._:][\\p{L}\\p{N}]{2,8}){2,3}(?![\\p{L}\\p{N}])")
    // Varian pemisah spasi hanya untuk grup yang SETIAP grupnya campuran
    // huruf+angka (AB12 CD34 EF56) — grup murni berspasi (alamat
    // "RT 04 RW 05", "AB 12 CD 34") sengaja tidak ditopeng (issue #694).
    private val GROUPED_CODES_SPACED = Regex("(?<![\\p{L}\\p{N}])(?:(?=[\\p{L}\\p{N}]*\\p{L})(?=[\\p{L}\\p{N}]*\\p{N})[\\p{L}\\p{N}]{2,8})(?: (?:(?=[\\p{L}\\p{N}]*\\p{L})(?=[\\p{L}\\p{N}]*\\p{N})[\\p{L}\\p{N}]{2,8})){2,3}(?![\\p{L}\\p{N}])")

    fun isOtp(text: String): Boolean = OTP_PATTERN.containsMatchIn(text)

    fun hasCode(text: String): Boolean = DIGIT_GROUPS.containsMatchIn(text)

    private val MASK_TOKEN = "••••"

    fun mask(text: String): String {
        val groupedMasked = GROUPED_CODES_SPACED.replace(
            GROUPED_CODES.replace(text) { match ->
                val alnum = match.value.filter { it.isLetterOrDigit() }
                if (alnum.length >= 6 && alnum.any { it.isLetter() } && alnum.any { it.isDigit() }) MASK_TOKEN else match.value
            },
            MASK_TOKEN
        )
        val digitsMasked = DIGIT_GROUPS.replace(groupedMasked, MASK_TOKEN)
        return CODE_TOKENS.replace(digitsMasked) { match ->
            val token = match.value
            if (token.any { it.isLetter() } && token.any { it.isDigit() }) MASK_TOKEN else token
        }
    }
}
