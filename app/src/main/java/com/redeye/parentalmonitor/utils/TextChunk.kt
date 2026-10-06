package com.redeye.parentalmonitor.utils

object TextChunk {
    fun safeCut(text: String, max: Int): Int {
        if (max <= 0) return 0
        if (text.length <= max) return text.length
        fun scan(cut: Int): Int {
            var c = cut
            if (c > 0 && c < text.length && Character.isHighSurrogate(text[c - 1]) && Character.isLowSurrogate(text[c])) c -= 1
            val amp = text.lastIndexOf('&', c - 1)
            if (amp >= 0 && amp > c - 12) {
                val semi = text.indexOf(';', amp)
                if (semi < 0 || semi >= c) {
                    val entity = text.substring(amp, c)
                    if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) c = amp
                }
            }
            val tag = text.lastIndexOf('<', c - 1)
            if (tag >= 0 && text.indexOf('>', tag) >= c) c = tag
            return c
        }
        var cut = scan(max)
        if (cut <= 0) {
            cut = max.coerceAtLeast(1)
            if (cut > 0 && cut < text.length && Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
            if (cut <= 0) cut = 1
        }
        return cut
    }
}
