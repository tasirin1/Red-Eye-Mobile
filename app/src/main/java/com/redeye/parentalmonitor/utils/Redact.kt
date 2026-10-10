package com.redeye.parentalmonitor.utils

object Redact {
    private val TOKEN_FALLBACK = Regex("[0-9]{5,15}:[A-Za-z0-9_-]{35,}")

    fun token(value: String?, vararg secrets: String): String {
        val src: String = value ?: ""
        if (src.isEmpty()) return ""
        var out = src
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            out = if (secret.length < 10 && secret.all { it.isDigit() }) replaceDigitBounded(out, secret) else out.replace(secret, "***")
        }
        if (!out.contains(':')) return out
        return out.replace(TOKEN_FALLBACK, "***")
    }

    private fun replaceDigitBounded(src: String, secret: String): String {
        var idx = src.indexOf(secret)
        if (idx < 0) return src
        val out = StringBuilder(src.length)
        var pos = 0
        while (idx >= 0) {
            val beforeOk = idx == 0 || !src[idx - 1].isDigit()
            val end = idx + secret.length
            val afterOk = end >= src.length || !src[end].isDigit()
            out.append(src, pos, idx)
            if (beforeOk && afterOk) out.append("***") else out.append(src, idx, end)
            pos = end
            idx = src.indexOf(secret, pos)
        }
        out.append(src, pos, src.length)
        return out.toString()
    }
}
