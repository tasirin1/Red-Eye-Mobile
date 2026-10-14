package com.redeye.parentalmonitor.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
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
    private val videoFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile
    private var activeAudioFile: File? = null
    @Volatile
    private var activePhotoFile: File? = null
    @Volatile
    private var activeShotFile: File? = null
    @Volatile
    private var activeVideoFile: File? = null
    private var ringJob: Job? = null
    private var recordJob: Job? = null
    private var smsJob: Job? = null
    private var watchdogJob: Job? = null
    private var loopWatchdogJob: Job? = null
    @Volatile
    private var loopWatchdogNoticeAt = 0L
    @Volatile
    private var monitorBeatAt = 0L
    @Volatile
    private var monitorCycleMs = 0L
    @Volatile
    private var cameraBeatAt = 0L
    @Volatile
    private var cameraCycleMs = 0L
    @Volatile
    private var idleDataCycles = 0
    @Volatile
    private var serviceStartAt = 0L
    private val cameraAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private val videoAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private var videoWatchdog: Job? = null
    private val shotBusy = AtomicBoolean(false)
    private var shotWatchdog: Job? = null
    private val shotAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var appliedFgsTypes = 0

    private enum class MediaSendOutcome {
        SENT, KEPT, DROPPED
    }
    private var monitoringJob: Job? = null
    private var cameraJob: Job? = null
    private var commandJob: Job? = null
    private var initialSyncJob: Job? = null
    private var patrolJob: Job? = null
    private val initialSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val initialSyncLock = Any()
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
    @Volatile
    private var cachedPatrolEnabled = false
    @Volatile
    private var cachedPatrolInterval = 30
    @Volatile
    private var cachedSetupTap: android.app.PendingIntent? = null
    private val handledUpdateIds = java.util.Collections.synchronizedSet(LinkedHashSet<Long>())
    private val handledCallbackIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        private const val HEX_DIGITS = "0123456789abcdef"
        private val SMS_NUMBER_REGEX = Regex("^\\+?[0-9]{7,15}$")
        private val CMD_SPLIT_REGEX = "\\s+".toRegex()
        private val CURFEW_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private val OTP_CODE_REGEX = Regex("\\b\\d{4,8}\\b")
        private val OTP_KEYWORDS = arrayOf("otp", "kode", "verifikasi", "verification", "otentikasi", "aktivasi", "konfirmasi", "token", "password", "kata sandi")
        private val PROMO_KEYWORDS = arrayOf("promo", "diskon", "discount", "cashback", "bonus", "hadiah", "undian", "pinjaman", "kredit", "cicilan", "belanja", "sale", "gratis", "klaim", "voucher", "kupon", "poin", "tukar")
        private val CONNECT_HELPER_REGEX = Regex("(?i)connectHelper:\\d+:\\s*")
        private val MUTATING_COMMANDS = setOf("/lock", "/ring", "/sms", "/smsconfirm", "/record", "/recordvideo", "/stop", "/resume", "/pause", "/photointerval", "/syncinterval", "/camera", "/notif", "/restart", "/flush", "/clearqueue", "/patrol", "/patrolinterval", "/night", "/watch", "/home", "/school", "/digest", "/darurat", "/jampulang", "/minggu", "/watchnomor", "/jejak", "/waspada", "/hilang", "/jemput")
        private val SENSITIVE_COMMANDS = setOf("/screenshot", "/photo", "/location", "/lastcalls", "/lastsms", "/lastnotif", "/contacts", "/history", "/apps", "/log", "/version", "/status", "/battery", "/uptime", "/storage", "/perms", "/ringkas")
        private val KNOWN_COMMANDS = setOf("/photo", "/screenshot", "/status", "/perms", "/lastcalls", "/lastsms", "/photointerval", "/patrol", "/patrolinterval", "/pause", "/battery", "/stop", "/resume", "/location", "/camera", "/notif", "/log", "/syncinterval", "/restart", "/flush", "/clearqueue", "/lock", "/ring", "/ping", "/record", "/recordvideo", "/sms", "/smsconfirm", "/lastnotif", "/version", "/uptime", "/contacts", "/apps", "/storage", "/history", "/help", "/start", "/night", "/ringkas", "/watch", "/home", "/school", "/digest", "/darurat", "/jampulang", "/minggu", "/watchnomor", "/jejak", "/waspada", "/hilang", "/jemput")
        private val ALIAS_COMMANDS = mapOf("dimana" to "/location", "lokasi" to "/location", "posisi" to "/location", "foto" to "/photo", "photo" to "/photo", "ding" to "/ring", "bunyi" to "/ring", "kunci" to "/lock", "baterai" to "/battery", "batre" to "/battery", "bantu" to "/help", "tolong" to "/help", "menu" to "/help", "lanjut" to "/resume", "versi" to "/version", "sms" to "/sms", "darurat" to "/darurat", "minggu" to "/minggu", "laporan" to "/minggu", "pulang" to "/jampulang", "jejak" to "/jejak", "waspada" to "/waspada", "hilang" to "/hilang", "jemput" to "/jemput")
        private const val COMMAND_MAX_AGE_SEC = 900L
        private const val MUTATING_MAX_AGE_SEC = 300L
        private const val PING_RATE_WINDOW_MS = 120_000L
        private const val PING_RESTART_COOLDOWN_MS = 300_000L
        private const val NOTIFICATION_ID = 1
        private const val MAX_AUDIO_KEPT = 5
        private const val MAX_VIDEO_KEPT = 3
        private val storageWarnAt = java.util.concurrent.atomic.AtomicLong(0L)
        private val smsReqSeq = java.util.concurrent.atomic.AtomicInteger(java.util.concurrent.ThreadLocalRandom.current().nextInt())
        private val authReminderAt = java.util.concurrent.atomic.AtomicLong(0L)
        private const val AUTH_NOTIF_ID = 4
        @Volatile
        var isRunning = false

    }

    override fun onCreate() {
        super.onCreate()
        try {
            preferencesManager = PreferencesManager.getInstance(this)
        } catch (_: Exception) {
            try {
                stopSelf()
            } catch (_: Exception) {
            }
            return
        }
        startForegroundImmediate()
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    if (PreferencesManager.refreshInstance(this)) {
                        refreshCreds()
                        refreshLoopConfig()
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        credsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            try {
            if (key == PreferencesManager.KEY_BOT_TOKEN || key == PreferencesManager.KEY_CHAT_ID) {
                refreshCreds()
                if (key == PreferencesManager.KEY_BOT_TOKEN) {
                    try {
                        synchronized(handledUpdateIds) { handledUpdateIds.clear() }
                    } catch (_: Exception) {
                    }
                    try {
                        synchronized(handledCallbackIds) { handledCallbackIds.clear() }
                    } catch (_: Exception) {
                    }
                    try {
                        serviceScope.launch { registerBotCommands() }
                    } catch (_: Exception) {
                    }
                }
            }
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL || key == PreferencesManager.KEY_SYNC_INTERVAL || key == PreferencesManager.KEY_PATROL_ENABLED || key == PreferencesManager.KEY_PATROL_INTERVAL) refreshLoopConfig()
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL) restartCameraLoop()
            if (key == PreferencesManager.KEY_PATROL_ENABLED || key == PreferencesManager.KEY_PATROL_INTERVAL) restartPatrolLoop()
            } catch (_: Exception) {
            }
        }
        try { credsListener?.let { preferencesManager.registerChangeListener(it) } } catch (_: Exception) { }
        try {
            smsRepository = SmsRepository(this)
            callLogRepository = CallLogRepository(this)
            messageQueue = MessageQueue.getInstance(this)
            cameraService = CameraService(this)
        } catch (_: Exception) {
            try {
                MessageScheduler.scheduleBootRestart(this)
            } catch (_: Exception) {
            }
            try {
                stopSelf()
            } catch (_: Exception) {
            }
            return
        }
        try {
            val pending = preferencesManager.pendingMsgDrops
            if (pending > 0) {
                msgDropCount.addAndGet(pending)
                synchronized(msgDropLock) {
                    if (msgDropJob?.isActive != true) {
                        msgDropJob = serviceScope.launch {
                            try {
                                delay(30_000L)
                            } catch (_: Exception) {
                            }
                            try {
                                flushMsgDropNotice()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        restoreRingVolumeIfStuck()
    }

    private fun restoreRingVolumeIfStuck() {
        try {
            if (ringBusy.get()) return
            val previous = try { preferencesManager.ringPrevVolume } catch (_: Exception) { -1 }
            val savedAt = try { preferencesManager.ringSavedAt } catch (_: Exception) { 0L }
            if (previous < 0 || savedAt <= 0L) return
            if (System.currentTimeMillis() - savedAt >= 12 * 60 * 60_000L) {
                try {
                    preferencesManager.clearRingStateSync()
                } catch (_: Exception) {
                }
                return
            }
            val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            try {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, previous, 0)
            } catch (_: Exception) {
            }
            try {
                preferencesManager.clearRingStateSync()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
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
            val startTypes = try {
                computeForegroundTypes()
            } catch (_: Exception) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(NOTIFICATION_ID, notification, startTypes)
                    appliedFgsTypes = startTypes
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
        try {
            startForegroundImmediate()
        } catch (_: Exception) {
        }
        return try {
            when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()
                return START_STICKY
            }
            else -> {
                if (shouldAutoResume()) {
                    startMonitoring()
                    return START_STICKY
                }
                try {
                    com.redeye.parentalmonitor.utils.Background.runCritical {
                        try {
                            if (PreferencesManager.refreshInstance(this@MonitoringService)) {
                                preferencesManager = PreferencesManager.getInstance(this@MonitoringService)
                                refreshCreds()
                                refreshLoopConfig()
                            }
                        } catch (_: Exception) {
                        }
                        try {
                            if (shouldAutoResume() || needsCredsRetry()) {
                                MessageScheduler.scheduleBootRestart(this@MonitoringService)
                            } else {
                                stopSelf()
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                return START_NOT_STICKY
            }
            }
            START_STICKY
        } catch (_: Exception) {
            START_STICKY
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
            val hex = CharArray(digest.size * 2)
            for (i in digest.indices) {
                val b = digest[i].toInt()
                hex[i * 2] = HEX_DIGITS[(b ushr 4) and 0xF]
                hex[i * 2 + 1] = HEX_DIGITS[b and 0xF]
            }
            String(hex)
        } catch (_: Exception) {
            value.hashCode().toString()
        }
    }

    private fun redactToken(value: String?): String {
        val token = try {
            cachedBotToken.ifEmpty { preferencesManager.botToken }
        } catch (_: Exception) {
            ""
        }
        val chat = try {
            cachedChatId.ifEmpty { preferencesManager.chatId }
        } catch (_: Exception) {
            ""
        }
        return com.redeye.parentalmonitor.utils.Redact.token(value, token, chat)
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
        if (monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true && loopWatchdogJob?.isActive == true && patrolJob?.isActive == true) {
            refreshCreds()
            refreshLoopConfig()
            return
        }
        serviceStartAt = System.currentTimeMillis()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Starting monitoring service ===")

        // Cancel any previous loops so a restart never duplicates work
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        patrolJob?.cancel()
        idlePolls = 0
        refreshCreds()
        refreshLoopConfig()

        val notificationBuilder = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
        try {
            notificationBuilder.setContentIntent(setupTapIntent())
        } catch (_: Exception) {
        }
        
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

        synchronized(initialSyncLock) {
            val alreadyRunning = initialSyncRunning.get() && initialSyncJob?.isActive == true
            if (!alreadyRunning) {
                try {
                    initialSyncJob?.cancel()
                } catch (_: Exception) {
                }
                initialSyncRunning.set(false)
                if (!preferencesManager.initialSyncDone && !preferencesManager.initialSyncStarted) {
                    preferencesManager.setInitialSyncStartedSync(true)
                }
                if (!preferencesManager.initialSyncDone) {
                    if (initialSyncRunning.compareAndSet(false, true)) {
                        initialSyncJob = serviceScope.launch {
                            val self = coroutineContext[Job]
                            try {
                                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Starting initial data collection...")
                                sendInitialData()
                                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Initial data collection completed")
                            } catch (_: Exception) {
                            } finally {
                                if (initialSyncJob === self) initialSyncRunning.set(false)
                            }
                        }
                    }
                }
            }
        }
        startPeriodicLoops()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Monitoring loop started")
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "📸 Camera monitoring started")
        serviceScope.launch {
            try {
                messageQueue.tryRestorePersistent()
            } catch (_: Exception) {
            }
            val stuckRing = preferencesManager.ringPrevVolume
            val ringSavedAt = preferencesManager.ringSavedAt
            val ringingNow = try { ringBusy.get() || ringJob?.isActive == true } catch (_: Exception) { false }
            val ringAgeStart = System.currentTimeMillis() - ringSavedAt
            if (!ringingNow && stuckRing >= 0 && ringSavedAt > 0 && (ringAgeStart < 0L || ringAgeStart < 12 * 60 * 60_000L)) {
                try {
                    val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, stuckRing, 0)
                } catch (_: Exception) {
                }
                preferencesManager.clearRingStateSync()
            } else if (!ringingNow && (stuckRing >= 0 || ringSavedAt > 0)) {
                preferencesManager.clearRingStateSync()
            }
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
                    monitorBeatAt = android.os.SystemClock.elapsedRealtime()
                    monitorCycleMs = if (!cachedMonitoringPaused) syncIntervalMillis() else 15 * 60_000L
                    try { checkBatteryAlert() } catch (_: Exception) { }
                    try { maybeSendDigest() } catch (_: Exception) { }
                    try { maybeSendNightDigest() } catch (_: Exception) { }
                    try { checkBegadang() } catch (_: Exception) { }
                    try { checkCurfew() } catch (_: Exception) { }
                    try { checkQuietHealth() } catch (_: Exception) { }
                    if (!cachedMonitoringPaused) {
                        if (checkAndSendNewData()) idleDataCycles = 0 else idleDataCycles = (idleDataCycles + 1).coerceAtMost(100)
                        try {
                            CrashReporter.flushPending(this@MonitoringService)
                        } catch (_: Exception) {
                        }
                        try {
                            flushPendingAudio()
                        } catch (_: Exception) {
                        }
                        try {
                            flushPendingPhotos()
                        } catch (_: Exception) {
                        }
                        try {
                            flushPendingVideos()
                        } catch (_: Exception) {
                        }
                        val effSync = effectiveSyncMillis()
                        monitorCycleMs = effSync
                        chunkedDelay(effSync)
                    } else {
                        chunkedDelay(15 * 60_000L)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in monitoring loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        startCameraLoop()
        startPatrolLoop()
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
                    cameraBeatAt = android.os.SystemClock.elapsedRealtime()
                    val minutes = cachedCameraInterval.coerceIn(0, 60)
                    val paused = System.currentTimeMillis() < cachedPhotoPausedUntil
                    if (!cachedMonitoringPaused && !paused && !isNightPaused() && minutes > 0) {
                        if (idleDataCycles >= 3 && !isScreenOn()) {
                            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Camera idle-skip (quiet device, screen off)")
                        } else {
                            captureAndSendPhoto()
                        }
                        cameraCycleMs = minutes * 60_000L
                        chunkedDelay(minutes * 60_000L)
                    } else if (cachedMonitoringPaused) {
                        cameraCycleMs = 5 * 60_000L
                        chunkedDelay(5 * 60_000L)
                    } else {
                        val remaining = cachedPhotoPausedUntil - System.currentTimeMillis()
                        val idle = if (remaining > 0) remaining.coerceAtMost(30 * 60_000L) else 30 * 60_000L
                        cameraCycleMs = idle
                        chunkedDelay(idle)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in camera loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun restartPatrolLoop() {
        try {
            patrolJob?.cancel()
        } catch (_: Exception) {
        }
        startPatrolLoop()
    }

    private fun startPatrolLoop() {
        patrolJob = serviceScope.launch {
            while (isActive && patrolJob === coroutineContext[Job]) {
                try {
                    val minutes = cachedPatrolInterval.coerceIn(5, 180)
                    val photoPaused = System.currentTimeMillis() < cachedPhotoPausedUntil
                    if (!cachedMonitoringPaused && !photoPaused && !isNightPaused() && cachedPatrolEnabled) {
                        patrolRound()
                        chunkedDelay(minutes * 60_000L)
                    } else if (cachedMonitoringPaused) {
                        chunkedDelay(15 * 60_000L)
                    } else {
                        chunkedDelay(30 * 60_000L)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in patrol loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MonitoringService", "Fatal patrol error, loop survives")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun patrolRound() {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
        ensureForegroundTypes()
        val location = try {
            fetchLocation()
        } catch (_: Exception) {
            null
        }
        val batteryManager = try {
            getSystemService(BATTERY_SERVICE) as? BatteryManager
        } catch (_: Exception) {
            null
        }
        val level = try {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }
        val body = buildString {
            appendLine("\uD83D\uDEF0 <b>Patrol</b>")
            if (location != null) {
                try { preferencesManager.locFailCount = 0 } catch (_: Exception) { }
                try { preferencesManager.lastLocAt = System.currentTimeMillis() } catch (_: Exception) { }
                try { checkGeofence(location.latitude, location.longitude) } catch (_: Exception) { }
                val prev = lastSentLatLon()
                val dist = if (prev == null) -1f else distanceMeters(prev.first, prev.second, location.latitude, location.longitude)
                if (dist >= 0f && dist < 100f) {
                    appendLine("Lokasi: masih di titik terakhir (±${dist.toInt()} m)")
                } else {
                    rememberSentLatLon(location.latitude, location.longitude)
                    appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
                }
                appendLine("Akurasi: ±${location.accuracy.toInt()} m")
            } else {
                try { preferencesManager.locFailCount = (preferencesManager.locFailCount + 1).coerceAtMost(100) } catch (_: Exception) { }
                appendLine("Lokasi: tak didapat")
            }
            appendLine("Baterai: ${if (level in 0..100) "$level%" else "tak tahu"}")
        }
        sendToTelegram(body)
        try {
            captureAndSendPhoto()
        } catch (_: Exception) {
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

    private fun adoptMigratedChat(errorBody: String?, currentChatId: String): Boolean {
        val migrated = NetworkUtils.extractMigratedChatId(errorBody) ?: return false
        val stored = try { preferencesManager.chatId } catch (_: Exception) { currentChatId }
        if (migrated == stored) {
            try {
                preferencesManager.credentialError = ""
                preferencesManager.credentialErrorAt = 0L
                refreshCreds()
            } catch (_: Exception) {
            }
            return true
        }
        if (migrated == currentChatId) return false
        return try {
            preferencesManager.setChatIdSync(migrated)
            preferencesManager.credentialError = ""
            preferencesManager.credentialErrorAt = 0L
            refreshCreds()
            try {
                messageQueue.addMessage("\u267B\uFE0F Group upgraded to supergroup \u2014 chat ID updated automatically.", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
            android.util.Log.i("MonitoringService", "Chat migrated to supergroup, adopting new chat id")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isOwner(senderId: String): Boolean {
        if (senderId.isEmpty()) return false
        if (senderId == cachedChatId) return true
        return cachedOwnerId != 0L && senderId == cachedOwnerId.toString()
    }

    private val ownerLock = Any()
    private fun rememberOwner(id: Long): Boolean {
        if (id == 0L) return false
        synchronized(ownerLock) {
            if (cachedOwnerId != 0L) return false
            try {
                preferencesManager.setOwnerIdSync(id)
            } catch (_: Exception) {
                return false
            }
            val stored = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
            if (stored != id) return false
            cachedOwnerId = id
        }
        return true
    }

    private val pairHintAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pairAttemptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pingRate = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 64
        }
    }
    private val lastPingRestartAt = java.util.concurrent.atomic.AtomicLong(0L)

    private fun pingRestartDue(): Boolean {
        return android.os.SystemClock.elapsedRealtime() - lastPingRestartAt.get() >= PING_RESTART_COOLDOWN_MS
    }

    private fun pingRestartMark() {
        lastPingRestartAt.set(android.os.SystemClock.elapsedRealtime())
    }

    private suspend fun sendPairHint(senderId: String) {
        try {
            if (cachedOwnerId != 0L) return
            val now = android.os.SystemClock.elapsedRealtime()
            try {
                val last = pairHintAt[senderId] ?: 0L
                if (now >= last && now - last < 3_600_000L) return
                if (pairHintAt.size > 200) pairHintAt.clear()
                pairHintAt[senderId] = now
            } catch (_: Exception) {
            }
            val token = try {
                preferencesManager.botToken
            } catch (_: Exception) {
                ""
            }.ifEmpty { return }
            val url = "https://api.telegram.org/bot${token}/sendMessage"
            TelegramClient.api.sendMessage(url, TelegramMessage(chatId = senderId, text = "Pairing: send /start <chat ID> <pairing code> shown in Setup status.", parseMode = null))
        } catch (_: Exception) {
        }
    }

    private fun isFreshLocation(last: android.location.Location): Boolean {
        return try {
            android.os.SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos < 120_000_000_000L
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
            cachedPatrolEnabled = preferencesManager.patrolEnabled
            cachedPatrolInterval = preferencesManager.patrolInterval
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

    @Volatile
    private var idlePolls = 0
    @Volatile
    private var commandBackoffUntil = 0L
    @Volatile
    private var updateConflictNoticeAt = 0L
    @Volatile
    private var mediaBackoffUntil = 0L
    private val msgDropCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var msgDropJob: Job? = null
    private val msgDropLock = Any()

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
                if (android.os.SystemClock.elapsedRealtime() < commandBackoffUntil) {
                    try {
                        val remaining = commandBackoffUntil - android.os.SystemClock.elapsedRealtime()
                        kotlinx.coroutines.delay(remaining.coerceIn(1L, 60_000L))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
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
        if (android.os.SystemClock.elapsedRealtime() < commandBackoffUntil) return false
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return false
        val (botToken, chatId) = sendCreds()
        if (botToken.isEmpty() || chatId.isEmpty()) return false

        val offset = preferencesManager.lastUpdateId + 1
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=30&limit=50&allowed_updates=%5B%22message%22,%22edited_message%22,%22channel_post%22,%22edited_channel_post%22,%22callback_query%22%5D"

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
            if (freshToken.isNotEmpty() && (freshToken != botToken || freshChat != chatId)) {
                val retryUrl = "https://api.telegram.org/bot$freshToken/getUpdates?offset=$offset&timeout=30&limit=50&allowed_updates=%5B%22message%22,%22edited_message%22,%22channel_post%22,%22edited_channel_post%22,%22callback_query%22%5D"
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
            val selfConflict = try {
                android.os.SystemClock.elapsedRealtime() - NotificationForwarderService.lastWakePollAt < 60_000L
            } catch (_: Exception) {
                false
            }
            android.util.Log.w("MonitoringService", "getUpdates conflict: another consumer is polling, backing off")
            commandBackoffUntil = android.os.SystemClock.elapsedRealtime() + (if (selfConflict) 60_000L else 300_000L)
            idlePolls = 4
            try {
                val nowConflict = System.currentTimeMillis()
                if (!selfConflict && nowConflict - updateConflictNoticeAt > 3_600_000L) {
                    updateConflictNoticeAt = nowConflict
                    serviceScope.launch {
                        sendToTelegram("Bot polling conflict (409): another client is reading updates, commands delayed. Stop other polling clients.")
                    }
                }
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 429) {
            val retryAfter = try {
                NetworkUtils.parseRetryAfter(response.errorBody()?.string())
            } catch (_: Exception) {
                5L
            }
            android.util.Log.w("MonitoringService", "getUpdates rate limited, backing off ${retryAfter}s")
            commandBackoffUntil = android.os.SystemClock.elapsedRealtime() + retryAfter.coerceIn(1L, 3600L) * 1000L
            NetworkUtils.noteRateLimited(retryAfter)
            return false
        }
        if (response.code() == 400) {
            try {
                val body = response.errorBody()?.string()?.lowercase(java.util.Locale.ROOT).orEmpty()
                if (body.contains("offset")) {
                    android.util.Log.w("MonitoringService", "getUpdates offset rejected, resyncing from zero")
                    try {
                        preferencesManager.setLastUpdateIdSync(0L)
                    } catch (_: Exception) {
                    }
                    commandBackoffUntil = android.os.SystemClock.elapsedRealtime() + 30_000L
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
                while (handledUpdateIds.size > 500) {
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
                val callback = update.callbackQuery
                if (callback != null) {
                    handleCallbackQuery(callback)
                } else {
                    val message = update.message ?: update.channelPost
                    val msgChatId = message?.chat?.id?.toString().orEmpty()
                    val senderId = message?.from?.id?.toString().orEmpty()
                    val chatOk = msgChatId.isNotEmpty() && msgChatId == chatId
                    val rawText = (message?.text ?: message?.caption)?.trim().orEmpty()
                    val head = rawText.substringBefore(" ")
                    val command = head.substringBefore("@").lowercase(java.util.Locale.ROOT)
                    val arg = if (head.length < rawText.length) rawText.substring(head.length + 1).trim() else ""
                    val startHead = command
                    val startArg = arg
                    if (senderId.isNotEmpty() && msgChatId == senderId && senderId != chatId && startHead == "/start" && startArg.isNotEmpty()) {
                        if (cachedOwnerId == 0L) {
                            val nowPair = android.os.SystemClock.elapsedRealtime()
                            val lastPair = try { pairAttemptAt[senderId] ?: 0L } catch (_: Exception) { 0L }
                            if (nowPair - lastPair >= 30_000L) {
                                try {
                                    if (pairAttemptAt.size > 200) pairAttemptAt.clear()
                                    pairAttemptAt[senderId] = nowPair
                                } catch (_: Exception) {
                                }
                                val startTokens = startArg.split(CMD_SPLIT_REGEX).filter { it.isNotEmpty() }
                                val pairCode = try { preferencesManager.ensureOwnerPairCode() } catch (_: Exception) { "" }
                                if (startTokens.size >= 2 && startTokens[0] == chatId && pairCode.isNotEmpty() && startTokens[1] == pairCode) {
                                    val learned = rememberOwner(senderId.toLongOrNull() ?: 0L)
                                    if (learned) {
                                        try { pairAttemptAt.remove(senderId) } catch (_: Exception) { }
                                        registerBotCommands()
                                        sendToTelegram("Owner tertaut via /start.")
                                    }
                                } else if (startTokens.isNotEmpty() && startTokens[0] == chatId) {
                                    sendPairHint(senderId)
                                }
                            }
                        } else if (senderId != cachedOwnerId.toString()) {
                            sendToTelegram("\u26A0\uFE0F Pairing attempt ignored: owner already linked.")
                        }
                    }
                    val ownerOk = isOwner(senderId)
                    if (chatOk || ownerOk) {
                        if (command.startsWith("/")) {
                            val aliasInput = resolveAliasInput(command, arg)
                            val input = aliasInput ?: if (arg.isEmpty()) command else "$command $arg"
                            val effCommand = input.substringBefore(" ")
                            val wakeSeen = effCommand == "/ping" && try { preferencesManager.wakePingSeen(update.updateId) } catch (_: Exception) { false }
                            handleTelegramCommand(input, message?.date ?: 0, ownerOk, chatOk, wakeSeen, senderId, msgChatId.isNotEmpty() && msgChatId == senderId)
                            if (wakeSeen) {
                                try { preferencesManager.removeWakePingId(update.updateId) } catch (_: Exception) { }
                            }
                        } else {
                            val aliasInput = resolveAliasInput(command, arg)
                            val privateOwner = ownerOk && msgChatId.isNotEmpty() && msgChatId == senderId
                            if (aliasInput != null && privateOwner) {
                                handleTelegramCommand(aliasInput, message?.date ?: 0, ownerOk, chatOk, false, senderId, true)
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
                    android.util.Log.w("MonitoringService", "offset persist failed")
                }
            }
        }
        return true
    }

    private suspend fun handleCallbackQuery(query: com.redeye.parentalmonitor.network.TelegramCallbackQuery) {
        val sender = query.from?.id?.toString() ?: return
        val chatId = try { preferencesManager.chatId } catch (_: Exception) { "" }
        val originChat = try { query.message?.chat?.id?.toString().orEmpty() } catch (_: Exception) { "" }
        val ownerOk = isOwner(sender)
        if (sender != chatId && originChat != chatId && !ownerOk) {
            answerCallback(query.id)
            return
        }
        answerCallback(query.id)
        val dupCallback = synchronized(handledCallbackIds) {
            val seen = !handledCallbackIds.add(query.id)
            while (handledCallbackIds.size > 500) {
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
        if (dupCallback) return
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
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
            "shot" -> "/screenshot"
            else -> return
        }
        val originOk = originChat.isNotEmpty() && originChat == chatId
        val pressedAt = try { query.message?.date ?: 0L } catch (_: Exception) { 0L }
        if (pressedAt > 0L && (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS)) {
            val menuAge = System.currentTimeMillis() / 1000L - pressedAt
            if (menuAge > MUTATING_MAX_AGE_SEC) {
                sendToTelegram("\u231B Menu kedaluwarsa, ini yang baru.", mainMenu())
                return
            }
        } else if (pressedAt <= 0L && (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS)) {
            sendToTelegram("\u231B Menu kedaluwarsa, ini yang baru.", mainMenu())
            return
        }
        handleTelegramCommand(command, pressedAt, ownerOk, originOk, false, sender, originChat.isNotEmpty() && originChat == sender)
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
            val token = try {
                preferencesManager.botToken
            } catch (_: Exception) {
                ""
            }
            if (token.isEmpty()) return
            val url = "https://api.telegram.org/bot${token}/answerCallbackQuery"
            TelegramClient.api.answerCallbackQuery(url, mapOf("callback_query_id" to callbackId))
        } catch (_: Exception) {
        }
    }

    private fun botCommandList(): List<com.redeye.parentalmonitor.network.BotCommand> {
        return listOf(
            com.redeye.parentalmonitor.network.BotCommand("photo", "Ambil foto sekarang"),
            com.redeye.parentalmonitor.network.BotCommand("screenshot", "Ambil layar HP"),
            com.redeye.parentalmonitor.network.BotCommand("camera", "Ganti kamera: /camera depan|belakang"),
            com.redeye.parentalmonitor.network.BotCommand("location", "Kirim lokasi sekarang"),
            com.redeye.parentalmonitor.network.BotCommand("lastcalls", "5 panggilan terakhir"),
            com.redeye.parentalmonitor.network.BotCommand("lastsms", "5 SMS terakhir"),
            com.redeye.parentalmonitor.network.BotCommand("photointerval", "Interval foto 0-60 mnt"),
            com.redeye.parentalmonitor.network.BotCommand("pause", "Jeda foto N menit"),
            com.redeye.parentalmonitor.network.BotCommand("battery", "Lihat baterai"),
            com.redeye.parentalmonitor.network.BotCommand("status", "Status monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("ringkas", "Ringkasan status+izin+antrean"),
            com.redeye.parentalmonitor.network.BotCommand("perms", "Daftar izin aplikasi"),
            com.redeye.parentalmonitor.network.BotCommand("night", "Mode malam: /night on|off"),
            com.redeye.parentalmonitor.network.BotCommand("stop", "Jeda monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("resume", "Lanjutkan monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("notif", "Teruskan notif: /notif on|off|status"),
            com.redeye.parentalmonitor.network.BotCommand("syncinterval", "Interval sync 1-1440 mnt"),
            com.redeye.parentalmonitor.network.BotCommand("restart", "Restart loop monitoring"),
            com.redeye.parentalmonitor.network.BotCommand("flush", "Kirim antrean sekarang"),
            com.redeye.parentalmonitor.network.BotCommand("clearqueue", "Buang antrean"),
            com.redeye.parentalmonitor.network.BotCommand("lock", "Kunci layar HP"),
            com.redeye.parentalmonitor.network.BotCommand("ring", "Bunyikan HP"),
            com.redeye.parentalmonitor.network.BotCommand("ping", "Cek delay: /ping [camera|location]"),
            com.redeye.parentalmonitor.network.BotCommand("record", "Rekam audio 5-600 dtk"),
            com.redeye.parentalmonitor.network.BotCommand("recordvideo", "Rekam video 5-180 dtk"),
            com.redeye.parentalmonitor.network.BotCommand("sms", "Kirim SMS: /sms nomor pesan"),
            com.redeye.parentalmonitor.network.BotCommand("smsconfirm", "Konfirmasi SMS tertunda"),
            com.redeye.parentalmonitor.network.BotCommand("lastnotif", "Notifikasi terakhir"),
            com.redeye.parentalmonitor.network.BotCommand("version", "Versi app + HP"),
            com.redeye.parentalmonitor.network.BotCommand("uptime", "Lama service jalan"),
            com.redeye.parentalmonitor.network.BotCommand("contacts", "Cari kontak: /contacts nama"),
            com.redeye.parentalmonitor.network.BotCommand("apps", "Daftar aplikasi"),
            com.redeye.parentalmonitor.network.BotCommand("storage", "Sisa penyimpanan"),
            com.redeye.parentalmonitor.network.BotCommand("history", "Call+SMS per nomor"),
            com.redeye.parentalmonitor.network.BotCommand("log", "Log error terakhir"),
            com.redeye.parentalmonitor.network.BotCommand("patrol", "Patroli foto + lokasi otomatis"),
            com.redeye.parentalmonitor.network.BotCommand("patrolinterval", "Interval patroli 5-180 mnt"),
            com.redeye.parentalmonitor.network.BotCommand("watch", "Awasi kata: /watch a,b|status|off"),
            com.redeye.parentalmonitor.network.BotCommand("home", "Geofence rumah: /home lat,lon,rad|status|off"),
            com.redeye.parentalmonitor.network.BotCommand("school", "Geofence sekolah: /school lat,lon,rad|status|off"),
            com.redeye.parentalmonitor.network.BotCommand("digest", "Kirim ringkasan harian"),
            com.redeye.parentalmonitor.network.BotCommand("darurat", "Lokasi+foto+baterai sekaligus"),
            com.redeye.parentalmonitor.network.BotCommand("jampulang", "Jam malam: /jampulang 21:30|off"),
            com.redeye.parentalmonitor.network.BotCommand("minggu", "Laporan 7 hari"),
            com.redeye.parentalmonitor.network.BotCommand("watchnomor", "Awasi nomor: /watchnomor 0812,.."),
            com.redeye.parentalmonitor.network.BotCommand("jejak", "Jejak lokasi hari ini"),
            com.redeye.parentalmonitor.network.BotCommand("waspada", "Skor kewaspadaan 0-100"),
            com.redeye.parentalmonitor.network.BotCommand("hilang", "HP hilang: bunyi+lokasi+foto"),
            com.redeye.parentalmonitor.network.BotCommand("jemput", "Kabari saat dekat rumah"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Daftar semua perintah")
        )
    }

    private fun publicCommandList(): List<com.redeye.parentalmonitor.network.BotCommand> {
        return listOf(
            com.redeye.parentalmonitor.network.BotCommand("ping", "Cek delay"),
            com.redeye.parentalmonitor.network.BotCommand("start", "Mulai / tautkan HP ini"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Daftar perintah")
        )
    }

    private suspend fun registerBotCommands() {
        try {
            val botToken = preferencesManager.botToken
            if (botToken.isEmpty()) return
            val ownerId = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
            val tokenHash = sha256Hex(botToken + ":" + ownerId + ":" + com.redeye.parentalmonitor.BuildConfig.VERSION_CODE)
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
                    com.redeye.parentalmonitor.network.BotCommandScope("chat", ownerId)
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
                listOf(button("\uD83D\uDD0B Battery", "battery"), button("\uD83D\uDCCA Status", "status")),
                listOf(button("\uD83D\uDDA5 Screenshot", "shot"))
            )
        )
    }

    private suspend fun handleTelegramCommand(raw: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, wakeSeen: Boolean = false, senderId: String = "", privateChat: Boolean = false) {
        val nowSec = System.currentTimeMillis() / 1000L
        if (sentAtSec > 0 && nowSec - sentAtSec > COMMAND_MAX_AGE_SEC) {
            serviceScope.launch {
                sendToTelegram("\u23F3\uFE0F Command kedaluwarsa, kirim lagi.")
            }
            return
        } else if (sentAtSec > 0 && sentAtSec - nowSec > COMMAND_MAX_AGE_SEC) {
            serviceScope.launch {
                sendToTelegram("\u23F3\uFE0F Command timestamp is in the future. Check the device clock, then send again.")
            }
            return
        }
        val parts = raw.split(CMD_SPLIT_REGEX, limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        try {
            handleTelegramCommandInner(command, arg, sentAtSec, senderOk, chatOk, wakeSeen, senderId, privateChat)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val redacted = redactToken(e.message)
            android.util.Log.e("MonitoringService", "Command failed: $command ($redacted)")
            try {
                val detail = redacted.ifEmpty { "unknown error" }
                sendToTelegram("\u26A0\uFE0F Command $command failed ($detail). Please try again or send /help.")
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleTelegramCommandInner(command: String, arg: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, wakeSeen: Boolean = false, senderId: String = "", privateChat: Boolean = false) {
        if (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS) {
            val ageSec = if (sentAtSec <= 0L) Long.MAX_VALUE else System.currentTimeMillis() / 1000L - sentAtSec
            if (ageSec > MUTATING_MAX_AGE_SEC) {
                sendToTelegram("\u23F3\uFE0F Command $command kedaluwarsa, kirim lagi.")
                return
            }
        }
        if (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS) {
            if (!senderOk) {
                if (privateChat) sendToTelegram("\u26D4 Hanya owner yang bisa pakai $command.")
                return
            }
        } else if (!senderOk && !chatOk) {
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
                            sendToTelegram("Cara pakai: /photo [front|back]")
                            return
                        }
                    }
                }
                if (preferencesManager.monitoringPaused) {
                    sendToTelegram("⏸️ Monitoring dijeda. Kirim /resume dulu.")
                } else {
                    sendToTelegram("📸 Ambil foto sekarang…")
                    captureAndSendPhoto(reportResult = true)
                }
            }
            "/screenshot" -> {
                if (preferencesManager.monitoringPaused) {
                    sendToTelegram("⏸️ Monitoring dijeda. Kirim /resume dulu.")
                } else {
                    sendToTelegram("\uD83D\uDDA5 Ambil layar…")
                    captureAndSendScreenshot(reportResult = true)
                }
            }
            "/status" -> {
                val lastSync = preferencesManager.lastSyncTime
                val lastSyncStr = if (lastSync > 0) formatDate(lastSync) else "belum pernah"
                val photoState = if (isPhotoPaused()) {
                    val leftMin = ((photoPausedElapsed() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                    "jeda ($leftMin mnt lagi)"
                } else if (preferencesManager.cameraInterval <= 0) {
                    "manual saja (/photo)"
                } else {
                    "tiap ${preferencesManager.cameraInterval} mnt"
                }
                sendToTelegram(
                    buildString {
                        appendLine("📊 <b>Status</b>")
                        appendLine("Monitoring: ${if (preferencesManager.monitoringPaused) "JEDA" else "JALAN"}")
                        appendLine("Sync terakhir: $lastSyncStr")
                        appendLine("Antrean: ${messageQueue.getQueueSize()}")
                        appendLine("Interval data: tiap ${preferencesManager.syncInterval} mnt" + if (idleDataCycles > 0) " (sepi adaptif, kini tiap ${effectiveSyncMillis() / 60_000L} mnt)" else "")
                        appendLine("Foto: $photoState")
                        appendLine("Patroli: ${if (preferencesManager.patrolEnabled) "JALAN tiap ${preferencesManager.patrolInterval} mnt" else "mati"}")
                        appendLine("Kamera: ${preferencesManager.cameraFacing}")
                        appendLine("Malam: " + if (preferencesManager.nightMode) "JALAN 23–05" + if (isNightPaused()) " (sunyi sekarang)" else "" else "mati")
                        appendLine("Watch: " + (watchList().takeIf { it.isNotEmpty() }?.joinToString(",") ?: "mati"))
                        appendLine("Rumah: " + fenceStatusText(true) + " · Sekolah: " + fenceStatusText(false))
                        appendLine("Izin kamera: ${if (hasCameraPermission()) "ada" else "HILANG"}")
                        appendLine("Izin lokasi: ${if (hasLocationPermission()) "ada" else "HILANG"}")
                        appendLine("Lokasi latar: ${if (hasBackgroundLocation()) "ada" else "HILANG"}")
                        appendLine("Notifikasi: ${if (isNotifForwarding()) "teruskan" else "mati"}")
                        appendLine("Foto terakhir: ${if (preferencesManager.lastPhotoTime > 0) formatDate(preferencesManager.lastPhotoTime) else "belum pernah"}")
                        appendLine("Jam malam: ${try { preferencesManager.curfewTime.takeIf { it.isNotEmpty() } ?: "mati" } catch (_: Exception) { "?" }}")
                    },
                    mainMenu()
                )
            }
            "/perms" -> {
                val notifOn = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
                sendToTelegram(permInventoryText(permissionRawStates(), notifOn))
            }
            "/ringkas" -> {
                val level = batteryLevel()
                val notifOn = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
                val snap = try { preferencesManager.permSnapshot } catch (_: Exception) { -1 }
                val missing = try { missingPermLabels(permissionMask(permissionRawStates(), notifOn, snap)) } catch (_: Exception) { ArrayList() }
                sendToTelegram(buildString {
                    appendLine("🧾 <b>Ringkas</b>")
                    appendLine("Monitoring: ${if (preferencesManager.monitoringPaused) "PAUSED" else "ON"}")
                    appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
                    appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
                    appendLine("Izin: " + if (missing.isEmpty()) "semua OK ✅" else "hilang: " + missing.joinToString(", "))
                    appendLine("Sync: tiap ${preferencesManager.syncInterval} mnt" + if (isNightPaused()) " · malam 🌙" else "")
                },
                    mainMenu()
                )
            }
            "/lastcalls" -> {
                val calls = callLogRepository.getAllCalls(5)
                if (calls.isEmpty()) {
                    sendToTelegram("📞 Tak ada riwayat panggilan.")
                } else {
                    sendToTelegram(formatCallMessage(calls))
                }
            }
            "/lastsms" -> {
                val sms = smsRepository.getRecentSms(5)
                if (sms.isEmpty()) {
                    sendToTelegram("💬 Tak ada SMS.")
                } else {
                    sendToTelegram(formatSmsMessage(sms))
                }
            }
            "/photointerval" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(0, 60)
                if (minutes == null) {
                    sendToTelegram("Cara pakai: /photointerval \u003c0-60\u003e (0 = manual saja)")
                } else if (minutes == 0) {
                    preferencesManager.cameraInterval = 0
                    sendToTelegram("📸 Foto otomatis MATI. Pakai /photo untuk manual.")
                } else {
                    preferencesManager.cameraInterval = minutes
                    sendToTelegram("📸 Interval foto: $minutes mnt.")
                }
            }
            "/patrol" -> {
                when (arg.lowercase(java.util.Locale.ROOT)) {
                    "on" -> {
                        preferencesManager.patrolEnabled = true
                        sendToTelegram("\uD83D\uDEF0 Patrol ON \u2014 photo + location every ${preferencesManager.patrolInterval} min.")
                    }
                    "off" -> {
                        preferencesManager.patrolEnabled = false
                        sendToTelegram("\uD83D\uDEF0 Patrol OFF.")
                    }
                    else -> {
                        val state = if (preferencesManager.patrolEnabled) "ON every ${preferencesManager.patrolInterval} min" else "OFF"
                        sendToTelegram("Cara pakai: /patrol \u003con|off\u003e (now $state)")
                    }
                }
            }
            "/patrolinterval" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(5, 180)
                if (minutes == null) {
                    sendToTelegram("Cara pakai: /patrolinterval \u003c5-180\u003e")
                } else {
                    preferencesManager.patrolInterval = minutes
                    sendToTelegram("\uD83D\uDEF0 Patrol interval set to $minutes min.")
                }
            }
            "/night" -> {
                when (arg.lowercase(java.util.Locale.ROOT)) {
                    "on" -> {
                        preferencesManager.nightMode = true
                        try { restartCameraLoop() } catch (_: Exception) { }
                        try { restartPatrolLoop() } catch (_: Exception) { }
                        sendToTelegram("🌙 Night mode ON — auto photos/location pause 23:00–05:00.")
                    }
                    "off" -> {
                        preferencesManager.nightMode = false
                        try { restartCameraLoop() } catch (_: Exception) { }
                        try { restartPatrolLoop() } catch (_: Exception) { }
                        sendToTelegram("🌙 Night mode OFF.")
                    }
                    else -> {
                        val state = if (preferencesManager.nightMode) "ON 23:00–05:00" else "OFF"
                        sendToTelegram("Cara pakai: /night \u003con|off\u003e (now $state)")
                    }
                }
            }
            "/pause" -> {
                val minutes = arg.toIntOrNull()?.coerceIn(1, 480)
                if (minutes == null) {
                    sendToTelegram("Cara pakai: /pause \u003cminutes\u003e (1-480)")
                } else {
                    preferencesManager.photoPausedUntil = System.currentTimeMillis() + minutes * 60_000L
                    sendToTelegram("⏸️ Foto dijeda $minutes mnt.")
                }
            }
            "/battery" -> {
                val level = try {
                    (getSystemService(BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                } catch (_: Exception) {
                    -1
                }
                val levelText = if (level in 0..100) "$level%" else "tak tahu"
                sendToTelegram("🔋 <b>Baterai</b>\nLevel: $levelText")
            }
            "/stop" -> {
                preferencesManager.monitoringPaused = true
                sendToTelegram("⏸️ Monitoring dijeda (panggilan, SMS, foto, dan teruskan notifikasi). Kirim /resume untuk lanjut.")
            }
            "/resume" -> {
                preferencesManager.monitoringPaused = false
                preferencesManager.photoPausedUntil = 0L
                sendToTelegram("▶️ Monitoring resumed.")
            }
            "/location" -> {
                if (!hasLocationPermission()) {
                    sendToTelegram("⚠️ Izin lokasi hilang. Buka Setup dan berikan izin Lokasi.")
                } else {
                    sendToTelegram("📍 Mencari lokasi…")
                    val location = fetchLocation()
                    if (location == null) {
                        sendToTelegram("⚠️ Lokasi tak didapat. Nyalakan Lokasi/GPS.")
                    } else {
                        val prev = lastSentLatLon()
                        val dist = if (prev == null) -1f else distanceMeters(prev.first, prev.second, location.latitude, location.longitude)
                        rememberSentLatLon(location.latitude, location.longitude)
                        val near = if (dist >= 0f) "\nJarak dari laporan terakhir: ${dist.toInt()} m" else ""
                        sendToTelegram("📍 <b>Lokasi</b>\nhttps://maps.google.com/?q=${location.latitude},${location.longitude}\nAkurasi: ±${location.accuracy.toInt()} m$near")
                    }
                }
            }
            "/camera" -> {
                when (arg.lowercase(java.util.Locale.ROOT)) {
                    "belakang", "back" -> {
                        preferencesManager.cameraFacing = "back"
                        sendToTelegram("📸 Kamera disetel belakang.")
                    }
                    "depan", "front" -> {
                        preferencesManager.cameraFacing = "front"
                        sendToTelegram("📸 Kamera disetel depan.")
                    }
                    else -> {
                        sendToTelegram("Cara pakai: /camera \u003cdepan|belakang\u003e (kini: ${preferencesManager.cameraFacing})")
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
                        sendToTelegram("Cara pakai: /notif <on|off|status>")
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
                    if (sendFitted(crash)) {
                        CrashReporter.clearPending(this)
                    }
                } else {
                    sendToTelegram("\uD83E\uDDFE Belum ada crash.")
                }
                sendToTelegram(
                    buildString {
                        appendLine("\uD83E\uDDFE <b>Log error</b>")
                        val credErr = try {
                            preferencesManager.credentialError
                        } catch (_: Exception) {
                            ""
                        }
                        appendLine("Auth: " + if (credErr.isEmpty()) "OK" else "FAILED ($credErr)")
                        appendLine("Antrean: ${messageQueue.getQueueSize()}")
                        appendLine("Penyimpanan: " + if (preferencesManager.isStorageEncrypted) "terenkripsi" else "volatil")
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
                    sendToTelegram("Cara pakai: /syncinterval \u003c1-1440\u003e (minutes)")
                } else {
                    preferencesManager.syncInterval = minutes
                    sendToTelegram("\u23F1\uFE0F Sync interval set to $minutes min.")
                    try {
                        restartAllLoops()
                    } catch (_: Exception) {
                    }
                }
            }
            "/restart" -> {
                if (restartAllLoops()) {
                    sendToTelegram("\u267B\uFE0F Loops restarted.")
                } else {
                    sendToTelegram("\u23F3\uFE0F Restart throttled, try again shortly.")
                }
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
                    try {
                        flushPendingVideos()
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
                val overflowTaken = try { messageQueue.takeOverflowDrops() } catch (_: Exception) { 0L }
                val expiredTaken = try { messageQueue.takeExpiredDrops() } catch (_: Exception) { 0L }
                try {
                    messageQueue.clearQueue()
                } catch (_: Exception) {
                }
                val dropExtra = buildString {
                    if (overflowTaken > 0L) append(" Plus $overflowTaken overflow drop(s) discarded.")
                    if (expiredTaken > 0L) append(" Plus $expiredTaken expired drop(s) discarded.")
                }
                sendToTelegram("\uD83D\uDDD1\uFE0F Queue cleared ($queued dropped). Note: SMS/call updates already marked as seen will not resend.$dropExtra", null, true)
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
                    sendToTelegram("Cara pakai: /ring [5-60] (seconds)")
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
                if (!senderOk) return
                val pingKey = senderId.ifEmpty { "unknown" }
                val nowPing = android.os.SystemClock.elapsedRealtime()
                val lastPing = try { synchronized(pingRate) { pingRate[pingKey] } } catch (_: Exception) { null }
                if (lastPing != null && nowPing - lastPing < PING_RATE_WINDOW_MS) return
                try { synchronized(pingRate) { pingRate[pingKey] = nowPing } } catch (_: Exception) { }
                when (arg.substringBefore(" ").lowercase(java.util.Locale.ROOT)) {
                    "camera", "photo" -> {
                        if (senderOk && !wakeSeen && cameraJob?.isActive != true && pingRestartDue()) {
                            restartCameraLoop()
                            pingRestartMark()
                        }
                        handleTelegramCommand("/photo", sentAtSec, senderOk, chatOk, wakeSeen, senderId, privateChat)
                    }
                    "location", "loc", "gps" -> {
                        handleTelegramCommand("/location", sentAtSec, senderOk, chatOk, wakeSeen, senderId, privateChat)
                    }
                    "" -> {
                        val initialStuck = initialSyncRunning.get() && initialSyncJob?.isActive != true
                        val loopsOk = monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true && patrolJob?.isActive == true && !initialStuck
                        var restarted = false
                        if (!loopsOk && senderOk && !wakeSeen && pingRestartDue()) {
                            restarted = restartAllLoops()
                            if (restarted) pingRestartMark()
                        }
                        val tail = if (restarted) " ⏰ Loops restarted." else ""
                        if (sentAtSec > 0) {
                            val lag = System.currentTimeMillis() / 1000L - sentAtSec
                            sendToTelegram("\uD83C\uDFD3 Pong! Delay ${lag.coerceAtLeast(0)} s." + tail)
                        } else {
                            sendToTelegram("\uD83C\uDFD3 Pong! " + TimeFmt.full(System.currentTimeMillis()) + tail)
                        }
                    }
                    else -> {
                        sendToTelegram("Cara pakai: /ping [camera|location]")
                    }
                }
            }
            "/record" -> {
                val seconds = arg.toIntOrNull()
                if (seconds == null || seconds !in 5..600) {
                    sendToTelegram("Cara pakai: /record \u003c5-600\u003e (seconds)")
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
            "/recordvideo" -> {
                val seconds = arg.toIntOrNull()
                if (seconds == null || seconds !in 5..180) {
                    sendToTelegram("Cara pakai: /recordvideo \u003c5-180\u003e (seconds)")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F Microphone permission missing. Open Setup and grant Microphone permission.")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F Camera permission missing. Open Setup and grant Camera permission.")
                } else if (ringBusy.get()) {
                    sendToTelegram("\u23F1\uFE0F Already ringing, please wait.")
                } else if (!recordBusy.compareAndSet(false, true)) {
                    sendToTelegram("\u23F1\uFE0F Already recording, please wait.")
                } else {
                    sendToTelegram("\uD83C\uDFA5 Recording video $seconds s\u2026")
                    recordJob = serviceScope.launch {
                        try {
                            recordAndSendVideo(seconds)
                        } finally {
                            recordBusy.set(false)
                        }
                    }
                }
            }
            "/sms" -> {
                val number = arg.substringBefore(" ").trim()
                val smsText = arg.substringAfter(" ", "").trim()
                var badChar = false
                var plusCount = 0
                val digits = StringBuilder(number.length + 1)
                if (number.startsWith("+")) digits.append('+')
                for (c in number) {
                    when {
                        c.isDigit() -> digits.append(c)
                        c == '+' -> { plusCount++; if (!number.startsWith("+") || plusCount > 1) badChar = true }
                        c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                        else -> badChar = true
                    }
                }
                val normalized = digits.toString()
                if (number.isEmpty() || smsText.isEmpty()) {
                    sendToTelegram("Cara pakai: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (badChar || plusCount > 1 || (plusCount == 1 && !number.startsWith("+")) || (plusCount == 1 && number.startsWith("+") && number.indexOf('+', 1) >= 0)) {
                    sendToTelegram("\u26A0\uFE0F Invalid number. Cara pakai: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (!normalized.matches(SMS_NUMBER_REGEX)) {
                    sendToTelegram("\u26A0\uFE0F Invalid number. Cara pakai: /sms \u003cnumber\u003e \u003cmessage\u003e")
                } else if (isPremiumSmsNumber(normalized)) {
                    sendToTelegram("\uD83D\uDEAB Nomor premium tidak boleh dipakai /sms.")
                } else if (isEmergencySmsNumber(normalized)) {
                    sendToTelegram("\uD83D\uDEAB Nomor darurat tidak boleh dipakai /sms. Hubungi langsung dari HP.")
                } else if (smsText.length > 500) {
                    sendToTelegram("\u26A0\uFE0F Message too long (max 500 characters).")
                } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sendToTelegram("\u26A0\uFE0F SMS permission missing. Open Setup and grant SMS permission.")
                } else {
                    val stagedNumber = try { preferencesManager.pendingSmsNumber } catch (_: Exception) { "" }
                    val stagedAt = try { preferencesManager.pendingSmsAt } catch (_: Exception) { 0L }
                    val stagedBy = try { preferencesManager.pendingSmsOwner } catch (_: Exception) { "" }
                    val nowSms = System.currentTimeMillis()
                    val lastSentAt = try { preferencesManager.lastSmsSendAt } catch (_: Exception) { 0L }
                    if (lastSentAt <= nowSms && nowSms - lastSentAt < 60_000L) {
                        sendToTelegram("\u26A0\uFE0F Please wait a moment before sending another SMS.")
                    } else if (stagedNumber.isNotEmpty() && stagedAt > 0L && stagedAt <= nowSms && nowSms - stagedAt <= 300_000L && stagedBy.isNotEmpty() && stagedBy != senderId) {
                        sendToTelegram("⛔ Only the requester can replace this pending SMS. Reply /smsconfirm to confirm it first, or wait for it to expire.")
                    } else {
                        preferencesManager.writeSmsPendingSync(normalized, smsText, System.currentTimeMillis(), senderId)
                        sendToTelegram("\uD83D\uDCE9 SMS to <code>$normalized</code> ready to send. Reply /smsconfirm to confirm (valid for 5 minutes).")
                    }
                }
            }
            "/smsconfirm" -> {
                val number = preferencesManager.pendingSmsNumber
                val smsText = preferencesManager.pendingSmsText
                val stagedAt = preferencesManager.pendingSmsAt
                val stagedBy = try { preferencesManager.pendingSmsOwner } catch (_: Exception) { "" }
                val nowConfirm = System.currentTimeMillis()
                if (number.isEmpty() || smsText.isEmpty() || stagedAt <= 0L || stagedAt > nowConfirm || nowConfirm - stagedAt > 300_000L) {
                    preferencesManager.writeSmsPendingSync("", "", 0L, "")
                    sendToTelegram("\u23F1\uFE0F No pending SMS. Send /sms \u003cnumber\u003e \u003cmessage\u003e first.")
                } else if (stagedBy.isNotEmpty() && stagedBy != senderId) {
                    sendToTelegram("\u26D4 Only the requester can confirm this SMS.")
                } else if (preferencesManager.lastSmsSendAt <= nowConfirm && nowConfirm - preferencesManager.lastSmsSendAt < 60_000L) {
                    sendToTelegram("\u26A0\uFE0F Please wait a moment before sending another SMS.")
                } else if (!smsBusy.compareAndSet(false, true)) {
                    sendToTelegram("\u23F1\uFE0F SMS still sending, please wait.")
                } else {
                    sendToTelegram("\uD83D\uDCE9 Sending SMS\u2026")
                    smsJob = serviceScope.launch {
                        try {
                            sendSmsPending(number, smsText, stagedAt)
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
                                appendLine("\u2022 " + Html.escape(item.app) + ": " + Html.escape(item.title).let { it.take(safeCut(it, 80)) } + " \u2014 " + Html.escape(item.text).let { it.take(safeCut(it, 120)) })
                            }
                        }
                    )
                }
            }
            "/version" -> {
                sendToTelegram(
                    buildString {
                        appendLine("\u2139\uFE0F <b>Versi</b>")
                        appendLine("Aplikasi: " + com.redeye.parentalmonitor.BuildConfig.VERSION_NAME + " (" + com.redeye.parentalmonitor.BuildConfig.VERSION_CODE + ")")
                        appendLine("Android: " + Html.escape(Build.VERSION.SDK_INT.toString()) + " (" + Html.escape(Build.VERSION.RELEASE) + ")")
                        appendLine("HP: " + Html.escape(Build.MANUFACTURER) + " " + Html.escape(Build.MODEL))
                    }
                )
            }
            "/uptime" -> {
                val started = serviceStartAt
                if (started <= 0) {
                    sendToTelegram("\u23F1\uFE0F Uptime: tak tahu.")
                } else {
                    val minutes = ((System.currentTimeMillis() - started) / 60_000L).coerceAtLeast(0L)
                    val hours = minutes / 60
                    val days = hours / 24
                    val span = when {
                        days > 0 -> "$days d ${hours % 24} h"
                        hours > 0 -> "$hours h ${minutes % 60} m"
                        else -> "$minutes m"
                    }
                    sendToTelegram("\u23F1\uFE0F <b>Uptime</b>\nJalan: $span\nSejak: " + formatDate(started))
                }
            }
            "/contacts" -> {
                if (arg.isEmpty()) {
                    sendToTelegram("Cara pakai: /contacts \u003cname\u003e")
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
                if (arg.isNotEmpty() && arg.toIntOrNull() == null) {
                    sendToTelegram("Cara pakai: /apps [5-50] (default 30)")
                    return
                }
                val limit = arg.toIntOrNull()?.coerceIn(5, 50) ?: 30
                val apps = listLaunchableApps(limit)
                if (apps.isEmpty()) {
                    sendToTelegram("\uD83D\uDCE6 Tak ada aplikasi.")
                } else {
                    sendToTelegram(
                        buildString {
                            appendLine("\uD83D\uDCE6 <b>Aplikasi (${apps.size})</b>")
                            for (label in apps) {
                                appendLine("\u2022 " + Html.escape(label))
                            }
                        }
                    )
                }
            }
            "/storage" -> {
                val stats = withContext(Dispatchers.IO) { cacheStats() }
                sendToTelegram(
                    buildString {
                        appendLine("\uD83D\uDCBE <b>Penyimpanan</b>")
                        appendLine("Cache: " + formatBytes(stats.bytes) + " (" + stats.photos + " foto, " + stats.audios + " audio, " + stats.videos + " video)")
                        appendLine("Antrean: ${messageQueue.getQueueSize()}")
                    }
                )
            }
            "/history" -> {
                val digits = arg.filter { it.isDigit() }
                if (digits.length < 7) {
                    sendToTelegram("Cara pakai: /history \u003cnumber\u003e (min 7 digits)")
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
                        sendToTelegram("\uD83D\uDD0E Tak ada riwayat untuk $digits (search covers roughly the last 12 months).")
                    } else {
                        if (calls.isNotEmpty()) sendToTelegram(formatCallMessage(calls))
                        if (sms.isNotEmpty()) sendToTelegram(formatSmsMessage(sms))
                    }
                }
            }
            "/watch" -> {
                val sub = arg.lowercase(java.util.Locale.ROOT).trim()
                if (sub.isEmpty() || sub == "status") {
                    if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                    val cur = watchList()
                    sendToTelegram(if (cur.isEmpty()) "👁️ Watch: off (contoh: /watch judi,pinjol,bully)" else "👁️ Watch: " + cur.joinToString(", "))
                } else if (sub == "off" || sub == "clear") {
                    preferencesManager.watchKeywords = ""
                    sendToTelegram("👁️ Watch dimatikan.")
                } else {
                    val words = sub.split(",", " ", ";").map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.length >= 3 }.distinct().take(10)
                    val dropped = sub.split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() && it.length < 3 }.distinct()
                    if (words.isEmpty()) {
                        sendToTelegram("Cara pakai: /watch \u003ckata1,kata2\u003e (min 3 huruf, maks 10) atau /watch off")
                    } else {
                        preferencesManager.watchKeywords = words.joinToString(",")
                        sendToTelegram("👁️ Watch aktif: " + words.joinToString(", ") + if (dropped.isNotEmpty()) "\nDiabaikan (<3 huruf): " + dropped.joinToString(", ") else "")
                    }
                }
            }
            "/home", "/school" -> {
                val name = if (command == "/home") "Rumah" else "Sekolah"
                val sub = arg.trim()
                if (sub.equals("off", ignoreCase = true)) {
                    if (command == "/home") {
                        preferencesManager.homeRadiusM = 0
                        preferencesManager.homeState = 0
                    } else {
                        preferencesManager.schoolRadiusM = 0
                        preferencesManager.schoolState = 0
                    }
                    sendToTelegram("📍 Geofence $name dimatikan.")
                } else if (sub.isEmpty() || sub.equals("status", ignoreCase = true)) {
                    if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                    sendToTelegram("📍 $name: " + fenceStatusText(command == "/home"))
                } else {
                    val nums = sub.replace(",", " ").split(CMD_SPLIT_REGEX).mapNotNull { it.toDoubleOrNull() }
                    if (nums.size < 2) {
                        sendToTelegram("Cara pakai: $command \u003clat,lon,radius\u003e (radius 50-5000 m, default 200)")
                    } else {
                        val lat = nums[0]
                        val lon = nums[1]
                        val rad = nums.getOrNull(2)?.toInt() ?: 200
                        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || rad !in 50..5000) {
                            sendToTelegram("Koordinat/radius tak valid (lat -90..90, lon -180..180, radius 50-5000).")
                        } else {
                            if (command == "/home") {
                                preferencesManager.homeLatE6 = (lat * 1_000_000).toLong()
                                preferencesManager.homeLonE6 = (lon * 1_000_000).toLong()
                                preferencesManager.homeRadiusM = rad
                                preferencesManager.homeState = 0
                            } else {
                                preferencesManager.schoolLatE6 = (lat * 1_000_000).toLong()
                                preferencesManager.schoolLonE6 = (lon * 1_000_000).toLong()
                                preferencesManager.schoolRadiusM = rad
                                preferencesManager.schoolState = 0
                            }
                            sendToTelegram("📍 Geofence $name aktif: $lat,$lon ±${rad} m.")
                        }
                    }
                }
            }
            "/digest" -> {
                sendDailyDigest(manual = true)
                try { preferencesManager.nightDigestDate = todayKey() } catch (_: Exception) { }
            }
            "/darurat" -> {
                val level = batteryLevel()
                sendToTelegram("🚨 <b>Darurat</b>: ambil lokasi + foto…")
                val loc = try { fetchLocation() } catch (_: Exception) { null }
                if (loc != null) {
                    try { rememberSentLatLon(loc.latitude, loc.longitude) } catch (_: Exception) { }
                    sendToTelegram(buildString {
                        appendLine("📍 <b>Lokasi darurat</b>")
                        appendLine("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                        appendLine("Akurasi: ±${loc.accuracy.toInt()} m")
                        appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
                        appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
                    })
                } else {
                    try { preferencesManager.locFailCount = (preferencesManager.locFailCount + 1).coerceAtMost(100) } catch (_: Exception) { }
                    sendToTelegram("📍 Lokasi tak didapat (GPS mati?). Baterai: ${if (level in 0..100) "$level%" else "?"}")
                }
                try { captureAndSendPhoto(reportResult = true) } catch (_: Exception) { }
            }
            "/jampulang" -> {
                val sub = arg.trim()
                if (sub.isEmpty() || sub.equals("status", ignoreCase = true)) {
                    if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                    val cur = try { preferencesManager.curfewTime } catch (_: Exception) { "" }
                    val school = fenceStatusText(false)
                    sendToTelegram("🌙 Jam malam: " + (cur.ifEmpty { "mati" }) + "\n🏫 Cek sekolah 08–16 (Senin–Jumat): " + school)
                } else if (sub.equals("off", ignoreCase = true)) {
                    preferencesManager.curfewTime = ""
                    sendToTelegram("🌙 Jam malam dimatikan.")
                } else if (CURFEW_REGEX.matches(sub)) {
                    preferencesManager.curfewTime = sub
                    sendToTelegram("🌙 Jam malam disetel $sub. Bila di luar rumah lewat jam itu, owner dikabari (maks 1x/hari).")
                } else {
                    sendToTelegram("Cara pakai: /jampulang \u003cHH:MM\u003e (contoh 21:30) atau /jampulang off")
                }
            }
            "/minggu" -> {
                sendWeeklyDigest()
            }
            "/watchnomor" -> {
                val sub = arg.lowercase(java.util.Locale.ROOT).trim()
                if (sub.isEmpty() || sub == "status") {
                    if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                    val cur = watchNumbers()
                    sendToTelegram(if (cur.isEmpty()) "📵 Watch nomor: off (contoh: /watchnomor 0812,62813)" else "📵 Watch nomor: " + cur.joinToString(", "))
                } else if (sub == "off" || sub == "clear") {
                    preferencesManager.watchNumbers = ""
                    sendToTelegram("📵 Watch nomor dimatikan.")
                } else {
                    val nums = sub.split(",", " ", ";").map { it.filter { c -> c.isDigit() } }.filter { it.length >= 7 }.distinct().take(10)
                    if (nums.isEmpty()) {
                        sendToTelegram("Cara pakai: /watchnomor \u003cnomor1,nomor2\u003e (min 7 digit, maks 10) atau /watchnomor off")
                    } else {
                        preferencesManager.watchNumbers = nums.joinToString(",")
                        sendToTelegram("📵 Watch nomor aktif: " + nums.joinToString(", "))
                    }
                }
            }
            "/jejak" -> {
                val trail = trailList()
                if (trail.isEmpty()) {
                    sendToTelegram("👣 Jejak kosong. Lokasi tercatat tiap patroli/perintah lokasi.")
                } else {
                    sendToTelegram(buildString {
                        appendLine("👣 <b>Jejak (${trail.size} titik)</b>")
                        for ((ts, la, lo) in trail.takeLast(10)) {
                            appendLine("${TimeFmt.short(ts)} — $la,$lo")
                        }
                        append("https://www.google.com/maps/dir/" + trail.takeLast(10).joinToString("/") { "${it.second},${it.third}" })
                    })
                }
            }
            "/waspada" -> {
                sendWaspada()
            }
            "/hilang" -> {
                val level = batteryLevel()
                sendToTelegram("🚨 <b>Hilang</b>: bunyikan + lokasi + foto…")
                try { ringDevice(60) } catch (_: Exception) { }
                val loc = try { fetchLocation() } catch (_: Exception) { null }
                if (loc != null) {
                    try { rememberSentLatLon(loc.latitude, loc.longitude) } catch (_: Exception) { }
                    sendToTelegram("📍 https://maps.google.com/?q=${loc.latitude},${loc.longitude}\nBaterai: ${if (level in 0..100) "$level%" else "?"}")
                } else {
                    sendToTelegram("📍 Lokasi tak didapat. Baterai: ${if (level in 0..100) "$level%" else "?"}")
                }
                try { captureAndSendPhoto(reportResult = true) } catch (_: Exception) { }
            }
            "/jemput" -> {
                val sub = arg.trim()
                if (sub.equals("off", ignoreCase = true)) {
                    preferencesManager.jemputRadiusM = 0
                    sendToTelegram("🏠 Jemput dimatikan.")
                } else if (sub.isEmpty() || sub.equals("status", ignoreCase = true)) {
                    val cur = try { preferencesManager.jemputRadiusM } catch (_: Exception) { 0 }
                    sendToTelegram(if (cur <= 0) "🏠 Jemput: off (contoh: /jemput 500)" else "🏠 Jemput: aktif ±${cur} m dari rumah (sekali bunyi)")
                } else {
                    val rad = sub.toIntOrNull()
                    if (rad == null || rad !in 50..5000) {
                        sendToTelegram("Cara pakai: /jemput \u003c50-5000\u003e (meter) atau /jemput off")
                    } else if (preferencesManager.homeLatE6 == Long.MIN_VALUE) {
                        sendToTelegram("Set /home dulu sebelum /jemput.")
                    } else {
                        preferencesManager.jemputRadiusM = rad
                        sendToTelegram("🏠 Jemput aktif: kabari sekali saat dalam ±${rad} m rumah.")
                    }
                }
            }
            "/help", "/start" -> {
                if (!senderOk) {
                    sendToTelegram(
                        buildString {
                            appendLine("🤖 <b>Perintah</b>")
                            appendLine("/help - daftar ini")
                        }
                    )
                    return
                }
                sendToTelegram(
                    buildString {
                        appendLine("👆 <b>Ketuk tombol di bawah</b>")
                        appendLine()
                        appendLine("🤖 <b>Perintah</b>")
                        appendLine("/photo - ambil foto sekarang")
                        appendLine("/screenshot - ambil layar HP")
                        appendLine("/camera \u003cdepan|belakang\u003e - ganti kamera")
                        appendLine("/location - kirim lokasi sekarang")
                        appendLine("/darurat - lokasi+foto+baterai sekaligus")
                        appendLine("/lastcalls - 5 panggilan terakhir")
                        appendLine("/lastsms - 5 SMS terakhir")
                        appendLine("/photointerval \u003c0-60\u003e - interval foto (0 = manual)")
                        appendLine("/patrol \u003con|off\u003e - patroli foto + lokasi otomatis")
                        appendLine("/patrolinterval \u003c5-180\u003e - interval patroli")
                        appendLine("/night \u003con|off\u003e - sunyi 23:00-05:00")
                        appendLine("/pause \u003cmenit\u003e - jeda foto")
                        appendLine("/battery - lihat baterai")
                        appendLine("/status - status monitoring")
                        appendLine("/ringkas - status+izin+antrean sekaligus")
                        appendLine("/perms - daftar izin aplikasi")
                        appendLine("/stop - jeda monitoring")
                        appendLine("/resume - lanjutkan monitoring")
                        appendLine("/notif <on|off|status> - teruskan notifikasi")
                        appendLine("/syncinterval \u003c1-1440\u003e - interval sync")
                        appendLine("/restart - restart loop monitoring")
                        appendLine("/flush - kirim antrean sekarang")
                        appendLine("/clearqueue - buang antrean")
                        appendLine("/lock - kunci layar HP")
                        appendLine("/ring [5-60] - bunyikan HP")
                        appendLine("/ping [camera|location] - cek delay")
                        appendLine("/record \u003c5-600\u003e - rekam audio (detik)")
                        appendLine("/recordvideo \u003c5-180\u003e - rekam video (detik)")
                        appendLine("/sms \u003cnomor\u003e \u003cpesan\u003e - kirim SMS")
                        appendLine("/smsconfirm - konfirmasi SMS tertunda")
                        appendLine("/lastnotif - notifikasi terakhir")
                        appendLine("/version - versi app + HP")
                        appendLine("/uptime - lama service jalan")
                        appendLine("/contacts \u003cnama\u003e - cari kontak")
                        appendLine("/apps [N] - daftar aplikasi")
                        appendLine("/storage - sisa penyimpanan")
                        appendLine("/history \u003cnomor\u003e - call+SMS per nomor")
                        appendLine("/watch \u003ca,b|status|off\u003e - awasi kata berbahaya")
                        appendLine("/watchnomor \u003cnomor|status|off\u003e - awasi nomor")
                        appendLine("/home \u003clat,lon,rad|status|off\u003e - geofence rumah")
                        appendLine("/school \u003clat,lon,rad|status|off\u003e - geofence sekolah")
                        appendLine("/jampulang \u003cHH:MM|off\u003e - jam malam + cek sekolah 08–16")
                        appendLine("/digest - ringkasan harian")
                        appendLine("/minggu - laporan 7 hari")
                        appendLine("/jejak - jejak lokasi hari ini")
                        appendLine("/waspada - skor kewaspadaan 0-100")
                        appendLine("/hilang - bunyi+lokasi+foto sekaligus")
                        appendLine("/jemput \u003cmeter|off\u003e - kabari saat dekat rumah")
                        appendLine("/log - log error terakhir")
                        appendLine("/help - daftar ini")
                    },
                    mainMenu()
                )
            }
            else -> {
                if (!senderOk) return
                val hint = try { suggestCommand(command) } catch (_: Exception) { null }
                val safeCommand = try { Html.escape(command) } catch (_: Exception) { command }
                if (hint != null) sendToTelegram("❓ Perintah tak dikenal: $safeCommand. Maksudnya $hint? Kirim /help untuk daftar.")
                else sendToTelegram("❓ Perintah tak dikenal: $safeCommand. Kirim /help untuk daftar.")
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
                        continue
                    }
                }
                if (providers.isEmpty()) return@withContext null
                val fix = try {
                    coroutineScope {
                        val pending = providers.map { provider ->
                            async {
                                val result = CompletableDeferred<android.location.Location?>()
                                val listener = object : android.location.LocationListener {
                                    override fun onLocationChanged(location: android.location.Location) {
                                        result.complete(location)
                                    }
                                    override fun onProviderDisabled(providerName: String) {}
                                    override fun onProviderEnabled(providerName: String) {}
                                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val signal = android.os.CancellationSignal()
                                        result.invokeOnCompletion { try { signal.cancel() } catch (_: Exception) { } }
                                        locationManager.getCurrentLocation(provider, signal, applicationContext.mainExecutor) { loc -> result.complete(loc) }
                                    } catch (_: Exception) {
                                        try {
                                            @Suppress("DEPRECATION")
                                            locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                                        } catch (_: Exception) {
                                            result.complete(null)
                                        }
                                    }
                                } else {
                                    try {
                                        @Suppress("DEPRECATION")
                                        locationManager.requestSingleUpdate(provider, listener, android.os.Looper.getMainLooper())
                                    } catch (_: Exception) {
                                        result.complete(null)
                                    }
                                }
                                try {
                                    result.await()
                                } finally {
                                    try {
                                        locationManager.removeUpdates(listener)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        val won = try {
                            kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                                val winner = CompletableDeferred<android.location.Location?>()
                                val left = java.util.concurrent.atomic.AtomicInteger(pending.size)
                                for (task in pending) {
                                    task.invokeOnCompletion {
                                        val got = try {
                                            task.getCompleted()
                                        } catch (_: Exception) {
                                            null
                                        }
                                        if (got != null) {
                                            try {
                                                if (!winner.isCompleted) winner.complete(got)
                                            } catch (_: Exception) {
                                            }
                                        } else if (left.decrementAndGet() == 0) {
                                            try {
                                                if (!winner.isCompleted) winner.complete(null)
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }
                                }
                                winner.await()
                            }
                        } catch (_: Exception) {
                            null
                        }
                        for (task in pending) {
                            try {
                                task.cancel()
                            } catch (_: Exception) {
                            }
                        }
                        won
                    }
                } catch (_: Exception) {
                    null
                }
                fix
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
            var smsTruncated = smsPages >= 5 && smsLastFull
            if (smsTruncated) {
                val probe = try { smsRepository.getNewSms(smsCursor) } catch (_: Exception) { emptyList() }
                smsTruncated = probe.isNotEmpty()
            }
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
            var callTruncated = callPages >= 5 && callLastFull
            if (callTruncated) {
                val probe = try { callLogRepository.getNewCalls(callTs, callId) } catch (_: Exception) { emptyList() }
                callTruncated = probe.isNotEmpty()
            }
            pendingCalls.sortWith(compareBy({ it.date }, { it.id }))
            if (pendingSms.isEmpty() && pendingCalls.isEmpty()) {
                val wasDone = try { preferencesManager.initialSyncDone } catch (_: Exception) { true }
                preferencesManager.setInitialSyncDoneSync(true)
                if (!wasDone) sendToTelegram("Monitoring jalan. Belum ada riwayat SMS/panggilan di HP ini.")
                return
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending start message...")
            val startMessage = buildString {
                appendLine("📱 <b>Monitoring started</b>")
                appendLine()
                appendLine("⏰ Waktu: ${formatDate(System.currentTimeMillis())}")
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
                            appendLine("📞 Nomor: ${Html.escape(sms.address)}")
                            val body = Html.escape(sms.body).let { it.take(safeCut(it, 200)) }
                            appendLine("📝 Isi: $body${if (sms.body.length > 200) "..." else ""}")
                            appendLine("🔄 Jenis: ${sms.getTypeString()}")
                            appendLine("⏰ Waktu: ${formatDate(sms.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    val sentSmsPart = sendFitted(message)
                    if (!sentSmsPart) {
                        initialOk = false
                        break
                    }
                    try {
                        preferencesManager.setSmsCursorSync(maxOf(preferencesManager.lastSmsId, part.maxOf { it.id }))
                    } catch (_: Exception) {
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
                            appendLine("📱 Nomor: ${Html.escape(call.number)}")
                            if (call.name != null) {
                                appendLine("👤 Nama: ${Html.escape(call.name)}")
                            }
                            appendLine("🔄 Jenis: ${call.getTypeString()}")
                            appendLine("⏱️ Durasi: ${call.getDurationString()}")
                            appendLine("⏰ Waktu: ${formatDate(call.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    val sentCallPart = sendFitted(message)
                    if (!sentCallPart) {
                        initialOk = false
                        break
                    }
                    try {
                        val latest = part.maxWith(compareBy({ it.date }, { it.id }))
                        if (latest.date > preferencesManager.lastCallTimestamp ||
                            (latest.date == preferencesManager.lastCallTimestamp && latest.id > preferencesManager.lastCallId)
                        ) {
                            preferencesManager.setCallCursorSync(latest.date, latest.id)
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
                val leftMin = ((photoPausedElapsed() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                "paused ($leftMin min left)"
            } else if (preferencesManager.cameraInterval <= 0) {
                "manual saja (/photo)"
            } else {
                "tiap ${preferencesManager.cameraInterval} mnt"
            }
            val completeMessage = buildString {
                appendLine("✅ <b>Sinkron riwayat selesai</b>")
                appendLine()
                appendLine("Mulai sekarang, hanya SMS dan panggilan baru yang dikirim.")
                appendLine()
                appendLine("📊 <b>Kini</b>")
                appendLine("Monitoring: JALAN")
                appendLine("Interval data: tiap ${preferencesManager.syncInterval} mnt")
                appendLine("Foto: $photoState")
                appendLine("Antrean: ${messageQueue.getQueueSize()}")
                appendLine()
                appendLine("Semua perintah ada di menu bot — ketuk /help kapan saja.")
            }
            if (initialOk && !smsTruncated && !callTruncated) {
                sendToTelegram(completeMessage)
                preferencesManager.setInitialSyncDoneSync(true)
            } else {
                sendToTelegram("Sinkron riwayat terkirim sebagian. Sisanya menyusul otomatis.")
            }
            idleDataCycles = 0
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Initial data sending complete ===")

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error in initial sync: ${redactToken(e.message)}")
        }
    }

    private suspend fun checkAndSendNewData(): Boolean {
        if (initialSyncRunning.get()) return false
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return false
        var found = false
        try {
            try { checkPermissionHealth() } catch (_: Exception) { }
            val lastSms = try {
                preferencesManager.lastSmsId
            } catch (_: Exception) {
                0L
            }
            val smsPage = smsRepository.getNewSms(lastSms)
            if (smsPage.isNotEmpty()) {
                found = true
                var smsCursor = lastSms
                var smsSentAny = false
                val freqMap = try { batchFreqMap() } catch (_: Exception) { null }
                val knownMap = try { smsPage.map { it.address }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
                for (part in smsPage.chunked(10)) {
                    val sentSms = sendFitted(formatSmsMessage(part, freqMap, knownMap))
                    if (sentSms) {
                        smsCursor = maxOf(smsCursor, part.maxOf { it.id })
                        try { preferencesManager.setSmsCursorSync(smsCursor) } catch (_: Exception) { }
                        smsSentAny = true
                    } else {
                        break
                    }
                }
                if (smsSentAny) {
                    try { preferencesManager.lastSyncTime = System.currentTimeMillis() } catch (_: Exception) { }
                    try { preferencesManager.lastDataAt = System.currentTimeMillis() } catch (_: Exception) { }
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
            val callPage = callLogRepository.getNewCalls(lastCallTs, lastCallId)
            if (callPage.isNotEmpty()) {
                found = true
                var callSentAny = false
                val callKnown = try { callPage.map { it.number }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
                for (part in callPage.chunked(10)) {
                    val sentCall = sendFitted(formatCallMessage(part, callKnown))
                    if (!sentCall) {
                        break
                    }
                    try {
                        val latest = part.maxWith(compareBy({ it.date }, { it.id }))
                        if (latest.date > preferencesManager.lastCallTimestamp ||
                            (latest.date == preferencesManager.lastCallTimestamp && latest.id > preferencesManager.lastCallId)
                        ) {
                            preferencesManager.setCallCursorSync(latest.date, latest.id)
                        }
                    } catch (_: Exception) {
                    }
                    callSentAny = true
                }
                    if (callSentAny) {
                    try { preferencesManager.lastSyncTime = System.currentTimeMillis() } catch (_: Exception) { }
                    try { preferencesManager.lastDataAt = System.currentTimeMillis() } catch (_: Exception) { }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error checking new data: ${redactToken(e.message)}")
        }
        return found
    }

    private val emergencyNumbers = setOf("112", "911", "999", "110", "000", "111", "118", "119")

    private fun isEmergencySmsNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        var intl = digits
        if (intl.startsWith("011")) intl = intl.substring(3)
        else if (intl.startsWith("00")) intl = intl.substring(2)
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        return digits in emergencyNumbers || intl in emergencyNumbers || local in emergencyNumbers
    }

    private fun isPremiumSmsNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return false
        if (digits in emergencyNumbers) return false
        var intl = digits
        if (intl.startsWith("011")) intl = intl.substring(3)
        else if (intl.startsWith("00")) intl = intl.substring(2)
        val local = if (digits.startsWith("0")) digits.substring(1) else digits
        if (local == "1900" || local.startsWith("1900") || local == "900" || local == "976") return true
        if (intl.startsWith("44")) {
            val uk = intl.substring(2)
            if (uk.startsWith("70") || uk.startsWith("90") || uk.startsWith("118") || uk.startsWith("09") || uk.startsWith("087") || uk.startsWith("084")) return true
        }
        if (intl.startsWith("1")) {
            if (intl.substring(1).startsWith("900")) return true
        }
        if (intl.startsWith("900")) return true
        if (local.length <= 6) {
            return true
        }
        return false
    }

    private suspend fun sendSmsPending(number: String, smsText: String, stagedAt: Long = 0L) {
        val uuid = java.util.UUID.randomUUID()
        val sentAction = "com.redeye.parentalmonitor.SMS_SENT_" + System.nanoTime() + "_" + uuid.toString()
        val baseCode = Math.floorMod(smsReqSeq.addAndGet(1000000), 1000000000) + (uuid.hashCode() and 0xfff)
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
            smsManager.divideMessage(smsText).ifEmpty { java.util.ArrayList(listOf(smsText)) }
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F SMS failed.")
            return
        }
        val expected = parts.size.coerceAtLeast(1)
        val okCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failCount = java.util.concurrent.atomic.AtomicInteger(0)
        val confirmedParts = java.util.Collections.synchronizedSet(java.util.HashSet<Int>())
        val counting = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action != sentAction) return
                try {
                    val part = try { intent?.getIntExtra("part", -1) ?: -1 } catch (_: Exception) { -1 }
                    if (part >= 0) {
                        try { if (!confirmedParts.add(part)) return } catch (_: Exception) { return }
                    } else if (okCount.get() + failCount.get() >= expected) return
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
                                android.content.Intent(sentAction).putExtra("part", it).putExtra("of", parts.size),
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
                val confirmed = withTimeoutOrNull(30_000L + (expected - 1) * 15_000L) { delivered.await() } ?: false
                if (confirmed) {
                    preferencesManager.setLastSmsSendAtSync(System.currentTimeMillis())
                    preferencesManager.clearSmsPendingIf(stagedAt)
                    sendToTelegram("\uD83D\uDCE9 SMS sent to $number.")
                } else if (okCount.get() > 0) {
                    preferencesManager.setLastSmsSendAtSync(System.currentTimeMillis())
                    val smsPreview = try { Html.escape(smsText).let { it.take(safeCut(it, 120)) } } catch (_: Exception) { "" }
                    val smsNotice = if (smsPreview.isEmpty()) "\u26A0\uFE0F SMS partially sent (${okCount.get()}/$expected parts). Pending kept; verify with the recipient before retrying because a retry may duplicate the delivered parts." else "\u26A0\uFE0F SMS partially sent (${okCount.get()}/$expected parts) to $number. Pending kept; verify with the recipient before retrying because a retry may duplicate the delivered parts. Text preview: $smsPreview"
                    sendToTelegram(smsNotice)
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

    private fun formatSmsMessage(smsList: List<com.redeye.parentalmonitor.data.models.SmsData>, freqMap: Map<String, Int>? = null, knownMap: Map<String, Boolean>? = null): String {
        return buildString {
            appendLine("💬 <b>SMS baru (${smsList.size})</b>")
            appendLine()
            
            smsList.forEach { sms ->
                appendLine("📞 Nomor: ${Html.escape(sms.address)}")
                val tag = smsTag(sms.address, sms.body)
                if (tag.isNotEmpty()) appendLine(tag)
                try {
                    if (watchNumberHit(sms.address) != null) appendLine("🚨 Nomor diawasi")
                } catch (_: Exception) {
                }
                try {
                    val key = sms.address.filter { it.isDigit() }.takeLast(10)
                    val freq = freqMap?.get(key) ?: todayCountFor(sms.address)
                    if (freq >= 3) appendLine("🔁 $freq x hari ini")
                    val known = knownMap?.get(sms.address) ?: isKnownContact(sms.address)
                    if (!known) appendLine("🆕 Nomor baru (tak ada di kontak)")
                } catch (_: Exception) {
                }
                val full = Html.escape(sms.body)
                val body = full.take(safeCut(full, 200))
                appendLine("📝 Isi: $body${if (full.length > body.length) "..." else ""}")
                appendLine("🔄 Jenis: ${sms.getTypeString()}")
                appendLine("⏰ Waktu: ${formatDate(sms.date)}")
                appendLine("━━━━━━━━━━━━━━━━")
            }
        }
    }

    private fun formatCallMessage(callList: List<com.redeye.parentalmonitor.data.models.CallData>, knownMap: Map<String, Boolean>? = null): String {
        return buildString {
            appendLine("📞 <b>Panggilan baru (${callList.size})</b>")
            appendLine()
            
            callList.forEach { call ->
                appendLine("📱 Nomor: ${Html.escape(call.number)}")
                if (call.name != null) {
                    appendLine("👤 Nama: ${Html.escape(call.name)}")
                } else {
                    try {
                        val known = knownMap?.get(call.number) ?: isKnownContact(call.number)
                        if (!known) appendLine("🆕 Nomor baru (tak ada di kontak)")
                    } catch (_: Exception) {
                    }
                }
                try {
                    if (watchNumberHit(call.number) != null) appendLine("🚨 Nomor diawasi")
                } catch (_: Exception) {
                }
                appendLine("🔄 Jenis: ${call.getTypeString()}")
                appendLine("⏱️ Durasi: ${call.getDurationString()}")
                appendLine("⏰ Waktu: ${formatDate(call.date)}")
                appendLine("━━━━━━━━━━━━━━━━")
            }
        }
    }

    private fun safeCut(text: String, max: Int): Int {
        return com.redeye.parentalmonitor.utils.TextChunk.safeCut(text, max)
    }

    private fun photoLuma(photoFile: File): Int {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 255
            var sample = 1
            while (bounds.outWidth / sample > 48 || bounds.outHeight / sample > 48) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return 255
            try {
                var sum = 0L
                var n = 0
                val stepX = (bmp.width / 16).coerceAtLeast(1)
                val stepY = (bmp.height / 16).coerceAtLeast(1)
                var y = 0
                while (y < bmp.height) {
                    var x = 0
                    while (x < bmp.width) {
                        val p = bmp.getPixel(x, y)
                        sum += (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                        n++
                        x += stepX
                    }
                    y += stepY
                }
                if (n == 0) 255 else (sum / n).toInt().coerceIn(0, 255)
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            255
        }
    }

    private fun photoHash(photoFile: File): Long {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0L
            var sample = 1
            while (bounds.outWidth / sample > 16 || bounds.outHeight / sample > 16) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return 0L
            try {
                val cell = IntArray(64)
                var sum = 0L
                for (cy in 0 until 8) {
                    for (cx in 0 until 8) {
                        val x = (((cx + 0.5) * bmp.width) / 8).toInt().coerceIn(0, bmp.width - 1)
                        val y = (((cy + 0.5) * bmp.height) / 8).toInt().coerceIn(0, bmp.height - 1)
                        val p = bmp.getPixel(x, y)
                        val luma = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                        cell[cy * 8 + cx] = luma
                        sum += luma
                    }
                }
                val avg = (sum / 64).toInt()
                var bits = 0L
                for (i in 0 until 64) {
                    if (cell[i] > avg) bits = bits or (1L shl i)
                }
                if (bits == 0L) bits = 1L
                bits
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun hammingDistance(a: Long, b: Long): Int {
        var x = a xor b
        var n = 0
        while (x != 0L) {
            n++
            x = x and (x - 1)
        }
        return n
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[n]
    }

    private fun transposedEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i + 1 >= a.length) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        for (j in i + 2 until a.length) if (a[j] != b[j]) return false
        return true
    }

    private fun suggestCommand(typed: String): String? {
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        for (known in KNOWN_COMMANDS) {
            val d = try { editDistance(typed, known) } catch (_: Exception) { Int.MAX_VALUE }
            if (d < bestDist) {
                bestDist = d
                best = known
            }
        }
        if (best != null && bestDist == 2) {
            try { if (transposedEquals(typed, best)) bestDist = 1 } catch (_: Exception) { }
        }
        val limit = if (typed.length <= 5) 1 else 2
        return if (best != null && bestDist in 1..limit) best else null
    }
    private suspend fun sendFitted(message: String, replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null, queueOnFail: Boolean = true): Boolean {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            if (queueOnFail) {
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
        }
        if (message.length <= 4000) {
            if (sendToTelegram(message, replyMarkup, false)) return true
            if (queueOnFail) {
                messageQueue.addMessage(message)
                if (replyMarkup != null) messageQueue.addMessage("Tap /help untuk menampilkan tombol menu.")
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
        }
        var ok = true
        val failed = mutableListOf<String>()
        val chunks = mutableListOf<String>()
        var rest = message
        while (rest.length > 4000) {
            val cut = safeCut(rest, 4000)
            chunks.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        chunks.add(rest)
        var lastSentIdx = -1
        try {
            for ((idx, chunk) in chunks.withIndex()) {
                val markup = if (idx == chunks.lastIndex) replyMarkup else null
                if (!sendToTelegram(chunk, markup, false)) {
                    ok = false
                    failed.add(chunk)
                } else {
                    lastSentIdx = idx
                }
                if (idx != chunks.lastIndex) delay(300)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (queueOnFail && lastSentIdx + 1 < chunks.size) {
                try {
                    messageQueue.addMessages(chunks.subList(lastSentIdx + 1, chunks.size))
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
            throw e
        }
        if (!ok) {
            if (queueOnFail) {
                messageQueue.addMessages(failed)
                if (replyMarkup != null && failed.isNotEmpty() && failed.last() == chunks.last()) messageQueue.addMessage("Tap /help untuk menampilkan tombol menu.")
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
        }
        return ok
    }

    private fun noteDroppedMessage() {
        msgDropCount.incrementAndGet()
        try {
            preferencesManager.setPendingMsgDropsSync(msgDropCount.get())
        } catch (_: Exception) {
        }
        synchronized(msgDropLock) {
            if (msgDropJob?.isActive == true) return
            msgDropJob = serviceScope.launch {
                try {
                    delay(30_000L)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                try {
                    flushMsgDropNotice()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun flushMsgDropNotice() {
        val count = msgDropCount.getAndSet(0)
        try {
            preferencesManager.setPendingMsgDropsSync(0)
        } catch (_: Exception) {
        }
        if (count <= 0) return
        try {
            val text = if (count == 1) "Dropped 1 message rejected by Telegram (400)."
                else "Dropped $count messages rejected by Telegram (400)."
            messageQueue.addMessage(text, true)
            MessageScheduler.scheduleMessageSend(this@MonitoringService)
        } catch (_: Exception) {
            msgDropCount.addAndGet(count)
            try {
                preferencesManager.setPendingMsgDropsSync(msgDropCount.get())
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendToTelegram(
        message: String,
        replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null,
        queueOnFail: Boolean = true
    ): Boolean {
        try {
            if (NetworkUtils.isAuthBlocked(preferencesManager)) {
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return false
            }
            val rateRemain = NetworkUtils.rateLimitedRemainMs()
            if (rateRemain > 0L) {
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleRateLimited(this, rateRemain)
                }
                return false
            }
            if (message.length > 4000) {
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
                return false
            }

            val hasNetwork = hasNetwork()
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Network available: $hasNetwork")
            
            if (!hasNetwork) {
                android.util.Log.w("MonitoringService", "No network, adding to queue")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleMessageSend(this)
                }
                return false
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
                NetworkUtils.noteRateLimited(retryAfter)
                android.util.Log.w("MonitoringService", "Rate limited, will retry via queue after ${retryAfter}s")
                if (queueOnFail) {
                    messageQueue.addMessage(message)
                    MessageScheduler.scheduleRateLimited(this, retryAfter * 1000L)
                }
                return false
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
                return false
            } else if (response.code() == 400) {
                val goneBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                if (adoptMigratedChat(goneBody, chatId)) {
                    if (queueOnFail) {
                        messageQueue.addMessage(message)
                        MessageScheduler.scheduleMessageSend(this)
                    }
                    return false
                }
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
                    return false
                }
                if (NetworkUtils.isRightsLimited(goneBody)) {
                    android.util.Log.w("MonitoringService", "Insufficient rights (400), queuing without auth block")
                    if (queueOnFail) {
                        messageQueue.addMessage(message)
                        MessageScheduler.scheduleMessageSend(this)
                    }
                    return false
                }
                val plain = Html.unescape(message)
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
                                MessageScheduler.scheduleRateLimited(this, retryAfter * 1000L)
                            }
                            return false
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
                            return false
                        } else if (fallbackResp.code() == 408 || fallbackResp.code() >= 500) {
                            if (queueOnFail) {
                                messageQueue.addMessage(plain)
                                MessageScheduler.scheduleMessageSend(this)
                            }
                            return false
                        } else if (fallbackResp.code() == 400) {
                            val fbBody = try { fallbackResp.errorBody()?.string() } catch (_: Exception) { null }
                            if (adoptMigratedChat(fbBody, chatId)) {
                                if (queueOnFail) {
                                    messageQueue.addMessage(plain)
                                    MessageScheduler.scheduleMessageSend(this)
                                }
                                return false
                            }
                            if (NetworkUtils.isChatMissing(fbBody)) {
                                try {
                                    preferencesManager.credentialError = fallbackResp.code().toString()
                                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                                } catch (_: Exception) {
                                }
                                if (queueOnFail) {
                                    messageQueue.addMessage(plain)
                                    MessageScheduler.scheduleMessageSend(this)
                                }
                                return false
                            }
                            if (NetworkUtils.isRightsLimited(fbBody)) {
                                if (queueOnFail) {
                                    messageQueue.addMessage(plain)
                                    MessageScheduler.scheduleMessageSend(this)
                                }
                                return false
                            }
                            android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                            try {
                                noteDroppedMessage()
                            } catch (_: Exception) {
                            }
                            return true
                        } else {
                            android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                            try {
                                noteDroppedMessage()
                            } catch (_: Exception) {
                            }
                            return true
                        }
                    } catch (_: Exception) {
                        if (queueOnFail) {
                            messageQueue.addMessage(plain)
                            MessageScheduler.scheduleMessageSend(this)
                        }
                        return false
                    }
                } else {
                    android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
                    try {
                        noteDroppedMessage()
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
                return false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${redactToken(e.message)}")
            if (queueOnFail) {
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            }
            return false
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

    private fun effectiveSyncMillis(): Long {
        val base = syncIntervalMillis()
        val factor = (idleDataCycles.coerceIn(0, 8) + 1).coerceAtMost(4).toLong()
        val stretched = try { base * factor } catch (_: Exception) { base }
        return stretched.coerceIn(1L, maxOf(base, 30 * 60_000L))
    }

    private fun isScreenOn(): Boolean {
        return try {
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        } catch (_: Exception) {
            true
        }
    }

    private fun isNightTime(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = (((now + off) / 3_600_000L) % 24L).toInt()
            h >= 23 || h < 5
        } catch (_: Exception) {
            false
        }
    }

    private fun isNightPaused(): Boolean {
        return try {
            preferencesManager.nightMode && isNightTime()
        } catch (_: Exception) {
            false
        }
    }

    private fun batteryLevel(): Int {
        return try {
            (getSystemService(BATTERY_SERVICE) as? BatteryManager)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private suspend fun checkBatteryAlert() {
        val level = batteryLevel()
        if (level < 0 || level >= 15) return
        val now = System.currentTimeMillis()
        val last = try { preferencesManager.batteryAlertAt } catch (_: Exception) { 0L }
        if (last <= now && now - last < 24 * 60 * 60_000L) return
        try { preferencesManager.batteryAlertAt = now } catch (_: Exception) { }
        sendToTelegram("🔋 Baterai perangkat tinggal $level%. Colok charger agar monitoring tak putus.")
    }

    private suspend fun maybeSendDigest() {
        val now = System.currentTimeMillis()
        val last = try { preferencesManager.digestAt } catch (_: Exception) { 0L }
        if (last <= now && now - last < 6 * 60 * 60_000L) return
        try { preferencesManager.digestAt = now } catch (_: Exception) { }
        val since = now - 6 * 60 * 60_000L
        val smsCount = try { smsRepository.getRecentSmsSince(since, 200).size } catch (_: Exception) { -1 }
        val callCount = try { callLogRepository.getCallsSince(since, 200).size } catch (_: Exception) { -1 }
        val level = batteryLevel()
        sendToTelegram(buildString {
            appendLine("📰 <b>Ringkasan 6 jam</b>")
            appendLine("SMS: ${if (smsCount < 0) "?" else if (smsCount >= 200) "200+" else "$smsCount"}")
            appendLine("Panggilan: ${if (callCount < 0) "?" else if (callCount >= 200) "200+" else "$callCount"}")
            appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
            appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
        })
    }

    private fun lastSentLatLon(): Pair<Double, Double>? {
        return try {
            val la = preferencesManager.lastLocLatE6
            val lo = preferencesManager.lastLocLonE6
            if (la == Long.MIN_VALUE || lo == Long.MIN_VALUE) null else Pair(la / 1_000_000.0, lo / 1_000_000.0)
        } catch (_: Exception) {
            null
        }
    }

    private fun rememberSentLatLon(lat: Double, lon: Double) {
        try {
            preferencesManager.lastLocLatE6 = (lat * 1_000_000).toLong()
            preferencesManager.lastLocLonE6 = (lon * 1_000_000).toLong()
            preferencesManager.lastLocAt = System.currentTimeMillis()
        } catch (_: Exception) {
        }
        try {
            val pts = preferencesManager.trail24.split(";").filter { it.contains(":") }.toMutableList()
            pts.add("${System.currentTimeMillis()}:${(lat * 1_000_000).toLong()},${(lon * 1_000_000).toLong()}")
            while (pts.size > 24) pts.removeAt(0)
            preferencesManager.trail24 = pts.joinToString(";")
        } catch (_: Exception) {
        }
    }

    private fun trailList(): List<Triple<Long, Double, Double>> {
        return try {
            preferencesManager.trail24.split(";").mapNotNull { p ->
                val ts = p.substringBefore(":").toLongOrNull() ?: return@mapNotNull null
                val ll = p.substringAfter(":").split(",")
                if (ll.size != 2) return@mapNotNull null
                val la = ll[0].toLongOrNull() ?: return@mapNotNull null
                val lo = ll[1].toLongOrNull() ?: return@mapNotNull null
                Triple(ts, la / 1_000_000.0, lo / 1_000_000.0)
            }.takeLast(24)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        return try {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(aLat, aLon, bLat, bLon, out)
            out[0]
        } catch (_: Exception) {
            -1f
        }
    }

    private fun smsTag(address: String, body: String): String {
        return try {
            val lower = body.lowercase(java.util.Locale.ROOT)
            val watch = watchHit(body)
            if (watch != null) return "⚠️ Watch: $watch"
            val hasCode = OTP_CODE_REGEX.containsMatchIn(body)
            val digits = address.filter { it.isDigit() }
            if (hasCode && (digits.isNotEmpty() && digits.length <= 6 || OTP_KEYWORDS.any { lower.contains(it) })) return "🔑 Kemungkinan OTP"
            if (PROMO_KEYWORDS.any { lower.contains(it) }) return "📢 Kemungkinan promo"
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveAliasInput(cmd: String, arg: String): String? {
        return try {
            if (cmd.startsWith("/")) {
                val bare = cmd.substring(1).lowercase(java.util.Locale.ROOT)
                val mapped = ALIAS_COMMANDS[bare] ?: return null
                if (arg.isEmpty()) mapped else "$mapped $arg"
            } else {
                val bare = cmd.trim().trimEnd('?', '!', '.').lowercase(java.util.Locale.ROOT)
                if (bare.isEmpty()) return null
                val mapped = ALIAS_COMMANDS[bare] ?: return null
                if (arg.isEmpty()) mapped else "$mapped $arg"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun watchList(): List<String> {
        return try {
            preferencesManager.watchKeywords.split(",", " ", ";").map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.length >= 3 }.distinct().take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun watchHit(text: String): String? {
        return try {
            val lower = text.lowercase(java.util.Locale.ROOT)
            watchList().firstOrNull { it.isNotEmpty() && lower.contains(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun fenceStatusText(isHome: Boolean): String {
        return try {
            val lat = (if (isHome) preferencesManager.homeLatE6 else preferencesManager.schoolLatE6) / 1_000_000.0
            val lon = (if (isHome) preferencesManager.homeLonE6 else preferencesManager.schoolLonE6) / 1_000_000.0
            val rad = if (isHome) preferencesManager.homeRadiusM else preferencesManager.schoolRadiusM
            val la = if (isHome) preferencesManager.homeLatE6 else preferencesManager.schoolLatE6
            if (la == Long.MIN_VALUE || rad <= 0) "off" else "$lat,$lon ±${rad}m"
        } catch (_: Exception) {
            "?"
        }
    }

    private suspend fun checkGeofence(lat: Double, lon: Double) {
        try {
            val fences = listOf(
                Triple(preferencesManager.homeLatE6, preferencesManager.homeLonE6, preferencesManager.homeRadiusM) to true,
                Triple(preferencesManager.schoolLatE6, preferencesManager.schoolLonE6, preferencesManager.schoolRadiusM) to false
            )
            for ((cfg, isHome) in fences) {
                val (laE6, loE6, rad) = cfg
                if (laE6 == Long.MIN_VALUE || loE6 == Long.MIN_VALUE || rad <= 0) continue
                val dist = distanceMeters(laE6 / 1_000_000.0, loE6 / 1_000_000.0, lat, lon)
                if (dist < 0f) continue
                val inside = dist <= rad
                if (isHome) {
                    try {
                        val jemput = preferencesManager.jemputRadiusM
                        if (jemput > 0 && dist <= jemput) {
                            preferencesManager.jemputRadiusM = 0
                            sendToTelegram("🏠 Anak dalam ±${dist.toInt()} m dari rumah — siap dijemput.")
                        }
                    } catch (_: Exception) {
                    }
                }
                val state = if (isHome) preferencesManager.homeState else preferencesManager.schoolState
                val now = if (inside) 1 else 2
                if (state !in 1..2) {
                    if (isHome) preferencesManager.homeState = now else preferencesManager.schoolState = now
                    continue
                }
                if (state != now) {
                    if (isHome) preferencesManager.homeState = now else preferencesManager.schoolState = now
                    val name = if (isHome) "Rumah" else "Sekolah"
                    val verb = if (inside) "masuk" else "keluar"
                    sendToTelegram("📍 $name: perangkat $verb area (±${dist.toInt()} m dari titik).")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun todayKey(): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
            sdf.timeZone = java.util.TimeZone.getDefault()
            sdf.format(java.util.Date(System.currentTimeMillis()))
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun sendDailyDigest(manual: Boolean = false) {
        try {
            val now = System.currentTimeMillis()
            val since = now - 24 * 60 * 60_000L
            val sms = try { smsRepository.getRecentSmsSince(since, 200) } catch (_: Exception) { emptyList() }
            val calls = try { callLogRepository.getCallsSince(since, 200) } catch (_: Exception) { emptyList() }
            val freq = HashMap<String, Int>()
            for (s in sms) {
                val d = s.address.filter { it.isDigit() }.takeLast(10)
                if (d.length >= 7) freq[d] = (freq[d] ?: 0) + 1
            }
            for (c in calls) {
                val d = c.number.filter { it.isDigit() }.takeLast(10)
                if (d.length >= 7) freq[d] = (freq[d] ?: 0) + 1
            }
            val top = freq.entries.sortedByDescending { it.value }.take(3)
            val watch = watchList()
            var hits = 0
            for (s in sms) {
                if (watchNumbers().isNotEmpty() && watchNumberHit(s.address) != null) hits++
                else if (watch.isNotEmpty()) {
                    val lower = try { s.body.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                    if (watch.any { lower.contains(it) }) hits++
                }
            }
            try {
                for (c in calls) if (watchNumberHit(c.number) != null) hits++
            } catch (_: Exception) {
            }
            val level = batteryLevel()
            sendToTelegram(buildString {
                appendLine("🌙 <b>Ringkasan harian</b>")
                appendLine("SMS 24 jam: ${if (sms.size >= 200) "200+" else "${sms.size}"}, Panggilan: ${if (calls.size >= 200) "200+" else "${calls.size}"}")
                if (top.isEmpty()) appendLine("Kontak teratas: -") else appendLine("Teratas: " + top.joinToString(", ") { it.key + " (${it.value}x)" })
                if (watch.isNotEmpty() || hits > 0) appendLine("Watch hit: $hits")
                appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
                appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
                if (!manual) appendLine("Kirim /digest kapan saja untuk versi manual.")
            },
                mainMenu()
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun maybeSendNightDigest() {
        try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = (((now + off) / 3_600_000L) % 24L).toInt()
            if (h < 21) return
            val key = todayKey()
            if (key.isEmpty()) return
            val done = try { preferencesManager.nightDigestDate } catch (_: Exception) { "" }
            if (done == key) return
            try { preferencesManager.nightDigestDate = key } catch (_: Exception) { }
            sendDailyDigest(manual = false)
        } catch (_: Exception) {
        }
    }

    private fun isKnownContact(number: String): Boolean {
        return try {
            val digits = number.filter { it.isDigit() }
            if (digits.isEmpty()) return true
            val tail = digits.takeLast(7)
            if (tail.length < 7) return true
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val c = contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?", arrayOf("%$tail%"), null) ?: return true
            c.use { cur ->
                while (cur.moveToNext()) {
                    val nb = try { cur.getString(0) ?: "" } catch (_: Exception) { "" }
                    if (contactNumberMatches(nb, digits)) return true
                }
                false
            }
        } catch (e: SecurityException) {
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun batchFreqMap(): Map<String, Int> {
        val m = HashMap<String, Int>()
        try {
            val since = System.currentTimeMillis() - 24 * 60 * 60_000L
            try {
                for (s in smsRepository.getRecentSmsSince(since, 200)) {
                    val d = s.address.filter { it.isDigit() }.takeLast(10)
                    if (d.length >= 7) m[d] = (m[d] ?: 0) + 1
                }
            } catch (_: Exception) {
            }
            try {
                for (c in callLogRepository.getCallsSince(since, 200)) {
                    val d = c.number.filter { it.isDigit() }.takeLast(10)
                    if (d.length >= 7) m[d] = (m[d] ?: 0) + 1
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        return m
    }

    private fun todayCountFor(number: String): Int {
        return try {
            val since = System.currentTimeMillis() - 24 * 60 * 60_000L
            val digits = number.filter { it.isDigit() }
            if (digits.length < 7) return 0
            var n = 0
            try {
                for (s in smsRepository.getRecentSmsSince(since, 200)) {
                    if (com.redeye.parentalmonitor.utils.PhoneNumbers.numbersEqualFast(s.address, number, null)) n++
                }
            } catch (_: Exception) {
            }
            try {
                for (c in callLogRepository.getCallsSince(since, 200)) {
                    if (com.redeye.parentalmonitor.utils.PhoneNumbers.numbersEqualFast(c.number, number, null)) n++
                }
            } catch (_: Exception) {
            }
            n
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun checkQuietHealth() {
        try {
            val now = System.currentTimeMillis()
            val lastAlert = try { preferencesManager.healthAlertAt } catch (_: Exception) { 0L }
            if (lastAlert <= now && now - lastAlert < 12 * 60 * 60_000L) return
            val notes = ArrayList<String>()
            val lastData = try { preferencesManager.lastDataAt } catch (_: Exception) { 0L }
            if (lastData > 0 && now - lastData > 48 * 60 * 60_000L && !preferencesManager.monitoringPaused) {
                notes.add("sepi >48 jam (data terakhir ${formatDate(lastData)})")
            }
            val fails = try { preferencesManager.locFailCount } catch (_: Exception) { 0 }
            if (fails >= 5) {
                notes.add("lokasi gagal $fails x beruntun")
                try { preferencesManager.locFailCount = 0 } catch (_: Exception) { }
            }
            val q = try { messageQueue.getQueueSize() } catch (_: Exception) { 0 }
            if (q >= 50) notes.add("antrean $q (≥50)")
            if (notes.isEmpty()) return
            try { preferencesManager.healthAlertAt = now } catch (_: Exception) { }
            sendToTelegram("🩺 Health: " + notes.joinToString("; ") + ". Cek /status & /perms.")
        } catch (_: Exception) {
        }
    }

    private fun watchNumbers(): List<String> {
        return try {
            preferencesManager.watchNumbers.split(",", " ", ";").map { it.filter { c -> c.isDigit() } }.filter { it.length >= 7 }.distinct().take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun watchNumberHit(number: String): String? {
        return try {
            val list = watchNumbers()
            if (list.isEmpty()) return null
            val have = number.filter { it.isDigit() }
            if (have.isEmpty()) return null
            list.firstOrNull { w ->
                have == w || (have.length >= 7 && w.length >= 7 &&
                    (have.endsWith(w.takeLast(7)) || w.endsWith(have.takeLast(7))))
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sparkline(hours: IntArray): String {
        return try {
            val bars = charArrayOf('▁', '▂', '▃', '▄', '▅', '▆', '▇', '█')
            val peak = hours.maxOrNull()?.coerceAtLeast(1) ?: 1
            buildString {
                for (h in hours) append(bars[((h * 7) / peak).coerceIn(0, 7)])
            }
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun sendWaspada() {
        try {
            val now = System.currentTimeMillis()
            val since = now - 24 * 60 * 60_000L
            val sms = try { smsRepository.getRecentSmsSince(since, 200) } catch (_: Exception) { emptyList() }
            val calls = try { callLogRepository.getCallsSince(since, 200) } catch (_: Exception) { emptyList() }
            val watch = watchList()
            var hits = 0
            var nights = 0
            val unknown = HashSet<String>()
            for (s in sms) {
                val lower = try { s.body.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                val numHit = watchNumberHit(s.address) != null
                if (numHit) hits++ else if (watch.isNotEmpty() && watch.any { lower.contains(it) }) hits++
                val h = hourOf(s.date)
                if (h >= 23 || h in 0..4) nights++
                if (!isKnownContact(s.address)) unknown.add(s.address.filter { it.isDigit() }.takeLast(10))
            }
            for (c in calls) {
                if (watchNumberHit(c.number) != null) hits++
                val h = hourOf(c.date)
                if (h >= 23 || h in 0..4) nights++
                if (c.name == null && !isKnownContact(c.number)) unknown.add(c.number.filter { it.isDigit() }.takeLast(10))
            }
            var score = 0
            score += (hits * 25).coerceAtMost(50)
            score += (unknown.size * 10).coerceAtMost(20)
            score += (nights * 2).coerceAtMost(20)
            try {
                val pos = lastSentLatLon()
                val la = preferencesManager.homeLatE6
                val lo = preferencesManager.homeLonE6
                val rad = preferencesManager.homeRadiusM
                if (pos != null && la != Long.MIN_VALUE && rad > 0 &&
                    distanceMeters(la / 1_000_000.0, lo / 1_000_000.0, pos.first, pos.second) > rad
                ) score += 10
            } catch (_: Exception) {
            }
            score = score.coerceIn(0, 100)
            val verdict = when {
                score >= 51 -> "🔴 Cek sekarang"
                score >= 21 -> "🟡 Pantau"
                else -> "🟢 Aman"
            }
            sendToTelegram(buildString {
                appendLine("🛡️ <b>Waspada: $score/100 — $verdict</b>")
                appendLine("Watch hit 24 jam: $hits")
                appendLine("Nomor baru: ${unknown.size}")
                appendLine("Begadang (23–05): $nights")
            },
                mainMenu()
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun checkBegadang() {
        try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = (((now + off) / 3_600_000L) % 24L).toInt()
            if (h !in 5..11) return
            val key = todayKey()
            if (key.isEmpty()) return
            if (try { preferencesManager.begadangDate } catch (_: Exception) { "" } == key) return
            val since = now - 24 * 60 * 60_000L
            var nights = 0
            try {
                for (s in smsRepository.getRecentSmsSince(since, 200)) {
                    val hh = hourOf(s.date)
                    if (hh >= 23 || hh in 0..4) nights++
                }
            } catch (_: Exception) {
            }
            try {
                for (c in callLogRepository.getCallsSince(since, 200)) {
                    val hh = hourOf(c.date)
                    if (hh >= 23 || hh in 0..4) nights++
                }
            } catch (_: Exception) {
            }
            if (nights < 10) return
            try { preferencesManager.begadangDate = key } catch (_: Exception) { }
            sendToTelegram("🌙 Begadang: $nights aktivitas 23–05 semalam. Cek /minggu untuk pola.")
        } catch (_: Exception) {
        }
    }

    private fun hourOf(ts: Long): Int {
        return try {
            val off = java.util.TimeZone.getDefault().getOffset(ts)
            (((ts + off) / 3_600_000L) % 24L).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    private suspend fun sendWeeklyDigest() {
        try {
            val now = System.currentTimeMillis()
            val since = now - 7 * 24 * 60 * 60_000L
            val sms = try { smsRepository.getRecentSmsSince(since, 200) } catch (_: Exception) { emptyList() }
            val calls = try { callLogRepository.getCallsSince(since, 200) } catch (_: Exception) { emptyList() }
            val capped = sms.size >= 200 || calls.size >= 200
            val freq = HashMap<String, Int>()
            val hours = IntArray(24)
            var nights = 0
            val watch = watchList()
            val watchNums = watchNumbers()
            var watchHits = 0
            for (s in sms) {
                val d = s.address.filter { it.isDigit() }.takeLast(10)
                if (d.length >= 7) freq[d] = (freq[d] ?: 0) + 1
                val h = hourOf(s.date)
                if (h in 0..23) {
                    hours[h]++
                    if (h >= 23 || h < 5) nights++
                }
                val lower = try { s.body.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                if (watchNums.isNotEmpty() && watchNumberHit(s.address) != null) watchHits++
                else if (watch.any { lower.contains(it) }) watchHits++
            }
            for (c in calls) {
                val d = c.number.filter { it.isDigit() }.takeLast(10)
                if (d.length >= 7) freq[d] = (freq[d] ?: 0) + 1
                val h = hourOf(c.date)
                if (h in 0..23) {
                    hours[h]++
                    if (h >= 23 || h < 5) nights++
                }
                if (watchNums.isNotEmpty() && watchNumberHit(c.number) != null) watchHits++
            }
            val top = freq.entries.sortedByDescending { it.value }.take(5)
            val busy = hours.indices.maxByOrNull { hours[it] } ?: -1
            sendToTelegram(buildString {
                appendLine("📊 <b>Laporan 7 hari</b>")
                appendLine("SMS: ${if (sms.size >= 200) "200+" else "${sms.size}"}, Panggilan: ${if (calls.size >= 200) "200+" else "${calls.size}"}")
                if (top.isEmpty()) appendLine("Top kontak: -") else appendLine("Top: " + top.joinToString(", ") { it.key + " (${it.value}x)" })
                if (busy in 0..23) appendLine("Jam tersibuk: ${busy}:00 (${hours[busy]} aktivitas)")
                appendLine("Aktif 00–23: " + sparkline(hours))
                appendLine("Begadang (23–05): $nights")
                if (watch.isNotEmpty() || watchNums.isNotEmpty()) appendLine("Watch hit: $watchHits")
                if (capped) appendLine("(dari 200 data terakhir)")
            },
                mainMenu()
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun checkCurfew() {
        try {
            val now = System.currentTimeMillis()
            val key = todayKey()
            if (key.isEmpty()) return
            val cal = java.util.Calendar.getInstance()
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
            val curfew = try { preferencesManager.curfewTime } catch (_: Exception) { "" }
            var inCurfew = false
            if (CURFEW_REGEX.matches(curfew)) {
                val parts = curfew.split(":")
                val ch = parts[0].toIntOrNull() ?: -1
                val cm = parts[1].toIntOrNull() ?: -1
                val pastMidnight = h < 5 && ch >= 12
                inCurfew = ch >= 0 && (h > ch || (h == ch && cal.get(java.util.Calendar.MINUTE) >= cm) || pastMidnight)
            }
            val inSchool = day != java.util.Calendar.SATURDAY && day != java.util.Calendar.SUNDAY && h in 8..15
            if (!inCurfew && !inSchool) return
            var pos = lastSentLatLon()
            val locAge = now - try { preferencesManager.lastLocAt } catch (_: Exception) { 0L }
            if (pos == null || locAge > 30 * 60_000L) {
                val fix = try { fetchLocation() } catch (_: Exception) { null }
                if (fix == null) {
                    try { preferencesManager.locFailCount = (preferencesManager.locFailCount + 1).coerceAtMost(100) } catch (_: Exception) { }
                    return
                }
                try { preferencesManager.locFailCount = 0 } catch (_: Exception) { }
                try { rememberSentLatLon(fix.latitude, fix.longitude) } catch (_: Exception) { }
                pos = Pair(fix.latitude, fix.longitude)
            }
            val curfewDone = try { preferencesManager.curfewAlertDate } catch (_: Exception) { "" }
            if (inCurfew && curfewDone != key) {
                val la = try { preferencesManager.homeLatE6 } catch (_: Exception) { Long.MIN_VALUE }
                val lo = try { preferencesManager.homeLonE6 } catch (_: Exception) { Long.MIN_VALUE }
                val rad = try { preferencesManager.homeRadiusM } catch (_: Exception) { 0 }
                if (la != Long.MIN_VALUE && lo != Long.MIN_VALUE && rad > 0) {
                    val dist = distanceMeters(la / 1_000_000.0, lo / 1_000_000.0, pos.first, pos.second)
                    if (dist >= 0f && dist > rad) {
                        try { preferencesManager.curfewAlertDate = key } catch (_: Exception) { }
                        sendToTelegram("🌙 Jam malam $curfew: perangkat masih di luar rumah (±${dist.toInt()} m).")
                        return
                    }
                }
            }
            val schoolDone = try { preferencesManager.schoolAlertDate } catch (_: Exception) { "" }
            if (inSchool && schoolDone != key) {
                val la = try { preferencesManager.schoolLatE6 } catch (_: Exception) { Long.MIN_VALUE }
                val lo = try { preferencesManager.schoolLonE6 } catch (_: Exception) { Long.MIN_VALUE }
                val rad = try { preferencesManager.schoolRadiusM } catch (_: Exception) { 0 }
                if (la != Long.MIN_VALUE && lo != Long.MIN_VALUE && rad > 0) {
                    val dist = distanceMeters(la / 1_000_000.0, lo / 1_000_000.0, pos.first, pos.second)
                    if (dist >= 0f && dist > rad) {
                        try { preferencesManager.schoolAlertDate = key } catch (_: Exception) { }
                        sendToTelegram("🏫 Jam sekolah: perangkat di luar area sekolah (±${dist.toInt()} m).")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun formatDate(timestamp: Long): String {
        return TimeFmt.full(timestamp)
    }

    private fun teardownJobs() {
        try {
            if (ringBusy.get()) restoreRingVolumeNow()
        } catch (_: Exception) {
        }
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        initialSyncJob?.cancel()
        patrolJob?.cancel()
        ringJob?.cancel()
        recordJob?.cancel()
        smsJob?.cancel()
        try {
            msgDropJob?.cancel()
        } catch (_: Exception) {
        }
        initialSyncRunning.set(false)
        idlePolls = 0
        commandBackoffUntil = 0L
        mediaBackoffUntil = 0L
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        watchdogJob?.cancel()
        videoWatchdog?.cancel()
        shotWatchdog?.cancel()
        cameraAttempt.set(0)
        videoAttempt.set(0)
        shotAttempt.set(0)
        shotBusy.set(false)
        cameraBusy.set(false)
        smsBusy.set(false)
        ringBusy.set(false)
        recordBusy.set(false)
        audioFlushBusy.set(false)
        photoFlushBusy.set(false)
        videoFlushBusy.set(false)
        try {
            cameraService.forceReset()
        } catch (_: Exception) {
        }
    }

    private fun restoreRingVolumeNow() {
        try {
            val previous = try { preferencesManager.ringPrevVolume } catch (_: Exception) { -1 }
            if (previous < 0) return
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, previous, 0)
            } catch (_: Exception) {
            }
            try {
                preferencesManager.clearRingStateSync()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun stopMonitoring() {
        teardownJobs()
        cachedSetupTap = null
        isRunning = false
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
            if (shouldAutoResume()) {
                MessageScheduler.scheduleBootRestart(this)
                MessageScheduler.scheduleWatchdog(this)
            }
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
            teardownJobs()
        } catch (_: Exception) {
        }
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
            flushMsgDropNotice()
        } catch (_: Exception) {
        }
        try {
            messageQueue.flushSync()
        } catch (_: Exception) {
        }
        try {
            teardownJobs()
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
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("Rate limited, photo delayed for retry.")
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
                        sendToTelegram("⚠️ Photo backlog full (offline). Oldest unsent discarded; newest capture skipped.")
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
                    if (cameraAttempt.get() != attempt) {
                        try {
                            photoFile.delete()
                        } catch (_: Exception) {
                        }
                        return@capturePhoto
                    }
                    if (!photoFile.exists() || photoFile.length() == 0L) {
                        cameraAttempt.incrementAndGet()
                        cameraBusy.set(false)
                        try {
                            wd.cancel()
                        } catch (_: Exception) {
                        }
                        deleteQuietly(photoFile)
                        if (reportResult) {
                            serviceScope.launch {
                                sendToTelegram("⚠️ Photo capture produced an empty file. Please try /photo again.")
                            }
                        }
                        return@capturePhoto
                    }
                    if (photoLuma(photoFile) < 12) {
                        cameraAttempt.incrementAndGet()
                        cameraBusy.set(false)
                        try {
                            wd.cancel()
                        } catch (_: Exception) {
                        }
                        deleteQuietly(photoFile)
                        if (reportResult) {
                            serviceScope.launch {
                                sendToTelegram("🌑 Photo too dark, skipped. Turn on a light or try /photo again.")
                            }
                        }
                        return@capturePhoto
                    }
                    val photoHashNow = photoHash(photoFile)
                    val prevPhotoHash = try { preferencesManager.lastPhotoHash } catch (_: Exception) { 0L }
                    if (photoHashNow != 0L && prevPhotoHash != 0L && hammingDistance(photoHashNow, prevPhotoHash) <= 6) {
                        cameraAttempt.incrementAndGet()
                        cameraBusy.set(false)
                        try {
                            wd.cancel()
                        } catch (_: Exception) {
                        }
                        deleteQuietly(photoFile)
                        if (reportResult) {
                            serviceScope.launch {
                                sendToTelegram("♻️ Photo same as the last one, skipped.")
                            }
                        }
                        return@capturePhoto
                    }
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
                                MediaSendOutcome.SENT -> {
                                    try { preferencesManager.setLastPhotoHashSync(photoHashNow) } catch (_: Exception) { }
                                    flushPendingPhotos()
                                }
                                MediaSendOutcome.DROPPED -> {
                                    prunePhotoCache()
                                    if (reportResult) {
                                        sendToTelegram("⚠️ Photo rejected by Telegram (400), file discarded.")
                                    }
                                }
                                MediaSendOutcome.KEPT -> {
                                    try { preferencesManager.setLastPhotoHashSync(photoHashNow) } catch (_: Exception) { }
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
    
    private fun hasScreenshotConsent(): Boolean {
        return try { preferencesManager.hasScreenshotConsent() } catch (_: Exception) { false }
    }

    private fun captureAndSendScreenshot(reportResult: Boolean = false) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("Auth rejected, screenshot delayed until the token is fixed in Setup.")
                }
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("Rate limited, screenshot delayed for retry.")
                }
            }
            return
        }
        if (!hasScreenshotConsent()) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Screen capture not granted. Open Setup and tap Capture Screen, then allow it once.")
                }
            }
            return
        }
        if (!shotBusy.compareAndSet(false, true)) {
            serviceScope.launch {
                if (reportResult) {
                    sendToTelegram("⚠️ Screenshot still running, please try /screenshot again in a moment.")
                }
            }
            return
        }
        val shotSeq = shotAttempt.incrementAndGet()
        shotWatchdog?.cancel()
        val wd = serviceScope.launch {
            delay(45_000)
            if (shotAttempt.get() == shotSeq && shotBusy.compareAndSet(true, false)) {
                android.util.Log.w("MonitoringService", "Screenshot watchdog: capture did not finish, flag reset")
                if (reportResult) {
                    sendToTelegram("⚠️ Screenshot timed out without a response. Please try /screenshot again.")
                }
            }
        }
        shotWatchdog = wd
        serviceScope.launch {
            try {
                ensureForegroundTypes()
                val shotFile = try {
                    takeScreenshotFrame()
                } catch (e: SecurityException) {
                    try { preferencesManager.clearScreenshotConsentSync() } catch (_: Exception) { }
                    try { wd.cancel() } catch (_: Exception) { }
                    shotBusy.set(false)
                    if (reportResult) {
                        sendToTelegram("⚠️ Screen capture was revoked. Open Setup and tap Capture Screen to grant it again.")
                    } else {
                        notifyPhotoSendFailure("screen capture revoked")
                    }
                    return@launch
                }
                if (shotAttempt.get() != shotSeq) {
                    try { shotFile?.delete() } catch (_: Exception) { }
                    shotBusy.set(false)
                    return@launch
                }
                try { wd.cancel() } catch (_: Exception) { }
                if (shotFile == null) {
                    shotBusy.set(false)
                    if (reportResult) {
                        sendToTelegram("⚠️ Screenshot failed. If the system revoked access, grant it again in Setup (Capture Screen).")
                    }
                    return@launch
                }
                activeShotFile = shotFile
                try {
                    when (sendPhotoFile(shotFile)) {
                        MediaSendOutcome.SENT -> flushPendingPhotos()
                        MediaSendOutcome.DROPPED -> {
                            prunePhotoCache()
                            if (reportResult) {
                                sendToTelegram("⚠️ Screenshot rejected by Telegram (400), file discarded.")
                            }
                        }
                        MediaSendOutcome.KEPT -> {
                            prunePhotoCache()
                            if (reportResult) {
                                sendToTelegram("⚠️ Screenshot captured but upload failed. File kept for retry.")
                            }
                        }
                    }
                } finally {
                    activeShotFile = null
                }
                shotBusy.set(false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                shotBusy.set(false)
                try { wd.cancel() } catch (_: Exception) { }
                android.util.Log.e("MonitoringService", "✗ Error in captureAndSendScreenshot: ${redactToken(e.message)}")
                serviceScope.launch {
                    if (reportResult) {
                        sendToTelegram("⚠️ Screenshot failed. If the system revoked access, grant it again in Setup (Capture Screen).")
                    }
                }
            }
        }
    }

    private suspend fun takeScreenshotFrame(): File? {
        val code = try { preferencesManager.screenshotResultCode } catch (_: Exception) { 0 }
        val uri = try { preferencesManager.screenshotData } catch (_: Exception) { "" }
        if (code == 0 || uri.isEmpty()) return null
        var projection: MediaProjection? = null
        var display: VirtualDisplay? = null
        var reader: ImageReader? = null
        try {
            val data = try {
                Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            } catch (_: Exception) {
                Intent.parseUri(uri, 0)
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            val metrics = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            try {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
            } catch (_: Exception) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(metrics)
            }
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            if (width <= 0 || height <= 0) return null
            val scale = if (width > 720) 720f / width else 1f
            width = (width * scale).toInt()
            height = (height * scale).toInt()
            val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            display = projection.createVirtualDisplay("redeye-shot", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
            var image: android.media.Image? = null
            for (i in 0 until 12) {
                try { image = reader.acquireLatestImage() } catch (_: Exception) { }
                if (image != null) break
                kotlinx.coroutines.delay(100)
            }
            val img = image ?: return null
            try {
                val planes = try { img.planes } catch (_: Exception) { null }
                if (planes == null || planes.isEmpty()) return null
                val plane = planes[0]
                val stride = try { plane.pixelStride } catch (_: Exception) { 0 }
                if (stride <= 0 || height <= 0) return null
                val rowWidth = plane.rowStride / stride
                if (rowWidth < width) return null
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (rowWidth == width) {
                    bmp.copyPixelsFromBuffer(plane.buffer)
                } else if (stride == 4) {
                    val src = plane.buffer
                    val rowBytes = width * 4
                    val packed = java.nio.ByteBuffer.allocateDirect(rowBytes * height)
                    for (y in 0 until height) {
                        src.position(y * plane.rowStride)
                        val slice = src.slice()
                        slice.limit(rowBytes)
                        packed.put(slice)
                    }
                    packed.flip()
                    bmp.copyPixelsFromBuffer(packed)
                } else {
                    try { bmp.recycle() } catch (_: Exception) { }
                    return null
                }
                val cropped = bmp
                val out = File(cacheDir, "screenshot_" + TimeFmt.fileStamp(System.currentTimeMillis()) + "_" + java.util.UUID.randomUUID() + ".jpg")
                try {
                    java.io.FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                } catch (_: Exception) {
                    deleteQuietly(out)
                    return null
                } finally {
                    try { cropped.recycle() } catch (_: Exception) { }
                }
                if (!out.exists() || out.length() == 0L) { deleteQuietly(out); return null }
                return out
            } finally {
                try { img.close() } catch (_: Exception) { }
            }
        } finally {
            try { display?.release() } catch (_: Exception) { }
            try { reader?.close() } catch (_: Exception) { }
            try { projection?.stop() } catch (_: Exception) { }
        }
    }

    private fun sanitizedCameraError(reason: String?): String {
        if (isCameraPolicyError(reason)) return "camera disabled by device policy (CAMERA_DISABLED)"
        val firstLine = (reason ?: "unknown error").lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty()) return "unknown error"
        val cleaned = firstLine.replace(CONNECT_HELPER_REGEX, "")
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
            if (last > 0 && now >= last && now - last < 30 * 60_000L) return
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
            if (last > 0 && now >= last && now - last < 30 * 60_000L) return
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
        if (hasLocationPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasScreenshotConsent()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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

    private fun hasSmsPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasCallLogPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_CALL_LOG
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasContactsPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasSendSmsPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.SEND_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryExempt(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            true
        }
    }

    private suspend fun checkPermissionHealth() {
        try {
            if (!preferencesManager.isConfigured()) return
            val notifEnabled = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
            val raw = permissionRawStates()
            val stored = try { preferencesManager.permSnapshot } catch (_: Exception) { -1 }
            val cur = permissionMask(raw, notifEnabled, stored)
            if (stored == -1) {
                try { preferencesManager.setPermSnapshotSync(cur) } catch (_: Exception) { }
                sendToTelegram(permInventoryText(raw, notifEnabled))
                return
            }
            val lost = ArrayList<String>()
            val regained = ArrayList<String>()
            for (i in raw.indices) {
                val was = (stored and (1 shl i)) != 0
                val now = (cur and (1 shl i)) != 0
                if (was && !now) lost.add(permLabels[i].lowercase(java.util.Locale.ROOT))
                else if (!was && now) regained.add(permLabels[i].lowercase(java.util.Locale.ROOT))
            }
            if (lost.isNotEmpty() || regained.isNotEmpty()) {
                try { preferencesManager.setPermSnapshotSync(cur) } catch (_: Exception) { }
                val locLabel = permLabels[6].lowercase(java.util.Locale.ROOT)
                val bgLabel = permLabels[7].lowercase(java.util.Locale.ROOT)
                val lostShown = if (lost.contains(locLabel)) lost.filter { it != bgLabel } else lost
                if (lostShown.isNotEmpty()) sendToTelegram("⚠️ Izin hilang: " + lostShown.joinToString(", ") + ". Monitoring buta sebagian — buka Setup di perangkat dan berikan lagi.")
                if (regained.isNotEmpty()) sendToTelegram("✅ Izin kembali: " + regained.joinToString(", ") + ".")
                return
            }
            val missing = missingPermLabels(cur)
            if (missing.isEmpty()) return
            val nowMs = System.currentTimeMillis()
            val last = try { preferencesManager.permHealthAlertAt } catch (_: Exception) { 0L }
            if (nowMs >= last && nowMs - last < 24 * 60 * 60_000L) return
            try { preferencesManager.setPermHealthAlertAtSync(nowMs) } catch (_: Exception) { }
            sendToTelegram("⚠️ Izin dicabut: " + missing.joinToString(", ") + ". Monitoring buta sebagian — buka Setup di perangkat dan berikan lagi.")
        } catch (_: Exception) {
        }
    }

    private val permLabels = arrayOf("Baca SMS", "Log panggilan", "Kontak", "Kirim SMS", "Kamera", "Mikrofon", "Lokasi", "Lokasi latar", "Akses notifikasi", "Baterai tanpa batas")

    private fun permissionRawStates(): BooleanArray {
        val notifEnabled = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
        return booleanArrayOf(
            hasSmsPermission(),
            hasCallLogPermission(),
            hasContactsPermission(),
            hasSendSmsPermission(),
            hasCameraPermission(),
            hasMicPermission(),
            hasLocationPermission(),
            hasBackgroundLocation(),
            !notifEnabled || isNotifForwarding(),
            isBatteryExempt()
        )
    }

    private fun permissionMask(raw: BooleanArray, notifEnabled: Boolean, stored: Int): Int {
        var mask = 0
        for (i in raw.indices) {
            var g = raw[i]
            if (i == 7 && !raw[6]) g = if (stored == -1) true else (stored and (1 shl 7)) != 0
            if (i == 8 && !notifEnabled) g = if (stored == -1) true else (stored and (1 shl 8)) != 0
            if (g) mask = mask or (1 shl i)
        }
        return mask
    }

    private fun missingPermLabels(mask: Int): ArrayList<String> {
        val out = ArrayList<String>()
        for (i in arrayOf(0, 1, 2, 3, 4, 5, 8, 9)) if ((mask and (1 shl i)) == 0) out.add(permLabels[i].lowercase(java.util.Locale.ROOT))
        if ((mask and (1 shl 6)) == 0) out.add(permLabels[6].lowercase(java.util.Locale.ROOT))
        else if ((mask and (1 shl 7)) == 0) out.add(permLabels[7].lowercase(java.util.Locale.ROOT))
        return out
    }

    private fun permInventoryText(raw: BooleanArray, notifEnabled: Boolean): String {
        return buildString {
            appendLine("🛡️ <b>Izin</b>")
            for (i in raw.indices) {
                appendLine("${if (raw[i]) "✅" else "❌"} ${permLabels[i]}")
            }
            if (!notifEnabled) appendLine("Penerusan notif dimatikan (/notif off).")
            appendLine("Cabut/pasang ulang? Buka Setup di perangkat.")
        }
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
            reason.contains("disabled by policy", ignoreCase = true)
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

    private fun pendingVideoCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4") && file != activeVideoFile
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun pendingPhotoCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg") && file != activePhotoFile && file != activeShotFile
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    @Volatile
    private var lastLoopRestartAt = 0L

    private fun restartAllLoops(fromWatchdog: Boolean = false): Boolean {
        val nowRestart = android.os.SystemClock.elapsedRealtime()
        if (!fromWatchdog && nowRestart - lastLoopRestartAt < 10_000L) return false
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
        try {
            patrolJob?.cancel()
        } catch (_: Exception) {
        }
        idlePolls = 0
        refreshCreds()
        refreshLoopConfig()
        startPeriodicLoops()
        startCommandPolling()
        startLoopWatchdog()
        try {
            synchronized(initialSyncLock) {
                try {
                    initialSyncJob?.cancel()
                } catch (_: Exception) {
                }
                initialSyncRunning.set(false)
                if (!preferencesManager.initialSyncDone) {
                    if (initialSyncRunning.compareAndSet(false, true)) {
                        initialSyncJob = serviceScope.launch {
                            val self = coroutineContext[Job]
                            try {
                                sendInitialData()
                            } catch (_: Exception) {
                            } finally {
                                if (initialSyncJob === self) initialSyncRunning.set(false)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return true
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
                    val nowBeat = android.os.SystemClock.elapsedRealtime()
                    val monitorStuck = monitoringJob?.isActive == true && monitorBeatAt > 0L && monitorCycleMs in 1L..1440 * 60_000L && nowBeat - monitorBeatAt > monitorCycleMs + 10 * 60_000L
                    val cameraStuck = cameraJob?.isActive == true && cameraBeatAt > 0L && cameraCycleMs in 1L..60 * 60_000L && nowBeat - cameraBeatAt > cameraCycleMs + 10 * 60_000L
                    if (monitoringJob?.isActive != true || cameraJob?.isActive != true || commandJob?.isActive != true || patrolJob?.isActive != true || initialStuck || monitorStuck || cameraStuck) {
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

    private fun finishedRingText(): String {
        return "\uD83D\uDD14 Ring finished."
    }

private suspend fun ringDevice(seconds: Int) {
        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val stream = android.media.AudioManager.STREAM_ALARM
        val previous = try {
            audioManager.getStreamVolume(stream)
        } catch (_: Exception) {
            -1
        }
        if (previous < 0) {
            sendToTelegram("⚠️ Volume tak terbaca, /ring dibatalkan agar tak macet maksimal.")
            return
        }
        try {
            preferencesManager.setRingStateSync(previous, System.currentTimeMillis())
        } catch (_: Exception) {
        }
        var player: android.media.MediaPlayer? = null
        var ringtone: android.media.Ringtone? = null
        try {
            try {
                audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
            } catch (_: Exception) {
            }
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            val ringEndAt = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
            var usePlayer = false
            try {
                player = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(applicationContext, uri)
                    isLooping = false
                    setOnCompletionListener { mp ->
                        try {
                            if (android.os.SystemClock.elapsedRealtime() < ringEndAt) mp.start()
                        } catch (_: Exception) {
                        }
                    }
                    prepare()
                    start()
                }
                usePlayer = true
            } catch (_: Exception) {
                try {
                    player?.release()
                } catch (_: Exception) {
                }
                player = null
                usePlayer = false
            }
            if (!usePlayer) {
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
                val ringEndFallback = android.os.SystemClock.elapsedRealtime() + seconds * 1000L
                while (android.os.SystemClock.elapsedRealtime() < ringEndFallback) {
                    try {
                        if (ringtone?.isPlaying != true) ringtone?.play()
                    } catch (_: Exception) {
                    }
                    try {
                        kotlinx.coroutines.delay(1_000L)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    currentCoroutineContext().ensureActive()
                }
                try {
                    sendToTelegram(finishedRingText())
                } catch (_: Exception) {
                }
                return
            }
            sendToTelegram("\uD83D\uDD14 Ringing for $seconds s\u2026")
            kotlinx.coroutines.delay(seconds * 1000L)
            currentCoroutineContext().ensureActive()
            sendToTelegram(finishedRingText())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F Ring failed.")
        } finally {
            try {
                player?.setOnCompletionListener(null)
            } catch (_: Exception) {
            }
            try {
                player?.stop()
            } catch (_: Exception) {
            }
            try {
                player?.release()
            } catch (_: Exception) {
            }
            try {
                ringtone?.stop()
            } catch (_: Exception) {
            }
            try {
                if (previous >= 0) audioManager.setStreamVolume(stream, previous, 0)
            } catch (_: Exception) {
            }
            try {
                preferencesManager.clearRingStateSync()
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun recordAndSendAudio(seconds: Int) {
        if (!hasMicPermission()) {
            sendToTelegram("⚠️ Microphone permission missing. Open Setup and grant Microphone permission.")
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            sendToTelegram("Rate limited, audio delayed for retry.")
            return
        }
        ensureForegroundTypes()
        val audioFile = File(cacheDir, "audio_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID() + ".m4a")
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
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            if (audioOutcome == MediaSendOutcome.SENT) {
                try {
                    messageQueue.addMessage("\uD83C\uDF99\uFE0F Audio sent (${seconds}s).")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            } else if (audioOutcome == MediaSendOutcome.KEPT) {
                keepForRetry = true
                try {
                    messageQueue.addMessage("\u26A0\uFE0F Audio recorded but send failed. File kept for automatic retry.")
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
            if (!audioFile.exists() || audioFile.length() == 0L) {
                deleteQuietly(audioFile)
                return MediaSendOutcome.DROPPED
            }
            if (NetworkUtils.isAuthBlocked(preferencesManager)) return MediaSendOutcome.KEPT
            if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return MediaSendOutcome.KEPT
            if (NetworkUtils.rateLimitedRemainMs() > 0L) return MediaSendOutcome.KEPT
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
            if (response.code() == 429) {
                val waitSecs = NetworkUtils.parseRetryAfter(try { response.errorBody()?.string() } catch (_: Exception) { null }).coerceIn(1L, 3600L)
                mediaBackoffUntil = android.os.SystemClock.elapsedRealtime() + waitSecs * 1000L
                NetworkUtils.noteRateLimited(waitSecs)
                try {
                    MessageScheduler.scheduleRateLimited(this, waitSecs * 1000L)
                } catch (_: Exception) {
                }
                return MediaSendOutcome.KEPT
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
                if (adoptMigratedChat(audioErr, chatId)) return MediaSendOutcome.KEPT
                if (NetworkUtils.isChatMissing(audioErr)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    return MediaSendOutcome.KEPT
                }
                if (NetworkUtils.isRightsLimited(audioErr)) {
                    android.util.Log.w("MonitoringService", "Audio rights limited (400), keeping file without auth block")
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

    private fun recordAndSendVideo(seconds: Int) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("Auth rejected, video delayed until the token is fixed in Setup.")
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("Rate limited, video delayed for retry.")
            }
            return
        }
        ensureForegroundTypes()
        if (!hasCameraPermission()) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("⚠️ Camera permission missing. Open Setup and grant Camera permission.")
            }
            return
        }
        if (!hasMicPermission()) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("⚠️ Microphone permission missing. Open Setup and grant Microphone permission.")
            }
            return
        }
        if (isCameraDisabledByPolicy()) {
            recordBusy.set(false)
            autoPausePhotosOnPolicyBlock()
            serviceScope.launch {
                sendToTelegram("⚠️ Video blocked by device policy (CAMERA_DISABLED). Automatic photos paused for 120 min.")
            }
            return
        }
        if (pendingVideoCount() >= MAX_VIDEO_KEPT) {
            try {
                pruneVideoCache(MAX_VIDEO_KEPT - 1)
            } catch (_: Exception) {
            }
            if (pendingVideoCount() >= MAX_VIDEO_KEPT) {
                recordBusy.set(false)
                serviceScope.launch {
                    sendToTelegram("⚠️ Video backlog full (offline). Oldest unsent discarded; newest recording skipped.")
                }
                return
            }
        }
        val attempt = videoAttempt.incrementAndGet()
        videoWatchdog?.cancel()
        val wd = serviceScope.launch {
            delay(seconds * 1000L + 120_000L)
            if (videoAttempt.get() == attempt && recordBusy.compareAndSet(true, false)) {
                videoAttempt.incrementAndGet()
                try { cameraService.forceReset() } catch (_: Exception) { }
                android.util.Log.w("MonitoringService", "Video watchdog: recording did not finish, flag reset")
                sendToTelegram("⚠️ Video recording timed out without a response. Please try /recordvideo again.")
            }
        }
        videoWatchdog = wd
        try {
            cameraService.captureVideo(
                durationMs = seconds * 1000L,
                lensFacing = selectedLensFacing(),
                onVideoTaken = { videoFile ->
                    if (videoAttempt.get() != attempt) {
                        try { videoFile.delete() } catch (_: Exception) { }
                        return@captureVideo
                    }
                    videoAttempt.incrementAndGet()
                    try { wd.cancel() } catch (_: Exception) { }
                    activeVideoFile = videoFile
                    serviceScope.launch {
                        try {
                            if (videoFile.length() > 48L * 1024L * 1024L) {
                                deleteQuietly(videoFile)
                                sendToTelegram("⚠️ Video too large (>48 MB). Try /recordvideo with fewer seconds.")
                            } else {
                                when (sendVideoFile(videoFile)) {
                                    MediaSendOutcome.SENT -> {
                                        sendToTelegram("🎥 Video sent (${seconds}s).")
                                        flushPendingVideos()
                                    }
                                    MediaSendOutcome.DROPPED -> {
                                        try { flushPendingVideos() } catch (_: Exception) { }
                                        sendToTelegram("⚠️ Video rejected by Telegram (400), file discarded.")
                                    }
                                    MediaSendOutcome.KEPT -> {
                                        try { flushPendingVideos() } catch (_: Exception) { }
                                        sendToTelegram("⚠️ Video recorded but send failed. File kept for automatic retry.")
                                    }
                                }
                            }
                        } finally {
                            activeVideoFile = null
                            recordBusy.set(false)
                        }
                    }
                },
                onError = { e ->
                    if (videoAttempt.get() != attempt) return@captureVideo
                    videoAttempt.incrementAndGet()
                    try { wd.cancel() } catch (_: Exception) { }
                    recordBusy.set(false)
                    serviceScope.launch {
                        sendToTelegram("⚠️ Video record failed: " + sanitizedCameraError(e.message))
                    }
                },
                timeoutMs = seconds * 1000L + 60_000L
            )
        } catch (e: Exception) {
            videoAttempt.incrementAndGet()
            try { wd.cancel() } catch (_: Exception) { }
            recordBusy.set(false)
            android.util.Log.e("MonitoringService", "✗ Error in recordAndSendVideo: ${redactToken(e.message)}")
            serviceScope.launch {
                sendToTelegram("⚠️ Video record failed.")
            }
        }
    }

    private suspend fun sendVideoFile(videoFile: File): MediaSendOutcome {
        try {
            if (!videoFile.exists() || videoFile.length() == 0L) {
                deleteQuietly(videoFile)
                return MediaSendOutcome.DROPPED
            }
            if (NetworkUtils.isAuthBlocked(preferencesManager)) return MediaSendOutcome.KEPT
            if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return MediaSendOutcome.KEPT
            if (NetworkUtils.rateLimitedRemainMs() > 0L) return MediaSendOutcome.KEPT
            if (!hasNetwork()) {
                android.util.Log.w("MonitoringService", "No network - video saved for later")
                return MediaSendOutcome.KEPT
            }
            val (botToken, chatId) = sendCreds()
            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.e("MonitoringService", "Bot credentials missing")
                return MediaSendOutcome.KEPT
            }
            val requestFile = videoFile.asRequestBody("video/mp4".toMediaTypeOrNull())
            val videoPart = MultipartBody.Part.createFormData("video", videoFile.name, requestFile)
            val chatIdBody = chatId.toRequestBody("text/plain".toMediaTypeOrNull())
            val caption = ("🎥 " + TimeFmt.full(System.currentTimeMillis())).toRequestBody("text/plain".toMediaTypeOrNull())
            val url = "https://api.telegram.org/bot$botToken/sendVideo"
            val response = TelegramMediaClient.api.sendVideo(url, chatIdBody, caption, videoPart)
            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "✓ Video sent successfully!")
                deleteQuietly(videoFile)
                return MediaSendOutcome.SENT
            }
            val errorFull = try {
                response.errorBody()?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            val errorBody = errorFull.take(200)
            if (response.code() == 429) {
                val waitSecs = NetworkUtils.parseRetryAfter(errorFull).coerceIn(1L, 3600L)
                mediaBackoffUntil = android.os.SystemClock.elapsedRealtime() + waitSecs * 1000L
                NetworkUtils.noteRateLimited(waitSecs)
                android.util.Log.w("MonitoringService", "Video rate limited, backing off ${waitSecs}s without blocking")
                try {
                    MessageScheduler.scheduleRateLimited(this, waitSecs * 1000L)
                } catch (_: Exception) {
                }
                return MediaSendOutcome.KEPT
            }
            if (response.code() == 401 || response.code() == 403) {
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                if (adoptMigratedChat(errorFull, chatId)) return MediaSendOutcome.KEPT
                if (NetworkUtils.isChatMissing(errorFull)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    return MediaSendOutcome.KEPT
                }
                if (NetworkUtils.isRightsLimited(errorFull)) {
                    android.util.Log.w("MonitoringService", "Video rights limited (400), keeping file without auth block")
                    return MediaSendOutcome.KEPT
                }
                android.util.Log.w("MonitoringService", "Video rejected (400), dropping file")
                deleteQuietly(videoFile)
                return MediaSendOutcome.DROPPED
            }
            return MediaSendOutcome.KEPT
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending video to Telegram: ${redactToken(e.message)}")
            return MediaSendOutcome.KEPT
        }
    }

    private suspend fun flushPendingVideos(max: Int = 3) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
        if (!videoFlushBusy.compareAndSet(false, true)) return
        try {
            if (!hasNetwork()) return
            val pending = try {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4")
                }?.sortedBy { it.lastModified() }?.take(max) ?: return
            } catch (e: Exception) {
                return
            }
            val now = System.currentTimeMillis()
            var droppedVideos = 0
            var videoProgress = false
            for (file in pending) {
                if (file == activeVideoFile) continue
                if (now - file.lastModified() < 10_000L) continue
                if (!file.exists() || file.length() == 0L) {
                    deleteQuietly(file)
                    continue
                }
                val videoOutcome = sendVideoFile(file)
                if (videoOutcome == MediaSendOutcome.KEPT) break
                videoProgress = true
                if (videoOutcome == MediaSendOutcome.DROPPED) droppedVideos++
                kotlinx.coroutines.delay(500)
            }
            if (droppedVideos > 0) {
                try {
                    messageQueue.addMessage("⚠️ $droppedVideos video file(s) rejected by Telegram (400), discarded.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } finally {
            videoFlushBusy.set(false)
            if (videoProgress || droppedVideos > 0) pruneVideoCache(MAX_VIDEO_KEPT)
        }
    }

    private fun pruneVideoCache(maxKept: Int) {
        try {
            val files = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4")
            }?.sortedBy { it.lastModified() }?.filter { it != activeVideoFile } ?: return
            val dropped = files.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("⚠️ ${dropped.size} video file(s) dropped (cache full).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun flushPendingAudio(max: Int = 5) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
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
            var droppedAudio = 0
            var audioProgress = false
            for (file in pending) {
                if (file == activeAudioFile) continue
                if (now - file.lastModified() < 10_000L) continue
                if (!file.exists() || file.length() == 0L) {
                    deleteQuietly(file)
                    continue
                }
                val audioOutcome = sendAudioFile(file)
                if (audioOutcome == MediaSendOutcome.KEPT) break
                audioProgress = true
                if (audioOutcome == MediaSendOutcome.DROPPED) droppedAudio++
                kotlinx.coroutines.delay(500)
            }
            if (droppedAudio > 0) {
                try {
                    messageQueue.addMessage("\u26A0\uFE0F $droppedAudio audio file(s) rejected by Telegram (400), discarded.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } finally {
            audioFlushBusy.set(false)
            if (audioProgress || droppedAudio > 0) pruneAudioCache(MAX_AUDIO_KEPT)
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
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} audio file(s) dropped (cache full).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun contactNumberMatches(haveRaw: String, wantDigits: String): Boolean {
        return try {
            val have = haveRaw.filter { it.isDigit() }
            if (have.isEmpty() || wantDigits.isEmpty()) return false
            if (have == wantDigits) return true
            if (wantDigits.length < 7 || have.length < 7) return false
            have.endsWith(wantDigits.takeLast(7)) || wantDigits.endsWith(have.takeLast(7))
        } catch (_: Exception) {
            false
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
            val qdigits = query.filter { it.isDigit() }
            val useTail = qdigits.length >= 7
            val numArg = if (useTail) "%" + qdigits.takeLast(7) + "%" else "%$escaped%"
            val cursor = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val bundle = android.os.Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\' OR " + android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ? ESCAPE '\\'")
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("%$escaped%", numArg))
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")
                        putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, if (useTail) 50 else 10)
                    }
                    contentResolver.query(uri, projection, bundle, null)
                } else {
                    contentResolver.query(
                        uri,
                        projection,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? ESCAPE '\\' OR " + android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ? ESCAPE '\\'",
                        arrayOf("%$escaped%", numArg),
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
                            val nm = c.getString(nameIdx) ?: ""
                            val nb = c.getString(numIdx) ?: ""
                            if (useTail && !(nm.contains(query, ignoreCase = true) || contactNumberMatches(nb, qdigits))) continue
                            out.add(nm to nb)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Volatile
    private var appsCacheAt = 0L
    @Volatile
    private var appsCache: List<String> = emptyList()

    private fun listLaunchableApps(limit: Int): List<String> {
        val cached = appsCache
        if (cached.isNotEmpty() && android.os.SystemClock.elapsedRealtime() - appsCacheAt < 10 * 60_000L) return cached.take(limit)
        val fresh = listLaunchableAppsFresh()
        if (fresh.isNotEmpty()) {
            appsCache = fresh
            appsCacheAt = android.os.SystemClock.elapsedRealtime()
            return fresh.take(limit)
        }
        return emptyList()
    }

    private fun listLaunchableAppsFresh(): List<String> {
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
            }.distinctBy { it.second }.sortedBy { it.first.lowercase(java.util.Locale.ROOT) }.map { it.first }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class CacheStats(val bytes: Long, val photos: Int, val audios: Int, val videos: Int)

    private fun cacheStats(): CacheStats {
        var bytes = 0L
        var photos = 0
        var audios = 0
        var videos = 0
        try {
            val top = try { cacheDir.listFiles() } catch (_: Exception) { null } ?: emptyArray()
            for (file in top) {
                try {
                    if (file.isFile) {
                        bytes += file.length()
                        if ((file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg")) photos++
                        if (file.name.startsWith("audio_") && file.name.endsWith(".m4a")) audios++
                        if (file.name.startsWith("video_") && file.name.endsWith(".mp4")) videos++
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return CacheStats(bytes, photos, audios, videos)
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
            if (!photoFile.exists() || photoFile.length() == 0L) {
                deleteQuietly(photoFile)
                return MediaSendOutcome.DROPPED
            }
            if (NetworkUtils.isAuthBlocked(preferencesManager)) return MediaSendOutcome.KEPT
            if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return MediaSendOutcome.KEPT
            if (NetworkUtils.rateLimitedRemainMs() > 0L) return MediaSendOutcome.KEPT
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
            val errorFull = try {
                response.errorBody()?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            val errorBody = errorFull.take(200)
            if (response.code() == 429) {
                val waitSecs = NetworkUtils.parseRetryAfter(errorFull).coerceIn(1L, 3600L)
                mediaBackoffUntil = android.os.SystemClock.elapsedRealtime() + waitSecs * 1000L
                NetworkUtils.noteRateLimited(waitSecs)
                android.util.Log.w("MonitoringService", "Photo rate limited, backing off ${waitSecs}s without blocking")
                try {
                    MessageScheduler.scheduleRateLimited(this, waitSecs * 1000L)
                } catch (_: Exception) {
                }
                return MediaSendOutcome.KEPT
            } else if (response.code() == 401 || response.code() == 403) {
                android.util.Log.e("MonitoringService", "Photo auth rejected (${response.code()}), keeping file for retry")
                try {
                    preferencesManager.credentialError = response.code().toString()
                    preferencesManager.credentialErrorAt = System.currentTimeMillis()
                } catch (_: Exception) {
                }
            } else if (response.code() == 400) {
                if (adoptMigratedChat(errorFull, chatId)) return MediaSendOutcome.KEPT
                if (NetworkUtils.isChatMissing(errorFull)) {
                    try {
                        preferencesManager.credentialError = response.code().toString()
                        preferencesManager.credentialErrorAt = System.currentTimeMillis()
                    } catch (_: Exception) {
                    }
                    return MediaSendOutcome.KEPT
                }
                if (NetworkUtils.isRightsLimited(errorFull)) {
                    android.util.Log.w("MonitoringService", "Photo rights limited (400), keeping file without auth block")
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
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
        if (!photoFlushBusy.compareAndSet(false, true)) return
        try {
            val pending = try {
                cacheDir.listFiles { file ->
                    file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg")
                }?.sortedBy { it.lastModified() }?.take(max) ?: return
            } catch (e: Exception) {
                return
            }
            val now = System.currentTimeMillis()
            var droppedPhotos = 0
            var photoProgress = false
            for (file in pending) {
                if (file == activePhotoFile || file == activeShotFile) continue
                if (now - file.lastModified() < 10_000L) continue
                if (!file.exists() || file.length() == 0L) {
                    deleteQuietly(file)
                    continue
                }
                val photoOutcome = sendPhotoFile(file)
                if (photoOutcome == MediaSendOutcome.KEPT) break
                photoProgress = true
                if (photoOutcome == MediaSendOutcome.DROPPED) droppedPhotos++
                kotlinx.coroutines.delay(500)
            }
            if (droppedPhotos > 0) {
                try {
                    messageQueue.addMessage("\u26A0\uFE0F $droppedPhotos photo file(s) rejected by Telegram (400), discarded.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
            if (photoProgress || droppedPhotos > 0) prunePhotoCache()
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
                file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() }?.filter { it != activePhotoFile && it != activeShotFile } ?: return
            val dropped = photos.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} photo file(s) dropped (cache full).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache: ${redactToken(e.message)}")
        }
    }
}
