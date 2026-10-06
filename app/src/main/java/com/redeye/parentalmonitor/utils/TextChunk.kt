package com.redeye.parentalmonitor.utils

object TextChunk {
    fun safeCut(text: String, max: Int): Int {
        if (max <= 0) return 0
        if (text.length <= max) return text.length
        var cut = max
        if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
        val amp = text.lastIndexOf('&', cut - 1)
        if (amp >= 0 && amp > cut - 12) {
            val semi = text.indexOf(';', amp)
            if (semi < 0 || semi >= cut) {
                val entity = text.substring(amp, cut)
                if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) cut = amp
            }
        }
        val tag = text.lastIndexOf('<', cut - 1)
        if (tag >= 0 && text.indexOf('>', tag) >= cut) cut = tag
        if (cut <= 0) cut = max
        return cut
    }
}
