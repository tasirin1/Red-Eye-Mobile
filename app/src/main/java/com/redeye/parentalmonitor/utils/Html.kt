package com.redeye.parentalmonitor.utils

object Html {
    private val tagStripRegex = Regex("</?[a-zA-Z][^>]*>")

    fun unescape(text: String): String {
        if (text.isEmpty()) return text
        val noTags = if (text.indexOf('<') >= 0) text.replace(tagStripRegex, "") else text
        if (noTags.indexOf('&') < 0) return noTags
        val out = StringBuilder(noTags.length)
        var i = 0
        val n = noTags.length
        while (i < n) {
            val c = noTags[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semi = noTags.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 7) {
                out.append(c)
                i++
                continue
            }
            when (noTags.substring(i, semi + 1)) {
                "&lt;" -> { out.append('<'); i = semi + 1 }
                "&gt;" -> { out.append('>'); i = semi + 1 }
                "&amp;" -> { out.append('&'); i = semi + 1 }
                "&quot;" -> { out.append('"'); i = semi + 1 }
                "&#39;" -> { out.append('\''); i = semi + 1 }
                "&#x27;" -> { out.append('\''); i = semi + 1 }
                "&apos;" -> { out.append('\''); i = semi + 1 }
                "&nbsp;" -> { out.append(' '); i = semi + 1 }
                "&#60;" -> { out.append('<'); i = semi + 1 }
                "&#62;" -> { out.append('>'); i = semi + 1 }
                "&#38;" -> { out.append('&'); i = semi + 1 }
                "&#34;" -> { out.append('"'); i = semi + 1 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
