package com.redeye.parentalmonitor.utils

import android.content.Context
import android.os.Build
import com.redeye.parentalmonitor.BuildConfig
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
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
            else file.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
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
            pendingFile(context).writeText(report)
        } catch (_: Exception) {
        }
    }

    private suspend fun flushPending(context: Context) {
        val file = pendingFile(context)
        val report = try {
            if (!file.exists()) return
            file.readText()
        } catch (_: Exception) {
            return
        }
        if (report.isBlank()) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
            return
        }
        if (!NetworkUtils.isNetworkAvailable(context)) return
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
            val url = "https://api.telegram.org/bot$token/sendMessage"
            val response = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = report))
            if (response.isSuccessful && response.body()?.ok == true) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildReport(context: Context, thread: Thread, error: Throwable): String {
        val token = try {
            PreferencesManager.getInstance(context).botToken
        } catch (_: Exception) {
            ""
        }
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
        var text = "<b>Force close</b>\n<pre>" + Html.escape(body.toString()) + "</pre>"
        if (token.isNotEmpty()) text = text.replace(token, "***")
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS)
        return text
    }

}
