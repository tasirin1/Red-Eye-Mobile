package com.redeye.parentalmonitor.utils

object Html {
    val tagStripRegex = Regex("</?[a-zA-Z][^>]*>")

    fun unescape(text: String): String {
        return text.replace(tagStripRegex, "").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'").replace("&amp;", "&")
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
