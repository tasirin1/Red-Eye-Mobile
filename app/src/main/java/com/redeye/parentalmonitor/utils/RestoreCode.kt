package com.redeye.parentalmonitor.utils

import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RestoreCode {
    const val WINDOW_MS = 10 * 60_000L

    fun generateSecret(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }

    fun current(secretHex: String, now: Long): String {
        val key = hexToBytes(secretHex)
        if (key.isEmpty()) return ""
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            val counter = (now / WINDOW_MS).toString().toByteArray(Charsets.UTF_8)
            val digest = mac.doFinal(counter)
            var value = 0L
            for (i in 0 until 4) {
                value = (value shl 8) or (digest[i].toLong() and 0xFFL)
            }
            String.format(Locale.ROOT, "%06d", value % 1_000_000L)
        } catch (_: Exception) {
            ""
        }
    }

    fun isValid(secretHex: String, code: String, now: Long): Boolean {
        val value = code.trim()
        if (value.length != 6 || value.any { it !in '0'..'9' }) return false
        return value == current(secretHex, now) || value == current(secretHex, now - WINDOW_MS)
    }

    fun windowEnd(now: Long): Long = (now / WINDOW_MS + 1L) * WINDOW_MS

    private fun hexToBytes(value: String): ByteArray {
        if (value.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(value.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(value[i * 2], 16)
            val lo = Character.digit(value[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
