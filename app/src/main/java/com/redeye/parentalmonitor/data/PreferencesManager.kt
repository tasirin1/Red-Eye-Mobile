@file:Suppress("DEPRECATION")

package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey = getMasterKey(appContext)

    private var storageEncrypted = true

    private val sharedPreferences: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            appContext,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        try { appContext.deleteSharedPreferences("secure_prefs_fallback") } catch (_: Exception) { }
        android.util.Log.w("PreferencesManager", "Encrypted prefs unavailable, using volatile memory", e)
        storageEncrypted = false
        MemoryPrefs()
    }

    val isStorageEncrypted: Boolean
        get() = storageEncrypted

    companion object {
        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }

        @Volatile
        private var sharedMasterKey: MasterKey? = null

        fun getMasterKey(context: Context): MasterKey {
            return sharedMasterKey ?: synchronized(this) {
                sharedMasterKey ?: MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                    .also { sharedMasterKey = it }
            }
        }
        private const val KEY_BOT_TOKEN = "bot_token"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_LAST_SMS_ID = "last_sms_id"
        private const val KEY_LAST_CALL_TIMESTAMP = "last_call_timestamp"
        private const val KEY_LAST_CALL_ID = "last_call_id"
        private const val KEY_USER_DISABLED = "user_disabled_monitoring"
        private const val KEY_INITIAL_SYNC_DONE = "initial_sync_done"
        private const val KEY_INITIAL_SYNC_STARTED = "initial_sync_started"
        private const val KEY_CAMERA_INTERVAL = "camera_interval"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_LAST_PHOTO_TIME = "last_photo_time"
        private const val KEY_LAST_CAM_ERR_NOTICE = "last_cam_err_notice"
        private const val KEY_LAST_UPLOAD_ERR_NOTICE = "last_upload_err_notice"
        private const val KEY_MONITORING_PAUSED = "monitoring_paused"
        private const val KEY_PHOTO_PAUSED_UNTIL = "photo_paused_until"
        private const val KEY_CAMERA_FACING = "camera_facing"
        private const val KEY_USER_CONSENTED = "user_consented_monitoring"
        private const val KEY_NOTIF_FORWARD = "notif_forward_enabled"
        private const val KEY_CRED_ERROR = "credential_error"
        private const val KEY_CRED_ERROR_AT = "credential_error_at"
        private const val KEY_COMMANDS_TOKEN_HASH = "commands_token_hash"
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
        get() = sharedPreferences.getInt(KEY_SYNC_INTERVAL, com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL)
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    var lastSmsId: Long
        get() = sharedPreferences.getLong(KEY_LAST_SMS_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SMS_ID, value).apply()

    var lastCallTimestamp: Long
        get() = sharedPreferences.getLong(KEY_LAST_CALL_TIMESTAMP, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CALL_TIMESTAMP, value).apply()

    var lastCallId: Long
        get() = sharedPreferences.getLong(KEY_LAST_CALL_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CALL_ID, value).apply()

    var userDisabledMonitoring: Boolean
        get() = sharedPreferences.getBoolean(KEY_USER_DISABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_DISABLED, value).apply()

    var initialSyncDone: Boolean
        get() = sharedPreferences.getBoolean(KEY_INITIAL_SYNC_DONE, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, value).apply()

    var initialSyncStarted: Boolean
        get() = sharedPreferences.getBoolean(KEY_INITIAL_SYNC_STARTED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_STARTED, value).apply()

    var cameraInterval: Int
        get() = sharedPreferences.getInt(KEY_CAMERA_INTERVAL, 1)
        set(value) = sharedPreferences.edit().putInt(KEY_CAMERA_INTERVAL, value).apply()

    var lastUpdateId: Long
        get() = sharedPreferences.getLong(KEY_LAST_UPDATE_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPDATE_ID, value).apply()

    var lastPhotoTime: Long
        get() = sharedPreferences.getLong(KEY_LAST_PHOTO_TIME, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_PHOTO_TIME, value).apply()

    var lastCameraErrorNotice: Long
        get() = sharedPreferences.getLong(KEY_LAST_CAM_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CAM_ERR_NOTICE, value).apply()

    var lastUploadErrorNotice: Long
        get() = sharedPreferences.getLong(KEY_LAST_UPLOAD_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPLOAD_ERR_NOTICE, value).apply()

    var monitoringPaused: Boolean
        get() = sharedPreferences.getBoolean(KEY_MONITORING_PAUSED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_PAUSED, value).apply()

    var photoPausedUntil: Long
        get() = sharedPreferences.getLong(KEY_PHOTO_PAUSED_UNTIL, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PHOTO_PAUSED_UNTIL, value).apply()

    var cameraFacing: String
        get() = sharedPreferences.getString(KEY_CAMERA_FACING, "front") ?: "front"
        set(value) = sharedPreferences.edit().putString(KEY_CAMERA_FACING, value).apply()

    fun isConfigured(): Boolean {
        return botToken.isNotEmpty() && chatId.isNotEmpty()
    }

    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        try { sharedPreferences.registerOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        try { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
    }

    fun snapshot(): Map<String, Any?> {
        return try {
            HashMap(sharedPreferences.all)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    var userConsentedMonitoring: Boolean
        get() = sharedPreferences.getBoolean(KEY_USER_CONSENTED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_CONSENTED, value).apply()

    var notifForwardEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_NOTIF_FORWARD, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NOTIF_FORWARD, value).apply()

    var credentialError: String
        get() = sharedPreferences.getString(KEY_CRED_ERROR, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_CRED_ERROR, value).apply()

    var credentialErrorAt: Long
        get() = sharedPreferences.getLong(KEY_CRED_ERROR_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_CRED_ERROR_AT, value).apply()

    var commandsTokenHash: String
        get() = sharedPreferences.getString(KEY_COMMANDS_TOKEN_HASH, "") ?: ""
        set(value) = sharedPreferences.edit().putString(KEY_COMMANDS_TOKEN_HASH, value).apply()

private class MemoryPrefs : SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): Map<String, *> = HashMap(data)
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? Set<String> ?: defValues
    }
    override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemoryEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }
    private inner class MemoryEditor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending[key] = null }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clearAll) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
            pending.clear()
            clearAll = false
        }
    }
}
}
