package com.redeye.parentalmonitor.utils

object Sha256Hex {
    private const val HEX_DIGITS = "0123456789abcdef"

    fun hex(value: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            val hex = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val b = digest[i].toInt()
                hex[i * 2] = HEX_DIGITS[(b ushr 4) and 0xF]
                hex[i * 2 + 1] = HEX_DIGITS[b and 0xF]
            }
            String(hex)
        } catch (_: Exception) {
            value.hashCode().toString()
        }
    }
}
