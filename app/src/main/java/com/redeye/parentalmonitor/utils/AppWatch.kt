package com.redeye.parentalmonitor.utils

import java.util.Locale

object AppWatch {

    private val SPLIT = Regex("[,;\\s]+")

    private val SENSITIVE_HINTS = listOf(
        "bank", "pay", "wallet", "finance", "password", "authenticator",
        "1password", "lastpass", "bitwarden", "keepass", "crypto", "bitcoin", "binance",
        "browser", "chrome", "firefox", "opera", "duckduckgo", "settings", "systemui", "emmx", "ucmobile",
        "id.dana", "ovo.id", "com.gojek.app", "com.gojek.gopay", "com.shopee.id",
        "id.co.linkaja", "id.flip", "com.kredivo", "com.akulaku", "com.jago", "com.jenius",
        "id.co.seabank", "com.bca", "com.bni", "id.bni", "co.bri", "com.mandiri", "bmri",
        "com.cimb", "cimbniaga", "btpn", "com.danamon", "com.permatabank", "com.bankbkemobile",
        "com.bsm", "permatamobilex", "id.com.uiux", "ocbc", "alloapp", "panin", "muamalat", "hsbc",
        "com.isaku", "com.indodana"
    )

    fun parseKeywords(raw: String): List<String> {
        return try {
            SPLIT.split(raw.lowercase(Locale.ROOT))
                .map { it.trim() }
                .filter { it.length in 3..64 }
                .distinct()
                .take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    private val WORD_RUN = Regex("[\\p{L}\\p{N}]+")

    private val MATCH_PREFIXES = listOf("memper", "ber", "ter", "per", "me", "di", "pe", "ke")

    private val MATCH_SUFFIXES = setOf("nya", "an", "i", "s", "ing", "ed")

    private fun wordMatches(word: String, keyword: String): Boolean {
        if (stemMatches(word, 0, keyword)) return true
        for (prefix in MATCH_PREFIXES) {
            if (word.length > prefix.length && word.startsWith(prefix)) {
                if (stemMatches(word, prefix.length, keyword)) return true
            }
        }
        return false
    }

    private fun stemMatches(word: String, start: Int, keyword: String): Boolean {
        val stemLen = word.length - start
        if (stemLen < keyword.length) return false
        if (!word.regionMatches(start, keyword, 0, keyword.length)) return false
        if (stemLen == keyword.length) return true
        val restStart = start + keyword.length
        val restLen = stemLen - keyword.length
        for (suffix in MATCH_SUFFIXES) {
            if (suffix.length == restLen && word.regionMatches(restStart, suffix, 0, restLen)) return true
        }
        val last = keyword[keyword.length - 1]
        var i = restStart
        while (i < word.length) {
            if (word[i] != last) return false
            i++
        }
        return true
    }

    fun hit(keywords: List<String>, text: String): String? {
        if (keywords.isEmpty() || text.isEmpty()) return null
        return hitLowered(keywords, text.lowercase(Locale.ROOT))
    }

    fun hitLowered(keywords: List<String>, lower: String): String? {
        if (keywords.isEmpty() || lower.isEmpty()) return null
        var words: List<String>? = null
        return keywords.firstOrNull { raw ->
            val keyword = raw.lowercase(Locale.ROOT)
            if (keyword.isEmpty()) {
                false
            } else if (keyword.any { !it.isLetterOrDigit() }) {
                lower.contains(keyword)
            } else if (!lower.contains(keyword)) {
                false
            } else {
                val split = words ?: WORD_SPLIT.split(lower).filter { it.isNotEmpty() }.also { words = it }
                split.any { wordMatches(it, keyword) }
            }
        }
    }

    fun contextSnippet(keyword: String, text: String, radius: Int = 40): String? {
        if (keyword.isEmpty() || text.isEmpty()) return null
        val key = keyword.lowercase(Locale.ROOT)
        val start: Int
        val end: Int
        if (key.any { !it.isLetterOrDigit() }) {
            val idx = text.lowercase(Locale.ROOT).indexOf(key)
            if (idx < 0) return null
            start = idx
            end = idx + key.length
        } else {
            var range: IntRange? = null
            for (match in WORD_RUN.findAll(text)) {
                if (wordMatches(match.value.lowercase(Locale.ROOT), key)) {
                    range = match.range
                    break
                }
            }
            val found = range ?: return null
            start = found.first
            end = found.last + 1
        }
        val from = maxOf(0, start - radius)
        val to = minOf(text.length, end + radius)
        var snippet = text.substring(from, to).replace(Regex("\\s+"), " ").trim()
        if (from > 0) snippet = "\u2026" + snippet
        if (to < text.length) snippet += "\u2026"
        return snippet
    }

    fun isSensitivePackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        return SENSITIVE_HINTS.any { pkg.contains(it) }
    }
}
