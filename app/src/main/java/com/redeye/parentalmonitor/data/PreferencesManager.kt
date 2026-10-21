package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val appContext = context.applicationContext

    private val securePrefs: Pair<SharedPreferences, Boolean> = openPrefs(appContext)

    @Volatile
    private var storageEncrypted = securePrefs.second

    @Volatile
    private var sharedPreferences: SharedPreferences = securePrefs.first

    val isStorageEncrypted: Boolean
        get() = storageEncrypted

    private val listenerLock = Any()
    private val listenerSet = mutableSetOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    private val upgradeLock = Any()
    private val pairCodeLock = Any()
    private val smsPendingLock = Any()
    private val auditLock = Any()

    fun upgradeToPersistent(): Boolean {
        if (storageEncrypted) return true
        synchronized(upgradeLock) {
            if (storageEncrypted) return true
            return try {
                val fresh = openEncryptedPrefs(appContext, PREFS_NAME)
                val mem = sharedPreferences
                val snap = try { HashMap(snapshot()) } catch (_: Exception) { emptyMap<String, Any?>() }
                if (snap.isNotEmpty()) {
                    val editor = fresh.edit()
                    for ((key, value) in snap) {
                        putEntryInto(editor, key, value)
                    }
                    if (!editor.commit()) return false
                }
                try {
                    val memNow = mem?.all ?: emptyMap()
                    if (memNow != snap) {
                        val editor2 = fresh.edit()
                        var changed = false
                        for ((key, value) in memNow) {
                            if (!snap.containsKey(key) || snap[key] != value) {
                                putEntryInto(editor2, key, value)
                                changed = true
                            }
                        }
                        if (changed && !editor2.commit()) return false
                    }
                } catch (_: Exception) {
                }
                sharedPreferences = fresh
                synchronized(listenerLock) {
                    for (l in listenerSet) {
                        try { fresh.registerOnSharedPreferenceChangeListener(l) } catch (_: Exception) { }
                    }
                }
                storageEncrypted = true
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun putEntryInto(editor: android.content.SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> try {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            } catch (_: Exception) {
                android.util.Log.w("PreferencesManager", "Dropping non-string set for $key during migration")
            }
            else -> android.util.Log.w("PreferencesManager", "Dropping unknown type for $key during migration")
        }
    }

    private fun commitGuarded(editor: android.content.SharedPreferences.Editor) {
        try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                editor.apply()
            } else {
                editor.commit()
            }
        } catch (_: Exception) {
        }
    }

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

        private const val PREFS_NAME = "secure_prefs"

        private val secureRandom = java.security.SecureRandom()

        private fun openPrefs(context: Context): Pair<SharedPreferences, Boolean> {
            return try {
                Pair(openEncryptedPrefs(context, PREFS_NAME), true)
            } catch (e: Exception) {
                try {
                    synchronized(this) {
                        sharedMasterKey = null
                    }
                } catch (_: Exception) {
                }
                android.util.Log.w("PreferencesManager", "Encrypted prefs unavailable, using volatile memory", e)
                Pair(MemoryPrefs(), false)
            }
        }

        fun openEncryptedPrefs(context: Context, prefsName: String): SharedPreferences {
            try {
                return createEncryptedPrefs(context, prefsName)
            } catch (e: Exception) {
                try {
                    synchronized(this) {
                        sharedMasterKey = null
                    }
                } catch (_: Exception) {
                }
                throw e
            }
        }

        private fun createEncryptedPrefs(context: Context, prefsName: String): SharedPreferences {
            return EncryptedSharedPreferences.create(
                context.applicationContext,
                prefsName,
                getMasterKey(context),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        const val KEY_BOT_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val KEY_LAST_SYNC = "last_sync"
        const val KEY_SYNC_INTERVAL = "sync_interval"
        private const val KEY_LAST_SMS_ID = "last_sms_id"
        private const val KEY_LAST_CALL_TIMESTAMP = "last_call_timestamp"
        private const val KEY_LAST_CALL_ID = "last_call_id"
        const val KEY_USER_DISABLED = "user_disabled_monitoring"
        private const val KEY_INITIAL_SYNC_DONE = "initial_sync_done"
        const val KEY_CAMERA_INTERVAL = "camera_interval"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_LAST_PHOTO_TIME = "last_photo_time"
        private const val KEY_LAST_CAM_ERR_NOTICE = "last_cam_err_notice"
        private const val KEY_LAST_UPLOAD_ERR_NOTICE = "last_upload_err_notice"
        const val KEY_MONITORING_PAUSED = "monitoring_paused"
        const val KEY_PHOTO_PAUSED_UNTIL = "photo_paused_until"
        const val KEY_PHOTO_PAUSE_SOURCE = "photo_pause_source"
        const val KEY_PATROL_ENABLED = "patrol_enabled"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_PATROL_INTERVAL = "patrol_interval"
        private const val KEY_CAMERA_FACING = "camera_facing"
        const val KEY_USER_CONSENTED = "user_consented_monitoring"
        const val KEY_NOTIF_FORWARD = "notif_forward_enabled"
        private const val KEY_OTP_MASK = "otp_mask_enabled"
        private const val KEY_RESTORE_STRICT = "restore_strict_enabled"
        private const val KEY_RESTORE_SECRET = "restore_code_secret"
        private const val KEY_AUDIT_LOG = "audit_log"
        const val KEY_CRED_ERROR = "credential_error"
        private const val KEY_CRED_ERROR_AT = "credential_error_at"
        private const val KEY_COMMANDS_TOKEN_HASH = "commands_token_hash"
        private const val KEY_RING_PREV_VOL = "ring_prev_volume"
        private const val KEY_RING_SAVED_AT = "ring_saved_at"
        private const val KEY_PENDING_SMS_NUMBER = "pending_sms_number"
        private const val KEY_PENDING_SMS_TEXT = "pending_sms_text"
        private const val KEY_PENDING_SMS_AT = "pending_sms_at"
        private const val KEY_PENDING_SMS_OWNER = "pending_sms_owner"
        private const val KEY_LAST_SMS_SEND_AT = "last_sms_send_at"
        private const val KEY_OWNER_ID = "owner_user_id"
        const val KEY_ACCESS_PIN = "access_pin"
        private const val KEY_OWNER_PAIR_CODE = "owner_pair_code"
        private const val KEY_OWNER_PAIR_AT = "owner_pair_at"
        private const val KEY_PENDING_MSG_DROPS = "pending_msg_drops"
        private const val KEY_PENDING_NOTIF_DROPS = "pending_notif_drops"
        private const val KEY_PERM_HEALTH_ALERT = "perm_health_alert_at"
        private const val KEY_PERM_SNAPSHOT = "perm_snapshot"
        private const val KEY_LAST_PHOTO_HASH = "last_photo_hash"
        private const val KEY_DIGEST_AT = "digest_at"
        private const val KEY_BATTERY_ALERT_AT = "battery_alert_at"
        private const val KEY_LAST_LOC_LAT = "last_loc_lat_e6"
        private const val KEY_LAST_LOC_LON = "last_loc_lon_e6"
        private const val KEY_LAST_LOC_AT = "last_loc_at"
        private const val KEY_TRAIL = "trail_24"
        private const val KEY_BEGADANG_DATE = "begadang_date"
        private const val KEY_JEMPUT_RAD = "jemput_radius_m"
        const val KEY_WATCH_KEYWORDS = "watch_keywords"
        private const val KEY_HOME_LAT = "home_lat_e6"
        private const val KEY_HOME_LON = "home_lon_e6"
        private const val KEY_HOME_RAD = "home_radius_m"
        private const val KEY_HOME_STATE = "home_state"
        private const val KEY_SCHOOL_LAT = "school_lat_e6"
        private const val KEY_SCHOOL_LON = "school_lon_e6"
        private const val KEY_SCHOOL_RAD = "school_radius_m"
        private const val KEY_SCHOOL_STATE = "school_state"
        private const val KEY_NIGHT_DIGEST_DATE = "night_digest_date"
        private const val KEY_LAST_DATA_AT = "last_data_at"
        private const val KEY_LOC_FAIL_COUNT = "loc_fail_count"
        private const val KEY_HEALTH_ALERT_AT = "health_alert_at"
        const val KEY_WATCH_NUMBERS = "watch_numbers"
        private const val KEY_CURFEW_TIME = "curfew_time"
        private const val KEY_CURFEW_DATE = "curfew_alert_date"
        private const val KEY_SCHOOL_DATE = "school_alert_date"
        private const val KEY_LAST_ALIVE_AT = "last_alive_at"
        private const val KEY_LAST_ALIVE_ELAPSED = "last_alive_elapsed"
        private const val KEY_LIVENESS_KILLS = "liveness_kills"
        private const val KEY_LIVENESS_KILLS_UNREPORTED = "liveness_kills_unreported"
        private const val KEY_LIVENESS_REPORT_AT = "liveness_report_at"
        private const val KEY_HEARTBEAT_HOURS = "heartbeat_hours"
        private const val KEY_HEARTBEAT_AT = "heartbeat_at"
        private const val KEY_LIVE_TEST_PENDING = "live_test_pending"
        private const val KEY_LIVE_TEST_STARTED_ELAPSED = "live_test_started_elapsed"
        private const val KEY_LIVE_TEST_BY_ALARM = "live_test_by_alarm"
        private const val KEY_LIVE_TEST_LAST_SEC = "live_test_last_sec"
        private const val KEY_LAST_SEND_OK_ELAPSED = "last_send_ok_elapsed"
        private const val KEY_SILENT_HEAL_AT = "silent_heal_at"
        private const val KEY_SILENT_HEAL_STAGE = "silent_heal_stage"
        private const val KEY_SILENT_HEALS = "silent_heals"
        private const val KEY_VEHICLE_ALERT_ENABLED = "vehicle_alert_enabled"
        private const val KEY_LAST_VEHICLE_ALERT_AT = "last_vehicle_alert_at"
        private const val KEY_DATA_SAVER_ENABLED = "data_saver_enabled"
        private const val KEY_DATA_SAVER_NOTICE_AT = "data_saver_notice_at"

        private fun clearStaleCredentialError(target: PreferencesManager) {
            try {
                val now = System.currentTimeMillis()
                val errAt = target.credentialErrorAt
                if (errAt != 0L && (now < errAt || now - errAt >= 30 * 60_000L)) {
                    target.setCredentialError("", 0L)
                }
            } catch (_: Exception) {
            }
        }

        fun refreshInstance(context: Context): Boolean {
            val current = synchronized(this) {
                instance ?: try {
                    val created = PreferencesManager(context.applicationContext)
                    if (instance == null) instance = created
                    instance
                } catch (_: Exception) {
                    null
                }
            } ?: return false
            if (current.isStorageEncrypted) return true
            return try {
                if (current.upgradeToPersistent()) {
                    clearStaleCredentialError(current)
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }

        fun recoverEncryptedStorage(context: Context): Boolean {
            synchronized(this) {
                try {
                    val app = context.applicationContext
                    app.deleteSharedPreferences(PREFS_NAME)
                    app.deleteSharedPreferences("encrypted_queue")
                } catch (e: Exception) {
                    android.util.Log.w("PreferencesManager", "Gagal menghapus prefs terenkripsi", e)
                    return false
                }
                try {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {
                }
                sharedMasterKey = null
                instance = null
            }
            return try {
                val ok = getInstance(context).isStorageEncrypted
                if (ok) {
                    try {
                        MessageQueue.getInstance(context).reopenAfterStorageReset()
                    } catch (_: Exception) {
                    }
                }
                ok
            } catch (_: Exception) {
                false
            }
        }
    }

    var botToken: String
        get() = try { sharedPreferences.getString(KEY_BOT_TOKEN, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_BOT_TOKEN, value).apply()

    var chatId: String
        get() = try { sharedPreferences.getString(KEY_CHAT_ID, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CHAT_ID, value).apply()

    val isMonitoringEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, false) } catch (_: Exception) { false }

    var lastSyncTime: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SYNC, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncInterval: Int
        get() = try { sharedPreferences.getInt(KEY_SYNC_INTERVAL, com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL) } catch (_: Exception) { com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL }
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    val lastSmsId: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SMS_ID, 0L) } catch (_: Exception) { 0L }

    val lastCallTimestamp: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_CALL_TIMESTAMP, 0L) } catch (_: Exception) { 0L }

    val lastCallId: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_CALL_ID, 0L) } catch (_: Exception) { 0L }

    var userDisabledMonitoring: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_USER_DISABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_DISABLED, value).apply()

    var initialSyncDone: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_INITIAL_SYNC_DONE, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, value).apply()

    fun setInitialSyncDoneSync(done: Boolean) {
        try {
            commitGuarded(sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, done))
        } catch (_: Exception) {
        }
    }

    var cameraInterval: Int
        get() = try { sharedPreferences.getInt(KEY_CAMERA_INTERVAL, 1) } catch (_: Exception) { 1 }
        set(value) = sharedPreferences.edit().putInt(KEY_CAMERA_INTERVAL, value).apply()

    var lastUpdateId: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_UPDATE_ID, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPDATE_ID, value).apply()

    fun setLastUpdateIdSync(id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_LAST_UPDATE_ID, id).commit()
        } catch (_: Exception) {
        }
    }

    var lastPhotoTime: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_PHOTO_TIME, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_PHOTO_TIME, value).apply()

    var lastCameraErrorNotice: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_CAM_ERR_NOTICE, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CAM_ERR_NOTICE, value).apply()

    var lastUploadErrorNotice: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_UPLOAD_ERR_NOTICE, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPLOAD_ERR_NOTICE, value).apply()

    var monitoringPaused: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_MONITORING_PAUSED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_PAUSED, value).apply()

    var photoPausedUntil: Long
        get() = try { sharedPreferences.getLong(KEY_PHOTO_PAUSED_UNTIL, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_PHOTO_PAUSED_UNTIL, value).apply()

    var photoPauseSource: Int
        get() = try { sharedPreferences.getInt(KEY_PHOTO_PAUSE_SOURCE, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_PHOTO_PAUSE_SOURCE, value).apply()

    var patrolEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_PATROL_ENABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_PATROL_ENABLED, value).apply()

    var nightMode: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_NIGHT_MODE, true) } catch (_: Exception) { true }
        set(value) = sharedPreferences.edit().putBoolean(KEY_NIGHT_MODE, value).apply()

    var patrolInterval: Int
        get() = try { sharedPreferences.getInt(KEY_PATROL_INTERVAL, 30) } catch (_: Exception) { 30 }
        set(value) = sharedPreferences.edit().putInt(KEY_PATROL_INTERVAL, value).apply()

    var cameraFacing: String
        get() = try { sharedPreferences.getString(KEY_CAMERA_FACING, "front") ?: "front" } catch (_: Exception) { "front" }
        set(value) = sharedPreferences.edit().putString(KEY_CAMERA_FACING, value).apply()

    fun isConfigured(): Boolean {
        return try { botToken.isNotEmpty() && chatId.isNotEmpty() } catch (_: Exception) { false }
    }

    fun registerChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listenerLock) { listenerSet.add(listener) }
        try { sharedPreferences.registerOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listenerLock) { listenerSet.remove(listener) }
        try { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
    }

    fun snapshot(): Map<String, Any?> {
        return try {
            val raw = sharedPreferences.all
            val copy = HashMap<String, Any?>(raw.size)
            for ((k, v) in raw) {
                copy[k] = if (v is Set<*>) HashSet(v) else v
            }
            copy
        } catch (_: Exception) {
            emptyMap()
        }
    }

    var userConsentedMonitoring: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_USER_CONSENTED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_CONSENTED, value).apply()

    var notifForwardEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_NOTIF_FORWARD, true) } catch (_: Exception) { true }
        set(value) = sharedPreferences.edit().putBoolean(KEY_NOTIF_FORWARD, value).apply()

    var otpMaskEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_OTP_MASK, true) } catch (_: Exception) { true }
        set(value) = sharedPreferences.edit().putBoolean(KEY_OTP_MASK, value).apply()

    var restoreStrict: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_RESTORE_STRICT, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_RESTORE_STRICT, value).apply()

    fun ensureRestoreCodeSecret(): String {
        synchronized(auditLock) {
            try {
                val cur = sharedPreferences.getString(KEY_RESTORE_SECRET, "") ?: ""
                if (cur.length == 64 && cur.all { it in '0'..'9' || it in 'a'..'f' }) return cur
                val fresh = com.redeye.parentalmonitor.utils.RestoreCode.generateSecret()
                sharedPreferences.edit().putString(KEY_RESTORE_SECRET, fresh).commit()
                return fresh
            } catch (_: Exception) {
                return ""
            }
        }
    }

    val auditLog: String
        get() = try { sharedPreferences.getString(KEY_AUDIT_LOG, "") ?: "" } catch (_: Exception) { "" }

    fun appendAudit(event: String, source: String) {
        synchronized(auditLock) {
            try {
                val safeEvent = event.replace('\t', ' ').replace('\n', ' ').take(80)
                val safeSource = source.replace('\t', ' ').replace('\n', ' ').take(40)
                val line = System.currentTimeMillis().toString() + "\t" + safeEvent + "\t" + safeSource
                val current = try { sharedPreferences.getString(KEY_AUDIT_LOG, "") ?: "" } catch (_: Exception) { "" }
                val lines = if (current.isEmpty()) mutableListOf<String>() else current.split('\n').toMutableList()
                lines.add(line)
                while (lines.size > 50) lines.removeAt(0)
                sharedPreferences.edit().putString(KEY_AUDIT_LOG, lines.joinToString("\n")).commit()
            } catch (_: Exception) {
            }
        }
    }

    var credentialError: String
        get() = try { sharedPreferences.getString(KEY_CRED_ERROR, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CRED_ERROR, value).apply()

    var credentialErrorAt: Long
        get() = try { sharedPreferences.getLong(KEY_CRED_ERROR_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_CRED_ERROR_AT, value).apply()

    fun setCredentialError(code: String, at: Long) {
        try {
            sharedPreferences.edit().putString(KEY_CRED_ERROR, code).putLong(KEY_CRED_ERROR_AT, at).apply()
        } catch (_: Exception) {
        }
    }

    var commandsTokenHash: String
        get() = try { sharedPreferences.getString(KEY_COMMANDS_TOKEN_HASH, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_COMMANDS_TOKEN_HASH, value).apply()

    var ringPrevVolume: Int
        get() = try { sharedPreferences.getInt(KEY_RING_PREV_VOL, -1) } catch (_: Exception) { -1 }
        set(value) = sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, value).apply()

    var ringSavedAt: Long
        get() = try { sharedPreferences.getLong(KEY_RING_SAVED_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_RING_SAVED_AT, value).apply()

    var lastAliveAt: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_ALIVE_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_ALIVE_AT, value).apply()

    val lastAliveElapsed: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_ALIVE_ELAPSED, 0L) } catch (_: Exception) { 0L }

    fun setLastAliveMarker(wallAt: Long, elapsedAt: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_LAST_ALIVE_AT, wallAt)
                .putLong(KEY_LAST_ALIVE_ELAPSED, elapsedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    var livenessKills: Int
        get() = try { sharedPreferences.getInt(KEY_LIVENESS_KILLS, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_LIVENESS_KILLS, value).apply()

    var livenessKillsUnreported: Int
        get() = try { sharedPreferences.getInt(KEY_LIVENESS_KILLS_UNREPORTED, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_LIVENESS_KILLS_UNREPORTED, value).apply()

    var livenessReportAt: Long
        get() = try { sharedPreferences.getLong(KEY_LIVENESS_REPORT_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LIVENESS_REPORT_AT, value).apply()

    var heartbeatHours: Int
        get() = try { sharedPreferences.getInt(KEY_HEARTBEAT_HOURS, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_HEARTBEAT_HOURS, value).apply()

    var heartbeatAt: Long
        get() = try { sharedPreferences.getLong(KEY_HEARTBEAT_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_HEARTBEAT_AT, value).apply()

    var liveTestPending: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_LIVE_TEST_PENDING, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_PENDING, value).apply()

    var liveTestStartedElapsed: Long
        get() = try { sharedPreferences.getLong(KEY_LIVE_TEST_STARTED_ELAPSED, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LIVE_TEST_STARTED_ELAPSED, value).apply()

    var liveTestByAlarm: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_LIVE_TEST_BY_ALARM, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_BY_ALARM, value).apply()

    var liveTestLastSec: Int
        get() = try { sharedPreferences.getInt(KEY_LIVE_TEST_LAST_SEC, -1) } catch (_: Exception) { -1 }
        set(value) = sharedPreferences.edit().putInt(KEY_LIVE_TEST_LAST_SEC, value).apply()

    val lastSendOkElapsed: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SEND_OK_ELAPSED, 0L) } catch (_: Exception) { 0L }

    fun setLastSendOkMarker(elapsedAt: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_LAST_SEND_OK_ELAPSED, elapsedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    var silentHealAt: Long
        get() = try { sharedPreferences.getLong(KEY_SILENT_HEAL_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_SILENT_HEAL_AT, value).apply()

    var silentHealStage: Int
        get() = try { sharedPreferences.getInt(KEY_SILENT_HEAL_STAGE, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_SILENT_HEAL_STAGE, value).apply()

    var silentHeals: Int
        get() = try { sharedPreferences.getInt(KEY_SILENT_HEALS, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_SILENT_HEALS, value).apply()

    var vehicleAlertEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_VEHICLE_ALERT_ENABLED, true) } catch (_: Exception) { true }
        set(value) = sharedPreferences.edit().putBoolean(KEY_VEHICLE_ALERT_ENABLED, value).apply()

    var lastVehicleAlertAt: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_VEHICLE_ALERT_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_VEHICLE_ALERT_AT, value).apply()

    var dataSaverEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_DATA_SAVER_ENABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_DATA_SAVER_ENABLED, value).apply()

    var dataSaverNoticeAt: Long
        get() = try { sharedPreferences.getLong(KEY_DATA_SAVER_NOTICE_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_DATA_SAVER_NOTICE_AT, value).apply()

    fun setRingStateSync(prev: Int, at: Long) {
        try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, prev).putLong(KEY_RING_SAVED_AT, at))
        } catch (_: Exception) {
        }
    }

    fun clearRingStateSync() {
        try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, -1).putLong(KEY_RING_SAVED_AT, 0L))
        } catch (_: Exception) {
        }
    }

    var pendingSmsNumber: String
        get() = try { sharedPreferences.getString(KEY_PENDING_SMS_NUMBER, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_NUMBER, value).apply()

    var pendingSmsText: String
        get() = try { sharedPreferences.getString(KEY_PENDING_SMS_TEXT, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_TEXT, value).apply()

    var pendingSmsAt: Long
        get() = try { sharedPreferences.getLong(KEY_PENDING_SMS_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_PENDING_SMS_AT, value).apply()

    var lastSmsSendAt: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SMS_SEND_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SMS_SEND_AT, value).apply()

    var pendingSmsOwner: String
        get() = try { sharedPreferences.getString(KEY_PENDING_SMS_OWNER, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_OWNER, value).apply()

    var ownerUserId: Long
        get() = try { sharedPreferences.getLong(KEY_OWNER_ID, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_OWNER_ID, value).apply()

    var accessPin: String
        get() = try { sharedPreferences.getString(KEY_ACCESS_PIN, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_ACCESS_PIN, value).apply()

    fun setOwnerIdSync(id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_OWNER_ID, id).commit()
        } catch (_: Exception) {
        }
    }

    fun adoptOwnerChatSync(ownerId: Long, newChatId: String): Boolean {
        if (ownerId == 0L || newChatId.isEmpty()) return false
        return try {
            sharedPreferences.edit()
                .putLong(KEY_OWNER_ID, ownerId)
                .putString(KEY_CHAT_ID, newChatId)
                .putString(KEY_OWNER_PAIR_CODE, "")
                .putString(KEY_COMMANDS_TOKEN_HASH, "")
                .putString(KEY_CRED_ERROR, "")
                .putLong(KEY_CRED_ERROR_AT, 0L)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun clearOwnerSync(): Boolean {
        return try {
            sharedPreferences.edit()
                .putLong(KEY_OWNER_ID, 0L)
                .putString(KEY_OWNER_PAIR_CODE, "")
                .putLong(KEY_OWNER_PAIR_AT, 0L)
                .putString(KEY_COMMANDS_TOKEN_HASH, "")
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    var ownerPairCode: String
        get() = try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, value).apply()

    fun ensureOwnerPairCode(): String {
        synchronized(pairCodeLock) {
            try {
                val cur = try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { "" }
                if (cur.length == 6 && cur.all { it in '0'..'9' }) {
                    val at = try { sharedPreferences.getLong(KEY_OWNER_PAIR_AT, 0L) } catch (_: Exception) { 0L }
                    if (at == 0L) {
                        try { sharedPreferences.edit().putLong(KEY_OWNER_PAIR_AT, System.currentTimeMillis()).apply() } catch (_: Exception) { }
                        return cur
                    }
                    val nowPairCode = System.currentTimeMillis()
                    if (at <= nowPairCode && nowPairCode - at < 24 * 60 * 60_000L) return cur
                }
                val code = (100000 + secureRandom.nextInt(900000)).toString()
                try {
                    sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, code).putLong(KEY_OWNER_PAIR_AT, System.currentTimeMillis()).commit()
                } catch (_: Exception) {
                    return code
                }
                return try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: code } catch (_: Exception) { code }
            } catch (_: Exception) {
                return ""
            }
        }
    }

    var pendingMsgDrops: Int
        get() = try { sharedPreferences.getInt(KEY_PENDING_MSG_DROPS, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_PENDING_MSG_DROPS, value).apply()

    fun setPendingMsgDropsSync(value: Int) {
        try {
            sharedPreferences.edit().putInt(KEY_PENDING_MSG_DROPS, value).commit()
        } catch (_: Exception) {
        }
    }

    var pendingNotifDrops: Int
        get() = try { sharedPreferences.getInt(KEY_PENDING_NOTIF_DROPS, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_PENDING_NOTIF_DROPS, value).apply()

    fun setPendingNotifDropsSync(value: Int) {
        try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_PENDING_NOTIF_DROPS, value))
        } catch (_: Exception) {
        }
    }

    var permSnapshot: Int
        get() = try { sharedPreferences.getInt(KEY_PERM_SNAPSHOT, -1) } catch (_: Exception) { -1 }
        set(value) = sharedPreferences.edit().putInt(KEY_PERM_SNAPSHOT, value).apply()

    fun setPermSnapshotSync(value: Int) {
        try {
            sharedPreferences.edit().putInt(KEY_PERM_SNAPSHOT, value).commit()
        } catch (_: Exception) {
        }
    }

    val lastPhotoHash: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_PHOTO_HASH, 0L) } catch (_: Exception) { 0L }

    fun setLastPhotoHashSync(value: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_LAST_PHOTO_HASH, value).commit()
        } catch (_: Exception) {
        }
    }

    var digestAt: Long
        get() = try { sharedPreferences.getLong(KEY_DIGEST_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_DIGEST_AT, value).apply()

    var batteryAlertAt: Long
        get() = try { sharedPreferences.getLong(KEY_BATTERY_ALERT_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_BATTERY_ALERT_AT, value).apply()

    var lastLocLatE6: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_LOC_LAT, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_LAT, value).apply()

    var lastLocLonE6: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_LOC_LON, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_LON, value).apply()

    var lastLocAt: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_LOC_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_AT, value).apply()

    var trail24: String
        get() = try { sharedPreferences.getString(KEY_TRAIL, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_TRAIL, value).apply()

    fun recordSentLocation(lat: Double, lon: Double, trail: String?) {
        try {
            val editor = sharedPreferences.edit()
                .putLong(KEY_LAST_LOC_LAT, (lat * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_LON, (lon * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_AT, System.currentTimeMillis())
            if (trail != null) editor.putString(KEY_TRAIL, trail)
            editor.apply()
        } catch (_: Exception) {
        }
    }

    var begadangDate: String
        get() = try { sharedPreferences.getString(KEY_BEGADANG_DATE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_BEGADANG_DATE, value).apply()

    var jemputRadiusM: Int
        get() = try { sharedPreferences.getInt(KEY_JEMPUT_RAD, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_JEMPUT_RAD, value).apply()

    var permHealthAlertAt: Long
        get() = try { sharedPreferences.getLong(KEY_PERM_HEALTH_ALERT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_PERM_HEALTH_ALERT, value).apply()

    fun setPermHealthAlertAtSync(value: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_PERM_HEALTH_ALERT, value).commit()
        } catch (_: Exception) {
        }
    }

    var watchKeywords: String
        get() = try { sharedPreferences.getString(KEY_WATCH_KEYWORDS, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_WATCH_KEYWORDS, value).apply()

    var homeLatE6: Long
        get() = try { sharedPreferences.getLong(KEY_HOME_LAT, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_HOME_LAT, value).apply()

    var homeLonE6: Long
        get() = try { sharedPreferences.getLong(KEY_HOME_LON, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_HOME_LON, value).apply()

    var homeRadiusM: Int
        get() = try { sharedPreferences.getInt(KEY_HOME_RAD, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_HOME_RAD, value).apply()

    var homeState: Int
        get() = try { sharedPreferences.getInt(KEY_HOME_STATE, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_HOME_STATE, value).apply()

    var schoolLatE6: Long
        get() = try { sharedPreferences.getLong(KEY_SCHOOL_LAT, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_LAT, value).apply()

    var schoolLonE6: Long
        get() = try { sharedPreferences.getLong(KEY_SCHOOL_LON, Long.MIN_VALUE) } catch (_: Exception) { Long.MIN_VALUE }
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_LON, value).apply()

    var schoolRadiusM: Int
        get() = try { sharedPreferences.getInt(KEY_SCHOOL_RAD, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_SCHOOL_RAD, value).apply()

    var schoolState: Int
        get() = try { sharedPreferences.getInt(KEY_SCHOOL_STATE, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_SCHOOL_STATE, value).apply()

    var nightDigestDate: String
        get() = try { sharedPreferences.getString(KEY_NIGHT_DIGEST_DATE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_NIGHT_DIGEST_DATE, value).apply()

    var lastDataAt: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_DATA_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_DATA_AT, value).apply()

    var locFailCount: Int
        get() = try { sharedPreferences.getInt(KEY_LOC_FAIL_COUNT, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_LOC_FAIL_COUNT, value).apply()

    var healthAlertAt: Long
        get() = try { sharedPreferences.getLong(KEY_HEALTH_ALERT_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_HEALTH_ALERT_AT, value).apply()

    var watchNumbers: String
        get() = try { sharedPreferences.getString(KEY_WATCH_NUMBERS, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_WATCH_NUMBERS, value).apply()

    var curfewTime: String
        get() = try { sharedPreferences.getString(KEY_CURFEW_TIME, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CURFEW_TIME, value).apply()

    var curfewAlertDate: String
        get() = try { sharedPreferences.getString(KEY_CURFEW_DATE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CURFEW_DATE, value).apply()

    var schoolAlertDate: String
        get() = try { sharedPreferences.getString(KEY_SCHOOL_DATE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_SCHOOL_DATE, value).apply()

    fun writeSmsPendingSync(number: String, text: String, at: Long, owner: String) {
        synchronized(smsPendingLock) {
            try {
                sharedPreferences.edit()
                    .putString(KEY_PENDING_SMS_NUMBER, number)
                    .putString(KEY_PENDING_SMS_TEXT, text)
                    .putLong(KEY_PENDING_SMS_AT, at)
                    .putString(KEY_PENDING_SMS_OWNER, owner)
                    .commit()
            } catch (_: Exception) {
            }
        }
    }

    fun clearSmsPendingIf(at: Long) {
        synchronized(smsPendingLock) {
            try {
                if (sharedPreferences.getLong(KEY_PENDING_SMS_AT, 0L) == at) {
                    sharedPreferences.edit()
                        .putString(KEY_PENDING_SMS_NUMBER, "")
                        .putString(KEY_PENDING_SMS_TEXT, "")
                        .putLong(KEY_PENDING_SMS_AT, 0L)
                        .putString(KEY_PENDING_SMS_OWNER, "")
                        .commit()
                }
            } catch (_: Exception) {
            }
        }
    }

    data class SmsPendingSync(val number: String?, val text: String?, val at: Long, val owner: String?)

    fun readSmsPendingSync(): SmsPendingSync {
        synchronized(smsPendingLock) {
            return SmsPendingSync(
                try { sharedPreferences.getString(KEY_PENDING_SMS_NUMBER, "") } catch (_: Exception) { null },
                try { sharedPreferences.getString(KEY_PENDING_SMS_TEXT, "") } catch (_: Exception) { null },
                try { sharedPreferences.getLong(KEY_PENDING_SMS_AT, 0L) } catch (_: Exception) { 0L },
                try { sharedPreferences.getString(KEY_PENDING_SMS_OWNER, "") } catch (_: Exception) { null }
            )
        }
    }

    fun setSmsCursorSync(id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_LAST_SMS_ID, id).commit()
        } catch (_: Exception) {
        }
    }

    fun setCallCursorSync(timestamp: Long, id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_LAST_CALL_TIMESTAMP, timestamp).putLong(KEY_LAST_CALL_ID, id).commit()
        } catch (_: Exception) {
        }
    }

    fun setLastSmsSendAtSync(now: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_LAST_SMS_SEND_AT, now).commit()
        } catch (_: Exception) {
        }
    }

    private fun putCredentialState(editor: android.content.SharedPreferences.Editor, newToken: String, newChatId: String) {
        val tokenChanged = try { newToken != botToken } catch (_: Exception) { true }
        val chatChanged = try { newChatId != chatId } catch (_: Exception) { true }
        if (tokenChanged || chatChanged) {
            editor.putString(KEY_COMMANDS_TOKEN_HASH, "")
            editor.putString(KEY_OWNER_PAIR_CODE, "")
            editor.remove(KEY_LAST_UPDATE_ID)
            editor.remove(KEY_INITIAL_SYNC_DONE)
            editor.putLong(KEY_LAST_SMS_ID, 0L)
            editor.putLong(KEY_LAST_CALL_TIMESTAMP, 0L)
            editor.putLong(KEY_LAST_CALL_ID, 0L)
            editor.putLong(KEY_OWNER_ID, 0L)
        }
        editor.putString(KEY_CRED_ERROR, "")
        editor.putLong(KEY_CRED_ERROR_AT, 0L)
    }

    fun setChatIdSync(newChatId: String) {
        try {
            sharedPreferences.edit().putString(KEY_CHAT_ID, newChatId).commit()
        } catch (_: Exception) {
        }
    }

    fun saveCredentials(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int): Boolean {
        try { upgradeToPersistent() } catch (_: Exception) { }
        if (!storageEncrypted) return false
        val editor = sharedPreferences.edit()
        putCredentialState(editor, newToken, newChatId)
        editor.putString(KEY_BOT_TOKEN, newToken)
        editor.putString(KEY_CHAT_ID, newChatId)
        editor.putInt(KEY_SYNC_INTERVAL, newSyncInterval)
        editor.putInt(KEY_CAMERA_INTERVAL, newCameraInterval)
        return try { editor.commit() } catch (_: Exception) { false }
    }

    fun setMonitoringActive(active: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_MONITORING_ENABLED, active)
        if (active) {
            editor.putBoolean(KEY_USER_DISABLED, false)
            editor.putBoolean(KEY_USER_CONSENTED, true)
            editor.putBoolean(KEY_MONITORING_PAUSED, false)
            editor.putLong(KEY_PHOTO_PAUSED_UNTIL, 0L)
            editor.putInt(KEY_PHOTO_PAUSE_SOURCE, 0)
        } else {
            editor.putBoolean(KEY_USER_DISABLED, true)
        }
        commitGuarded(editor)
    }

private class MemoryPrefs : SharedPreferences {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): Map<String, *> = HashMap(data)
    override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        val stored = data[key] as? Set<String>
        return if (stored != null) HashSet(stored) else defValues
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
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = if (values != null) HashSet(values) else null }
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
            val changed = HashSet(pending.keys)
            pending.clear()
            clearAll = false
            val copy = synchronized(listeners) { ArrayList(listeners) }
            for (l in copy) {
                for (k in changed) {
                    try { l.onSharedPreferenceChanged(this@MemoryPrefs, k) } catch (_: Exception) { }
                }
            }
        }
    }
}
}
