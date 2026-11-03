package com.redeye.parentalmonitor.utils

import java.util.Locale

object AppWatch {

    private val SPLIT = Regex("[,;\\s]+")

    private val SENSITIVE_HINTS = listOf(
        "bank", "pay", "wallet", "finance", "password", "authenticator",
        "lastpass", "bitwarden", "keepass", "crypto", "bitcoin", "binance",
        "browser", "chrome", "firefox", "opera", "duckduckgo", "settings", "systemui", "emmx", "ucmobile",
        "id.dana", "ovo.id", "com.gojek.app", "com.shopee.id",
        "id.co.linkaja", "id.flip", "com.kredivo", "com.akulaku", "com.jago", "com.jenius",
        "com.bca", "com.bni", "id.bni", "co.bri", "com.mandiri", "bmri",
        "com.cimb", "cimbniaga", "btpn", "com.danamon",
        "com.bsm", "permatamobilex", "id.com.uiux", "ocbc", "alloapp", "panin", "muamalat", "hsbc",
        "com.isaku", "com.indodana", "com.bibit", "com.stockbit", "ajaib.co.id",
        "emasdigi", "com.valar.pintu", "indopremier", "bareksa", "koinworks", "rekeningku", "tanamduit",
        "pegadaiandigital", "myhomecredit", "com.krom", "fintopia", "kreditpintar", "com.adakami", "amartha",
        "id.co.triv", "siminvest", "lakuemas", "gotrade", "miraeasset", "com.bmoney",
        "nanovest", "dana.rupiah", "pinjamango", "cmcm.uangme", "finplus", "pinjamduit", "id.makmur", "shopintar", "indosk", "cairin", "sucor", "mncsecurities", "tunaiku", "com.uatas", "modalku", "modalnasional", "revolut", "transferwise",
        "coinbase", "metamask", "kraken", "ledger",
        "venmo", "squareup",
        "authy", "dashlane", "aegis"
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

    // Batas teks yang dinilai pemindaian kata pantau (issue #857):
    // sejak #830 kata pantau dinilai atas teks node UTUH, satu node
    // raksasa membuat lowercase + belah kata setara ukurannya di
    // eksekutor A11y. Kata pantau adalah kata/frasa pendek di
    // konten yang tampak — kecocokan di kedalaman >8.192 karakter
    // dalam satu node bukan konten layar yang dimaksud pemilik.
    // Batasnya tinggal di hitLowered (issue #879) agar SEMUA pintu
    // masuk tertutup — dua situs NotificationForwarderService
    // sebelumnya memanggil hitLowered langsung atas teks gabungan
    // notifikasi tanpa batas.
    private const val MAX_HIT_TEXT_CHARS = 8192

    // Memo lowercase berbatas (issue #1096): pemindaian A11y
    // menilai teks node yang sama berulang kali antar
    // peristiwa pada layar yang sama; lowercase atas teks
    // utuh (s.d. 8.192 karakter) per node per pindai adalah
    // biaya berulang terbesarnya. Hanya teks pendek yang
    // di-memo (yang paling sering berulang); isi peta dibatasi
    // 32 entri LRU. Semantik hit() tidak berubah sama sekali.
    private val lowerCache = object : java.util.LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }

    private fun lowered(text: String): String {
        if (text.length > 512) return text.lowercase(Locale.ROOT)
        synchronized(lowerCache) { lowerCache[text]?.let { return it } }
        val hasil = text.lowercase(Locale.ROOT)
        synchronized(lowerCache) { lowerCache[text] = hasil }
        return hasil
    }

    fun hit(keywords: List<String>, text: String): String? {
        if (keywords.isEmpty() || text.isEmpty()) return null
        val bounded = if (text.length > MAX_HIT_TEXT_CHARS) text.substring(0, MAX_HIT_TEXT_CHARS) else text
        return hitLowered(keywords, lowered(bounded))
    }

    fun hitLowered(keywords: List<String>, lowerRaw: String): String? {
        if (keywords.isEmpty() || lowerRaw.isEmpty()) return null
        val lower = if (lowerRaw.length > MAX_HIT_TEXT_CHARS) lowerRaw.substring(0, MAX_HIT_TEXT_CHARS) else lowerRaw
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
