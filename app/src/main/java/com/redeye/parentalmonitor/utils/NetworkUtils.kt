package com.redeye.parentalmonitor.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
