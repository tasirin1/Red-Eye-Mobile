package com.redeye.parentalmonitor.utils

object Redact {
    private val TOKEN_FALLBACK = Regex("[0-9]{5,15}:[A-Za-z0-9_-]{20,}")

    fun token(value: String?, vararg secrets: String): String {
        val src: String = value ?: ""
        if (src.isEmpty()) return ""
        var out = src
        for (secret in secrets) {
            if (secret.isNotEmpty()) out = out.replace(secret, "***")
        }
        return out.replace(TOKEN_FALLBACK, "***")
    }
}
