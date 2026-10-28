package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    enum class StorageRecovery { FAILED, RESET }

    private val appContext = context.applicationContext

    private val securePrefs: Pair<SharedPreferences, Boolean> = openPrefs(appContext)

    @Volatile
    private var storageEncrypted = securePrefs.second

    @Volatile
    private var sharedPreferences: SharedPreferences = securePrefs.first

    private fun prefString(key: String, default: String): String =
        try { sharedPreferences.getString(key, default) ?: default } catch (_: Exception) { default }

    private fun prefLong(key: String, default: Long): Long =
        try { sharedPreferences.getLong(key, default) } catch (_: Exception) { default }

    private fun prefInt(key: String, default: Int): Int =
        try { sharedPreferences.getInt(key, default) } catch (_: Exception) { default }

    private fun prefBoolean(key: String, default: Boolean): Boolean =
        try { sharedPreferences.getBoolean(key, default) } catch (_: Exception) { default }

    private fun prefFloat(key: String, default: Float): Float =
        try { sharedPreferences.getFloat(key, default) } catch (_: Exception) { default }

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
            for (attempt in 1..UPGRADE_ATTEMPTS) {
                if (attempt > 1) {
                    resetCachedMasterKey()
                }
                if (attemptUpgradeToPersistent()) return true
            }
            return false
        }
    }

    private fun syncPrefsInto(from: SharedPreferences?, to: SharedPreferences, state: Map<String, Any?>): Boolean {
        if (from == null) return false
        return try {
            val target = HashMap(to.all)
            val editor = to.edit()
            var changed = false
            for ((key, value) in state) {
                if (!target.containsKey(key) || target[key] != value) {
                    putEntryInto(editor, key, value)
                    changed = true
                }
            }
            for (key in target.keys) {
                if (!state.containsKey(key)) {
                    editor.remove(key)
                    changed = true
                }
            }
            !changed || editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun mergeLateWritesInto(to: SharedPreferences, memNow: Map<String, Any?>, baseMem: Map<String, Any?>, baseFresh: Map<String, Any?>): Boolean {
        return try {
            val target = HashMap(to.all)
            val editor = to.edit()
            var changed = false
            for ((key, value) in memNow) {
                if (baseMem.containsKey(key) && baseMem[key] == value) continue
                if (target.containsKey(key) && target[key] != baseFresh[key]) continue
                if (!target.containsKey(key) || target[key] != value) {
                    putEntryInto(editor, key, value)
                    changed = true
                }
            }
            for (key in target.keys) {
                if (memNow.containsKey(key)) continue
                if (!baseMem.containsKey(key)) continue
                if (target[key] != baseFresh[key]) continue
                editor.remove(key)
                changed = true
            }
            !changed || editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    private fun attemptUpgradeToPersistent(): Boolean {
        return try {
            val fresh = openEncryptedPrefs(appContext, PREFS_NAME)
            val mem = sharedPreferences
            val firstSnap: Map<String, Any?> = try { HashMap(snapshot()) } catch (_: Exception) { emptyMap<String, Any?>() }
            val firstTarget: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { HashMap<String, Any?>() }
            val adopt = firstSnap.isEmpty() && firstTarget.isNotEmpty()
            if (!adopt) {
                var stable = false
                var attempts = 0
                while (!stable && attempts < 5) {
                    attempts++
                    val cur: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
                    if (!syncPrefsInto(mem, fresh, cur)) return false
                    val after: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
                    val freshNow: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
                    stable = after == cur && freshNow == cur
                }
                if (!stable) return false
            }
            val baseMem: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
            val baseFresh: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
            sharedPreferences = fresh
            if (!adopt) {
                var guard = 0
                while (guard < 5) {
                    guard++
                    val memNow: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
                    val freshNow: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
                    if (memNow == freshNow) break
                    if (!mergeLateWritesInto(fresh, memNow, baseMem, baseFresh)) break
                }
            }
            synchronized(listenerLock) {
                for (l in listenerSet) {
                    try { fresh.registerOnSharedPreferenceChangeListener(l) } catch (_: Exception) { }
                }
            }
            clearStorageError()
            storageEncrypted = true
            true
        } catch (e: Exception) {
            markStorageError(describeStorageError(e))
            false
        }
    }

    private fun reopenStorage(): Boolean {
        val opened = openPrefs(appContext)
        sharedPreferences = opened.first
        storageEncrypted = opened.second
        synchronized(listenerLock) {
            for (l in listenerSet) {
                try { opened.first.registerOnSharedPreferenceChangeListener(l) } catch (_: Exception) { }
            }
        }
        return storageEncrypted
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

    private fun commitGuarded(editor: android.content.SharedPreferences.Editor): Boolean {
        return try {
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val credentialLock = Any()

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }

        fun peekInstance(): PreferencesManager? = instance

        @Volatile
        private var sharedMasterKey: MasterKey? = null

        @Volatile
        private var lastStorageError: String = ""

        val storageFailureReason: String
            get() = lastStorageError

        private const val OPEN_ATTEMPTS = 3
        private const val UPGRADE_ATTEMPTS = 2

        fun getMasterKey(context: Context): MasterKey {
            return sharedMasterKey ?: synchronized(this) {
                sharedMasterKey ?: MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                    .also { sharedMasterKey = it }
            }
        }

        private fun resetCachedMasterKey() {
            synchronized(this) {
                sharedMasterKey = null
            }
        }

        private fun markStorageError(reason: String) {
            synchronized(this) {
                lastStorageError = reason
            }
        }

        private fun clearStorageError() {
            synchronized(this) {
                lastStorageError = ""
            }
        }

        private const val PREFS_NAME = "secure_prefs"

        private val secureRandom = java.security.SecureRandom()

        private fun openPrefs(context: Context): Pair<SharedPreferences, Boolean> {
            var failure: Exception? = null
            for (attempt in 1..OPEN_ATTEMPTS) {
                try {
                    val opened = openEncryptedPrefs(context, PREFS_NAME)
                    lastStorageError = ""
                    return Pair(opened, true)
                } catch (e: Exception) {
                    failure = e
                    synchronized(this) {
                        sharedMasterKey = null
                    }
                }
            }
            lastStorageError = describeStorageError(failure)
            android.util.Log.w("PreferencesManager", "Encrypted prefs unavailable, using volatile memory", failure)
            return Pair(MemoryPrefs(), false)
        }

        private fun describeStorageError(e: Exception?): String {
            if (e == null) return "unknown"
            val name = try { e.javaClass.simpleName.ifEmpty { "Exception" } } catch (_: Exception) { "Exception" }
            val message = try {
                e.message.orEmpty().replace(Regex("[^A-Za-z0-9 .:_-]"), " ").replace(Regex(" +"), " ").trim()
            } catch (_: Exception) {
                ""
            }
            val safeName = name.replace(Regex("[^A-Za-z0-9]"), "")
            val safeMessage = message.take(120)
            return if (safeMessage.isEmpty()) safeName else safeName + ": " + safeMessage
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
        private const val KEY_LAST_SMS_TIMESTAMP = "last_sms_timestamp"
        private const val KEY_LAST_CALL_TIMESTAMP = "last_call_timestamp"
        private const val KEY_SIM_IDENTITY = "sim_identity"
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
        private const val KEY_LAST_LOC_ACC = "last_loc_acc_m"
        private const val KEY_TRAIL = "trail_24"
        private const val KEY_DAILY_FACTS = "daily_facts"
        private const val KEY_ANOMALY_ALERTS = "anomaly_alerts"
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
        private const val KEY_A11Y_USAGE_DATE = "a11y_usage_date"
        private const val KEY_A11Y_USAGE_JSON = "a11y_usage_json"
        private const val KEY_A11Y_KNOWN_APPS = "a11y_known_apps"
        private const val KEY_A11Y_KNOWN_SEEDED = "a11y_known_seeded"
        private const val KEY_A11Y_APP_ALERT = "a11y_app_alert"
        private const val KEY_A11Y_TAMPER = "a11y_tamper"
        private const val KEY_A11Y_SETTINGS_GRACE = "a11y_settings_grace"
        private const val KEY_A11Y_APP_WATCH = "a11y_app_watch"
        private const val KEY_A11Y_APP_WATCH_PACKAGES = "a11y_app_watch_packages"
        private const val KEY_A11Y_EVER_ENABLED = "a11y_ever_enabled"
        private const val KEY_A11Y_DISABLED_ALERT_AT = "a11y_disabled_alert_at"
        private const val KEY_A11Y_KEYWORD_DAY = "a11y_keyword_day"
        private const val KEY_A11Y_KEYWORD_COUNT = "a11y_keyword_count"
        private const val KEY_A11Y_KEYWORD_JSON = "a11y_keyword_json"

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

        fun resetEncryptedStorage(context: Context): StorageRecovery {
            var current: PreferencesManager? = null
            var reopened = false
            synchronized(this) {
                try {
                    val app = context.applicationContext
                    app.deleteSharedPreferences(PREFS_NAME)
                    app.deleteSharedPreferences("encrypted_queue")
                } catch (e: Exception) {
                    android.util.Log.w("PreferencesManager", "Gagal menghapus prefs terenkripsi", e)
                    return StorageRecovery.FAILED
                }
                try {
                    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                    keyStore.load(null)
                    keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                } catch (_: Exception) {
                }
                sharedMasterKey = null
                current = instance
                reopened = try { current?.reopenStorage() == true } catch (_: Exception) { false }
            }
            return try {
                val ok = if (current != null) reopened else getInstance(context).isStorageEncrypted
                if (ok) {
                    try {
                        MessageQueue.getInstance(context).reopenAfterStorageReset()
                    } catch (_: Exception) {
                    }
                    StorageRecovery.RESET
                } else {
                    StorageRecovery.FAILED
                }
            } catch (_: Exception) {
                StorageRecovery.FAILED
            }
        }
    }

    var botToken: String
        get() = prefString(KEY_BOT_TOKEN, "")
        set(value) {
            synchronized(credentialLock) {
                sharedPreferences.edit().putString(KEY_BOT_TOKEN, value).apply()
            }
        }

    var chatId: String
        get() = prefString(KEY_CHAT_ID, "")
        set(value) {
            synchronized(credentialLock) {
                sharedPreferences.edit().putString(KEY_CHAT_ID, value).apply()
            }
        }

    val isMonitoringEnabled: Boolean
        get() = prefBoolean(KEY_MONITORING_ENABLED, false)

    var lastSyncTime: Long
        get() = prefLong(KEY_LAST_SYNC, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncInterval: Int
        get() = prefInt(KEY_SYNC_INTERVAL, com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL)
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    val lastSmsId: Long
        get() = prefLong(KEY_LAST_SMS_ID, 0L)

    val lastSmsTimestamp: Long
        get() = prefLong(KEY_LAST_SMS_TIMESTAMP, 0L)

    val lastCallTimestamp: Long
        get() = prefLong(KEY_LAST_CALL_TIMESTAMP, 0L)

    val lastCallId: Long
        get() = prefLong(KEY_LAST_CALL_ID, 0L)

    var userDisabledMonitoring: Boolean
        get() = prefBoolean(KEY_USER_DISABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_DISABLED, value).apply()

    var initialSyncDone: Boolean
        get() = prefBoolean(KEY_INITIAL_SYNC_DONE, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, value).apply()

    fun setInitialSyncDoneSync(done: Boolean): Boolean {
        return try {
            commitGuarded(sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, done))
        } catch (_: Exception) {
            false
        }
    }

    var cameraInterval: Int
        get() = prefInt(KEY_CAMERA_INTERVAL, 1)
        set(value) = sharedPreferences.edit().putInt(KEY_CAMERA_INTERVAL, value).apply()

    var lastUpdateId: Long
        get() = prefLong(KEY_LAST_UPDATE_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPDATE_ID, value).apply()

    private fun commitWithRetry(build: (android.content.SharedPreferences.Editor) -> android.content.SharedPreferences.Editor): Boolean {
        return try {
            build(sharedPreferences.edit()).commit() || build(sharedPreferences.edit()).commit()
        } catch (_: Exception) {
            false
        }
    }

    fun setLastUpdateIdSync(id: Long): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_UPDATE_ID, id) }
    }

    var lastPhotoTime: Long
        get() = prefLong(KEY_LAST_PHOTO_TIME, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_PHOTO_TIME, value).apply()

    var lastCameraErrorNotice: Long
        get() = prefLong(KEY_LAST_CAM_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CAM_ERR_NOTICE, value).apply()

    var lastUploadErrorNotice: Long
        get() = prefLong(KEY_LAST_UPLOAD_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPLOAD_ERR_NOTICE, value).apply()

    var monitoringPaused: Boolean
        get() = prefBoolean(KEY_MONITORING_PAUSED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_PAUSED, value).apply()

    var photoPausedUntil: Long
        get() = prefLong(KEY_PHOTO_PAUSED_UNTIL, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PHOTO_PAUSED_UNTIL, value).apply()

    var photoPauseSource: Int
        get() = prefInt(KEY_PHOTO_PAUSE_SOURCE, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_PHOTO_PAUSE_SOURCE, value).apply()

    var patrolEnabled: Boolean
        get() = prefBoolean(KEY_PATROL_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_PATROL_ENABLED, value).apply()

    var nightMode: Boolean
        get() = prefBoolean(KEY_NIGHT_MODE, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NIGHT_MODE, value).apply()

    var patrolInterval: Int
        get() = prefInt(KEY_PATROL_INTERVAL, 30)
        set(value) = sharedPreferences.edit().putInt(KEY_PATROL_INTERVAL, value).apply()

    var cameraFacing: String
        get() = prefString(KEY_CAMERA_FACING, "front")
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
        get() = prefBoolean(KEY_USER_CONSENTED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_CONSENTED, value).apply()

    var notifForwardEnabled: Boolean
        get() = prefBoolean(KEY_NOTIF_FORWARD, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NOTIF_FORWARD, value).apply()

    var otpMaskEnabled: Boolean
        get() = prefBoolean(KEY_OTP_MASK, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_OTP_MASK, value).apply()

    var restoreStrict: Boolean
        get() = prefBoolean(KEY_RESTORE_STRICT, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_RESTORE_STRICT, value).apply()

    fun ensureRestoreCodeSecret(): String {
        synchronized(auditLock) {
            try {
                val cur = sharedPreferences.getString(KEY_RESTORE_SECRET, "") ?: ""
                if (cur.length == 64 && cur.all { it in '0'..'9' || it in 'a'..'f' }) return cur
                val fresh = com.redeye.parentalmonitor.utils.RestoreCode.generateSecret()
                val saved = try {
                    sharedPreferences.edit().putString(KEY_RESTORE_SECRET, fresh).commit()
                } catch (_: Exception) {
                    false
                }
                if (!saved) return ""
                return fresh
            } catch (_: Exception) {
                return ""
            }
        }
    }

    val auditLog: String
        get() = prefString(KEY_AUDIT_LOG, "")

    fun clearAuditLog() {
        synchronized(auditLock) {
            try {
                sharedPreferences.edit().remove(KEY_AUDIT_LOG).apply()
            } catch (_: Exception) {
            }
        }
    }

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
                sharedPreferences.edit().putString(KEY_AUDIT_LOG, lines.joinToString("\n")).apply()
            } catch (_: Exception) {
            }
        }
    }

    var credentialError: String
        get() = prefString(KEY_CRED_ERROR, "")
        set(value) = sharedPreferences.edit().putString(KEY_CRED_ERROR, value).apply()

    var credentialErrorAt: Long
        get() = prefLong(KEY_CRED_ERROR_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_CRED_ERROR_AT, value).apply()

    fun setCredentialError(code: String, at: Long) {
        try {
            synchronized(credentialLock) {
                sharedPreferences.edit().putString(KEY_CRED_ERROR, code).putLong(KEY_CRED_ERROR_AT, at).apply()
            }
        } catch (_: Exception) {
        }
    }

    fun setCredentialErrorIfCredentialsMatch(token: String, chat: String, code: String, at: Long): Boolean {
        return synchronized(credentialLock) {
            if (botToken != token || chatId != chat) return@synchronized false
            try {
                sharedPreferences.edit().putString(KEY_CRED_ERROR, code).putLong(KEY_CRED_ERROR_AT, at).apply()
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    var commandsTokenHash: String
        get() = prefString(KEY_COMMANDS_TOKEN_HASH, "")
        set(value) = sharedPreferences.edit().putString(KEY_COMMANDS_TOKEN_HASH, value).apply()

    var ringPrevVolume: Int
        get() = prefInt(KEY_RING_PREV_VOL, -1)
        set(value) = sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, value).apply()

    var ringSavedAt: Long
        get() = prefLong(KEY_RING_SAVED_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_RING_SAVED_AT, value).apply()

    var lastAliveAt: Long
        get() = prefLong(KEY_LAST_ALIVE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_ALIVE_AT, value).apply()

    val lastAliveElapsed: Long
        get() = prefLong(KEY_LAST_ALIVE_ELAPSED, 0L)

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
        get() = prefInt(KEY_LIVENESS_KILLS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_LIVENESS_KILLS, value).apply()

    var livenessKillsUnreported: Int
        get() = prefInt(KEY_LIVENESS_KILLS_UNREPORTED, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_LIVENESS_KILLS_UNREPORTED, value).apply()

    var livenessReportAt: Long
        get() = prefLong(KEY_LIVENESS_REPORT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LIVENESS_REPORT_AT, value).apply()

    var heartbeatHours: Int
        get() = prefInt(KEY_HEARTBEAT_HOURS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_HEARTBEAT_HOURS, value).apply()

    var heartbeatAt: Long
        get() = prefLong(KEY_HEARTBEAT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_HEARTBEAT_AT, value).apply()

    var liveTestPending: Boolean
        get() = prefBoolean(KEY_LIVE_TEST_PENDING, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_PENDING, value).apply()

    var liveTestStartedElapsed: Long
        get() = prefLong(KEY_LIVE_TEST_STARTED_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LIVE_TEST_STARTED_ELAPSED, value).apply()

    var liveTestByAlarm: Boolean
        get() = prefBoolean(KEY_LIVE_TEST_BY_ALARM, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_BY_ALARM, value).apply()

    var liveTestLastSec: Int
        get() = prefInt(KEY_LIVE_TEST_LAST_SEC, -1)
        set(value) = sharedPreferences.edit().putInt(KEY_LIVE_TEST_LAST_SEC, value).apply()

    val lastSendOkElapsed: Long
        get() = prefLong(KEY_LAST_SEND_OK_ELAPSED, 0L)

    fun setLastSendOkMarker(elapsedAt: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_LAST_SEND_OK_ELAPSED, elapsedAt)
                .apply()
        } catch (_: Exception) {
        }
    }

    var silentHealAt: Long
        get() = prefLong(KEY_SILENT_HEAL_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_SILENT_HEAL_AT, value).apply()

    var silentHealStage: Int
        get() = prefInt(KEY_SILENT_HEAL_STAGE, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_SILENT_HEAL_STAGE, value).apply()

    var silentHeals: Int
        get() = prefInt(KEY_SILENT_HEALS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_SILENT_HEALS, value).apply()

    var vehicleAlertEnabled: Boolean
        get() = prefBoolean(KEY_VEHICLE_ALERT_ENABLED, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_VEHICLE_ALERT_ENABLED, value).apply()

    var lastVehicleAlertAt: Long
        get() = prefLong(KEY_LAST_VEHICLE_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_VEHICLE_ALERT_AT, value).apply()

    var dataSaverEnabled: Boolean
        get() = prefBoolean(KEY_DATA_SAVER_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DATA_SAVER_ENABLED, value).apply()

    var dataSaverNoticeAt: Long
        get() = prefLong(KEY_DATA_SAVER_NOTICE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_DATA_SAVER_NOTICE_AT, value).apply()

    fun setRingStateSync(prev: Int, at: Long): Boolean {
        return try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, prev).putLong(KEY_RING_SAVED_AT, at))
        } catch (_: Exception) {
            false
        }
    }

    fun clearRingStateSync(): Boolean {
        return try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, -1).putLong(KEY_RING_SAVED_AT, 0L))
        } catch (_: Exception) {
            false
        }
    }

    var pendingSmsNumber: String
        get() = prefString(KEY_PENDING_SMS_NUMBER, "")
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_NUMBER, value).apply()

    var pendingSmsText: String
        get() = prefString(KEY_PENDING_SMS_TEXT, "")
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_TEXT, value).apply()

    var pendingSmsAt: Long
        get() = prefLong(KEY_PENDING_SMS_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PENDING_SMS_AT, value).apply()

    var lastSmsSendAt: Long
        get() = prefLong(KEY_LAST_SMS_SEND_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SMS_SEND_AT, value).apply()

    var pendingSmsOwner: String
        get() = prefString(KEY_PENDING_SMS_OWNER, "")
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_SMS_OWNER, value).apply()

    var ownerUserId: Long
        get() = prefLong(KEY_OWNER_ID, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_OWNER_ID, value).apply()

    var accessPin: String
        get() = prefString(KEY_ACCESS_PIN, "")
        set(value) = sharedPreferences.edit().putString(KEY_ACCESS_PIN, value).apply()

    fun setOwnerIdSync(id: Long): Boolean {
        return try {
            sharedPreferences.edit().putLong(KEY_OWNER_ID, id).commit()
        } catch (_: Exception) {
            false
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
        get() = prefString(KEY_OWNER_PAIR_CODE, "")
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

    fun currentOwnerPairCode(): String {
        synchronized(pairCodeLock) {
            val cur = try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { return "" }
            if (cur.length != 6 || !cur.all { it in '0'..'9' }) return ""
            val at = try { sharedPreferences.getLong(KEY_OWNER_PAIR_AT, 0L) } catch (_: Exception) { 0L }
            if (at == 0L) return cur
            val now = System.currentTimeMillis()
            return if (at <= now && now - at < 24 * 60 * 60_000L) cur else ""
        }
    }

    var pendingMsgDrops: Int
        get() = prefInt(KEY_PENDING_MSG_DROPS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_PENDING_MSG_DROPS, value).apply()

    fun setPendingMsgDropsSync(value: Int): Boolean {
        return try {
            sharedPreferences.edit().putInt(KEY_PENDING_MSG_DROPS, value).commit()
        } catch (_: Exception) {
            false
        }
    }

    var pendingNotifDrops: Int
        get() = prefInt(KEY_PENDING_NOTIF_DROPS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_PENDING_NOTIF_DROPS, value).apply()

    fun setPendingNotifDropsSync(value: Int): Boolean {
        return try {
            commitGuarded(sharedPreferences.edit().putInt(KEY_PENDING_NOTIF_DROPS, value))
        } catch (_: Exception) {
            false
        }
    }

    var permSnapshot: Int
        get() = prefInt(KEY_PERM_SNAPSHOT, -1)
        set(value) = sharedPreferences.edit().putInt(KEY_PERM_SNAPSHOT, value).apply()

    fun setPermSnapshotSync(value: Int): Boolean {
        return try {
            sharedPreferences.edit().putInt(KEY_PERM_SNAPSHOT, value).commit()
        } catch (_: Exception) {
            false
        }
    }

    val lastPhotoHash: Long
        get() = prefLong(KEY_LAST_PHOTO_HASH, 0L)

    fun setLastPhotoHashSync(value: Long): Boolean {
        return try {
            sharedPreferences.edit().putLong(KEY_LAST_PHOTO_HASH, value).commit()
        } catch (_: Exception) {
            false
        }
    }

    var digestAt: Long
        get() = prefLong(KEY_DIGEST_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_DIGEST_AT, value).apply()

    var batteryAlertAt: Long
        get() = prefLong(KEY_BATTERY_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_BATTERY_ALERT_AT, value).apply()

    var lastLocLatE6: Long
        get() = prefLong(KEY_LAST_LOC_LAT, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_LAT, value).apply()

    var lastLocLonE6: Long
        get() = prefLong(KEY_LAST_LOC_LON, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_LON, value).apply()

    var lastLocAt: Long
        get() = prefLong(KEY_LAST_LOC_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_LOC_AT, value).apply()

    var lastLocAccM: Float
        get() = prefFloat(KEY_LAST_LOC_ACC, -1f)
        set(value) = sharedPreferences.edit().putFloat(KEY_LAST_LOC_ACC, value).apply()

    var simIdentity: String
        get() = prefString(KEY_SIM_IDENTITY, "")
        set(value) = sharedPreferences.edit().putString(KEY_SIM_IDENTITY, value).apply()

    var trail24: String
        get() = prefString(KEY_TRAIL, "")
        set(value) = sharedPreferences.edit().putString(KEY_TRAIL, value).apply()

    var dailyFacts: String
        get() = prefString(KEY_DAILY_FACTS, "")
        set(value) = sharedPreferences.edit().putString(KEY_DAILY_FACTS, value).apply()

    var anomalyAlerts: String
        get() = prefString(KEY_ANOMALY_ALERTS, "")
        set(value) = sharedPreferences.edit().putString(KEY_ANOMALY_ALERTS, value).apply()

    fun recordSentLocation(lat: Double, lon: Double, trail: String?, accuracyM: Float = -1f) {
        try {
            val editor = sharedPreferences.edit()
                .putLong(KEY_LAST_LOC_LAT, (lat * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_LON, (lon * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_AT, System.currentTimeMillis())
            if (accuracyM >= 0f) editor.putFloat(KEY_LAST_LOC_ACC, accuracyM)
            if (trail != null) editor.putString(KEY_TRAIL, trail)
            editor.apply()
        } catch (_: Exception) {
        }
    }

    var begadangDate: String
        get() = prefString(KEY_BEGADANG_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_BEGADANG_DATE, value).apply()

    var jemputRadiusM: Int
        get() = prefInt(KEY_JEMPUT_RAD, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_JEMPUT_RAD, value).apply()

    var permHealthAlertAt: Long
        get() = prefLong(KEY_PERM_HEALTH_ALERT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PERM_HEALTH_ALERT, value).apply()

    fun setPermHealthAlertAtSync(value: Long): Boolean {
        return try {
            sharedPreferences.edit().putLong(KEY_PERM_HEALTH_ALERT, value).commit()
        } catch (_: Exception) {
            false
        }
    }

    var watchKeywords: String
        get() = prefString(KEY_WATCH_KEYWORDS, "")
        set(value) = sharedPreferences.edit().putString(KEY_WATCH_KEYWORDS, value).apply()

    var a11yUsageDate: String
        get() = prefString(KEY_A11Y_USAGE_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_DATE, value).apply()

    var a11yUsageJson: String
        get() = prefString(KEY_A11Y_USAGE_JSON, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_JSON, value).apply()

    fun setA11yUsage(date: String, json: String) {
        try {
            sharedPreferences.edit()
                .putString(KEY_A11Y_USAGE_DATE, date)
                .putString(KEY_A11Y_USAGE_JSON, json)
                .apply()
        } catch (_: Exception) {
        }
    }

    var a11yKeywordAlertDay: String
        get() = prefString(KEY_A11Y_KEYWORD_DAY, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_KEYWORD_DAY, value).apply()

    var a11yKeywordAlertCount: Int
        get() = prefInt(KEY_A11Y_KEYWORD_COUNT, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_A11Y_KEYWORD_COUNT, value).apply()

    var a11yKeywordAlertJson: String
        get() = prefString(KEY_A11Y_KEYWORD_JSON, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_KEYWORD_JSON, value).apply()

    fun setA11yKeywordAlertState(day: String, count: Int, json: String) {
        try {
            sharedPreferences.edit()
                .putString(KEY_A11Y_KEYWORD_DAY, day)
                .putInt(KEY_A11Y_KEYWORD_COUNT, count)
                .putString(KEY_A11Y_KEYWORD_JSON, json)
                .apply()
        } catch (_: Exception) {
        }
    }

    var a11yKnownSeeded: Boolean
        get() = prefBoolean(KEY_A11Y_KNOWN_SEEDED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_A11Y_KNOWN_SEEDED, value).apply()

    var a11yAppAlert: Boolean
        get() = prefBoolean(KEY_A11Y_APP_ALERT, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_A11Y_APP_ALERT, value).apply()

    var a11yTamperEnabled: Boolean
        get() = prefBoolean(KEY_A11Y_TAMPER, true)
        set(value) = sharedPreferences.edit().putBoolean(KEY_A11Y_TAMPER, value).apply()

    var a11ySettingsGraceUntil: Long
        get() = prefLong(KEY_A11Y_SETTINGS_GRACE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_SETTINGS_GRACE, value).apply()

    var a11yAppWatchEnabled: Boolean
        get() = prefBoolean(KEY_A11Y_APP_WATCH, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_A11Y_APP_WATCH, value).apply()

    var a11yAppWatchPackages: String
        get() = prefString(KEY_A11Y_APP_WATCH_PACKAGES, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_APP_WATCH_PACKAGES, value).apply()

    fun a11yAppWatchPackageSet(): MutableSet<String> {
        return try {
            a11yAppWatchPackages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        } catch (_: Exception) {
            HashSet()
        }
    }

    fun setA11yAppWatchPackageSet(packages: Collection<String>) {
        val value = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
        a11yAppWatchPackages = value
    }

    var accessibilityEverEnabled: Boolean
        get() = prefBoolean(KEY_A11Y_EVER_ENABLED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_A11Y_EVER_ENABLED, value).apply()

    var a11yDisabledAlertAt: Long
        get() = prefLong(KEY_A11Y_DISABLED_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_DISABLED_ALERT_AT, value).apply()

    fun a11yKnownApps(): MutableSet<String> {
        return try { sharedPreferences.getStringSet(KEY_A11Y_KNOWN_APPS, emptySet())?.toHashSet() ?: HashSet() } catch (_: Exception) { HashSet() }
    }

    fun rememberA11yApp(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            val current = a11yKnownApps()
            if (packageName in current) return false
            current.add(packageName)
            sharedPreferences.edit().putStringSet(KEY_A11Y_KNOWN_APPS, current).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun seedA11yKnownApps(packages: Collection<String>) {
        try {
            val current = a11yKnownApps()
            current.addAll(packages)
            sharedPreferences.edit()
                .putStringSet(KEY_A11Y_KNOWN_APPS, current)
                .putBoolean(KEY_A11Y_KNOWN_SEEDED, true)
                .apply()
        } catch (_: Exception) {
        }
    }

    var homeLatE6: Long
        get() = prefLong(KEY_HOME_LAT, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_HOME_LAT, value).apply()

    var homeLonE6: Long
        get() = prefLong(KEY_HOME_LON, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_HOME_LON, value).apply()

    var homeRadiusM: Int
        get() = prefInt(KEY_HOME_RAD, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_HOME_RAD, value).apply()

    var homeState: Int
        get() = prefInt(KEY_HOME_STATE, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_HOME_STATE, value).apply()

    var schoolLatE6: Long
        get() = prefLong(KEY_SCHOOL_LAT, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_LAT, value).apply()

    var schoolLonE6: Long
        get() = prefLong(KEY_SCHOOL_LON, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_LON, value).apply()

    var schoolRadiusM: Int
        get() = prefInt(KEY_SCHOOL_RAD, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_SCHOOL_RAD, value).apply()

    var schoolState: Int
        get() = prefInt(KEY_SCHOOL_STATE, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_SCHOOL_STATE, value).apply()

    var nightDigestDate: String
        get() = prefString(KEY_NIGHT_DIGEST_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_NIGHT_DIGEST_DATE, value).apply()

    var lastDataAt: Long
        get() = prefLong(KEY_LAST_DATA_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_DATA_AT, value).apply()

    var locFailCount: Int
        get() = prefInt(KEY_LOC_FAIL_COUNT, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_LOC_FAIL_COUNT, value).apply()

    var healthAlertAt: Long
        get() = prefLong(KEY_HEALTH_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_HEALTH_ALERT_AT, value).apply()

    var watchNumbers: String
        get() = prefString(KEY_WATCH_NUMBERS, "")
        set(value) = sharedPreferences.edit().putString(KEY_WATCH_NUMBERS, value).apply()

    var curfewTime: String
        get() = prefString(KEY_CURFEW_TIME, "")
        set(value) = sharedPreferences.edit().putString(KEY_CURFEW_TIME, value).apply()

    var curfewAlertDate: String
        get() = prefString(KEY_CURFEW_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_CURFEW_DATE, value).apply()

    var schoolAlertDate: String
        get() = prefString(KEY_SCHOOL_DATE, "")
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

    fun setSmsCursorSync(id: Long, timestamp: Long = 0L): Boolean {
        return commitWithRetry { editor ->
            editor.putLong(KEY_LAST_SMS_ID, id)
            if (timestamp > 0L) editor.putLong(KEY_LAST_SMS_TIMESTAMP, timestamp)
            editor
        }
    }

    fun setCallCursorSync(timestamp: Long, id: Long): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_CALL_TIMESTAMP, timestamp).putLong(KEY_LAST_CALL_ID, id) }
    }

    fun resetSmsCursorSync(): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_SMS_ID, 0L).putLong(KEY_LAST_SMS_TIMESTAMP, 0L) }
    }

    fun resetCallCursorSync(): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_CALL_TIMESTAMP, 0L).putLong(KEY_LAST_CALL_ID, 0L) }
    }

    fun setLastSmsSendAtSync(now: Long): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_SMS_SEND_AT, now) }
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
            editor.putLong(KEY_LAST_SMS_TIMESTAMP, 0L)
            editor.putLong(KEY_LAST_CALL_TIMESTAMP, 0L)
            editor.putLong(KEY_LAST_CALL_ID, 0L)
            editor.putLong(KEY_OWNER_ID, 0L)
        }
        editor.putString(KEY_CRED_ERROR, "")
        editor.putLong(KEY_CRED_ERROR_AT, 0L)
    }

    fun setChatIdSync(newChatId: String): Boolean {
        return try {
            synchronized(credentialLock) {
                sharedPreferences.edit().putString(KEY_CHAT_ID, newChatId).commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun saveCredentials(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int): Boolean {
        if (!storageEncrypted) {
            val upgraded = try { upgradeToPersistent() } catch (_: Exception) { false }
            if (!upgraded || !storageEncrypted) return false
        }
        return synchronized(credentialLock) {
            try {
                val editor = sharedPreferences.edit()
                putCredentialState(editor, newToken, newChatId)
                editor.putString(KEY_BOT_TOKEN, newToken)
                editor.putString(KEY_CHAT_ID, newChatId)
                editor.putInt(KEY_SYNC_INTERVAL, newSyncInterval)
                editor.putInt(KEY_CAMERA_INTERVAL, newCameraInterval)
                editor.commit()
            } catch (_: Exception) {
                false
            }
        }
    }

    fun setMonitoringActive(active: Boolean): Boolean {
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
        return commitGuarded(editor)
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
