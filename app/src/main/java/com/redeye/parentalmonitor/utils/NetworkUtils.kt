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
    private const val KEY_RATE_LIMITED_WALL_AT_WRITE = "rate_limited_wall_at_write"
    private const val KEY_RATE_LIMITED_ELAPSED_AT_WRITE = "rate_limited_elapsed_at_write"
    private const val MAX_RATE_LIMIT_MS = 3600_000L

    // Usia penanda memakai jam ganda (issue #733, pola #687/#698): selama
    // jangkar monoton berlaku (boot sama), usia dihitung dari elapsed —
    // memundurkan/memajukan jam dinding tidak mengubah keputusan. Tanpa
    // jangkar (penanda warisan) atau sesudah reboot, jam dinding dipakai;
    // jam dinding yang mundur tanpa jangkar mengembalikan -1 (tak
    // diketahui) agar perilaku warisan pemanggil tetap berlaku.
    // Jangkar elapsed wajib terbukti boot yang sama (issue #1008,
    // keluarga #941/#957): cap elapsed tersimpan bisa terlewati lagi
    // oleh elapsedRealtime boot BARU (mis. penanda di elapsed 5 hari,
    // reboot, 5 hari kemudian terlampaui) dan selisihnya terbaca
    // sebagai usia muda palsu — sameBootOrClockShift menolaknya,
    // lalu usia jam dinding yang dipakai.
    fun blockAgeMs(atWall: Long, atElapsed: Long, nowWall: Long, nowElapsed: Long): Long {
        if (atElapsed > 0L && nowElapsed >= atElapsed && BootClock.sameBootOrClockShift(atWall, atElapsed, nowWall, nowElapsed)) return nowElapsed - atElapsed
        if (nowWall < atWall) return -1L
        return nowWall - atWall
    }

    // Sisa jendela bertenggat jam dinding dengan jangkar ganda: durasi
    // awal (untilWall - writtenWall) dikurangi usia monoton bila boot
    // sama; selain itu sisa jam dinding polos. Selalu terkurung 0..maxMs.
    fun remainMsDual(untilWall: Long, writtenWall: Long, writtenElapsed: Long, nowWall: Long, nowElapsed: Long, maxMs: Long): Long {
        if (untilWall <= 0L) return 0L
        // Jangkar elapsed wajib terbukti boot yang sama
        // (issue #1193, keluarga #941/#957/#1008): tanpa bukti
        // itu, penanda dari boot lama yang elapsed-nya terlewati
        // lagi oleh boot baru menghasilkan sisa hantu — pola
        // yang sama yang sudah ditutup blockAgeMs di #1008
        // tetapi terlewat di sini karena penandanya jam ganda.
        val remain = if (writtenWall > 0L && writtenElapsed > 0L && nowElapsed >= writtenElapsed &&
            BootClock.sameBootOrClockShift(writtenWall, writtenElapsed, nowWall, nowElapsed)) {
            (untilWall - writtenWall) - (nowElapsed - writtenElapsed)
        } else {
            untilWall - nowWall
        }
        return remain.coerceIn(0L, maxMs)
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var rateLimitedUntil = 0L

    private val rateLimitLock = Any()

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
            if (stored > 0L) {
                // Tulis gabung di bawah kunci yang sama dengan
                // noteRateLimited + maxOf (issue #1098): tulis
                // telanjang di sini bisa menimpa jendela 429
                // yang lebih panjang yang baru dipasang penulis
                // terkunci, membuat pemanggil bangun lebih awal
                // dari retry_after.
                synchronized(rateLimitLock) {
                    val candidate = nowElapsed + stored
                    if (candidate > rateLimitedUntil) rateLimitedUntil = candidate
                }
            }
            stored
        } catch (_: Exception) {
            0L
        }
    }

    private fun storedRateLimitRemainMs(): Long {
        val context = appContext ?: return 0L
        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return 0L
        }
        val until = try { prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L) } catch (_: Exception) { 0L }
        if (until <= 0L) return 0L
        val writtenWall = try { prefs.getLong(KEY_RATE_LIMITED_WALL_AT_WRITE, 0L) } catch (_: Exception) { 0L }
        val writtenElapsed = try { prefs.getLong(KEY_RATE_LIMITED_ELAPSED_AT_WRITE, 0L) } catch (_: Exception) { 0L }
        return remainMsDual(until, writtenWall, writtenElapsed, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), MAX_RATE_LIMIT_MS)
    }

    fun noteRateLimited(retryAfterSecs: Long) {
        synchronized(rateLimitLock) {
            try {
                val waitMs = retryAfterSecs.coerceIn(1L, 3600L) * 1000L
                rateLimitedUntil = maxOf(rateLimitedUntil, android.os.SystemClock.elapsedRealtime() + waitMs)
                val context = appContext ?: return
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val nowWall = System.currentTimeMillis()
                val untilWall = nowWall + waitMs
                if (untilWall > prefs.getLong(KEY_RATE_LIMITED_UNTIL, 0L)) {
                    prefs.edit()
                        .putLong(KEY_RATE_LIMITED_UNTIL, untilWall)
                        .putLong(KEY_RATE_LIMITED_WALL_AT_WRITE, nowWall)
                        .putLong(KEY_RATE_LIMITED_ELAPSED_AT_WRITE, android.os.SystemClock.elapsedRealtime())
                        .apply()
                }
            } catch (_: Exception) {
            }
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
            val age = blockAgeMs(prefs.credentialErrorAt, prefs.credentialErrorElapsed, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            age >= 0L && age < 30 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    fun isPollBlocked(prefs: PreferencesManager): Boolean {
        return try {
            val err = prefs.credentialError
            if (err != "401" && err != "403") return false
            val window = if (err == "403") 5 * 60_000L else 30 * 60_000L
            val age = blockAgeMs(prefs.credentialErrorAt, prefs.credentialErrorElapsed, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())
            age >= 0L && age < window
        } catch (_: Exception) {
            false
        }
    }

    fun sweepAuthBlock(prefs: PreferencesManager) {
        try {
            val err = prefs.credentialError
            if (err.isEmpty()) return
            val now = System.currentTimeMillis()
            val at = prefs.credentialErrorAt
            val elapsed = prefs.credentialErrorElapsed
            val age = blockAgeMs(at, elapsed, now, android.os.SystemClock.elapsedRealtime())
            // Pembersihan dini warisan (jam dinding mundur tanpa jangkar)
            // hanya berlaku untuk penanda tanpa jangkar monoton.
            // Hapus lewat penjaga kecocokan (issue #1052): penanda
            // segar yang ditulis di antara baca dan hapus tak
            // boleh ikut lenyap.
            if (age >= 30 * 60_000L || (elapsed <= 0L && now < at)) {
                prefs.clearCredentialErrorIfMatches(err, at, elapsed)
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
            // CAS terhadap chat kiriman (issue #1377): adopsi
            // hanya bila chat tersimpan masih chat yang dipakai
            // kiriman gagal ini — bukan kredensial yang lebih
            // baru dari pemilik.
            if (!prefs.setChatIdIfCurrentSync(currentChatId, migrated)) return false
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
