package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context.applicationContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("PreferencesManager", "Encrypted prefs unavailable, using PLAINTEXT fallback storage", e)
        context.applicationContext.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_LAST_SMS_ID = "last_sms_id"
        private const val KEY_LAST_CALL_TIMESTAMP = "last_call_timestamp"
    }

    var botToken: String
        get() = sharedPreferences.getString(KEY_BOT_TOKEN, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_BOT_TOKEN, value).apply()

    var chatId: String
        get() = sharedPreferences.getString(KEY_CHAT_ID, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_CHAT_ID, value).apply()

    var isMonitoringEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    var lastSyncTime: Long
        get() = sharedPreferences.getLong(KEY_LAST_SYNC, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncInterval: Int
        get() = sharedPreferences.getInt(KEY_SYNC_INTERVAL, 60) // default 60 minutes
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    var lastSmsId: Long
        get() = sharedPreferences.getLong(KEY_LAST_SMS_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SMS_ID, value).apply()

    var lastCallTimestamp: Long
        get() = sharedPreferences.getLong(KEY_LAST_CALL_TIMESTAMP, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CALL_TIMESTAMP, value).apply()

    fun isConfigured(): Boolean {
        return botToken.isNotEmpty() && chatId.isNotEmpty()
    }
}

