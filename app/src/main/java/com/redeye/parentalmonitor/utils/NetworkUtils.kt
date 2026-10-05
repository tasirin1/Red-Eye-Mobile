package com.redeye.parentalmonitor.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.redeye.parentalmonitor.data.PreferencesManager

object NetworkUtils {

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
        if (errorBody.isNullOrEmpty() || !errorBody.contains("migrate_to_chat_id")) return null
        return try {
            val id = com.google.gson.JsonParser.parseString(errorBody)
                ?.asJsonObject
                ?.getAsJsonObject("parameters")
                ?.get("migrate_to_chat_id")
                ?.asLong
            if (id != null && id != 0L) id.toString() else null
        } catch (_: Exception) {
            null
        }
    }

    fun parseRetryAfter(errorBody: String?): Long {
        if (errorBody.isNullOrEmpty() || !errorBody.contains("retry_after")) return 5L
        return try {
            com.google.gson.JsonParser.parseString(errorBody)
                ?.asJsonObject
                ?.getAsJsonObject("parameters")
                ?.get("retry_after")
                ?.asLong
                ?.coerceIn(1, 300) ?: 5L
        } catch (e: Exception) {
            5L
        }
    }
}
