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

    // Berkas penyimpanan terenkripsi ADA di disk tetapi tak bisa
    // dibuka (kunci KeyStore hilang/ter-invalidasi): perangkat ini
    // PERNAH terkonfigurasi dan kredensialnya kini tak terbaca
    // (issue #916) — keadaan yang harus dibedakan dari pemasangan
    // baru yang memang belum berisi apa pun.
    val credentialsLikelyLost: Boolean
        get() = !storageEncrypted && try {
            java.io.File(appContext.filesDir.parentFile, "shared_prefs/secure_prefs.xml").exists()
        } catch (_: Exception) {
            false
        }

    val prefsRmwLock = Any()
    private val listenerLock = Any()
    private val listenerSet = mutableSetOf<android.content.SharedPreferences.OnSharedPreferenceChangeListener>()

    private val upgradeLock = Any()
    private val pairCodeLock = Any()
    private val smsPendingLock = Any()
    private val auditLock = Any()
    private val restoreSecretLock = Any()

    private enum class UpgradeResult { SUCCESS, UNSTABLE, FAILED }

    fun upgradeToPersistent(): Boolean {
        if (storageEncrypted) return true
        synchronized(upgradeLock) {
            if (storageEncrypted) return true
            for (attempt in 1..UPGRADE_ATTEMPTS) {
                if (attempt > 1) {
                    resetCachedMasterKey()
                }
                val result = attemptUpgradeToPersistent()
                if (result == UpgradeResult.SUCCESS) return true
                if (result == UpgradeResult.UNSTABLE && attempt < UPGRADE_ATTEMPTS) {
                    try { Thread.sleep(150L) } catch (_: InterruptedException) { }
                }
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
            var result = false
            var done = false
            var tries = 0
            val raced = mutableSetOf<String>()
            var finalPass = false
            while (!done) {
                tries++
                val target = HashMap(to.all)
                val editor = to.edit()
                var changed = false
                val touched = mutableSetOf<String>()
                for ((key, value) in memNow) {
                    if (key in raced) continue
                    if (baseMem.containsKey(key) && baseMem[key] == value) continue
                    if (target.containsKey(key) && target[key] != baseFresh[key]) continue
                    if (!target.containsKey(key) && baseFresh.containsKey(key)) continue
                    if (!target.containsKey(key) || target[key] != value) {
                        putEntryInto(editor, key, value)
                        changed = true
                        touched.add(key)
                    }
                }
                for (key in target.keys) {
                    if (key in raced) continue
                    if (memNow.containsKey(key)) continue
                    if (!baseMem.containsKey(key)) continue
                    if (target[key] != baseFresh[key]) continue
                    editor.remove(key)
                    changed = true
                    touched.add(key)
                }
                if (!changed) {
                    result = true
                    done = true
                } else if (finalPass) {
                    result = editor.commit()
                    done = true
                } else {
                    val latest = HashMap(to.all)
                    val drifted = touched.filter { key ->
                        latest.containsKey(key) != target.containsKey(key) || latest[key] != target[key]
                    }
                    if (drifted.isEmpty()) {
                        result = editor.commit()
                        done = true
                    } else {
                        raced.addAll(drifted)
                        if (tries >= 3) finalPass = true
                    }
                }
            }
            result
        } catch (_: Exception) {
            false
        }
    }

    private fun attemptUpgradeToPersistent(): UpgradeResult {
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
                    // Gabung, jangan timpa (issue #942): kunci yang
                    // hanya ada di store persisten lama (mis.
                    // kredensial) wajib selamat dari upgrade —
                    // snapshot volatile menang per kunci yang sama,
                    // bukan menghapus sisanya. Penghapusan yang
                    // terjadi selama sesi volatile memang tak ikut
                    // terbawa; kehilangan penghapusan jauh lebih
                    // aman daripada kehilangan kredensial.
                    val merged = HashMap(firstTarget)
                    merged.putAll(cur)
                    if (!syncPrefsInto(mem, fresh, merged)) return UpgradeResult.FAILED
                    val after: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
                    val freshNow: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
                    stable = after == cur && freshNow == merged
                    if (!stable && attempts < 5) {
                        try { Thread.sleep(50L) } catch (_: Exception) { }
                    }
                }
                if (!stable) return UpgradeResult.UNSTABLE
                // Samakan isi volatile dengan state gabungan agar
                // loop konvergensi sesudah saklar membandingkan
                // dua store yang setara (issue #942). Kegagalan di
                // sini tak menggagalkan upgrade: loop konvergensi
                // tetap menyelaraskan tulisan yang datang telat.
                try {
                    val targetNow: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
                    if (mem != null) syncPrefsInto(fresh, mem, targetNow)
                } catch (_: Exception) { }
            }
            val baseMem: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
            val baseFresh: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
            sharedPreferences = fresh
            if (!adopt) {
                var guard = 0
                var stablePasses = 0
                while (guard < 10 && stablePasses < 2) {
                    guard++
                    val memNow: Map<String, Any?> = try { HashMap(mem?.all ?: emptyMap<String, Any?>()) } catch (_: Exception) { emptyMap<String, Any?>() }
                    val freshNow: Map<String, Any?> = try { HashMap(fresh.all) } catch (_: Exception) { emptyMap<String, Any?>() }
                    if (memNow == freshNow) {
                        stablePasses++
                        if (stablePasses < 2) {
                            try { Thread.sleep(50L) } catch (_: InterruptedException) { }
                        }
                        continue
                    }
                    stablePasses = 0
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
            UpgradeResult.SUCCESS
        } catch (e: Exception) {
            markStorageError(describeStorageError(e))
            UpgradeResult.FAILED
        }
    }

    // Pertukaran penyimpanan diserialkan dengan upgrade
    // (issue #1102): reopenStorage dulu berjalan tanpa
    // upgradeLock yang dipakai upgradeToPersistent, jadi
    // reset-dari-Setup dan refresh-upgrade bisa saling
    // menimpa hasil tukar beserta tulisan di antaranya.
    private fun reopenStorage(): Boolean {
        synchronized(upgradeLock) {
            return reopenStorageLocked()
        }
    }

    private fun reopenStorageLocked(): Boolean {
        val opened = openPrefs(appContext)
        sharedPreferences = opened.first
        storageEncrypted = opened.second
        // Penyimpanan diganti (reset): cermin himpunan dikenal
        // memuat isi berkas LAMA dan wajib dibuang, kalau tidak
        // jawaban "sudah dikenal" berasal dari data pra-reset
        // dan penulisan berikutnya membangkitkan himpunan lama
        // ke prefs baru (issue #1047).
        synchronized(a11yKnownAppsLock) { knownAppsMirror = null }
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
        private const val UPGRADE_ATTEMPTS = 4

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

        // RENCANA MIGRASI security-crypto (issue #1196):
        // seluruh API EncryptedSharedPreferences/MasterKey
        // terdepresiasi sejak 1.1.0-alpha07 dan artefaknya tak
        // berencana rilis lagi. Fungsi ini SATU-SATUNYA corong
        // konstruksinya di repo (terverifikasi grep) — semua
        // konsumen lewat openEncryptedPrefs — jadi migrasi
        // cukup menyentuh satu titik ini. Rancangan pengganti:
        // SharedPreferences berkas biasa yang nilainya
        // dienkripsi AES-256-GCM dengan kunci AndroidKeyStore
        // dan kuncinya ditransformasi deterministik (pengganti
        // peran AES256_SIV agar pencarian kunci tetap bisa);
        // langkahnya: baca warisan -> tulis format baru ->
        // verifikasi baca -> hapus warisan, di belakang
        // openEncryptedPrefs yang sama. Pemicu eksekusi:
        // sebelum menaikkan toolchain yang membuang artefak
        // ini, atau bila artefak ditarik dari repositori.
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
        const val KEY_BOT_USERNAME = "bot_username"
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
        private const val KEY_CREDENTIAL_GENERATION = "credential_generation"
        private const val KEY_LAST_PHOTO_TIME = "last_photo_time"
        private const val KEY_LAST_CAM_ERR_NOTICE = "last_cam_err_notice"
        private const val KEY_LAST_UPLOAD_ERR_NOTICE = "last_upload_err_notice"
        private const val KEY_LAST_CAM_ERR_AT = "last_cam_err_at"
        private const val KEY_LAST_UPLOAD_ERR_AT = "last_upload_err_at"
        const val KEY_MONITORING_PAUSED = "monitoring_paused"
        const val KEY_PHOTO_PAUSED_UNTIL = "photo_paused_until"
        const val KEY_PHOTO_PAUSE_SOURCE = "photo_pause_source"
        private const val KEY_PHOTO_PAUSED_ELAPSED_UNTIL = "photo_paused_elapsed_until"
        const val KEY_PATROL_ENABLED = "patrol_enabled"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_PATROL_INTERVAL = "patrol_interval"
        private const val KEY_CAMERA_FACING = "camera_facing"
        const val KEY_USER_CONSENTED = "user_consented_monitoring"
        const val KEY_NOTIF_FORWARD = "notif_forward_enabled"
        private const val KEY_OTP_MASK = "otp_mask_enabled"
        private const val KEY_RESTORE_STRICT = "restore_strict_enabled"
        private const val KEY_RESTORE_PIN_FAIL_COUNT = "restore_pin_fail_count"
        private const val KEY_RESTORE_PIN_LAST_KEY = "restore_pin_last_key"
        private const val KEY_RESTORE_PIN_LOCKOUT_UNTIL = "restore_pin_lockout_until"
        private const val KEY_RESTORE_PIN_LOCKOUT_ELAPSED_START = "restore_pin_lockout_elapsed_start"
        private const val KEY_ACCESS_PIN_FAIL_COUNT = "access_pin_fail_count"
        private const val KEY_ACCESS_PIN_LOCKOUT_UNTIL = "access_pin_lockout_until"
        private const val KEY_ACCESS_PIN_LOCKOUT_ELAPSED_START = "access_pin_lockout_elapsed_start"
        private const val KEY_RESTORE_CODE_FAIL_COUNT = "restore_code_fail_count"
        private const val KEY_RESTORE_CODE_LOCKOUT_UNTIL = "restore_code_lockout_until"
        private const val KEY_RESTORE_CODE_LOCKOUT_ELAPSED_START = "restore_code_lockout_elapsed_start"
        private const val KEY_RESTORE_SECRET = "restore_code_secret"
        private const val KEY_AUDIT_LOG = "audit_log"
        const val KEY_CRED_ERROR = "credential_error"
        private const val KEY_CRED_ERROR_AT = "credential_error_at"
        private const val KEY_CRED_ERROR_ELAPSED = "credential_error_elapsed"
        private const val KEY_COMMANDS_TOKEN_HASH = "commands_token_hash"
        private const val KEY_RING_PREV_VOL = "ring_prev_volume"
        private const val KEY_RING_SAVED_AT = "ring_saved_at"
        private const val KEY_PENDING_PROMPT_CMD = "pending_prompt_cmd"
        private const val KEY_PENDING_PROMPT_AT = "pending_prompt_at"
        private const val KEY_PENDING_SMS_NUMBER = "pending_sms_number"
        private const val KEY_PENDING_SMS_TEXT = "pending_sms_text"
        private const val KEY_PENDING_SMS_AT = "pending_sms_at"
        private const val KEY_PENDING_SMS_OWNER = "pending_sms_owner"
        private const val KEY_LAST_SMS_SEND_AT = "last_sms_send_at"
        const val KEY_OWNER_ID = "owner_user_id"
        const val KEY_ACCESS_PIN = "access_pin"
        private const val KEY_OWNER_PAIR_CODE = "owner_pair_code"
        private const val KEY_OWNER_PAIR_AT = "owner_pair_at"
        private const val KEY_PENDING_MSG_DROPS = "pending_msg_drops"
        private const val KEY_PENDING_NOTIF_DROPS = "pending_notif_drops"
        private const val KEY_DROP_NOTICE_BATCH = "drop_notice_batch"
        private const val KEY_FORWARDED_NOTIF_IDS = "forwarded_notif_ids"
        private const val KEY_NOTIF_GROUP_FORWARDED = "notif_group_forwarded"
        private const val KEY_PENDING_NOTIF_DROPS_SINCE = "pending_notif_drops_since"
        private const val KEY_PERM_HEALTH_ALERT = "perm_health_alert_at"
        private const val KEY_PERM_SNAPSHOT = "perm_snapshot"
        private const val KEY_LAST_PHOTO_HASH = "last_photo_hash"
        private const val KEY_HELD_PHOTO_HASH = "held_photo_hash"
        private const val KEY_HELD_PHOTO_HASHES = "held_photo_hashes"
        private const val KEY_DIGEST_AT = "digest_at"
        private const val KEY_PATROL_LAST_AT = "patrol_last_at"
        private const val KEY_PATROL_LAST_ELAPSED = "patrol_last_elapsed"
        private const val KEY_BATTERY_ALERT_AT = "battery_alert_at"
        private const val KEY_LAST_LOC_LAT = "last_loc_lat_e6"
        private const val KEY_LAST_LOC_LON = "last_loc_lon_e6"
        private const val KEY_LAST_LOC_AT = "last_loc_at"
        private const val KEY_LAST_LOC_ACC = "last_loc_acc_m"
        private const val KEY_TRAIL = "trail_24"
        private const val KEY_TRAIL_TRIMMED_FROM = "trail_trimmed_from"
        private const val KEY_DAILY_FACTS = "daily_facts"
        private const val KEY_ANOMALY_ALERTS = "anomaly_alerts"
        private const val KEY_ANOMALY_ALERTS_AT = "anomaly_alerts_at"
        private const val KEY_BEGADANG_DATE = "begadang_date"
        private const val KEY_BEGADANG_DATE_AT = "begadang_date_at"
        private const val KEY_JEMPUT_RAD = "jemput_radius_m"
        const val KEY_WATCH_KEYWORDS = "watch_keywords"
        private const val KEY_HOME_LAT = "home_lat_e6"
        private const val KEY_HOME_LON = "home_lon_e6"
        private const val KEY_HOME_RAD = "home_radius_m"
        private const val KEY_HOME_STATE = "home_state"
        private const val KEY_HOME_ALERT_AT = "home_alert_at"
        private const val KEY_SCHOOL_LAT = "school_lat_e6"
        private const val KEY_SCHOOL_LON = "school_lon_e6"
        private const val KEY_SCHOOL_RAD = "school_radius_m"
        private const val KEY_SCHOOL_STATE = "school_state"
        private const val KEY_SCHOOL_ALERT_AT = "school_alert_at"
        private const val KEY_NIGHT_DIGEST_DATE = "night_digest_date"
        private const val KEY_NIGHT_DIGEST_DONE_AT = "night_digest_done_at"
        private const val KEY_NIGHT_DIGEST_OFFSET = "night_digest_offset"
        private const val KEY_LAST_DATA_AT = "last_data_at"
        private const val KEY_LOC_FAIL_COUNT = "loc_fail_count"
        private const val KEY_HEALTH_ALERT_AT = "health_alert_at"
        const val KEY_WATCH_NUMBERS = "watch_numbers"
        private const val KEY_CURFEW_TIME = "curfew_time"
        private const val KEY_CURFEW_DATE = "curfew_alert_date"
        private const val KEY_CURFEW_DATE_AT = "curfew_alert_date_at"
        private const val KEY_SCHOOL_DATE = "school_alert_date"
        private const val KEY_SCHOOL_DATE_AT = "school_alert_date_at"
        private const val KEY_LAST_ALIVE_AT = "last_alive_at"
        private const val KEY_LAST_ALIVE_ELAPSED = "last_alive_elapsed"
        private const val KEY_LIVENESS_KILLS = "liveness_kills"
        private const val KEY_LIVENESS_KILLS_UNREPORTED = "liveness_kills_unreported"
        private const val KEY_LIVENESS_REPORT_AT = "liveness_report_at"
        private const val KEY_HEARTBEAT_HOURS = "heartbeat_hours"
        private const val KEY_HEARTBEAT_AT = "heartbeat_at"
        private const val KEY_HEARTBEAT_ELAPSED = "heartbeat_elapsed"
        private const val KEY_LIVE_TEST_PENDING = "live_test_pending"
        private const val KEY_LIVE_TEST_STARTED_ELAPSED = "live_test_started_elapsed"
        private const val KEY_LIVE_TEST_STARTED_WALL = "live_test_started_wall"
        private const val KEY_LIVE_TEST_BOOT_COUNT = "live_test_boot_count"
        private const val KEY_LIVE_TEST_BY_ALARM = "live_test_by_alarm"
        private const val KEY_LIVE_TEST_LAST_SEC = "live_test_last_sec"
        private const val KEY_LAST_SEND_OK_ELAPSED = "last_send_ok_elapsed"
        private const val KEY_LAST_SEND_OK_WALL = "last_send_ok_wall"
        private const val KEY_SILENT_HEAL_AT = "silent_heal_at"
        private const val KEY_SILENT_HEAL_STAGE = "silent_heal_stage"
        private const val KEY_SILENT_HEALS = "silent_heals"
        private const val KEY_VEHICLE_ALERT_ENABLED = "vehicle_alert_enabled"
        private const val KEY_LAST_VEHICLE_ALERT_AT = "last_vehicle_alert_at"
        private const val KEY_DATA_SAVER_ENABLED = "data_saver_enabled"
        private const val KEY_DATA_SAVER_NOTICE_AT = "data_saver_notice_at"
        private const val KEY_A11Y_USAGE_DATE = "a11y_usage_date"
        private const val KEY_A11Y_USAGE_JSON = "a11y_usage_json"
        private const val KEY_A11Y_USAGE_PREV_DATE = "a11y_usage_prev_date"
        private const val KEY_A11Y_USAGE_PREV_JSON = "a11y_usage_prev_json"
        private const val KEY_A11Y_USAGE_PREV2_DATE = "a11y_usage_prev2_date"
        private const val KEY_A11Y_USAGE_PREV2_JSON = "a11y_usage_prev2_json"
        private const val KEY_A11Y_NIGHT_ALERT_PKG = "a11y_night_alert_pkg"
        private const val KEY_A11Y_NIGHT_ALERT_AT = "a11y_night_alert_at"
        private const val KEY_A11Y_NIGHT_ALERT_ELAPSED = "a11y_night_alert_elapsed"
        private const val KEY_A11Y_OPEN_SESSION_PKG = "a11y_open_session_pkg"
        private const val KEY_A11Y_OPEN_SESSION_START_WALL = "a11y_open_session_start_wall"
        private const val KEY_A11Y_OPEN_SESSION_START_ELAPSED = "a11y_open_session_start_elapsed"
        private const val KEY_A11Y_KNOWN_APPS = "a11y_known_apps"
        private const val KEY_A11Y_KNOWN_SEEDED = "a11y_known_seeded"
        private const val KEY_A11Y_APP_ALERT = "a11y_app_alert"
        private const val KEY_A11Y_TAMPER = "a11y_tamper"
        private const val KEY_A11Y_SETTINGS_GRACE = "a11y_settings_grace"
        private const val KEY_A11Y_ADMIN_GRACE = "a11y_admin_grace"
        private const val KEY_A11Y_SETTINGS_GRACE_ELAPSED = "a11y_settings_grace_elapsed"
        private const val KEY_A11Y_SETTINGS_GRACE_BOOT = "a11y_settings_grace_boot"
        private const val KEY_A11Y_ADMIN_GRACE_BOOT = "a11y_admin_grace_boot"
        private const val KEY_A11Y_ADMIN_GRACE_ELAPSED = "a11y_admin_grace_elapsed"
        private const val KEY_A11Y_APP_WATCH = "a11y_app_watch"
        private const val KEY_A11Y_APP_WATCH_PACKAGES = "a11y_app_watch_packages"
        private const val KEY_A11Y_EVER_ENABLED = "a11y_ever_enabled"
        private const val KEY_A11Y_DISABLED_ALERT_AT = "a11y_disabled_alert_at"
        private const val KEY_A11Y_KEYWORD_DAY = "a11y_keyword_day"
        private const val KEY_A11Y_KEYWORD_DAY_AT = "a11y_keyword_day_at"
        private const val KEY_A11Y_KEYWORD_COUNT = "a11y_keyword_count"
        private const val KEY_A11Y_KEYWORD_JSON = "a11y_keyword_json"
        private const val KEY_A11Y_TAMPER_JSON = "a11y_tamper_json"

        private fun clearStaleCredentialError(target: PreferencesManager) {
            try {
                val now = System.currentTimeMillis()
                val errAt = target.credentialErrorAt
                if (errAt == 0L) return
                // Pola jam ganda yang sama dengan sweepAuthBlock
                // (issue #756 — salinan ini tertinggal dari perbaikan
                // #733): usia blokir dihitung dari jangkar monoton bila
                // ada; pembersihan dini karena jam dinding mundur hanya
                // berlaku untuk penanda warisan tanpa jangkar.
                val elapsed = target.credentialErrorElapsed
                val age = com.redeye.parentalmonitor.utils.NetworkUtils.blockAgeMs(
                    errAt, elapsed, now, android.os.SystemClock.elapsedRealtime()
                )
                if (age >= 30 * 60_000L || (elapsed <= 0L && now < errAt)) {
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
            var preservedGeneration = 0L
            synchronized(this) {
                preservedGeneration = try { instance?.credentialGeneration ?: 0L } catch (_: Exception) { 0L }
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
                if (ok && preservedGeneration > 0L) {
                    try { (current ?: instance)?.seedCredentialGeneration(preservedGeneration) } catch (_: Exception) { }
                }
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
                val editor = sharedPreferences.edit().putString(KEY_BOT_TOKEN, value)
                if (value != botToken) bumpCredentialGeneration(editor)
                editor.apply()
            }
        }

    var chatId: String
        get() = prefString(KEY_CHAT_ID, "")
        set(value) {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit().putString(KEY_CHAT_ID, value)
                if (value != chatId) bumpCredentialGeneration(editor)
                editor.apply()
            }
        }

    // Username bot sendiri (issue #843): dipelajari dari getMe agar
    // perintah beralamat @BotLain bisa dikenali bukan untuk bot ini.
    // Metadata saja, bukan kredensial — tak menaikkan generasi.
    var botUsername: String
        get() = prefString(KEY_BOT_USERNAME, "")
        set(value) {
            sharedPreferences.edit().putString(KEY_BOT_USERNAME, value).apply()
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
        // Tiap percobaan ditangkap sendiri (issue #1180):
        // sebelumnya satu try membungkus kedua commit sehingga
        // commit pertama yang MELEMPAR langsung gagal total
        // tanpa percobaan kedua — retry hanya berlaku untuk
        // commit yang mengembalikan false.
        for (percobaan in 0..1) {
            val ok = try {
                build(sharedPreferences.edit()).commit()
            } catch (_: Exception) {
                false
            }
            if (ok) return true
        }
        return false
    }

    fun setLastUpdateIdSync(id: Long): Boolean {
        return commitWithRetry { it.putLong(KEY_LAST_UPDATE_ID, id) }
    }

    fun setLastUpdateIdIfGenerationSync(id: Long, generation: Long): Boolean {
        return synchronized(credentialLock) {
            if (credentialGeneration != generation) return@synchronized false
            if (id <= lastUpdateId) return@synchronized true
            setLastUpdateIdSync(id) && lastUpdateId >= id
        }
    }

    fun seedCredentialGeneration(value: Long) {
        synchronized(credentialLock) {
            if (value > credentialGeneration) {
                try {
                    sharedPreferences.edit().putLong(KEY_CREDENTIAL_GENERATION, value).commit()
                } catch (_: Exception) {
                }
            }
        }
    }

    val credentialGeneration: Long
        get() = prefLong(KEY_CREDENTIAL_GENERATION, 0L)

    var lastPhotoTime: Long
        get() = prefLong(KEY_LAST_PHOTO_TIME, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_PHOTO_TIME, value).apply()

    var lastCameraErrorNotice: Long
        get() = prefLong(KEY_LAST_CAM_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CAM_ERR_NOTICE, value).apply()

    var lastUploadErrorNotice: Long
        get() = prefLong(KEY_LAST_UPLOAD_ERR_NOTICE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPLOAD_ERR_NOTICE, value).apply()

    var lastCameraErrorAt: Long
        get() = prefLong(KEY_LAST_CAM_ERR_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_CAM_ERR_AT, value).apply()

    var lastUploadErrorAt: Long
        get() = prefLong(KEY_LAST_UPLOAD_ERR_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LAST_UPLOAD_ERR_AT, value).apply()

    var monitoringPaused: Boolean
        get() = prefBoolean(KEY_MONITORING_PAUSED, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_MONITORING_PAUSED, value).apply()

    var photoPausedUntil: Long
        get() = prefLong(KEY_PHOTO_PAUSED_UNTIL, 0L)
        set(value) {
            // Jangkar monoton disimpan dalam edit yang sama
            // (issue #939): tenggat elapsedRealtime pendamping
            // tenggat jam dinding, agar sisa jeda kebal
            // kemunduran jam dinding.
            val elapsed = try {
                if (value > 0L) {
                    android.os.SystemClock.elapsedRealtime() +
                        (value - System.currentTimeMillis()).coerceAtLeast(0L)
                } else {
                    0L
                }
            } catch (_: Exception) {
                0L
            }
            sharedPreferences.edit()
                .putLong(KEY_PHOTO_PAUSED_UNTIL, value)
                .putLong(KEY_PHOTO_PAUSED_ELAPSED_UNTIL, elapsed)
                .apply()
        }

    var photoPausedElapsedUntil: Long
        get() = prefLong(KEY_PHOTO_PAUSED_ELAPSED_UNTIL, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PHOTO_PAUSED_ELAPSED_UNTIL, value).apply()

    var photoPauseSource: Int
        get() = prefInt(KEY_PHOTO_PAUSE_SOURCE, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_PHOTO_PAUSE_SOURCE, value).apply()

    fun setPhotoPause(until: Long, source: Int) {
        try {
            val elapsed = if (until > 0L) {
                android.os.SystemClock.elapsedRealtime() +
                    (until - System.currentTimeMillis()).coerceAtLeast(0L)
            } else {
                0L
            }
            sharedPreferences.edit()
                .putLong(KEY_PHOTO_PAUSED_UNTIL, until)
                .putLong(KEY_PHOTO_PAUSED_ELAPSED_UNTIL, elapsed)
                .putInt(KEY_PHOTO_PAUSE_SOURCE, source)
                .apply()
        } catch (_: Exception) {
        }
    }

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
        // Tambah ke himpunan + tempel ke objek prefs yang
        // SAMA di bawah satu kunci (issue #1102): membaca
        // field di luar kunci bisa menempelkan listener ke
        // objek lama yang sedang ditinggalkan pertukaran
        // penyimpanan — listener tak pernah menerima
        // perubahan dari objek baru.
        synchronized(listenerLock) {
            listenerSet.add(listener)
            try { sharedPreferences.registerOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
        }
    }

    fun unregisterChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(listenerLock) {
            listenerSet.remove(listener)
            try { sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener) } catch (_: Exception) { }
        }
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

    var restorePinFailCount: Int
        get() = try { sharedPreferences.getInt(KEY_RESTORE_PIN_FAIL_COUNT, 0) } catch (_: Exception) { 0 }
        set(value) { try { sharedPreferences.edit().putInt(KEY_RESTORE_PIN_FAIL_COUNT, value).commit() } catch (_: Exception) { } }

    // Kunci percobaan (berkas, PIN) terakhir yang gagal (issue #873).
    var restorePinLastAttemptKey: String
        get() = prefString(KEY_RESTORE_PIN_LAST_KEY, "")
        set(value) = sharedPreferences.edit().putString(KEY_RESTORE_PIN_LAST_KEY, value).apply()

    var restorePinLockoutUntil: Long
        get() = try { sharedPreferences.getLong(KEY_RESTORE_PIN_LOCKOUT_UNTIL, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_RESTORE_PIN_LOCKOUT_UNTIL, value).commit() } catch (_: Exception) { } }

    var restorePinLockoutElapsedStart: Long
        get() = try { sharedPreferences.getLong(KEY_RESTORE_PIN_LOCKOUT_ELAPSED_START, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_RESTORE_PIN_LOCKOUT_ELAPSED_START, value).commit() } catch (_: Exception) { } }

    // Catat kegagalan PIN restore secara ATOMIK (issue
    // #1210): anggaran lockout DIBAGI restore lokal
    // (SetupActivity) dan jarak jauh (MonitoringService),
    // jadi baca-hitung-tulis wajib di bawah prefsRmwLock
    // yang sama untuk kedua pemanggil — tanpa kunci, dua
    // tebakan berbeda yang bersamaan terhitung satu.
    // Seluruh keadaan ditulis dalam SATU edit commit agar
    // mati proses di tengah tak menyisakan lockoutUntil
    // tanpa jangkar monotonnya.
    fun registerRestorePinFailure(blob: String, pin: String): Triple<Int, Long, Boolean> {
        synchronized(prefsRmwLock) {
            val prevCount = try { sharedPreferences.getInt(KEY_RESTORE_PIN_FAIL_COUNT, 0) } catch (_: Exception) { 0 }
            val prevKey = prefString(KEY_RESTORE_PIN_LAST_KEY, "")
            val newKey = com.redeye.parentalmonitor.utils.RestorePinGate.attemptKey(blob, pin)
            val hasil = com.redeye.parentalmonitor.utils.RestorePinGate.registerFailure(prevCount, System.currentTimeMillis(), prevKey, newKey)
            if (hasil.third) {
                try {
                    val editor = sharedPreferences.edit()
                        .putInt(KEY_RESTORE_PIN_FAIL_COUNT, hasil.first)
                        .putString(KEY_RESTORE_PIN_LAST_KEY, newKey)
                    if (hasil.second > 0L) {
                        editor.putLong(KEY_RESTORE_PIN_LOCKOUT_UNTIL, hasil.second)
                            .putLong(KEY_RESTORE_PIN_LOCKOUT_ELAPSED_START, android.os.SystemClock.elapsedRealtime())
                    }
                    editor.commit()
                } catch (_: Exception) {
                }
            }
            return hasil
        }
    }

    var accessPinFailCount: Int
        get() = try { sharedPreferences.getInt(KEY_ACCESS_PIN_FAIL_COUNT, 0) } catch (_: Exception) { 0 }
        set(value) { try { sharedPreferences.edit().putInt(KEY_ACCESS_PIN_FAIL_COUNT, value).commit() } catch (_: Exception) { } }

    var accessPinLockoutUntil: Long
        get() = try { sharedPreferences.getLong(KEY_ACCESS_PIN_LOCKOUT_UNTIL, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_ACCESS_PIN_LOCKOUT_UNTIL, value).commit() } catch (_: Exception) { } }

    var accessPinLockoutElapsedStart: Long
        get() = try { sharedPreferences.getLong(KEY_ACCESS_PIN_LOCKOUT_ELAPSED_START, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_ACCESS_PIN_LOCKOUT_ELAPSED_START, value).commit() } catch (_: Exception) { } }

    var restoreCodeFailCount: Int
        get() = try { sharedPreferences.getInt(KEY_RESTORE_CODE_FAIL_COUNT, 0) } catch (_: Exception) { 0 }
        set(value) { try { sharedPreferences.edit().putInt(KEY_RESTORE_CODE_FAIL_COUNT, value).commit() } catch (_: Exception) { } }

    var restoreCodeLockoutUntil: Long
        get() = try { sharedPreferences.getLong(KEY_RESTORE_CODE_LOCKOUT_UNTIL, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_RESTORE_CODE_LOCKOUT_UNTIL, value).commit() } catch (_: Exception) { } }

    var restoreCodeLockoutElapsedStart: Long
        get() = try { sharedPreferences.getLong(KEY_RESTORE_CODE_LOCKOUT_ELAPSED_START, 0L) } catch (_: Exception) { 0L }
        set(value) { try { sharedPreferences.edit().putLong(KEY_RESTORE_CODE_LOCKOUT_ELAPSED_START, value).commit() } catch (_: Exception) { } }

    fun ensureRestoreCodeSecret(): String {
        // Kunci khusus (issue #1179): sebelumnya meminjam
        // auditLock milik log audit — menyesatkan pembaca dan
        // berebut dengan penulisan log.
        synchronized(restoreSecretLock) {
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

    // Jangkar jam monoton pendamping credentialErrorAt (issue #733):
    // blokir auth tidak lagi bisa dibuka lebih awal dengan memundurkan
    // jam dinding. 0 berarti penanda warisan tanpa jangkar.
    val credentialErrorElapsed: Long
        get() = prefLong(KEY_CRED_ERROR_ELAPSED, 0L)

    fun setCredentialError(code: String, at: Long) {
        try {
            synchronized(credentialLock) {
                val elapsed = if (at > 0L) android.os.SystemClock.elapsedRealtime() else 0L
                sharedPreferences.edit().putString(KEY_CRED_ERROR, code).putLong(KEY_CRED_ERROR_AT, at).putLong(KEY_CRED_ERROR_ELAPSED, elapsed).apply()
            }
        } catch (_: Exception) {
        }
    }

    // Hapus penanda error hanya bila isinya masih persis yang
    // dibaca pemanggil (issue #1052): sweepAuthBlock menilai
    // penanda lama basi, tetapi tanpa penjaga ini penanda
    // 401/403 SEGAR yang ditulis jalur kirim di antaranya ikut
    // terhapus dan blokir kredensial runtuh.
    fun clearCredentialErrorIfMatches(code: String, at: Long, elapsed: Long): Boolean {
        return try {
            synchronized(credentialLock) {
                if (credentialError != code || credentialErrorAt != at || credentialErrorElapsed != elapsed) return@synchronized false
                sharedPreferences.edit().putString(KEY_CRED_ERROR, "").putLong(KEY_CRED_ERROR_AT, 0L).putLong(KEY_CRED_ERROR_ELAPSED, 0L).apply()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setCredentialErrorIfCredentialsMatch(token: String, chat: String, code: String, at: Long): Boolean {
        return synchronized(credentialLock) {
            if (botToken != token || chatId != chat) return@synchronized false
            if (credentialError == code && credentialErrorAt == at) return@synchronized true
            try {
                val elapsed = if (at > 0L) android.os.SystemClock.elapsedRealtime() else 0L
                sharedPreferences.edit().putString(KEY_CRED_ERROR, code).putLong(KEY_CRED_ERROR_AT, at).putLong(KEY_CRED_ERROR_ELAPSED, elapsed).apply()
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

    // Dua fungsi penghitung liveness di bawah satu kunci
    // (issue #924): baca-ubah-tulis tanpa kunci berhadapan
    // dengan penolan penghitung di markLivenessReported bisa
    // membuat laporan kill dobel atau hilang.
    fun incrementLivenessKills(): Int {
        synchronized(prefsRmwLock) {
            val unreported = prefInt(KEY_LIVENESS_KILLS_UNREPORTED, 0) + 1
            try {
                sharedPreferences.edit()
                    .putInt(KEY_LIVENESS_KILLS, prefInt(KEY_LIVENESS_KILLS, 0) + 1)
                    .putInt(KEY_LIVENESS_KILLS_UNREPORTED, unreported)
                    .apply()
            } catch (_: Exception) {
            }
            return unreported
        }
    }

    fun markLivenessReported(reportAt: Long) {
        synchronized(prefsRmwLock) {
            try {
                sharedPreferences.edit()
                    .putLong(KEY_LIVENESS_REPORT_AT, reportAt)
                    .putInt(KEY_LIVENESS_KILLS_UNREPORTED, 0)
                    .apply()
            } catch (_: Exception) {
            }
        }
    }

    var heartbeatHours: Int
        get() = prefInt(KEY_HEARTBEAT_HOURS, 0)
        set(value) = sharedPreferences.edit().putInt(KEY_HEARTBEAT_HOURS, value).apply()

    var heartbeatAt: Long
        get() = prefLong(KEY_HEARTBEAT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_HEARTBEAT_AT, value).apply()

    // Pasangan monoton cap heartbeat (issue #1105): jangkar
    // dinding saja membuat lompatan jam maju memicu heartbeat
    // prematur + cap palsu, dan koreksinya memperpanjang
    // senyap. Pola pasangan yang sama dengan patroli (#923).
    var heartbeatElapsed: Long
        get() = prefLong(KEY_HEARTBEAT_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_HEARTBEAT_ELAPSED, value).apply()

    fun setHeartbeatAt(at: Long, elapsed: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_HEARTBEAT_AT, at)
                .putLong(KEY_HEARTBEAT_ELAPSED, elapsed)
                .apply()
        } catch (_: Exception) {
        }
    }

    var liveTestPending: Boolean
        get() = prefBoolean(KEY_LIVE_TEST_PENDING, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_PENDING, value).apply()

    var liveTestStartedElapsed: Long
        get() = prefLong(KEY_LIVE_TEST_STARTED_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LIVE_TEST_STARTED_ELAPSED, value).apply()

    var liveTestByAlarm: Boolean
        get() = prefBoolean(KEY_LIVE_TEST_BY_ALARM, false)
        set(value) = sharedPreferences.edit().putBoolean(KEY_LIVE_TEST_BY_ALARM, value).apply()

    // Pasangan dinding cap mulai uji (issue #1020): deteksi
    // reboot tak lagi hanya dari elapsed — uji yang dimulai
    // pada uptime kecil lalu reboot lolos sebagai "durasi
    // singkat yang sah" dan laporannya palsu.
    val liveTestStartedWall: Long
        get() = prefLong(KEY_LIVE_TEST_STARTED_WALL, 0L)

    // Penghitung boot persisten saat uji dimulai (issue
    // #1034): bukti reboot yang kebal jam — penghitung di
    // boot_meta bertambah sekali per boot oleh BootReceiver.
    // 0 = tak diketahui (uji warisan) → vonis memakai jam.
    var liveTestBootCount: Long
        get() = prefLong(KEY_LIVE_TEST_BOOT_COUNT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_LIVE_TEST_BOOT_COUNT, value).apply()

    fun beginLiveTest(startedElapsed: Long, startedWall: Long, bootCount: Long = 0L) {
        try {
            sharedPreferences.edit()
                .putBoolean(KEY_LIVE_TEST_PENDING, true)
                .putLong(KEY_LIVE_TEST_STARTED_ELAPSED, startedElapsed)
                .putLong(KEY_LIVE_TEST_STARTED_WALL, startedWall)
                .putLong(KEY_LIVE_TEST_BOOT_COUNT, bootCount)
                .putBoolean(KEY_LIVE_TEST_BY_ALARM, false)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearLiveTest() {
        try {
            sharedPreferences.edit()
                .putBoolean(KEY_LIVE_TEST_PENDING, false)
                .putLong(KEY_LIVE_TEST_STARTED_ELAPSED, 0L)
                .putLong(KEY_LIVE_TEST_STARTED_WALL, 0L)
                .putLong(KEY_LIVE_TEST_BOOT_COUNT, 0L)
                .putBoolean(KEY_LIVE_TEST_BY_ALARM, false)
                .apply()
        } catch (_: Exception) {
        }
    }

    var liveTestLastSec: Int
        get() = prefInt(KEY_LIVE_TEST_LAST_SEC, -1)
        set(value) = sharedPreferences.edit().putInt(KEY_LIVE_TEST_LAST_SEC, value).apply()

    val lastSendOkElapsed: Long
        get() = prefLong(KEY_LAST_SEND_OK_ELAPSED, 0L)

    // Pasangan dinding penanda kirim (issue #1021): usia
    // bisu tak lagi dihitung dari elapsed lintas boot.
    val lastSendOkWall: Long
        get() = prefLong(KEY_LAST_SEND_OK_WALL, 0L)

    fun setLastSendOkMarker(elapsedAt: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_LAST_SEND_OK_ELAPSED, elapsedAt)
                .putLong(KEY_LAST_SEND_OK_WALL, System.currentTimeMillis())
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

    // Penulis pasangan prompt dalam SATU edit (issue #923, pola
    // #890): cmd+cap yang ditulis dua apply() terpisah bisa
    // tercampur oleh kematian proses di antaranya (cmd baru
    // dengan cap lama/nol).
    fun setPendingPrompt(command: String, at: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_PENDING_PROMPT_CMD, command)
                .putLong(KEY_PENDING_PROMPT_AT, at)
                .apply()
        } catch (_: Exception) {
        }
    }

    var pendingPromptCmd: String
        get() = prefString(KEY_PENDING_PROMPT_CMD, "")
        set(value) = sharedPreferences.edit().putString(KEY_PENDING_PROMPT_CMD, value).apply()

    var pendingPromptAt: Long
        get() = prefLong(KEY_PENDING_PROMPT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PENDING_PROMPT_AT, value).apply()

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
        set(value) {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit().putLong(KEY_OWNER_ID, value)
                if (value != ownerUserId) bumpCredentialGeneration(editor)
                editor.apply()
            }
        }

    var accessPin: String
        get() = prefString(KEY_ACCESS_PIN, "")
        set(value) {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit().putString(KEY_ACCESS_PIN, value)
                if (value != accessPin) bumpCredentialGeneration(editor)
                editor.apply()
            }
        }

    fun setOwnerIdSync(id: Long): Boolean {
        return try {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit().putLong(KEY_OWNER_ID, id)
                if (id != ownerUserId) bumpCredentialGeneration(editor)
                editor.commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun adoptOwnerChatSync(ownerId: Long, newChatId: String): Boolean {
        if (ownerId == 0L || newChatId.isEmpty()) return false
        return try {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit()
                    .putLong(KEY_OWNER_ID, ownerId)
                    .putString(KEY_CHAT_ID, newChatId)
                    .putString(KEY_OWNER_PAIR_CODE, "")
                    .putString(KEY_COMMANDS_TOKEN_HASH, "")
                    .putString(KEY_CRED_ERROR, "")
                    .putLong(KEY_CRED_ERROR_AT, 0L)
                    .putLong(KEY_CRED_ERROR_ELAPSED, 0L)
                if (ownerId != ownerUserId || newChatId != chatId) bumpCredentialGeneration(editor)
                editor.commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun clearOwnerSync(): Boolean {
        return try {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit()
                    .putLong(KEY_OWNER_ID, 0L)
                    .putString(KEY_OWNER_PAIR_CODE, "")
                    .putLong(KEY_OWNER_PAIR_AT, 0L)
                    .putString(KEY_COMMANDS_TOKEN_HASH, "")
                if (ownerUserId != 0L) bumpCredentialGeneration(editor)
                editor.commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setOwnerPairCode(value: String) {
        sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, value).apply()
    }

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
                val saved = try {
                    sharedPreferences.edit().putString(KEY_OWNER_PAIR_CODE, code).putLong(KEY_OWNER_PAIR_AT, System.currentTimeMillis()).commit()
                } catch (_: Exception) {
                    false
                }
                if (!saved) return ""
                return try { sharedPreferences.getString(KEY_OWNER_PAIR_CODE, "") ?: "" } catch (_: Exception) { "" }
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

    fun clearOwnerPairCodeSync(): Boolean {
        synchronized(pairCodeLock) {
            return try {
                sharedPreferences.edit()
                    .putString(KEY_OWNER_PAIR_CODE, "")
                    .putLong(KEY_OWNER_PAIR_AT, 0L)
                    .commit()
            } catch (_: Exception) {
                false
            }
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

    var dropNoticeBatch: String
        get() = prefString(KEY_DROP_NOTICE_BATCH, "")
        set(value) = sharedPreferences.edit().putString(KEY_DROP_NOTICE_BATCH, value).apply()

    fun setDropNoticeBatchSync(batch: String): Boolean {
        return try {
            commitGuarded(sharedPreferences.edit().putString(KEY_DROP_NOTICE_BATCH, batch))
        } catch (_: Exception) {
            false
        }
    }

    var pendingNotifDropsSince: Long
        get() = prefLong(KEY_PENDING_NOTIF_DROPS_SINCE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PENDING_NOTIF_DROPS_SINCE, value).apply()

    fun setPendingNotifDropsSync(value: Int, since: Long): Boolean {
        return try {
            commitGuarded(
                sharedPreferences.edit()
                    .putInt(KEY_PENDING_NOTIF_DROPS, value)
                    .putLong(KEY_PENDING_NOTIF_DROPS_SINCE, since)
            )
        } catch (_: Exception) {
            false
        }
    }

    fun setDropNoticeStateSync(value: Int, batch: String, since: Long): Boolean {
        return try {
            commitGuarded(
                sharedPreferences.edit()
                    .putInt(KEY_PENDING_NOTIF_DROPS, value)
                    .putString(KEY_DROP_NOTICE_BATCH, batch)
                    .putLong(KEY_PENDING_NOTIF_DROPS_SINCE, since)
            )
        } catch (_: Exception) {
            false
        }
    }

    // Pengurai daftar CSV bersama (issue #986): badan dua
    // getter daftar penanda dulu salinan persis selain kunci.
    private fun prefStringList(key: String): List<String> {
        val raw = prefString(key, "")
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        while (start <= raw.length) {
            val sep = raw.indexOf(',', start)
            val part = if (sep >= 0) raw.substring(start, sep) else raw.substring(start)
            val v = part.trim()
            if (v.isNotEmpty()) out.add(v)
            if (sep < 0) break
            start = sep + 1
        }
        return out
    }

    val forwardedNotifIds: List<String>
        get() = prefStringList(KEY_FORWARDED_NOTIF_IDS)

    val notifGroupForwarded: List<String>
        get() = prefStringList(KEY_NOTIF_GROUP_FORWARDED)

    fun setForwardedMarkersSync(groupValues: List<String>, idValues: List<String>): Boolean {
        return try {
            sharedPreferences.edit()
                .putString(KEY_NOTIF_GROUP_FORWARDED, groupValues.joinToString(","))
                .putString(KEY_FORWARDED_NOTIF_IDS, idValues.joinToString(","))
                .commit()
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

    val heldPhotoHash: Long
        get() = prefLong(KEY_HELD_PHOTO_HASH, 0L)

    val heldPhotoEntriesRaw: String
        get() = prefString(KEY_HELD_PHOTO_HASHES, "")

    // Pasangan daftar entri + penolkan hash warisan dalam SATU
    // edit atomik (issue #1147): dua commit terpisah menyisakan
    // jendela mati-proses di mana daftar baru tersimpan tetapi
    // hash warisan belum dinolkan, dan bacaan berikutnya
    // menggabungkan entri warisan yang baru dilepas itu kembali
    // sebagai entri hantu (kelas #923).
    fun setHeldPhotoEntriesAndClearLegacySync(value: String): Boolean {
        return try {
            sharedPreferences.edit()
                .putString(KEY_HELD_PHOTO_HASHES, value)
                .putLong(KEY_HELD_PHOTO_HASH, 0L)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    var digestAt: Long
        get() = prefLong(KEY_DIGEST_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_DIGEST_AT, value).apply()

    var patrolLastAt: Long
        get() = prefLong(KEY_PATROL_LAST_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PATROL_LAST_AT, value).apply()

    var patrolLastElapsed: Long
        get() = prefLong(KEY_PATROL_LAST_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_PATROL_LAST_ELAPSED, value).apply()

    // Penulis pasangan cap patroli (dinding+monoton) dalam SATU
    // edit (issue #923, pola #890) agar tak tercampur oleh
    // kematian proses di antara dua penulisan terpisah.
    fun setPatrolLast(at: Long, elapsed: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_PATROL_LAST_AT, at)
                .putLong(KEY_PATROL_LAST_ELAPSED, elapsed)
                .apply()
        } catch (_: Exception) {
        }
    }

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

    // Epoch penulisan cap anomali (issue #1029, pola #852):
    // pasangan wajib agar cap bertanggal masa depan hasil
    // excursi jam bisa dikenali sebagai basi oleh DayStamp.
    var anomalyAlertsAtMs: Long
        get() = prefLong(KEY_ANOMALY_ALERTS_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_ANOMALY_ALERTS_AT, value).apply()

    // Tulis cap anomali + epoch-nya dalam SATU edit (pola
    // #890): dua apply() terpisah bisa menyisakan pasangan
    // tak konsisten bila proses mati di antaranya.
    fun setAnomalyAlerts(value: String, atMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_ANOMALY_ALERTS, value)
                .putLong(KEY_ANOMALY_ALERTS_AT, atMs)
                .apply()
        } catch (_: Exception) {
        }
    }

    val trailTrimmedFrom: Long
        get() = prefLong(KEY_TRAIL_TRIMMED_FROM, 0L)

    fun recordSentLocation(lat: Double, lon: Double, trail: String?, accuracyM: Float = -1f, trimmedFrom: Long = -1L) {
        try {
            val editor = sharedPreferences.edit()
                .putLong(KEY_LAST_LOC_LAT, (lat * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_LON, (lon * 1_000_000).toLong())
                .putLong(KEY_LAST_LOC_AT, System.currentTimeMillis())
            if (accuracyM >= 0f) editor.putFloat(KEY_LAST_LOC_ACC, accuracyM)
            if (trail != null) editor.putString(KEY_TRAIL, trail)
            if (trimmedFrom >= 0L) editor.putLong(KEY_TRAIL_TRIMMED_FROM, trimmedFrom)
            editor.apply()
        } catch (_: Exception) {
        }
    }

    var begadangDate: String
        get() = prefString(KEY_BEGADANG_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_BEGADANG_DATE, value).apply()

    // Epoch penulisan cap begadang (issue #1029, pola #852).
    var begadangDateAtMs: Long
        get() = prefLong(KEY_BEGADANG_DATE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_BEGADANG_DATE_AT, value).apply()

    fun setBegadangDone(date: String, atMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_BEGADANG_DATE, date)
                .putLong(KEY_BEGADANG_DATE_AT, atMs)
                .apply()
        } catch (_: Exception) {
        }
    }

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

    // Slot sebelumnya data pemakaian (issue #1037): slot
    // tunggal yatim saat tanggal perangkat melompat lalu
    // proses restart di tengah excursi — persist bertanggal
    // palsu menimpa akumulasi hari asli. Setiap penulisan
    // yang mengganti tanggal merotasi isi lama ke slot
    // cadangan dalam edit yang sama; loadUsage memulihkan
    // dari cadangan bila tanggal slot utama bukan hari ini
    // tetapi tanggal cadangan adalah hari ini.
    var a11yUsagePrevDate: String
        get() = prefString(KEY_A11Y_USAGE_PREV_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_PREV_DATE, value).apply()

    var a11yUsagePrevJson: String
        get() = prefString(KEY_A11Y_USAGE_PREV_JSON, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_PREV_JSON, value).apply()

    // Generasi cadangan kedua (issue #1054): satu slot
    // cadangan hanya tahan satu lompatan tanggal — excursi
    // dua langkah (D -> F1 -> F2, restart di antaranya)
    // menimpa cadangan berisi D sebelum jam terkoreksi.
    // Rotasi kini dua generasi: utama -> prev -> prev2.
    var a11yUsagePrev2Date: String
        get() = prefString(KEY_A11Y_USAGE_PREV2_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_PREV2_DATE, value).apply()

    var a11yUsagePrev2Json: String
        get() = prefString(KEY_A11Y_USAGE_PREV2_JSON, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_USAGE_PREV2_JSON, value).apply()

    fun setA11yUsage(date: String, json: String) {
        try {
            // Penempatan slot sadar tanggal (issue #1127):
            // rotasi lama berjalan pada SETIAP ganti tanggal
            // tulis — satu tulis bertanggal palsu (perjalanan
            // jam maju) + satu tulis koreksi merotasi dua kali
            // dan membuang riwayat kemarin/kemarin-lusa,
            // digantikan hari hantu. Kini: tanggal maju merotasi
            // seperti biasa; tanggal yang cocok slot cadangan
            // memperbarui slot ITU (tulis susulan hari kemarin
            // memang sah); tanggal tak dikenal yang lebih tua
            // dari semua slot diabaikan. Kunci hari yyyy-MM-dd
            // terurut leksikografis.
            val curDate = prefString(KEY_A11Y_USAGE_DATE, "")
            val prevDate = prefString(KEY_A11Y_USAGE_PREV_DATE, "")
            val prev2Date = prefString(KEY_A11Y_USAGE_PREV2_DATE, "")
            // Hantu yang bisa DIBUKTIKAN (issue #1153): tulis
            // hidup (tanggalnya = hari ini menurut jam saat ini)
            // berhadapan slot kini yang melompat >=2 hari ke
            // depan. Margin sehari melindungi koreksi mundur
            // yang menyebrangi tengah malam (slot kini besok
            // tepat masih mungkin data malam yang sah).
            // Satu titik waktu untuk kedua kunci (issue
            // #1162): dua panggilan jam terpisah bisa berbeda
            // hari bila tengah malam jatuh di antaranya.
            val nowMs = System.currentTimeMillis()
            val todayNow = try { com.redeye.parentalmonitor.utils.TimeFmt.dayKey(nowMs) } catch (_: Exception) { "" }
            val tomorrowNow = try { com.redeye.parentalmonitor.utils.TimeFmt.dayKey(nowMs + 86_400_000L) } catch (_: Exception) { "" }
            val liveGhost = todayNow.isNotEmpty() && tomorrowNow.isNotEmpty() && date == todayNow && curDate > tomorrowNow
            val editor = sharedPreferences.edit()
            when {
                curDate.isEmpty() || date == curDate ->
                    editor.putString(KEY_A11Y_USAGE_DATE, date)
                        .putString(KEY_A11Y_USAGE_JSON, json)
                date > curDate -> {
                    editor.putString(KEY_A11Y_USAGE_PREV2_DATE, prevDate)
                    editor.putString(KEY_A11Y_USAGE_PREV2_JSON, prefString(KEY_A11Y_USAGE_PREV_JSON, ""))
                    editor.putString(KEY_A11Y_USAGE_PREV_DATE, curDate)
                    editor.putString(KEY_A11Y_USAGE_PREV_JSON, prefString(KEY_A11Y_USAGE_JSON, ""))
                    editor.putString(KEY_A11Y_USAGE_DATE, date)
                    editor.putString(KEY_A11Y_USAGE_JSON, json)
                }
                date == prevDate ->
                    editor.putString(KEY_A11Y_USAGE_PREV_JSON, json)
                date == prev2Date ->
                    editor.putString(KEY_A11Y_USAGE_PREV2_JSON, json)
                liveGhost -> {
                    // Slot kini (dan mungkin cadangan) berisi
                    // tanggal hantu terbukti: tulis hidup hari ini
                    // mengambil alih slot kini, cadangan hantu
                    // diruntuhkan — prev2 yang bukan hantu naik
                    // ke prev, sisanya dikosongkan.
                    editor.putString(KEY_A11Y_USAGE_DATE, date)
                    editor.putString(KEY_A11Y_USAGE_JSON, json)
                    if (prevDate > tomorrowNow) {
                        if (prev2Date.isNotEmpty() && prev2Date <= tomorrowNow) {
                            editor.putString(KEY_A11Y_USAGE_PREV_DATE, prev2Date)
                            editor.putString(KEY_A11Y_USAGE_PREV_JSON, prefString(KEY_A11Y_USAGE_PREV2_JSON, ""))
                        } else {
                            editor.putString(KEY_A11Y_USAGE_PREV_DATE, "")
                            editor.putString(KEY_A11Y_USAGE_PREV_JSON, "")
                        }
                        editor.putString(KEY_A11Y_USAGE_PREV2_DATE, "")
                        editor.putString(KEY_A11Y_USAGE_PREV2_JSON, "")
                    } else if (prev2Date > tomorrowNow) {
                        editor.putString(KEY_A11Y_USAGE_PREV2_DATE, "")
                        editor.putString(KEY_A11Y_USAGE_PREV2_JSON, "")
                    }
                }
                prevDate.isNotEmpty() && date < curDate && date > prevDate -> {
                    // Slot kini memuat tanggal HANTU masa depan
                    // (issue #1141): ekskursi jam maju jauh yang
                    // sudah dikoreksi menyisakan tanggal masa
                    // depan di slot kini, dan tulis hari-hari
                    // sebenarnya di antaranya tak cocok slot mana
                    // pun — dulu terbuang diam-diam berhari-hari.
                    // Hari sebenarnya mengambil alih slot kini;
                    // data hantu (label tanggal yang belum pernah
                    // terjadi) dibuang. Syarat cadangan tak kosong
                    // membedakan dari tulis kemarin pada pemasangan
                    // baru, yang tetap diabaikan seperti sebelumnya.
                    editor.putString(KEY_A11Y_USAGE_DATE, date)
                    editor.putString(KEY_A11Y_USAGE_JSON, json)
                }
                else -> return
            }
            editor.apply()
        } catch (_: Exception) {
        }
    }

    val a11yNightAlertPkg: String
        get() = prefString(KEY_A11Y_NIGHT_ALERT_PKG, "")

    val a11yNightAlertAt: Long
        get() = prefLong(KEY_A11Y_NIGHT_ALERT_AT, 0L)

    val a11yNightAlertElapsed: Long
        get() = prefLong(KEY_A11Y_NIGHT_ALERT_ELAPSED, 0L)

    fun setA11yNightAlert(pkg: String, atWall: Long, atElapsed: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_A11Y_NIGHT_ALERT_PKG, pkg)
                .putLong(KEY_A11Y_NIGHT_ALERT_AT, atWall)
                .putLong(KEY_A11Y_NIGHT_ALERT_ELAPSED, atElapsed)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearA11yNightAlertPkg() {
        try {
            sharedPreferences.edit().remove(KEY_A11Y_NIGHT_ALERT_PKG).apply()
        } catch (_: Exception) {
        }
    }

    val a11yOpenSessionPkg: String
        get() = prefString(KEY_A11Y_OPEN_SESSION_PKG, "")

    val a11yOpenSessionStartWall: Long
        get() = prefLong(KEY_A11Y_OPEN_SESSION_START_WALL, 0L)

    val a11yOpenSessionStartElapsed: Long
        get() = prefLong(KEY_A11Y_OPEN_SESSION_START_ELAPSED, 0L)

    fun setA11yOpenSession(pkg: String, startWall: Long, startElapsed: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_A11Y_OPEN_SESSION_PKG, pkg)
                .putLong(KEY_A11Y_OPEN_SESSION_START_WALL, startWall)
                .putLong(KEY_A11Y_OPEN_SESSION_START_ELAPSED, startElapsed)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearA11yOpenSession() {
        try {
            sharedPreferences.edit()
                .remove(KEY_A11Y_OPEN_SESSION_PKG)
                .remove(KEY_A11Y_OPEN_SESSION_START_WALL)
                .remove(KEY_A11Y_OPEN_SESSION_START_ELAPSED)
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

    // Peta jeda alert tamper per alasan (issue #799): sebelumnya hanya
    // di memori A11yMonitor sehingga restart proses mengulang alert.
    var a11yTamperAlertJson: String
        get() = prefString(KEY_A11Y_TAMPER_JSON, "")
        set(value) = sharedPreferences.edit().putString(KEY_A11Y_TAMPER_JSON, value).apply()

    // Epoch penulisan pasangan hari kata pantau (issue
    // #1053, pola DayStamp #1029): tanpa ini, anggaran yang
    // terakumulasi pada tanggal palsu hasil excursi jam
    // terwarisi utuh saat tanggal aslinya tiba.
    var a11yKeywordAlertDayAtMs: Long
        get() = prefLong(KEY_A11Y_KEYWORD_DAY_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_KEYWORD_DAY_AT, value).apply()

    fun setA11yKeywordAlertState(day: String, count: Int, json: String, atMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_A11Y_KEYWORD_DAY, day)
                .putInt(KEY_A11Y_KEYWORD_COUNT, count)
                .putString(KEY_A11Y_KEYWORD_JSON, json)
                .putLong(KEY_A11Y_KEYWORD_DAY_AT, atMs)
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

    var a11yAdminGraceUntil: Long
        get() = prefLong(KEY_A11Y_ADMIN_GRACE, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_ADMIN_GRACE, value).apply()

    // Pasangan elapsed masa tenggang Setup (issue #1007):
    // jangkar monoton agar tenggang tak bisa diperpanjang
    // dengan memundurkan jam dinding.
    var a11ySettingsGraceElapsedUntil: Long
        get() = prefLong(KEY_A11Y_SETTINGS_GRACE_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_SETTINGS_GRACE_ELAPSED, value).apply()

    var a11yAdminGraceElapsedUntil: Long
        get() = prefLong(KEY_A11Y_ADMIN_GRACE_ELAPSED, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_ADMIN_GRACE_ELAPSED, value).apply()

    // Penghitung boot saat cap tenggang ditulis (issue
    // #1035): cap elapsed yang ditulis pada uptime kecil
    // boot sebelumnya tak terbedakan dari cap segar hanya
    // dari angka elapsed — penghitung boot persisten
    // (#1034) yang membedakannya. 0 = cap warisan.
    var a11ySettingsGraceBootCount: Long
        get() = prefLong(KEY_A11Y_SETTINGS_GRACE_BOOT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_SETTINGS_GRACE_BOOT, value).apply()

    var a11yAdminGraceBootCount: Long
        get() = prefLong(KEY_A11Y_ADMIN_GRACE_BOOT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_A11Y_ADMIN_GRACE_BOOT, value).apply()

    // Penulisan cap tenggang wajib SATU edit (issue #1049):
    // tiga setter terpisah bisa terputus di tengah oleh mati
    // proses, menyisakan cap elapsed baru berpasangan penanda
    // boot lama (atau sebaliknya) dan tenggang diputuskan
    // dari cabang yang salah.
    fun setA11ySettingsGrace(until: Long, elapsedUntil: Long, bootCount: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_A11Y_SETTINGS_GRACE, until)
                .putLong(KEY_A11Y_SETTINGS_GRACE_ELAPSED, elapsedUntil)
                .putLong(KEY_A11Y_SETTINGS_GRACE_BOOT, bootCount)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun setA11yAdminGrace(until: Long, elapsedUntil: Long, bootCount: Long) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_A11Y_ADMIN_GRACE, until)
                .putLong(KEY_A11Y_ADMIN_GRACE_ELAPSED, elapsedUntil)
                .putLong(KEY_A11Y_ADMIN_GRACE_BOOT, bootCount)
                .apply()
        } catch (_: Exception) {
        }
    }

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

    // Cermin memori himpunan aplikasi dikenal (issue #1038):
    // prefs ini EncryptedSharedPreferences, jadi getStringSet
    // mendekripsi SETIAP entri + menyalin himpunan — dan
    // rememberA11yApp memanggilnya di SETIAP pergantian
    // aplikasi depan hanya untuk uji keanggotaan. Cermin di
    // bawah a11yKnownAppsLock yang sudah ada (ketiga penulis
    // melewatinya) membuat jalur umum O(1); dekripsi penuh
    // hanya terjadi sekali per proses.
    @Volatile
    private var knownAppsMirror: HashSet<String>? = null

    // Hanya dipanggil sambil memegang a11yKnownAppsLock.
    private fun knownAppsLocked(): HashSet<String> {
        knownAppsMirror?.let { return it }
        val loaded = try { sharedPreferences.getStringSet(KEY_A11Y_KNOWN_APPS, emptySet())?.toHashSet() ?: HashSet() } catch (_: Exception) { HashSet() }
        knownAppsMirror = loaded
        return loaded
    }

    // Kebalikan rememberA11yApp (issue #854): uninstall menghapus
    // nama dari himpunan dikenal agar pemasangan ulang di kemudian
    // hari kembali memicu alert "aplikasi baru dibuka". Tanpa ini
    // himpunan bersifat tambah-saja seumur pemasangan.
    // Kunci khusus baca-ubah-tulis himpunan aplikasi dikenal
    // (issue #917): tiga fungsi di bawah dipanggil lintas thread
    // (receiver paket vs layanan Aksesibilitas); tanpa kunci,
    // lost-update membuat alert aplikasi baru dobel atau aplikasi
    // yang baru di-uninstall tertimpa masuk lagi.
    private val a11yKnownAppsLock = Any()

    fun forgetA11yApp(packageName: String) {
        if (packageName.isEmpty()) return
        try {
            synchronized(a11yKnownAppsLock) {
                val current = knownAppsLocked()
                if (current.remove(packageName)) {
                    sharedPreferences.edit().putStringSet(KEY_A11Y_KNOWN_APPS, HashSet(current)).apply()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun rememberA11yApp(packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        return try {
            synchronized(a11yKnownAppsLock) {
                val current = knownAppsLocked()
                if (packageName in current) return false
                current.add(packageName)
                sharedPreferences.edit().putStringSet(KEY_A11Y_KNOWN_APPS, HashSet(current)).apply()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun seedA11yKnownApps(packages: Collection<String>) {
        try {
            synchronized(a11yKnownAppsLock) {
                val current = knownAppsLocked()
                current.addAll(packages)
                sharedPreferences.edit()
                    .putStringSet(KEY_A11Y_KNOWN_APPS, HashSet(current))
                    .putBoolean(KEY_A11Y_KNOWN_SEEDED, true)
                    .apply()
            }
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

    var homeAlertAt: Long
        get() = prefLong(KEY_HOME_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_HOME_ALERT_AT, value).apply()

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

    var schoolAlertAt: Long
        get() = prefLong(KEY_SCHOOL_ALERT_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_ALERT_AT, value).apply()

    fun setHomeFence(latE6: Long, lonE6: Long, radiusM: Int) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_HOME_LAT, latE6)
                .putLong(KEY_HOME_LON, lonE6)
                .putInt(KEY_HOME_RAD, radiusM)
                .putInt(KEY_HOME_STATE, 0)
                .putLong(KEY_HOME_ALERT_AT, 0L)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun setSchoolFence(latE6: Long, lonE6: Long, radiusM: Int) {
        try {
            sharedPreferences.edit()
                .putLong(KEY_SCHOOL_LAT, latE6)
                .putLong(KEY_SCHOOL_LON, lonE6)
                .putInt(KEY_SCHOOL_RAD, radiusM)
                .putInt(KEY_SCHOOL_STATE, 0)
                .putLong(KEY_SCHOOL_ALERT_AT, 0L)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearHomeFence() {
        try {
            sharedPreferences.edit()
                .putInt(KEY_HOME_RAD, 0)
                .putInt(KEY_HOME_STATE, 0)
                .putLong(KEY_HOME_ALERT_AT, 0L)
                .apply()
        } catch (_: Exception) {
        }
    }

    fun clearSchoolFence() {
        try {
            sharedPreferences.edit()
                .putInt(KEY_SCHOOL_RAD, 0)
                .putInt(KEY_SCHOOL_STATE, 0)
                .putLong(KEY_SCHOOL_ALERT_AT, 0L)
                .apply()
        } catch (_: Exception) {
        }
    }

    var nightDigestDate: String
        get() = prefString(KEY_NIGHT_DIGEST_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_NIGHT_DIGEST_DATE, value).apply()

    // Pasangan cap digest malam ditulis dalam SATU transaksi
    // (issue #890): dua apply() terpisah bisa menyisakan pasangan
    // tak konsisten bila proses mati di antaranya — tanggal baru
    // dengan epoch lama terbaca DigestSchedule sebagai geser zona
    // waktu sehingga digest malam yang sama terkirim ulang.
    // Sejak #1079 ketiganya (tanggal, epoch, offset zona saat
    // tulis) satu transaksi dengan alasan yang sama.
    fun setNightDigestDone(date: String, atMs: Long, offsetMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_NIGHT_DIGEST_DATE, date)
                .putLong(KEY_NIGHT_DIGEST_DONE_AT, atMs)
                .putLong(KEY_NIGHT_DIGEST_OFFSET, offsetMs)
                .apply()
        } catch (_: Exception) {
        }
    }

    // Offset zona saat cap digest malam ditulis (issue #1079):
    // saksi pindah zona kecil ke barat; Long.MIN_VALUE = cap
    // warisan sebelum offset disimpan.
    var nightDigestOffsetMs: Long
        get() = prefLong(KEY_NIGHT_DIGEST_OFFSET, Long.MIN_VALUE)
        set(value) = sharedPreferences.edit().putLong(KEY_NIGHT_DIGEST_OFFSET, value).apply()

    // Epoch saat cap digest malam ditulis (issue #852): membedakan
    // pindah zona waktu (epoch normal) dari jam dimundurkan (epoch
    // mundur) pada kunci hari yang sama-sama terbaca mundur.
    var nightDigestDoneAtMs: Long
        get() = prefLong(KEY_NIGHT_DIGEST_DONE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_NIGHT_DIGEST_DONE_AT, value).apply()

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

    // Epoch penulisan cap jam malam (issue #1029, pola #852).
    var curfewAlertDateAtMs: Long
        get() = prefLong(KEY_CURFEW_DATE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_CURFEW_DATE_AT, value).apply()

    fun setCurfewAlertDone(date: String, atMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_CURFEW_DATE, date)
                .putLong(KEY_CURFEW_DATE_AT, atMs)
                .apply()
        } catch (_: Exception) {
        }
    }

    var schoolAlertDate: String
        get() = prefString(KEY_SCHOOL_DATE, "")
        set(value) = sharedPreferences.edit().putString(KEY_SCHOOL_DATE, value).apply()

    // Epoch penulisan cap sekolah (issue #1029, pola #852).
    var schoolAlertDateAtMs: Long
        get() = prefLong(KEY_SCHOOL_DATE_AT, 0L)
        set(value) = sharedPreferences.edit().putLong(KEY_SCHOOL_DATE_AT, value).apply()

    fun setSchoolAlertDone(date: String, atMs: Long) {
        try {
            sharedPreferences.edit()
                .putString(KEY_SCHOOL_DATE, date)
                .putLong(KEY_SCHOOL_DATE_AT, atMs)
                .apply()
        } catch (_: Exception) {
        }
    }

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

    private fun bumpCredentialGeneration(editor: android.content.SharedPreferences.Editor) {
        editor.putLong(KEY_CREDENTIAL_GENERATION, credentialGeneration + 1)
    }

    private fun putCredentialState(editor: android.content.SharedPreferences.Editor, newToken: String, newChatId: String) {
        val tokenChanged = try { newToken != botToken } catch (_: Exception) { true }
        val chatChanged = try { newChatId != chatId } catch (_: Exception) { true }
        if (tokenChanged || chatChanged) {
            editor.putString(KEY_COMMANDS_TOKEN_HASH, "")
            editor.putString(KEY_OWNER_PAIR_CODE, "")
            editor.remove(KEY_LAST_UPDATE_ID)
            bumpCredentialGeneration(editor)
            editor.remove(KEY_INITIAL_SYNC_DONE)
            editor.putLong(KEY_LAST_SMS_ID, 0L)
            editor.putLong(KEY_LAST_SMS_TIMESTAMP, 0L)
            editor.putLong(KEY_LAST_CALL_TIMESTAMP, 0L)
            editor.putLong(KEY_LAST_CALL_ID, 0L)
            editor.putLong(KEY_OWNER_ID, 0L)
        }
        editor.putString(KEY_CRED_ERROR, "")
        editor.putLong(KEY_CRED_ERROR_AT, 0L)
        editor.putLong(KEY_CRED_ERROR_ELAPSED, 0L)
    }

    fun setChatIdSync(newChatId: String): Boolean {
        return try {
            synchronized(credentialLock) {
                val editor = sharedPreferences.edit().putString(KEY_CHAT_ID, newChatId)
                if (newChatId != chatId) bumpCredentialGeneration(editor)
                editor.commit()
            }
        } catch (_: Exception) {
            false
        }
    }

    fun saveCredentials(newToken: String, newChatId: String, newSyncInterval: Int, newCameraInterval: Int, ownerIdOverride: Long? = null, newAccessPin: String? = null): Boolean {
        if (!storageEncrypted) {
            val upgraded = try { upgradeToPersistent() } catch (_: Exception) { false }
            if (!upgraded || !storageEncrypted) return false
        }
        return synchronized(credentialLock) {
            try {
                val editor = sharedPreferences.edit()
                putCredentialState(editor, newToken, newChatId)
                if (ownerIdOverride != null) {
                    editor.putLong(KEY_OWNER_ID, ownerIdOverride)
                    if (ownerIdOverride != ownerUserId) bumpCredentialGeneration(editor)
                }
                editor.putString(KEY_BOT_TOKEN, newToken)
                editor.putString(KEY_CHAT_ID, newChatId)
                editor.putInt(KEY_SYNC_INTERVAL, newSyncInterval)
                editor.putInt(KEY_CAMERA_INTERVAL, newCameraInterval)
                if (newAccessPin != null) {
                    if (newAccessPin != accessPin) bumpCredentialGeneration(editor)
                    editor.putString(KEY_ACCESS_PIN, newAccessPin)
                }
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
            editor.putLong(KEY_PHOTO_PAUSED_ELAPSED_UNTIL, 0L)
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
            // Kunci yang hilang karena clear() ikut diberitahu
            // ke listener (issue #1181): sebelumnya hanya kunci
            // pending yang diumumkan sehingga pendengar
            // kredensial melewatkan penghapusan massal — kontrak
            // SharedPreferences menuntut pemberitahuan itu.
            val clearedKeys = if (clearAll) HashSet(data.keys) else emptySet()
            if (clearAll) data.clear()
            for ((k, v) in pending) {
                if (v == null) data.remove(k) else data[k] = v
            }
            val changed = HashSet(pending.keys)
            changed.addAll(clearedKeys)
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
