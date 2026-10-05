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

    private fun savePending(context: Context, report: String) {
        try {
            val file = pendingFile(context)
            val prev = try {
                if (file.exists()) stripElapsed(file.readText()) else ""
            } catch (_: Exception) {
                ""
            }
            val combined = if (prev.isBlank()) report else (prev + "\n---\n" + report).takeLast(MAX_CHARS * 2 + 16)
            file.writeText(android.os.SystemClock.elapsedRealtime().toString() + "\n" + combined)
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
                try {
                    file.delete()
                } catch (_: Exception) {
                }
                return
            }
            val report = stripElapsed(raw)
            if (report.isBlank()) {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
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
            if (token.isEmpty() || chatId.isEmpty()) return
            try {
                val wallAge = System.currentTimeMillis() - file.lastModified()
                val savedElapsed = raw.substring(0, raw.indexOf('\n').takeIf { it > 0 } ?: 0).toLongOrNull() ?: 0L
                val monoAge = if (savedElapsed > 0L) android.os.SystemClock.elapsedRealtime() - savedElapsed else wallAge
                val age = if (wallAge < 0L && monoAge < 0L) 0L else if (wallAge < 0L) monoAge else if (monoAge < 0L) wallAge else maxOf(wallAge, monoAge)
                if (age > 7 * 24 * 60 * 60_000L) {
                    try { file.delete() } catch (_: Exception) { }
                    return
                }
            } catch (_: Exception) {
            }
            val allowed = try {
                prefs.isMonitoringEnabled && prefs.userConsentedMonitoring && !prefs.userDisabledMonitoring
            } catch (_: Exception) {
                false
            }
            if (!allowed) {
                try { file.delete() } catch (_: Exception) { }
                return
            }
            try {
                val fitted = if (report.length > 4000) report.take(TextChunk.safeCut(report, 4000)) else report
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = fitted, parseMode = "HTML"))
                if (response.isSuccessful && response.body()?.ok == true) {
                    file.delete()
                } else if (response.code() == 400) {
                    val body = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    if (!NetworkUtils.isChatMissing(body)) {
                        val delivered = try {
                            val plain = fitted.replace(Regex("<[^>]*>"), "")
                            val plainResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                            plainResp.isSuccessful && plainResp.body()?.ok == true
                        } catch (_: Exception) {
                            false
                        }
                        if (delivered) {
                            try {
                                file.delete()
                            } catch (_: Exception) {
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
        raw = Redact.token(raw)
        if (raw.length > MAX_CHARS) raw = safeTake(raw, MAX_CHARS)
        val escaped = Html.escape(raw)
        val full = "<b>Force close</b>\n<pre>" + escaped + "</pre>"
        if (full.length <= 4000) return full
        val keep = TextChunk.safeCut(escaped, (4000 - 60).coerceAtLeast(500))
        return "<b>Force close</b>
<pre>" + escaped.take(keep) + "</pre>"
    }

}
