package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

@Suppress("DEPRECATION")
class PreferencesManager(context: Context) {

    private val masterKey = getMasterKey(context)

    private var storageEncrypted = true

    private val sharedPreferences: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("PreferencesManager", "Encrypted prefs unavailable, using plaintext fallback", e)
        storageEncrypted = false
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
    }

    val isStorageEncrypted: Boolean
        get() = storageEncrypted

    companion object {
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
        private const val KEY_CAMERA_INTERVAL = "camera_interval"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_LAST_PHOTO_TIME = "last_photo_time"
        private const val KEY_LAST_CAM_ERR_NOTICE = "last_cam_err_notice"
        private const val KEY_MONITORING_PAUSED = "monitoring_paused"
        private const val KEY_PHOTO_PAUSED_UNTIL = "photo_paused_until"
        private const val KEY_CAMERA_FACING = "camera_facing"
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
}
