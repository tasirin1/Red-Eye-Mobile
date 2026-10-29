package com.redeye.parentalmonitor.utils

import java.util.Locale

object AppWatch {

    private val SPLIT = Regex("[,;\\s]+")

    private val SENSITIVE_HINTS = listOf(
        "bank", "pay", "wallet", "finance", "password", "authenticator",
        "1password", "lastpass", "bitwarden", "keepass", "crypto", "bitcoin", "binance",
        "browser", "chrome", "firefox", "opera", "duckduckgo", "settings", "systemui", "emmx", "ucmobile",
        "id.dana", "com.ovo.id", "com.gojek.app", "com.gojek.gopay", "com.shopee.id",
        "id.co.linkaja", "com.kredivo", "com.akulaku", "com.jago", "com.jenius",
        "id.co.seabank", "com.bca", "com.bni", "com.bri", "com.mandiri", "com.cimb",
        "com.danamon", "com.permatabank", "com.bankbkemobile"
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

    private val MATCH_PREFIXES = listOf("memper", "ber", "ter", "per", "me", "di", "pe", "ke")

    private val MATCH_SUFFIXES = setOf("nya", "an", "i", "s", "ing", "ed")

    private fun wordMatches(word: String, keyword: String): Boolean {
        if (word == keyword) return true
        val stems = ArrayList<String>(MATCH_PREFIXES.size + 1)
        stems.add(word)
        for (prefix in MATCH_PREFIXES) {
            if (word.length > prefix.length && word.startsWith(prefix)) {
                stems.add(word.substring(prefix.length))
            }
        }
        for (stem in stems) {
            if (stem == keyword) return true
            if (stem.length > keyword.length && stem.startsWith(keyword)) {
                val rest = stem.substring(keyword.length)
                if (rest in MATCH_SUFFIXES) return true
                if (rest.all { it == keyword.last() }) return true
            }
        }
        return false
    }

    fun hit(keywords: List<String>, text: String): String? {
        if (keywords.isEmpty() || text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)
        val words = WORD_SPLIT.split(lower).filter { it.isNotEmpty() }
        return keywords.firstOrNull { raw ->
            val keyword = raw.lowercase(Locale.ROOT)
            if (keyword.isEmpty()) {
                false
            } else if (keyword.any { !it.isLetterOrDigit() }) {
                lower.contains(keyword)
            } else {
                words.any { wordMatches(it, keyword) }
            }
        }
    }

    fun isSensitivePackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        return SENSITIVE_HINTS.any { pkg.contains(it) }
    }
}
