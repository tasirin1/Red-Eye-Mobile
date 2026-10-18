package com.redeye.parentalmonitor.utils

import android.content.Context
import android.os.Build
import com.redeye.parentalmonitor.BuildConfig
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object CrashReporter {

    private const val PENDING_FILE = "crash_pending.txt"
    private const val MAX_CHARS = 3500
    private const val MAX_FRAMES = 25
    private const val MAX_CAUSES = 4

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val flushing = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                saveNow(appContext, thread, error)
            } catch (_: Exception) {
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
        scope.launch {
            try {
                flushPending(appContext)
            } catch (_: Exception) {
            }
        }
    }

    fun pendingReport(context: Context): String? {
        return try {
            val file = pendingFile(context.applicationContext)
            if (!file.exists()) null
            else stripElapsed(file.readText()).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun stripElapsed(raw: String): String {
        val nl = raw.indexOf('\n')
        if (nl <= 0) return raw
        return if (raw.substring(0, nl).toLongOrNull() != null) raw.substring(nl + 1) else raw
    }

    fun clearPending(context: Context) {
        try {
            pendingFile(context.applicationContext).delete()
        } catch (_: Exception) {
        }
    }

    fun saveNow(context: Context, thread: Thread, error: Throwable) {
        try {
            savePending(context.applicationContext, buildReport(context.applicationContext, thread, error))
        } catch (_: Exception) {
        }
    }

    fun saveNow(context: Context, error: Throwable) {
        saveNow(context, Thread.currentThread(), error)
    }

    private fun pendingFile(context: Context): File {
        return File(context.filesDir, PENDING_FILE)
    }

    private val saveLock = Any()

    private fun savePending(context: Context, report: String) {
        synchronized(saveLock) {
            val file = pendingFile(context)
            val prevRaw = try {
                if (file.exists()) file.readText() else ""
            } catch (_: Exception) {
                ""
            }
            val prev = stripElapsed(prevRaw)
            val merged = if (prev.isBlank()) report else (prev + "\n---\n" + report)
            val cap = MAX_CHARS * 2 + 16
            var start = if (merged.length > cap) merged.length - cap else 0
            if (start > 0 && start < merged.length && Character.isLowSurrogate(merged[start]) && Character.isHighSurrogate(merged[start - 1])) start += 1
            val combined = if (start > 0) merged.substring(start) else merged
            val elapsed0 = prevRaw.substringBefore('\n').toLongOrNull() ?: 0L
            writeReportFile(file, (if (elapsed0 > 0L) elapsed0 else android.os.SystemClock.elapsedRealtime()).toString() + "\n" + combined)
        }
    }

    private fun writeReportFile(file: File, text: String) {
        val tmp = File(file.parent, file.name + ".tmp")
        try {
            tmp.writeText(text)
            if (tmp.renameTo(file)) return
            tmp.copyTo(file, overwrite = true)
        } catch (_: Exception) {
        } finally {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun writePendingRest(file: File, elapsed0: Long, rest: String) {
        try {
            writeReportFile(file, (if (elapsed0 > 0L) elapsed0 else android.os.SystemClock.elapsedRealtime()).toString() + "\n" + rest)
        } catch (_: Exception) {
        }
    }

    private fun keepRemainder(file: File, raw: String, rest: String, elapsed0: Long) {
        try {
            val cur = try { if (file.exists()) file.readText() else null } catch (_: Exception) { null }
            if (cur == null || cur == raw) {
                if (rest.isBlank()) {
                    try { if (cur != null) file.delete() } catch (_: Exception) { }
                } else writePendingRest(file, elapsed0, rest)
                return
            }
            val appended = if (cur.startsWith(raw)) cur.substring(raw.length).removePrefix("\n---\n") else null
            if (appended != null) {
                val combined = when {
                    rest.isBlank() -> appended
                    appended.isBlank() -> rest
                    else -> rest + "\n---\n" + appended
                }
                writePendingRest(file, elapsed0, combined)
                return
            }
            val curReport = stripElapsed(cur)
            val combined = if (rest.isBlank()) curReport else (curReport + "\n---\n" + rest)
            writePendingRest(file, elapsed0, combined)
        } catch (_: Exception) {
        }
    }

    suspend fun flushPending(context: Context) {
        if (!flushing.compareAndSet(false, true)) return
        try {
            val file = pendingFile(context)
            val raw = try {
                if (!file.exists()) return
                file.readText()
            } catch (_: Exception) {
                return
            }
            if (raw.isBlank()) {
                keepRemainder(file, raw, "", 0L)
                return
            }
            val report = stripElapsed(raw)
            val savedElapsed0 = raw.substring(0, raw.indexOf('\n').takeIf { it > 0 } ?: 0).toLongOrNull() ?: 0L
            if (report.isBlank()) {
                keepRemainder(file, raw, "", 0L)
                return
            }
            if (!NetworkUtils.isNetworkAvailable(context)) return
            try {
                if (NetworkUtils.isAuthBlocked(PreferencesManager.getInstance(context))) return
            } catch (_: Exception) {
            }
            try { PreferencesManager.refreshInstance(context) } catch (_: Exception) { }
            val prefs = PreferencesManager.getInstance(context)
            val token = try {
                prefs.botToken
            } catch (_: Exception) {
                ""
            }
            val chatId = try {
                prefs.chatId
            } catch (_: Exception) {
                ""
            }
            try {
                val wallAge = System.currentTimeMillis() - file.lastModified()
                val savedElapsed = savedElapsed0
                val monoAge = if (savedElapsed > 0L) android.os.SystemClock.elapsedRealtime() - savedElapsed else wallAge
                val age = if (wallAge < 0L && monoAge < 0L) 0L else if (wallAge < 0L) monoAge else if (monoAge < 0L) wallAge else minOf(wallAge, monoAge)
                if (age > 7 * 24 * 60 * 60_000L) {
                    keepRemainder(file, raw, "", 0L)
                    return
                }
            } catch (_: Exception) {
            }
            // Always allow crash reports through - they're critical for debugging
            // Only skip if credentials are missing
            if (token.isEmpty() || chatId.isEmpty()) return
            try {
                if (NetworkUtils.rateLimitedRemainMs() > 0L) return
            } catch (_: Exception) {
            }
            try {
                val cut = if (report.length > 4000) TextChunk.safeCut(report, 4000) else report.length
                val fitted = report.take(cut)
                val rest = if (cut < report.length) report.substring(cut) else ""
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = fitted, parseMode = "HTML"))
                if (response.isSuccessful && response.body()?.ok == true) {
                    if (rest.isBlank()) {
                        keepRemainder(file, raw, "", 0L)
                    } else {
                        keepRemainder(file, raw, rest, savedElapsed0)
                    }
                } else if (response.code() == 429) {
                    try {
                        NetworkUtils.noteRateLimited(NetworkUtils.parseRetryAfter(response.errorBody()?.string()))
                    } catch (_: Exception) {
                    }
                } else if (response.code() == 401 || response.code() == 403) {
                    try {
                        prefs.credentialError = response.code().toString()
                        prefs.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                } else if (response.code() == 400) {
                    val body = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    if (NetworkUtils.isChatMissing(body)) {
                        try {
                            prefs.credentialError = "400"
                            prefs.credentialErrorAt = System.currentTimeMillis()
                        } catch (_: Exception) {
                        }
                    }
                    if (!NetworkUtils.isChatMissing(body)) {
                        val delivered: Boolean = try {
                            val plain = Html.unescape(fitted)
                            val plainResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                            if (plainResp.isSuccessful && plainResp.body()?.ok == true) {
                                true
                            } else if (plainResp.code() == 429) {
                                try {
                                    NetworkUtils.noteRateLimited(NetworkUtils.parseRetryAfter(plainResp.errorBody()?.string()))
                                } catch (_: Exception) { }
                                false
                            } else if (plainResp.code() == 401 || plainResp.code() == 403) {
                                try { prefs.credentialError = "401"; prefs.credentialErrorAt = System.currentTimeMillis() } catch (_: Exception) { }
                                false
                            } else if (plainResp.code() == 400) {
                                val fbBody = try { plainResp.errorBody()?.string() } catch (_: Exception) { null }
                                if (NetworkUtils.isChatMissing(fbBody)) {
                                    try { prefs.credentialError = "400"; prefs.credentialErrorAt = System.currentTimeMillis() } catch (_: Exception) { }
                                }
                                false
                            } else {
                                false
                            }
                        } catch (_: Exception) {
                            false
                        }
                        if (delivered) {
                            if (rest.isBlank()) {
                                keepRemainder(file, raw, "", 0L)
                            } else {
                                keepRemainder(file, raw, rest, savedElapsed0)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        } finally {
            flushing.set(false)
        }
    }

    private fun safeTake(text: String, max: Int): String {
        return text.take(TextChunk.safeCut(text, max))
    }

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val body = StringBuilder()
        body.append("v").append(BuildConfig.VERSION_NAME)
            .append(" api").append(Build.VERSION.SDK_INT)
            .append(' ').append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append('\n').append(thread.name).append('\n')
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (depth > 0) body.append("Caused by: ")
            body.append(current.javaClass.name).append(": ").append(current.message ?: "").append('\n')
            val frames = current.stackTrace.take(MAX_FRAMES)
            for (frame in frames) {
                body.append("  at ").append(frame.className).append('.').append(frame.methodName)
                    .append(" (").append(frame.fileName ?: "?").append(':').append(frame.lineNumber).append(")\n")
            }
            current = current.cause
            depth++
        }
        var raw = body.toString()
        val secrets = try {
            val prefs = PreferencesManager.getInstance(context)
            arrayOf(prefs.botToken, prefs.chatId)
        } catch (_: Exception) {
            emptyArray()
        }
        raw = Redact.token(raw, *secrets)
        if (raw.length > MAX_CHARS) raw = safeTake(raw, MAX_CHARS)
        val escaped = Html.escape(raw)
        val full = "<b>Force close</b>\n<pre>" + escaped + "</pre>"
        if (full.length <= 4000) return full
        val keep = TextChunk.safeCut(escaped, (4000 - 60).coerceAtLeast(500))
        return "<b>Force close</b>\n<pre>" + safeTake(escaped, keep) + "</pre>"
    }

}
