package com.redeye.parentalmonitor.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.redeye.parentalmonitor.data.PreferencesManager

object NetworkUtils {

    private val MIGRATE_ID_REGEX = Regex("\"migrate_to_chat_id\"\\s*:\\s*(-?\\d+)")
    private val LEGACY_CHAT_ID_REGEX = Regex("(?i)new chat id\\s*:\\s*(-?\\d+)")
    private val RETRY_AFTER_REGEX = Regex("(?i)retry[_ ]after\\D*(\\d+)")

    private const val PREFS_NAME = "network_state"
    private const val KEY_RATE_LIMITED_UNTIL = "rate_limited_until_wall"
    private const val MAX_RATE_LIMIT_MS = 3600_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var rateLimitedUntil = 0L

    fun init(context: Context) {
        try {
            appContext = context.applicationContext ?: context
        } catch (_: Exception) {
        }
    }

    fun rateLimitedRemainMs(): Long {
        return try {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val mem = (rateLimitedUntil - nowElapsed).coerceAtLeast(0L)
            if (mem > 0L) return mem
            val stored = storedRateLimitRemainMs()
            if (stored > 0L) rateLimitedUntil = nowElapsed + stored
            stored
        } catch (_: Exception) {
            0L
        }
    }

    private fun storedRateLimitRemainMs(): Long {
        val context = appContext ?: return 0L
        val until = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_RATE_LIMITED_UNTIL, 0L)
        } catch (_: Exception) {
            0L
        }
        if (until <= 0L) return 0L
        return (until - System.currentTimeMillis()).coerceIn(0L, MAX_RATE_LIMIT_MS)
    }

    fun noteRateLimited(retryAfterSecs: Long) {
        try {
            val waitMs = retryAfterSecs.coerceIn(1L, 3600L) * 1000L
            rateLimitedUntil = maxOf(rateLimitedUntil, android.os.SystemClock.elapsedRealtime() + waitMs)
            val context = appContext ?: return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val untilWall = System.currentTimeMillis() + waitMs
            if (untilWall > prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)) {
                prefs.edit().putLong(KEY_RATE_LIMITED_UNTIL, untilWall).apply()
            }
        } catch (_: Exception) {
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    fun isChatMissing(errorBody: String?): Boolean {
        if (errorBody.isNullOrEmpty()) return false
        return errorBody.contains("chat not found", ignoreCase = true) || errorBody.contains("bot was blocked", ignoreCase = true) || errorBody.contains("user not found", ignoreCase = true) || errorBody.contains("group chat was deleted", ignoreCase = true) || errorBody.contains("group chat was upgraded", ignoreCase = true) || errorBody.contains("chat_id is empty", ignoreCase = true) || errorBody.contains("bot was kicked", ignoreCase = true) || errorBody.contains("not a member", ignoreCase = true)
    }

    fun isRightsLimited(errorBody: String?): Boolean {
        if (errorBody.isNullOrEmpty()) return false
        return errorBody.contains("not enough rights", ignoreCase = true) || errorBody.contains("have no rights", ignoreCase = true) || errorBody.contains("need administrator rights", ignoreCase = true)
    }

    fun isAuthBlocked(prefs: PreferencesManager): Boolean {
        return try {
            val err = prefs.credentialError
            if (err != "400" && err != "401" && err != "403") return false
            val now = System.currentTimeMillis()
            val at = prefs.credentialErrorAt
            if (now < at) return false
            now - at < 30 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    fun isPollBlocked(prefs: PreferencesManager): Boolean {
        return try {
            val err = prefs.credentialError
            if (err != "401" && err != "403") return false
            val now = System.currentTimeMillis()
            val at = prefs.credentialErrorAt
            if (now < at) return false
            val window = if (err == "403") 5 * 60_000L else 30 * 60_000L
            now - at < window
        } catch (_: Exception) {
            false
        }
    }

    fun sweepAuthBlock(prefs: PreferencesManager) {
        try {
            if (prefs.credentialError.isEmpty()) return
            val now = System.currentTimeMillis()
            val at = prefs.credentialErrorAt
            if (now < at || now - at >= 30 * 60_000L) {
                prefs.setCredentialError("", 0L)
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
                val fallback = MIGRATE_ID_REGEX.find(errorBody)?.groupValues?.getOrNull(1)
                if (fallback != null && fallback != "0") return fallback
            } catch (_: Exception) {
            }
        }
        try {
            val legacy = LEGACY_CHAT_ID_REGEX.find(errorBody)?.groupValues?.getOrNull(1)
            if (legacy != null && legacy != "0") return legacy
        } catch (_: Exception) {
        }
        return null
    }

    val MIGRATED_CHAT_NOTICE = "\u267B\uFE0F Grup ditingkatkan jadi supergrup \u2014 Chat ID diperbarui otomatis."

    fun adoptMigratedChat(
        prefs: PreferencesManager,
        errorBody: String?,
        currentChatId: String,
        refresh: () -> Unit,
        announce: () -> Unit
    ): Boolean {
        val migrated = extractMigratedChatId(errorBody) ?: return false
        val stored = try { prefs.chatId } catch (_: Exception) { currentChatId }
        if (migrated == stored) {
            try {
                prefs.setCredentialError("", 0L)
                refresh()
            } catch (_: Exception) {
            }
            return true
        }
        if (migrated == currentChatId) return false
        return try {
            if (!prefs.setChatIdSync(migrated)) return false
            prefs.setCredentialError("", 0L)
            refresh()
            try { announce() } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun parseRetryAfter(errorBody: String?): Long {
        if (errorBody.isNullOrEmpty()) return 5L
        try {
            val parsed = com.google.gson.JsonParser.parseString(errorBody)
                ?.asJsonObject
                ?.getAsJsonObject("parameters")
                ?.get("retry_after")
                ?.asLong
            if (parsed != null) return parsed.coerceIn(1, 3600)
        } catch (_: Exception) {
        }
        try {
            val fallback = RETRY_AFTER_REGEX.find(errorBody)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (fallback != null) return fallback.coerceIn(1, 3600)
        } catch (_: Exception) {
        }
        return 5L
    }
}
