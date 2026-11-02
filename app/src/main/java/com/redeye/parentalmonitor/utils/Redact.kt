package com.redeye.parentalmonitor.utils

object Redact {
    // Bentuk kanonik dan bentuk ter-encode URL (%3A, huruf besar/kecil) —
    // pesan galat yang memuat URL ber-token sebelumnya lolos fallback
    // (issue #707, sisa #724).
    private val TOKEN_FALLBACK = Regex("[0-9]{5,15}(?::|(?i:%3A))[A-Za-z0-9_-]{35,}")
    private val LINE_SPLIT = Regex("(?<=[A-Za-z0-9_:-])\r?\n(?=[A-Za-z0-9_:-])")

    fun token(value: String?, vararg secrets: String): String {
        val src: String = value ?: ""
        if (src.isEmpty()) return ""
        var out = src
        for (secret in secrets) {
            if (secret.isEmpty()) continue
            out = if (secret.length < 10 && secret.all { it.isDigit() }) replaceDigitBounded(out, secret) else out.replace(secret, "***")
        }
        if (!out.contains(':') && !out.contains("%3a", ignoreCase = true)) return out
        val direct = out.replace(TOKEN_FALLBACK, "***")
        // Token terbelah baris: satukan baris di antara karakter token lalu
        // coba lagi — dijalankan juga sesudah penggantian langsung berhasil,
        // agar token terbelah kedua dalam string yang sama tidak lolos
        // (issue #724). Teks hanya diubah bila pola token benar-benar cocok.
        val joined = direct.replace(LINE_SPLIT, "")
        val rejoined = joined.replace(TOKEN_FALLBACK, "***")
        return if (rejoined != joined) rejoined else direct
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
