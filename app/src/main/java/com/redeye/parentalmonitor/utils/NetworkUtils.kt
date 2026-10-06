package com.redeye.parentalmonitor.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.redeye.parentalmonitor.data.PreferencesManager

object NetworkUtils {

    @Volatile
    var rateLimitedUntil = 0L

    fun rateLimitedRemainMs(): Long {
        return try {
            (rateLimitedUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    fun noteRateLimited(retryAfterSecs: Long) {
        try {
            val waitMs = retryAfterSecs.coerceIn(1L, 300L) * 1000L
            rateLimitedUntil = maxOf(rateLimitedUntil, android.os.SystemClock.elapsedRealtime() + waitMs)
        } catch (_: Exception) {
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }

    fun isChatMissing(errorBody: String?): Boolean {
        if (errorBody.isNullOrEmpty()) return false
        val lower = errorBody.lowercase(java.util.Locale.ROOT)
        return lower.contains("chat not found") || lower.contains("bot was blocked") || lower.contains("user not found") || lower.contains("group chat was deleted") || lower.contains("group chat was upgraded") || lower.contains("chat_id is empty") || lower.contains("bot was kicked") || lower.contains("not a member")
    }

    fun isRightsLimited(errorBody: String?): Boolean {
        if (errorBody.isNullOrEmpty()) return false
        val lower = errorBody.lowercase(java.util.Locale.ROOT)
        return lower.contains("not enough rights") || lower.contains("have no rights") || lower.contains("need administrator rights") || lower.contains("not enough rights to send") || lower.contains("need administrator rights in the channel")
    }

    fun isAuthBlocked(prefs: PreferencesManager): Boolean {
        return try {
            val err = prefs.credentialError
            if (err != "401" && err != "403" && err != "400") return false
            val now = System.currentTimeMillis()
            if (now < prefs.credentialErrorAt) {
                prefs.credentialError = ""
                prefs.credentialErrorAt = 0L
                return false
            }
            now - prefs.credentialErrorAt < 30 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    fun sweepAuthBlock(prefs: PreferencesManager) {
        try {
            if (prefs.credentialError.isEmpty()) return
            val now = System.currentTimeMillis()
            if (now < prefs.credentialErrorAt || now - prefs.credentialErrorAt >= 30 * 60_000L) {
                prefs.credentialError = ""
                prefs.credentialErrorAt = 0L
            }
        } catch (_: Exception) {
        }
    }

    fun extractMigratedChatId(errorBody: String?): String? {
        if (errorBody.isNullOrEmpty()) return null
        if (errorBody.contains("migrate_to_chat_id")) {
            try {
                val id = com.google.gson.JsonParser.parseString(errorBody)
                    ?.asJsonObject
                    ?.getAsJsonObject("parameters")
                    ?.get("migrate_to_chat_id")
                    ?.asLong
                if (id != null && id != 0L) return id.toString()
            } catch (_: Exception) {
            }
            try {
                val fallback = Regex("\"migrate_to_chat_id\"\\s*:\\s*(-?\\d+)").find(errorBody)?.groupValues?.getOrNull(1)
                if (fallback != null && fallback != "0") return fallback
            } catch (_: Exception) {
            }
        }
        try {
            val legacy = Regex("(?i)new chat id\\s*:\\s*(-?\\d+)").find(errorBody)?.groupValues?.getOrNull(1)
            if (legacy != null && legacy != "0") return legacy
        } catch (_: Exception) {
        }
        return null
    }

    fun parseRetryAfter(errorBody: String?): Long {
        if (errorBody.isNullOrEmpty()) return 5L
        try {
            val parsed = com.google.gson.JsonParser.parseString(errorBody)
                ?.asJsonObject
                ?.getAsJsonObject("parameters")
                ?.get("retry_after")
                ?.asLong
            if (parsed != null) return parsed.coerceIn(1, 300)
        } catch (_: Exception) {
        }
        try {
            val fallback = Regex("(?i)retry[_ ]after\\D*(\\d+)").find(errorBody)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (fallback != null) return fallback.coerceIn(1, 300)
        } catch (_: Exception) {
        }
        return 5L
    }
}
