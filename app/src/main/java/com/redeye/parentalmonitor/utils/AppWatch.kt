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

    fun hit(keywords: List<String>, text: String): String? {
        if (keywords.isEmpty() || text.isEmpty()) return null
        val lower = text.lowercase(Locale.ROOT)
        return keywords.firstOrNull { it.isNotEmpty() && lower.contains(it) }
    }

    fun isSensitivePackage(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        return SENSITIVE_HINTS.any { pkg.contains(it) }
    }
}
