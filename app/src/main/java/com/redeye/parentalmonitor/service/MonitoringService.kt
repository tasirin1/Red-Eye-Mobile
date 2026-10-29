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
import android.os.SystemClock
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.redeye.parentalmonitor.ParentalMonitorApp
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMediaClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.network.TelegramPollClient
import com.redeye.parentalmonitor.utils.AccessPin
import com.redeye.parentalmonitor.utils.AppWatch
import com.redeye.parentalmonitor.utils.CommandSuggest
import com.redeye.parentalmonitor.utils.ContactsPaging
import com.redeye.parentalmonitor.utils.CrashReporter
import com.redeye.parentalmonitor.utils.CurfewLogic
import com.redeye.parentalmonitor.utils.DailyFacts
import com.redeye.parentalmonitor.utils.DigestHighlights
import com.redeye.parentalmonitor.utils.DigestSchedule
import com.redeye.parentalmonitor.utils.FgsTypes
import com.redeye.parentalmonitor.utils.GeofenceLogic
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.IntentParser
import com.redeye.parentalmonitor.utils.Liveness
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.OwnerAccess
import com.redeye.parentalmonitor.repository.CallLogRepository
import com.redeye.parentalmonitor.repository.SmsRepository
import com.redeye.parentalmonitor.utils.NetworkUtils
import com.redeye.parentalmonitor.utils.PatternBaseline
import com.redeye.parentalmonitor.utils.PermReport
import com.redeye.parentalmonitor.utils.PhotoFingerprint
import com.redeye.parentalmonitor.utils.ReportFormat
import com.redeye.parentalmonitor.utils.RingVolume
import com.redeye.parentalmonitor.utils.RestoreCode
import com.redeye.parentalmonitor.utils.RestoreRules
import com.redeye.parentalmonitor.utils.SettingsBackup
import com.redeye.parentalmonitor.utils.Sha256Hex
import com.redeye.parentalmonitor.utils.Sparkline
import com.redeye.parentalmonitor.utils.SmsCommandParser
import com.redeye.parentalmonitor.utils.SmsNumbers
import com.redeye.parentalmonitor.utils.TelegramSendPolicy
import com.redeye.parentalmonitor.utils.TimeFmt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import android.os.BatteryManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import com.redeye.parentalmonitor.utils.ServiceStart

class MonitoringService : Service() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var smsRepository: SmsRepository
    private lateinit var callLogRepository: CallLogRepository
    private lateinit var messageQueue: MessageQueue
    private lateinit var cameraService: CameraService
    private val batteryManager: BatteryManager? by lazy {
        try { getSystemService(BATTERY_SERVICE) as? BatteryManager } catch (_: Exception) { null }
    }
    private val usageStatsManager: android.app.usage.UsageStatsManager? by lazy {
        try { getSystemService(USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager } catch (_: Exception) { null }
    }
    private val locationManagerSvc: android.location.LocationManager? by lazy {
        try { getSystemService(LOCATION_SERVICE) as? android.location.LocationManager } catch (_: Exception) { null }
    }
    private val connectivityManager: android.net.ConnectivityManager? by lazy {
        try { getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager } catch (_: Exception) { null }
    }
    private val audioManagerSvc: android.media.AudioManager? by lazy {
        try { getSystemService(AUDIO_SERVICE) as? android.media.AudioManager } catch (_: Exception) { null }
    }
    private val powerManagerSvc: android.os.PowerManager? by lazy {
        try { getSystemService(POWER_SERVICE) as? android.os.PowerManager } catch (_: Exception) { null }
    }
    private val devicePolicySvc: android.app.admin.DevicePolicyManager? by lazy {
        try { getSystemService(DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager } catch (_: Exception) { null }
    }
    private val mediaProjectionSvc: MediaProjectionManager? by lazy {
        try { getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager } catch (_: Exception) { null }
    }
    private val windowManagerSvc: android.view.WindowManager? by lazy {
        try { getSystemService(WINDOW_SERVICE) as? android.view.WindowManager } catch (_: Exception) { null }
    }

    private val scopeErrorHandler = CoroutineExceptionHandler { _, error ->
        try {
            CrashReporter.saveNow(this, error)
        } catch (_: Exception) {
        }
        android.util.Log.e("MonitoringService", "Background failure recorded: ${redactToken(error.message)}")
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scopeErrorHandler)
    private val commandLock = kotlinx.coroutines.sync.Mutex()
    private val controlLock = kotlinx.coroutines.sync.Mutex()
    private var lastLoginJob: kotlinx.coroutines.Job? = null
    private var lastLoginSender: String = ""
    private val adoptionGateLock = Any()
    private val adoptionWaiters = java.util.TreeSet<Pair<Long, String>>(compareBy { it.first })
    private val cameraBusy = AtomicBoolean(false)
    private val smsBusy = AtomicBoolean(false)
    private val ringBusy = AtomicBoolean(false)
    private val recordBusy = AtomicBoolean(false)
    private val recorderActive = AtomicBoolean(false)
    @Volatile
    private var recordingKind = ""
    private val audioFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val photoFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val photoSendMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile
    private var curfewFetchFailAt = 0L
    private var trailMirrorRaw: String? = null
    private var trailMirrorPts: List<String>? = null
    private val videoFlushBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activeMediaFiles = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<File, Boolean>()
    )
    @Volatile
    private var ringJob: Job? = null
    @Volatile
    private var recordJob: Job? = null
    @Volatile
    private var smsJob: Job? = null
    @Volatile
    private var photoWatchdog: Job? = null
    @Volatile
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
    private var commandBeatAt = 0L
    @Volatile
    private var commandCycleMs = 0L
    @Volatile
    private var patrolBeatAt = 0L
    @Volatile
    private var patrolCycleMs = 0L
    @Volatile
    private var lastGeofenceCheckAt = 0L
    @Volatile
    private var idleDataCycles = 0
    @Volatile
    private var lowPowerNotified = false
    @Volatile
    private var serviceStartAt = 0L
    @Volatile
    private var serviceStartElapsed = 0L
    private val cameraAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private val videoAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var videoWatchdog: Job? = null
    @Volatile
    private var videoWakeLock: android.os.PowerManager.WakeLock? = null
    @Volatile
    private var lastDarkPhotoAlertAt = 0L
    private val shotBusy = AtomicBoolean(false)
    @Volatile
    private var shotWatchdog: Job? = null
    private val shotAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    private var appliedFgsTypes = 0
    @Volatile
    private var rejectedFgsTypes = 0
    @Volatile
    private var rejectedFgsAt = 0L
    @Volatile
    private var cameraWakeAt = 0L
    @Volatile
    private var cameraWakeNotifiedAt = 0L
    @Volatile
    private var cameraWakeFails = 0
    @Volatile
    private var coreInitialized = false
    private val shotSessionLock = Any()
    private var shotDisplay: VirtualDisplay? = null
    private var shotReader: ImageReader? = null
    private var shotProjection: MediaProjection? = null
    private var shotStopCallback: MediaProjection.Callback? = null
    private var shotCallbackProjection: MediaProjection? = null
    private var shotWidth = 0
    private var shotHeight = 0

    private enum class MediaSendOutcome {
        SENT, KEPT, DROPPED
    }
    @Volatile
    private var monitoringJob: Job? = null
    @Volatile
    private var cameraJob: Job? = null
    @Volatile
    private var commandJob: Job? = null
    @Volatile
    private var initialSyncJob: Job? = null
    @Volatile
    private var patrolJob: Job? = null
    private val initialSyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val initialSyncGen = java.util.concurrent.atomic.AtomicInteger(0)
    private val initialSyncLock = Any()
    @Volatile
    private var initialSyncRetryAt = 0L
    @Volatile
    private var initialStartSent = false
    @Volatile
    private var initialPartialNotified = false
    @Volatile
    private var cachedBotToken = ""
    @Volatile
    private var cachedChatId = ""
    @Volatile
    private var cachedOwnerId = 0L
    @Volatile
    private var cachedAccessPinActive = false
    private class PendingRestore(
        var stage: Int,
        var blob: String? = null,
        var at: Long = SystemClock.elapsedRealtime(),
        var attempts: Int = 0,
        var data: SettingsBackup.BackupData? = null
    )
    @Volatile
    private var pendingRestore: PendingRestore? = null
    @Volatile
    private var ownerEpoch = 0
    @Volatile
    private var skipUpdateOffsetOnce = false
    private val offsetPersistLock = Any()
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
    private var cachedVehicleEnabled = true
    @Volatile
    private var cachedDataSaver = false
    @Volatile
    private var dataSaverCheckedAtElapsed = 0L
    @Volatile
    private var dataSaverDeferResult = false
    private var vehiclePrevFix: Triple<Double, Double, Long>? = null
    private var vehicleFastSamples = 0
    @Volatile
    private var cachedSetupTap: android.app.PendingIntent? = null
    private val handledUpdateIds = java.util.Collections.synchronizedSet(LinkedHashSet<Long>())
    private val handledCallbackIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun <T> markSeen(set: MutableSet<T>, id: T): Boolean {
        return synchronized(set) {
            val seen = !set.add(id)
            while (set.size > 500) {
                try {
                    val it = set.iterator()
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
    }

    private fun forgetSeen(updateId: Long) {
        synchronized(handledUpdateIds) { handledUpdateIds.remove(updateId) }
    }

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_LIVENESS_TEST = "LIVENESS_SELF_TEST"
        const val ACTION_REFRESH_FGS = "REFRESH_FGS"
        const val ACTION_ADMIN_ALERT = "ADMIN_ALERT_ATTEMPT"
        const val ACTION_UNLOCK_FAILED = "UNLOCK_FAILED"
        const val EXTRA_UNLOCK_ATTEMPTS = "unlock_attempts"
        const val ACTION_SMS_RECEIVED = "SMS_RECEIVED"
        const val ACTION_CALL_STATE = "CALL_STATE_IDLE"
        const val ACTION_AFTER_SHOT_GRANT = "AFTER_SHOT_GRANT"
        private const val SELF_TEST_ALARM_DELAY_MS = 45_000L
        private val SMS_NUMBER_REGEX = Regex("^\\+?[0-9]{7,15}$")
        private val CMD_SPLIT_REGEX = "\\s+".toRegex()
        private val WATCH_SPLIT_REGEX = "[,;\\s]+".toRegex()
        private val OTP_CODE_REGEX = Regex("\\b\\d{4,8}\\b")
        private val OTP_KEYWORDS = arrayOf("otp", "kode", "verifikasi", "verification", "otentikasi", "aktivasi", "konfirmasi", "token", "password", "kata sandi")
        private val PROMO_KEYWORDS = arrayOf("promo", "diskon", "discount", "cashback", "bonus", "hadiah", "undian", "pinjaman", "kredit", "cicilan", "belanja", "sale", "gratis", "klaim", "voucher", "kupon", "poin", "tukar")
        private val CONNECT_HELPER_REGEX = Regex("(?i)connectHelper:\\d+:\\s*")
        private const val RESTORE_TTL_MS = 10 * 60_000L
        private const val RESTORE_CANCEL_WINDOW_MS = 60_000L
        private const val RESTORE_MAX_PIN_TRIES = 3
        private const val RESTORE_STAGE_DOC = 1
        private const val RESTORE_STAGE_PIN = 2
        private const val RESTORE_STAGE_CONFIRM = 3
        private val AUDIT_COMMANDS = setOf("/pulihkan", "/stop", "/sms", "/lock", "/hilang", "/sembuh", "/restart", "/clearqueue", "/logout")
        private val MUTATING_COMMANDS = setOf("/lock", "/ring", "/sms", "/smsconfirm", "/record", "/recordvideo", "/stop", "/resume", "/pause", "/photointerval", "/syncinterval", "/camera", "/notif", "/restart", "/flush", "/clearqueue", "/patrol", "/patrolinterval", "/heartbeat", "/night", "/watch", "/home", "/school", "/digest", "/darurat", "/jampulang", "/minggu", "/watchnomor", "/jejak", "/waspada", "/hilang", "/jemput", "/sembuh", "/kendara", "/hematdata", "/izinshot", "/pulihkan", "/bersihkan", "/logout", "/appalert", "/lindungi", "/pantauapp")
        private val DEFAULT_APP_WATCH_PACKAGES = listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.zhiliaoapp.musically",
            "com.snapchat.android",
            "com.twitter.android",
            "com.discord",
            "jp.naver.line.android",
            "com.viber.voip",
            "org.thoughtcrime.securesms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )
        private val CONTROL_COMMANDS = setOf("/stop", "/pause", "/resume", "/restart", "/logout", "/clearqueue", "/lock")
        private val SENSITIVE_COMMANDS = setOf("/screenshot", "/photo", "/location", "/lastcalls", "/lastsms", "/lastnotif", "/contacts", "/history", "/apps", "/log", "/version", "/status", "/battery", "/uptime", "/storage", "/perms", "/ringkas", "/alive", "/cekmati", "/layar", "/audit")
        private val ALIAS_COMMANDS = mapOf("dimana" to "/location", "lokasi" to "/location", "posisi" to "/location", "foto" to "/photo", "photo" to "/photo", "ding" to "/ring", "bunyi" to "/ring", "kunci" to "/lock", "baterai" to "/battery", "batre" to "/battery", "bantu" to "/help", "tolong" to "/help", "menu" to "/help", "lanjut" to "/resume", "versi" to "/version", "sms" to "/sms", "darurat" to "/darurat", "minggu" to "/minggu", "laporan" to "/minggu", "pulang" to "/jampulang", "jejak" to "/jejak", "waspada" to "/waspada", "hilang" to "/hilang", "jemput" to "/jemput", "cekmati" to "/cekmati", "ujimati" to "/cekmati", "sembuh" to "/sembuh", "pulih" to "/sembuh", "layar" to "/layar", "screentime" to "/layar", "kendara" to "/kendara", "berkendara" to "/kendara", "kendaraan" to "/kendara", "hematdata" to "/hematdata", "hematkuota" to "/hematdata", "kuota" to "/hematdata", "restore" to "/pulihkan")
        private const val COMMAND_MAX_AGE_SEC = 900L
        private const val ADOPTION_WAIT_DEADLINE_MS = COMMAND_MAX_AGE_SEC * 1000L
        private const val COMMAND_SILENT_AGE_SEC = 3600L
        private const val PAIR_NOTICE_COOLDOWN_MS = 600_000L
        private const val LOGIN_MAX_FAILS = 5
        private const val LOGIN_LOCK_MS = 15 * 60_000L
        private const val LOGIN_GLOBAL_MAX_FAILS = 50
        private const val LOGIN_GLOBAL_LOCK_MS = 10 * 60_000L
        private const val LOGIN_ATTEMPT_THROTTLE_MS = 1_500L
        private const val MUTATING_MAX_AGE_SEC = 300L
        private const val NUMBER_INPUT_TTL_MS = 300_000L
        private val NO_MARKUP = com.redeye.parentalmonitor.network.InlineKeyboardMarkup(emptyList())
        private const val PING_RATE_WINDOW_MS = 120_000L
        private const val PING_RESTART_COOLDOWN_MS = 300_000L
        private const val VIDEO_BYTES_PER_SEC = 300_000L
        private const val MAX_VIDEO_BYTES = 48L * 1024L * 1024L
        private const val CAMERA_WAKE_FRESH_MS = 10 * 60_000L
        private const val CAMERA_WAKE_NOTIFY_GAP_MS = 6 * 60 * 60_000L
        private const val NOTIFICATION_ID = 1
        private const val MAX_AUDIO_KEPT = 5
        private const val MAX_VIDEO_KEPT = 3
        private const val MAX_PHOTO_KEPT = 10
        private const val DATA_QUEUE_SOFT_LIMIT = 90
        private const val MEDIA_UPLOAD_GRACE_MS = 600_000L
        private const val MEDIA_FLUSH_DEADLINE_MS = 600_000L
        private const val MEDIA_FLUSH_MIN_START_MS = 15_000L
        private const val AUDIO_UPLOAD_TIMEOUT_MS = 300_000L
        private const val VIDEO_UPLOAD_TIMEOUT_MS = 600_000L
        private const val PHOTO_PAUSE_NONE = 0
        private const val PHOTO_PAUSE_ADMIN = 1
        private const val PHOTO_PAUSE_UNKNOWN = 2
        private const val LOW_BATTERY_LEVEL = 15
        private const val DATA_SAVER_CHECK_WINDOW_MS = 15_000L
        private const val VEHICLE_MIN_SPEED_MPS = 8.3f
        private const val VEHICLE_MAX_SPEED_MPS = 55f
        private const val VEHICLE_COOLDOWN_MS = 20 * 60_000L
        private const val LOCATION_GOOD_ACCURACY_M = 50f
        private const val LOCATION_LASTKNOWN_FRESH_MS = 120_000L
        private const val LOCATION_LASTKNOWN_MAX_MS = 24 * 60 * 60_000L
        private const val LOCATION_STALE_MS = 5 * 60_000L
        private const val SILENT_LINK_MS = 90 * 60_000L
        private const val SILENT_HEAL_SPACING_MS = 45 * 60_000L
        private const val SILENT_MAX_STAGE = 3
        private val storageWarnAt = java.util.concurrent.atomic.AtomicLong(0L)
        private val smsReqSeq = java.util.concurrent.atomic.AtomicInteger(java.util.concurrent.ThreadLocalRandom.current().nextInt())
        private val authReminderAt = java.util.concurrent.atomic.AtomicLong(0L)
        @Volatile
        private var commandsRegFailAt = 0L
        private const val AUTH_NOTIF_ID = 5
        @Volatile
        var isRunning = false
        private val appLabelCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, String>>()

        fun invalidateAppLabel(pkg: String) {
            try {
                appLabelCache.remove(pkg)
            } catch (_: Exception) {
            }
        }

    }

    override fun onCreate() {
        super.onCreate()
        try {
            preferencesManager = PreferencesManager.getInstance(this)
        } catch (_: Exception) {
            try {
                Liveness.schedule(this)
            } catch (_: Exception) {
            }
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
        if (!startForegroundImmediate()) {
            return
        }
        com.redeye.parentalmonitor.utils.Background.runCritical {
            try {
                if (PreferencesManager.refreshInstance(this)) {
                    refreshCreds()
                    refreshLoopConfig()
                }
            } catch (_: Exception) {
            }
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
                    serviceScope.launch { registerBotCommands() }
                }
            }
            if (key == PreferencesManager.KEY_ACCESS_PIN) serviceScope.launch {
                refreshCreds()
                registerBotCommands(force = true)
            }
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL || key == PreferencesManager.KEY_SYNC_INTERVAL || key == PreferencesManager.KEY_PATROL_ENABLED || key == PreferencesManager.KEY_PATROL_INTERVAL) refreshLoopConfig()
            if (key == PreferencesManager.KEY_CAMERA_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED || key == PreferencesManager.KEY_PHOTO_PAUSED_UNTIL) restartCameraLoop()
            if (key == PreferencesManager.KEY_PATROL_ENABLED || key == PreferencesManager.KEY_PATROL_INTERVAL || key == PreferencesManager.KEY_MONITORING_PAUSED) restartPatrolLoop()
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
                scheduleMsgDropFlush()
            }
        } catch (_: Exception) {
        }
        restoreRingVolumeIfStuck()
        try { A11yMonitor.attach(this) } catch (_: Exception) { }
        coreInitialized = true
    }

    private fun restoreRingVolumeIfStuck() {
        if (ringBusy.get()) return
        restoreRingVolumeShared()
    }

    private fun restoreRingVolumeShared() {
        try {
            RingVolume.restoreStuck(this, preferencesManager)
        } catch (_: Exception) {
        }
    }

    private fun startInitialSync(force: Boolean) {
        try {
            synchronized(initialSyncLock) {
                if (!force) {
                    val alreadyRunning = initialSyncRunning.get() && initialSyncJob?.isActive == true
                    if (alreadyRunning) return
                }
                try {
                    initialSyncJob?.cancel()
                } catch (_: Exception) {
                }
                initialSyncRunning.set(false)
                if (preferencesManager.initialSyncDone) return
                if (!initialSyncRunning.compareAndSet(false, true)) return
                val syncGen = initialSyncGen.incrementAndGet()
                initialSyncJob = serviceScope.launch {
                    try {
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Starting initial data collection...")
                        sendInitialData()
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Initial data collection completed")
                    } catch (_: Exception) {
                    } finally {
                        if (initialSyncGen.get() == syncGen) initialSyncRunning.set(false)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, ParentalMonitorApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .setSilent(true)
            .setShowWhen(false)
        try {
            builder.setContentIntent(setupTapIntent())
        } catch (_: Exception) {
        }
        return builder.build()
    }

    private fun startForegroundImmediate(): Boolean {
        try {
            val notification = buildForegroundNotification()
            if (applyForegroundTypes(notification)) return true
        } catch (e: Exception) {
            android.util.Log.w("MonitoringService", "Immediate foreground start failed: ${redactToken(e.message)}")
        }
        try {
            MessageScheduler.scheduleBootRestart(this)
        } catch (_: Exception) {
        }
        try {
            MessageScheduler.scheduleWatchdog(this)
        } catch (_: Exception) {
        }
        try {
            Liveness.schedule(this)
        } catch (_: Exception) {
        }
        try {
            stopSelf()
        } catch (_: Exception) {
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundOk = try {
            startForegroundImmediate()
        } catch (_: Exception) {
            false
        }
        if (!foregroundOk) return START_NOT_STICKY
        if (!coreInitialized) return START_NOT_STICKY
        return try {
            when (intent?.action) {
            ACTION_START_MONITORING -> {
                startMonitoring()
                return START_STICKY
            }
            ACTION_LIVENESS_TEST -> {
                if (isRunning) {
                    runLivenessSelfTest()
                    return START_STICKY
                }
                if (shouldAutoResume()) {
                    startMonitoring()
                    if (isRunning) {
                        runLivenessSelfTest()
                        return START_STICKY
                    }
                }
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_REFRESH_FGS -> {
                if (!isRunning && shouldAutoResume()) {
                    startMonitoring()
                }
                confirmCameraWakeRestored()
                if (!isRunning) stopSelfResult(startId)
                return if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_ADMIN_ALERT -> {
                if (!isRunning && shouldAutoResume()) {
                    startMonitoring()
                }
                if (isRunning) {
                    serviceScope.launch { handleAdminAlert() }
                    return START_STICKY
                }
                serviceScope.launch {
                    try {
                        handleAdminAlert()
                    } catch (_: Exception) {
                    } finally {
                        stopSelfResult(startId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_UNLOCK_FAILED -> {
                if (!isRunning && shouldAutoResume()) {
                    startMonitoring()
                }
                val attempts = intent?.getIntExtra(EXTRA_UNLOCK_ATTEMPTS, 0) ?: 0
                if (isRunning) {
                    serviceScope.launch { handleUnlockFailed(attempts) }
                    return START_STICKY
                }
                serviceScope.launch {
                    try {
                        handleUnlockFailed(attempts)
                    } catch (_: Exception) {
                    } finally {
                        stopSelfResult(startId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_SMS_RECEIVED, ACTION_CALL_STATE -> {
                if (!isRunning && shouldAutoResume()) {
                    startMonitoring()
                }
                if (isRunning) {
                    serviceScope.launch { triggerImmediateCheck() }
                    return START_STICKY
                }
                serviceScope.launch {
                    try {
                        triggerImmediateCheck()
                    } catch (_: Exception) {
                    } finally {
                        stopSelfResult(startId)
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_AFTER_SHOT_GRANT -> {
                if (!isRunning && shouldAutoResume()) {
                    startMonitoring()
                }
                if (!isRunning) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch { sendToTelegram("\u2705 Izin tangkap layar diterima \u2014 mengambil layar\u2026") }
                captureAndSendScreenshot()
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
                            if (shouldAutoResume()) {
                                startMonitoring()
                            } else {
                                if (needsCredsRetry()) {
                                    MessageScheduler.scheduleBootRestart(this@MonitoringService)
                                }
                                stopSelfResult(startId)
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                return START_NOT_STICKY
            }
            }
        } catch (_: Exception) {
            START_STICKY
        }
    }

    private fun canRunMonitoring(requireConfigured: Boolean): Boolean {
        return try {
            preferencesManager.isMonitoringEnabled && preferencesManager.isConfigured() == requireConfigured && !preferencesManager.userDisabledMonitoring && preferencesManager.userConsentedMonitoring
        } catch (_: Exception) {
            false
        }
    }

    private fun shouldAutoResume(): Boolean {
        return canRunMonitoring(true)
    }

    private fun needsCredsRetry(): Boolean {
        return canRunMonitoring(false)
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
        serviceStartElapsed = android.os.SystemClock.elapsedRealtime()
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

        val foregroundNotification = try {
            buildForegroundNotification()
        } catch (_: Exception) {
            stopSelf()
            return
        }
        if (!applyForegroundTypes(foregroundNotification)) {
            stopSelf()
            return
        }
        isRunning = true
        ensureForegroundTypes()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Foreground notification started")
        try {
            Liveness.schedule(this)
        } catch (_: Exception) {
        }
        try {
            Liveness.noteServiceStart(this, preferencesManager)
        } catch (_: Exception) {
        }
        serviceScope.launch { reportLivenessSelfTest() }
        try {
            seedSendOkMarker()
        } catch (_: Exception) {
        }
        if (!preferencesManager.isStorageEncrypted && storageWarnAt.compareAndSet(0L, System.currentTimeMillis())) {
            serviceScope.launch {
                sendToTelegram("Mode cadangan: penyimpanan aman tak tersedia, data ditahan di memori sementara sampai Setup dibuka lagi.")
            }
        }

        startInitialSync(force = false)
        startPeriodicLoops()
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Monitoring loop started")
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "📸 Camera monitoring started")
        serviceScope.launch {
            try {
                messageQueue.tryRestorePersistent()
            } catch (_: Exception) {
            }
            val ringingNow = try { ringBusy.get() || ringJob?.isActive == true } catch (_: Exception) { false }
            if (!ringingNow) restoreRingVolumeShared()
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
        val monitoring = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            while (isActive && (monitoringJob == null || monitoringJob === self)) {
                try {
                    monitorBeatAt = android.os.SystemClock.elapsedRealtime()
                    monitorCycleMs = if (!cachedMonitoringPaused) syncIntervalMillis() else 15 * 60_000L
                    try { Liveness.markAlive(preferencesManager) } catch (_: Exception) { }
                    try { maybeSendHeartbeat() } catch (_: Exception) { }
                    try { checkBatteryAlert() } catch (_: Exception) { }
                    try { maybeSendDigest() } catch (_: Exception) { }
                    try { maybeSendNightDigest() } catch (_: Exception) { }
                    try { maybeRetryBotCommands() } catch (_: Exception) { }
                    try { checkBegadang() } catch (_: Exception) { }
                    try { checkCurfew() } catch (_: Exception) { }
                    try { checkGeofenceFallback() } catch (_: Exception) { }
                    try { checkQuietHealth() } catch (_: Exception) { }
                    try { checkAccessibilityHealth() } catch (_: Exception) { }
                    try { checkSilentLink() } catch (_: Exception) { }
                    try { checkVehicle() } catch (_: Exception) { }
                    if (!cachedMonitoringPaused) {
                        val dataAuthBlocked = try { NetworkUtils.isAuthBlocked(preferencesManager) } catch (_: Exception) { false }
                        if (checkAndSendNewData()) idleDataCycles = 0 else if (!dataAuthBlocked) idleDataCycles = (idleDataCycles + 1).coerceAtMost(3)
                        val initialRetryAt = initialSyncRetryAt
                        if (initialRetryAt > 0L && !initialSyncRunning.get() && hasNetwork() && android.os.SystemClock.elapsedRealtime() >= initialRetryAt) {
                            initialSyncRetryAt = 0L
                            startInitialSync(force = false)
                        }
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
                        pauseAwareDelay(effSync)
                    } else {
                        pauseAwareDelay(15 * 60_000L)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e("MonitoringService", "Error in monitoring loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        monitoringJob = monitoring
        monitoring.start()
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
        val camera = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            while (isActive && (cameraJob == null || cameraJob === self)) {
                try {
                    if (cachedCameraInterval < 0) refreshLoopConfig()
                    cameraBeatAt = android.os.SystemClock.elapsedRealtime()
                    val minutes = cachedCameraInterval.coerceIn(0, 60)
                    val lowPower = lowPowerActive()
                    val effMinutes = if (lowPower) (minutes * 2).coerceAtMost(60) else minutes
                    try { syncLowPowerNotice(lowPower) } catch (_: Exception) { }
                    val paused = System.currentTimeMillis() < cachedPhotoPausedUntil
                    if (!cachedMonitoringPaused && !paused && !isNightPaused() && effMinutes > 0) {
                        if (idleDataCycles >= 3 && !isScreenOn()) {
                            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Camera idle-skip (quiet device, screen off)")
                        } else {
                            captureAndSendPhoto()
                        }
                        cameraCycleMs = effMinutes * 60_000L
                        delayMs(effMinutes * 60_000L)
                    } else if (cachedMonitoringPaused) {
                        cameraCycleMs = 5 * 60_000L
                        delayMs(5 * 60_000L)
                    } else {
                        val adminPaused = effMinutes > 0 && photoPauseSource() == PHOTO_PAUSE_ADMIN
                        if (adminPaused) {
                            if (isCameraDisabledByPolicy()) {
                                if (isPhotoPaused()) {
                                    cachedPhotoPausedUntil = photoPausedElapsed()
                                } else {
                                    val until = System.currentTimeMillis() + 120 * 60_000L
                                    cachedPhotoPausedUntil = until
                                    try { preferencesManager.photoPausedUntil = until } catch (_: Exception) { }
                                }
                                cameraCycleMs = 2 * 60_000L
                                delayMs(2 * 60_000L)
                            } else {
                                try { resumePhotosAfterPolicyLift() } catch (_: Exception) { }
                                cameraCycleMs = 2_000L
                                delayMs(2_000L)
                            }
                        } else {
                            val remaining = cachedPhotoPausedUntil - System.currentTimeMillis()
                            val idle = if (remaining > 0) remaining.coerceAtMost(30 * 60_000L) else 30 * 60_000L
                            cameraCycleMs = idle
                            delayMs(idle)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.e("MonitoringService", "Error in camera loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        cameraJob = camera
        camera.start()
    }

    private fun restartPatrolLoop() {
        try {
            patrolJob?.cancel()
        } catch (_: Exception) {
        }
        startPatrolLoop()
    }

    private fun startPatrolLoop() {
        val patrol = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            while (isActive && (patrolJob == null || patrolJob === self)) {
                patrolBeatAt = android.os.SystemClock.elapsedRealtime()
                try {
                    val minutes = cachedPatrolInterval.coerceIn(5, 180)
                    val lowPower = lowPowerActive()
                    val effMinutes = if (lowPower) (minutes * 2).coerceAtMost(180) else minutes
                    val photoPaused = System.currentTimeMillis() < cachedPhotoPausedUntil
                    val sleepMs = if (!cachedMonitoringPaused && !photoPaused && !isNightPaused() && cachedPatrolEnabled) {
                        patrolRound()
                        effMinutes * 60_000L
                    } else if (cachedMonitoringPaused) {
                        15 * 60_000L
                    } else {
                        30 * 60_000L
                    }
                    patrolCycleMs = sleepMs
                    delayMs(sleepMs)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in patrol loop: ${redactToken(e.message)}")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                } catch (_: Throwable) {
                    android.util.Log.e("MonitoringService", "Fatal patrol error, loop survives")
                    try {
                        delay(10_000L)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        patrolJob = patrol
        patrol.start()
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
        val level = batteryLevel()
        val body = buildString {
            appendLine("\uD83D\uDEF0 <b>Patrol</b>")
            if (location != null) {
                resetLocFailCount()
                try { preferencesManager.lastLocAt = System.currentTimeMillis() } catch (_: Exception) { }
                try { checkGeofence(location.latitude, location.longitude, location.accuracy) } catch (_: Exception) { }
                val prev = lastSentLatLon()
                val dist = if (prev == null) -1f else ReportFormat.distanceMeters(prev.first, prev.second, location.latitude, location.longitude)
                if (dist >= 0f && dist < 100f) {
                    appendLine("Lokasi: masih di titik terakhir (±${dist.toInt()} m)")
                } else {
                    rememberSentLatLon(location.latitude, location.longitude, location.accuracy)
                    appendLine("https://maps.google.com/?q=${location.latitude},${location.longitude}")
                }
                appendLine("Akurasi: ±${location.accuracy.toInt()} m")
            } else {
                bumpLocFailCount()
                appendLine("Lokasi: tak didapat — " + locationFailureHint())
            }
            appendLine("Baterai: ${if (level in 0..100) "$level%" else "tak tahu"}")
        }
        sendToTelegram(body)
        try {
            captureAndSendPhoto()
        } catch (_: Exception) {
        }
    }

    private suspend fun delayMs(totalMs: Long) {
        if (totalMs <= 0L) return
        currentCoroutineContext().ensureActive()
        delay(totalMs)
    }

    private suspend fun pauseAwareDelay(totalMs: Long) {
        val pausedAtStart = cachedMonitoringPaused
        val genAtStart = loopConfigGen
        var remaining = totalMs.coerceAtLeast(0L)
        while (remaining > 0L) {
            val slice = remaining.coerceAtMost(30_000L)
            delay(slice)
            remaining -= slice
            if (cachedMonitoringPaused != pausedAtStart || loopConfigGen != genAtStart) return
        }
    }

    private val credsLock = Any()
    private val fenceLock = Any()
    @Volatile
    private var loopConfigGen = 0
    private fun refreshCreds() {
        synchronized(credsLock) {
            try {
                cachedBotToken = preferencesManager.botToken
                cachedChatId = preferencesManager.chatId
                cachedOwnerId = preferencesManager.ownerUserId
                cachedAccessPinActive = preferencesManager.accessPin.isNotEmpty()
            } catch (_: Exception) {
            }
        }
    }

    private fun adoptMigratedChat(errorBody: String?, currentChatId: String): Boolean {
        return NetworkUtils.adoptMigratedChat(
            preferencesManager,
            errorBody,
            currentChatId,
            refresh = { refreshCreds() },
            announce = {
                messageQueue.addMessage(NetworkUtils.MIGRATED_CHAT_NOTICE, true)
                MessageScheduler.scheduleMessageSend(this)
                android.util.Log.i("MonitoringService", "Chat migrated to supergroup, adopting new chat id")
            }
        )
    }

    private fun isOwner(senderId: String): Boolean {
        return OwnerAccess.isOwner(senderId, cachedChatId, cachedOwnerId, cachedAccessPinActive)
    }

    private fun awaitingOwnerLogin(): Boolean {
        return try {
            preferencesManager.ownerUserId == 0L && preferencesManager.accessPin.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun loginHintText(): String =
        "\uD83D\uDD10 Belum login. Kirim /login <PIN> dari private chat untuk mulai menerima laporan."

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
    private val pairGlobalFails = java.util.concurrent.atomic.AtomicInteger(0)
    private val pairGlobalLockoutUntil = java.util.concurrent.atomic.AtomicLong(0L)
    private val pairNoticeAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val loginAttemptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val loginFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val loginLockUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val loginGlobalFails = java.util.concurrent.atomic.AtomicInteger(0)
    private val loginGlobalLockoutUntil = java.util.concurrent.atomic.AtomicLong(0L)
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
            val token = try {
                preferencesManager.botToken
            } catch (_: Exception) {
                ""
            }.ifEmpty { return }
            val now = android.os.SystemClock.elapsedRealtime()
            try {
                val last = pairHintAt[senderId] ?: 0L
                if (last > 0L && now - last < 3_600_000L) return
                if (pairHintAt.size > 200) pairHintAt.clear()
                pairHintAt[senderId] = now
            } catch (_: Exception) {
            }
            val url = "https://api.telegram.org/bot${token}/sendMessage"
            TelegramClient.api.sendMessage(url, TelegramMessage(chatId = senderId, text = "Pairing: buka link pairing dari Setup, atau kirim /start <chat ID> <kode pairing>.", parseMode = null))
        } catch (_: Exception) {
        }
    }

    private suspend fun sendDirect(targetChatId: String, text: String): Boolean {
        if (targetChatId.isEmpty()) return false
        return try {
            val token = try { preferencesManager.botToken } catch (_: Exception) { "" }
            if (token.isEmpty()) return false
            val response = TelegramClient.api.sendMessage(
                "https://api.telegram.org/bot${token}/sendMessage",
                TelegramMessage(chatId = targetChatId, text = text, parseMode = null)
            )
            response.isSuccessful && response.body()?.ok == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun handleOwnerLogin(
        command: String,
        arg: String,
        senderId: String,
        msgChatId: String,
        messageId: Long,
        accessPin: String
    ): Boolean {
        val isStartHint = command == "/start" && arg.isEmpty()
        if (command != "/login" && !isStartHint) return false
        val privateChat = senderId.isNotEmpty() && msgChatId == senderId
        if (!privateChat) {
            if (command == "/login") {
                if (arg.isNotEmpty() && messageId > 0L) {
                    val tokenGrup = try { preferencesManager.botToken } catch (_: Exception) { "" }
                    deleteIncomingMessage(msgChatId, messageId, tokenGrup)
                    sendDirect(senderId, "\uD83D\uDD10 Login PIN hanya dari private chat. Pesan PIN yang terketik di grup sudah dicoba dihapus — bila masih terlihat, hapus manual sekarang: anggota grup bisa memakai PIN itu untuk login lebih dulu.")
                } else {
                    sendDirect(senderId, "\uD83D\uDD10 Login PIN hanya dari private chat.")
                }
            }
            return true
        }
        if (cachedOwnerId != 0L) {
            if (command == "/login") {
                if (senderId == cachedOwnerId.toString()) {
                    sendDirect(senderId, "\u2705 Sudah login sebagai owner.")
                } else {
                    sendDirect(senderId, "\uD83D\uDD12 Owner sudah tertaut; login PIN ditutup.")
                }
            } else if (senderId == cachedOwnerId.toString()) {
                sendDirect(senderId, "\u2705 Bot aktif. Ketuk /help untuk daftar perintah.")
            }
            return true
        }
        if (accessPin.isEmpty()) {
            if (command == "/login") {
                sendDirect(senderId, "\uD83D\uDD11 PIN akses belum diatur. Buka Setup dan isi PIN akses bot.")
            } else {
                sendPairHint(senderId)
            }
            return true
        }
        if (isStartHint || arg.isEmpty()) {
            sendDirect(senderId, "\uD83D\uDD11 Kirim /login <PIN> (6-12 digit) untuk masuk.")
            return true
        }
        val token = try { preferencesManager.botToken } catch (_: Exception) { "" }
        if (messageId > 0L) deleteIncomingMessage(senderId, messageId, token)
        val now = SystemClock.elapsedRealtime()
        if (now < loginGlobalLockoutUntil.get()) {
            sendDirect(senderId, "\u23F3 Login dikunci sementara. Coba lagi nanti.")
            return true
        }
        val lockedUntil = try { loginLockUntil[senderId] ?: 0L } catch (_: Exception) { 0L }
        if (now < lockedUntil) {
            val minutes = ((lockedUntil - now + 59_999L) / 60_000L).coerceAtLeast(1L)
            sendDirect(senderId, "\u23F3 PIN salah terlalu sering. Coba lagi dalam ${minutes} menit.")
            return true
        }
        val lastAttempt = try { loginAttemptAt[senderId] ?: 0L } catch (_: Exception) { 0L }
        if (lastAttempt > 0L && now - lastAttempt < LOGIN_ATTEMPT_THROTTLE_MS) return true
        try {
            if (loginAttemptAt.size > 200) loginAttemptAt.clear()
            loginAttemptAt[senderId] = now
        } catch (_: Exception) {
        }
        if (!AccessPin.verify(accessPin, arg)) {
            val fails = try { (loginFails[senderId] ?: 0) + 1 } catch (_: Exception) { 1 }
            try {
                if (loginFails.size > 200) loginFails.clear()
                loginFails[senderId] = fails
            } catch (_: Exception) {
            }
            val globalFails = loginGlobalFails.incrementAndGet()
            if (globalFails >= LOGIN_GLOBAL_MAX_FAILS) {
                loginGlobalFails.set(0)
                loginGlobalLockoutUntil.set(now + LOGIN_GLOBAL_LOCK_MS)
                audit("login pin terkunci global", "private")
                sendDirect(senderId, "\u23F3 Terlalu banyak PIN salah. Login dikunci sementara.")
                return true
            }
            if (fails >= LOGIN_MAX_FAILS) {
                try {
                    loginFails.remove(senderId)
                    loginAttemptAt.remove(senderId)
                    loginLockUntil[senderId] = now + LOGIN_LOCK_MS
                } catch (_: Exception) {
                }
                audit("login pin terkunci", "private")
                sendDirect(senderId, "\u23F3 PIN salah ${LOGIN_MAX_FAILS} kali. Coba lagi 15 menit.")
                return true
            }
            audit("login pin salah", "private")
            sendDirect(senderId, "\u274C PIN salah. Sisa ${LOGIN_MAX_FAILS - fails} percobaan.")
            return true
        }
        val ownerId = senderId.toLongOrNull() ?: return true
        val adopted = synchronized(ownerLock) {
            if (cachedOwnerId != 0L) {
                false
            } else {
                val stored = try {
                    preferencesManager.adoptOwnerChatSync(ownerId, senderId)
                } catch (_: Exception) {
                    false
                }
                if (stored) {
                    cachedChatId = senderId
                    cachedOwnerId = ownerId
                }
                stored
            }
        }
        if (!adopted) {
            audit("login pin simpan gagal", "private")
            sendDirect(senderId, "\u26A0\uFE0F PIN benar, tetapi akses gagal disimpan. Coba lagi.")
            return true
        }
        try {
            loginAttemptAt.remove(senderId)
            loginFails.remove(senderId)
            loginLockUntil.remove(senderId)
            loginGlobalFails.set(0)
            loginGlobalLockoutUntil.set(0L)
        } catch (_: Exception) {
        }
        refreshCreds()
        audit("login pin ok", "private")
        try { registerBotCommands(force = true) } catch (_: Exception) {
        }
        val hello = "\u2705 Login berhasil. Chat ini sekarang menjadi owner dan tujuan pemantauan.\n\nGunakan /help untuk membuka menu."
        if (!sendToTelegram(hello, queueOnFail = false)) sendDirect(senderId, hello)
        try { handleHelpCommand(true) } catch (_: Exception) {
        }
        return true
    }

    private suspend fun handleLogoutCommand(privateChat: Boolean) {
        if (!privateChat) {
            sendToTelegram("🔐 /logout hanya bisa dipakai dari private chat owner.")
            return
        }
        val pinActive = try { preferencesManager.accessPin.isNotEmpty() } catch (_: Exception) { false }
        if (!pinActive) {
            sendToTelegram("🔐 Atur PIN akses bot di Setup dulu supaya bisa login lagi setelah logout.")
            return
        }
        val ownerId = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
        if (ownerId == 0L) {
            sendToTelegram("ℹ️ Tidak ada sesi owner yang aktif. Kirim /login <PIN> untuk masuk.")
            return
        }
        val cleared = try { preferencesManager.clearOwnerSync() } catch (_: Exception) { false }
        if (!cleared) {
            sendToTelegram("⚠️ Logout gagal. Coba lagi.")
            return
        }
        pendingRestore = null
        pendingInputCommand = ""
        pendingInputAt = 0L
        pendingInputChat = ""
        ownerEpoch++
        val queueCleared = try { messageQueue.clearQueue() } catch (_: Exception) { false }
        try { NotificationForwarderService.clearHistory() } catch (_: Exception) {
        }
        try { MessageScheduler.cancelMessageSend(this) } catch (_: Exception) {
        }
        try {
            loginAttemptAt.clear()
            loginFails.clear()
            loginLockUntil.clear()
            loginGlobalFails.set(0)
            loginGlobalLockoutUntil.set(0L)
        } catch (_: Exception) {
        }
        refreshCreds()
        try { clearChatCommands(ownerId) } catch (_: Exception) {
        }
        try { registerBotCommands(force = true) } catch (_: Exception) {
        }
        val message = if (queueCleared) {
            "🚪 Logout berhasil. Sesi owner dihapus; kirim /login <PIN> untuk masuk lagi."
        } else {
            "⚠️ Logout berhasil, tetapi antrean pesan gagal dibersihkan. Penyimpanan antrean belum dipastikan kosong."
        }
        if (!sendToTelegram(message, queueOnFail = false)) sendDirect(cachedChatId, message)
    }

    private fun bestFix(candidates: List<android.location.Location>): android.location.Location? {
        var best: android.location.Location? = null
        var bestScore = Float.MAX_VALUE
        for (candidate in candidates) {
            val score = if (candidate.accuracy > 0f) candidate.accuracy else Float.MAX_VALUE
            if (best == null || score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun refreshLoopConfig() {
        try {
            cachedCameraInterval = preferencesManager.cameraInterval
            cachedMonitoringPaused = preferencesManager.monitoringPaused
            cachedPhotoPausedUntil = photoPausedElapsed()
            cachedSyncInterval = preferencesManager.syncInterval
            cachedPatrolEnabled = preferencesManager.patrolEnabled
            cachedPatrolInterval = preferencesManager.patrolInterval
            cachedVehicleEnabled = preferencesManager.vehicleAlertEnabled
            cachedDataSaver = preferencesManager.dataSaverEnabled
            loopConfigGen++
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
        return synchronized(credsLock) { cachedBotToken to cachedChatId }
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
    private var webhookClearAt = 0L
    @Volatile
    private var webhookClearNoticeAt = 0L
    @Volatile
    private var offsetFailCount = 0
    @Volatile
    private var serverClockOffsetMs = 0L
    @Volatile
    private var serverClockSynced = false
    @Volatile
    private var mediaBackoffUntil = 0L
    private val msgDropCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var msgDropJob: Job? = null
    private val msgDropLock = Any()

    private fun startCommandPolling() {
        val command = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            while (isActive && (commandJob == null || commandJob === self)) {
                commandBeatAt = android.os.SystemClock.elapsedRealtime()
                commandCycleMs = if (cachedMonitoringPaused) 60_000L else 15_000L
                if (cachedMonitoringPaused) {
                    try {
                        pollTelegramCommands()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e("MonitoringService", "Error polling commands while paused: ${redactToken(e.message)}")
                    } catch (_: Throwable) {
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
                } catch (_: Throwable) {
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
                } else if (NetworkUtils.isPollBlocked(preferencesManager)) {
                    delay(300_000)
                } else {
                    delay((10_000L + idlePolls * 5_000L).coerceAtMost(30_000L))
                }
            }
        }
        commandJob = command
        command.start()
    }



    private fun updatesUrl(token: String, offset: Long): String {
        return "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=30&limit=50&allowed_updates=%5B%22message%22,%22edited_message%22,%22channel_post%22,%22edited_channel_post%22,%22callback_query%22%5D"
    }

    private suspend fun clearStaleWebhook(botToken: String) {
        val cleared = try {
            val url = "https://api.telegram.org/bot$botToken/deleteWebhook"
            val resp = TelegramClient.api.deleteWebhook(url, emptyMap())
            resp.isSuccessful && resp.body()?.ok == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        val now = System.currentTimeMillis()
        if (cleared) {
            commandBackoffUntil = 0L
            if (now - webhookClearNoticeAt > 3_600_000L) {
                webhookClearNoticeAt = now
                try {
                    sendToTelegram("🔗 Webhook bot lama dibersihkan; perintah Telegram aktif lagi.")
                } catch (_: Exception) {
                }
            }
        } else if (now - updateConflictNoticeAt > 3_600_000L) {
            updateConflictNoticeAt = now
            try {
                sendToTelegram("Konflik polling bot (409): klien lain sedang membaca pembaruan, perintah tertunda. Hentikan klien polling lain.")
            } catch (_: Exception) {
            }
        }
    }

    private fun noteServerDate(response: retrofit2.Response<*>) {
        try {
            val date = response.headers().getDate("Date") ?: return
            serverClockOffsetMs = date.time - System.currentTimeMillis()
            serverClockSynced = true
        } catch (_: Exception) {
        }
    }

    private fun serverNowSec(): Long {
        return (System.currentTimeMillis() + if (serverClockSynced) serverClockOffsetMs else 0L) / 1000L
    }

    private suspend fun pollTelegramCommands(): Boolean {
        if (android.os.SystemClock.elapsedRealtime() < commandBackoffUntil) return false
        if (NetworkUtils.isPollBlocked(preferencesManager)) return false
        val rateRemainMs = NetworkUtils.rateLimitedRemainMs()
        if (rateRemainMs > 0L) {
            commandBackoffUntil = maxOf(commandBackoffUntil, android.os.SystemClock.elapsedRealtime() + rateRemainMs)
            return false
        }
        val (botToken, chatId) = sendCreds()
        if (botToken.isEmpty() || chatId.isEmpty()) return false

        val offset = preferencesManager.lastUpdateId + 1
        val url = updatesUrl(botToken, offset)

        var response = try {
            TelegramPollClient.api.getUpdates(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        noteServerDate(response)
        var responseToken = botToken
        var responseChat = chatId
        if (response.code() == 401 || response.code() == 403) {
            try {
                refreshCreds()
            } catch (_: Exception) {
            }
            val (freshToken, freshChat) = sendCreds()
            if (freshToken.isNotEmpty() && (freshToken != botToken || freshChat != chatId)) {
                responseToken = freshToken
                responseChat = freshChat
                val retryUrl = updatesUrl(freshToken, offset)
                response = try {
                    TelegramPollClient.api.getUpdates(retryUrl)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return false
                }
                noteServerDate(response)
            }
        }
        if (response.code() == 401 || response.code() == 403) {
            try { response.errorBody()?.close() } catch (_: Exception) { }
            val marked = preferencesManager.setCredentialErrorIfCredentialsMatch(
                responseToken,
                responseChat,
                response.code().toString(),
                System.currentTimeMillis()
            )
            if (!marked) return false
            try {
                postAuthFailureReminder(response.code())
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 409) {
            try { response.errorBody()?.close() } catch (_: Exception) { }
            val now409 = android.os.SystemClock.elapsedRealtime()
            val selfConflict = now409 - lastLoopRestartAt < 60_000L
            android.util.Log.w("MonitoringService", "getUpdates conflict: another consumer is polling, backing off")
            commandBackoffUntil = now409 + (if (selfConflict) 60_000L else 300_000L)
            idlePolls = 4
            if (!selfConflict) {
                val nowClear = System.currentTimeMillis()
                if (nowClear - webhookClearAt > 10 * 60_000L) {
                    webhookClearAt = nowClear
                    serviceScope.launch {
                        try {
                            clearStaleWebhook(botToken)
                        } catch (_: Exception) {
                        }
                    }
                }
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
                    offsetFailCount++
                    if (offsetFailCount >= 3) {
                        offsetFailCount = 0
                        try {
                            preferencesManager.setLastUpdateIdSync(0L)
                        } catch (_: Exception) {
                        }
                        android.util.Log.w("MonitoringService", "getUpdates offset rejected 3x, resynced offset to 0")
                    } else {
                        android.util.Log.w("MonitoringService", "getUpdates offset rejected, backing off without offset reset")
                    }
                    commandBackoffUntil = android.os.SystemClock.elapsedRealtime() + 30_000L
                }
            } catch (_: Exception) {
            }
            return false
        }
        if (response.code() == 408 || response.code() >= 500) {
            try { response.errorBody()?.close() } catch (_: Exception) { }
            commandBackoffUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
            return false
        }
        if (!response.isSuccessful || response.body()?.ok != true) return false
        offsetFailCount = 0

        val updates = response.body()?.result ?: return false
        if (updates.isEmpty()) return false
        for (update in updates) {
            val seen = markSeen(handledUpdateIds, update.updateId)
            if (seen) {
                continue
            }
            beginUpdateTrack(update.updateId)
            var offsetDeferred = false
            var delivered = true
            try {
                val callback = update.callbackQuery
                if (callback != null) {
                    offsetDeferred = true
                    dispatchTracked(update.updateId, "Callback dispatch error") {
                        try {
                            handleCallbackQuery(callback)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            try {
                                sendToTelegram("\u26A0\uFE0F Tombol gagal diproses. Coba lagi atau kirim /help.")
                            } catch (_: Exception) {
                            }
                            throw e
                        }
                    }
                } else {
                    val message = update.message ?: update.editedMessage ?: update.channelPost ?: update.editedChannelPost
                    val msgChatId = message?.chat?.id?.toString().orEmpty()
                    val senderId = message?.from?.id?.toString().orEmpty()
                    val chatOk = msgChatId.isNotEmpty() && msgChatId == chatId
                    val rawText = (message?.text ?: message?.caption)?.trim().orEmpty()
                    val headArg = rawText.split(CMD_SPLIT_REGEX, limit = 2)
                    val head = headArg.getOrNull(0).orEmpty()
                    val command = head.substringBefore("@").lowercase(java.util.Locale.ROOT)
                    val arg = headArg.getOrNull(1)?.trim().orEmpty()
                    val loginOnly = command == "/login" || (command == "/start" && arg.isEmpty())
                    if (loginOnly) {
                        val accessPin = try { preferencesManager.accessPin } catch (_: Exception) { "" }
                        offsetDeferred = true
                        lastLoginSender = senderId
                        lastLoginJob = dispatchTracked(update.updateId, "Login dispatch error") {
                            var locksHeld = false
                            val lockDeadline = android.os.SystemClock.elapsedRealtime() + ADOPTION_WAIT_DEADLINE_MS
                            while (!locksHeld) {
                                if (controlLock.tryLock()) {
                                    if (commandLock.tryLock()) {
                                        locksHeld = true
                                    } else {
                                        controlLock.unlock()
                                    }
                                }
                                if (!locksHeld) {
                                    if (android.os.SystemClock.elapsedRealtime() >= lockDeadline) break
                                    delay(50)
                                }
                            }
                            if (!locksHeld) {
                                android.util.Log.w("MonitoringService", "Login ditolak: kunci perintah sibuk melewati tenggat tunggu adopsi")
                                try { sendDirect(senderId, "\u23F3\uFE0F Login belum bisa diproses (sistem sibuk terlalu lama). Kirim /login lagi.") } catch (_: Exception) { }
                            } else {
                                try {
                                    handleOwnerLogin(command, arg, senderId, msgChatId, message?.messageId ?: 0L, accessPin)
                                } finally {
                                    commandLock.unlock()
                                    controlLock.unlock()
                                }
                            }
                        }
                        continue
                    }
                    if (command == "/start" && arg.isNotEmpty()) {
                        val accessPin = try { preferencesManager.accessPin } catch (_: Exception) { "" }
                        if (cachedOwnerId == 0L && senderId.isNotEmpty() && msgChatId == senderId && accessPin.isNotEmpty()) {
                            sendDirect(senderId, "\uD83D\uDD11 PIN akses aktif. Kirim /login <PIN> untuk masuk.")
                            offsetDeferred = true
                            finishUpdateTrack(update.updateId, true)
                            continue
                        }
                    }
                    val loginJobSnapshot = lastLoginJob
                    val followsLogin = senderId.isNotEmpty() && senderId == lastLoginSender &&
                        loginJobSnapshot != null && !loginJobSnapshot.isCompleted
                    if (!followsLogin && awaitingOwnerLogin() && command.startsWith("/") && command != "/login" && command != "/start") {
                        val hintTarget = senderId.ifEmpty { msgChatId }
                        val hintNow = android.os.SystemClock.elapsedRealtime()
                        val hintLast = try { pairHintAt["login:$hintTarget"] ?: 0L } catch (_: Exception) { 0L }
                        if (hintLast == 0L || hintNow - hintLast >= 3_600_000L) {
                            try {
                                if (pairHintAt.size > 200) pairHintAt.clear()
                                pairHintAt["login:$hintTarget"] = hintNow
                            } catch (_: Exception) {
                            }
                            sendDirect(hintTarget, loginHintText())
                        }
                        offsetDeferred = true
                        finishUpdateTrack(update.updateId, true)
                        continue
                    }
                    if (senderId.isNotEmpty() && msgChatId == senderId && senderId != chatId && command == "/start" && arg.isNotEmpty()) {
                        if (cachedOwnerId == 0L && android.os.SystemClock.elapsedRealtime() >= pairGlobalLockoutUntil.get()) {
                            val nowPair = android.os.SystemClock.elapsedRealtime()
                            val lastPair = try { pairAttemptAt[senderId] ?: 0L } catch (_: Exception) { 0L }
                            if (nowPair - lastPair >= 30_000L) {
                                try {
                                    if (pairAttemptAt.size > 200) pairAttemptAt.clear()
                                    pairAttemptAt[senderId] = nowPair
                                } catch (_: Exception) {
                                }
                                val startTokens = arg.split(CMD_SPLIT_REGEX).filter { it.isNotEmpty() }
                                var startChat = startTokens.getOrNull(0).orEmpty()
                                var startCode = startTokens.getOrNull(1).orEmpty()
                                if (startCode.isEmpty()) {
                                    val sep = startChat.lastIndexOf('_')
                                    if (sep > 0) {
                                        startCode = startChat.substring(sep + 1)
                                        startChat = startChat.substring(0, sep)
                                    }
                                }
                                val pairCode = try { preferencesManager.ensureOwnerPairCode() } catch (_: Exception) { "" }
                                if (startChat == chatId && pairCode.isNotEmpty() && startCode == pairCode) {
                                    val learned = rememberOwner(senderId.toLongOrNull() ?: 0L)
                                    if (learned) {
                                        try { pairAttemptAt.remove(senderId) } catch (_: Exception) { }
                                        try { pairGlobalFails.set(0) } catch (_: Exception) { }
                                        registerBotCommands()
                                        sendToTelegram("Pemilik tertaut via /start.")
                                    }
                                } else {
                                    if (startChat == chatId && pairCode.isNotEmpty()) {
                                        try {
                                            if (pairGlobalFails.incrementAndGet() >= 10) {
                                                pairGlobalFails.set(0)
                                                pairGlobalLockoutUntil.set(android.os.SystemClock.elapsedRealtime() + 3_600_000L)
                                            }
                                        } catch (_: Exception) {
                                        }
                                    }
                                    if (startChat == chatId) {
                                        sendPairHint(senderId)
                                    }
                                }
                            }
                        } else if (cachedOwnerId != 0L && senderId != cachedOwnerId.toString()) {
                            val nowNotice = android.os.SystemClock.elapsedRealtime()
                            if (nowNotice >= pairNoticeAt.get()) {
                                pairNoticeAt.set(nowNotice + PAIR_NOTICE_COOLDOWN_MS)
                                sendToTelegram("\u26A0\uFE0F Pemasangan diabaikan: pemilik sudah tertaut.")
                            }
                        }
                    }
                    val ownerOk = isOwner(senderId)
                    val privateOwner = ownerOk && msgChatId.isNotEmpty() && msgChatId == senderId
                    if (chatOk || ownerOk || followsLogin) {
                        val aliasInput = resolveAliasInput(command, arg)
                        val intentInput = if (aliasInput == null && !command.startsWith("/")) {
                            try { IntentParser.parse(rawText) } catch (_: Exception) { null }
                        } else {
                            null
                        }
                        val effective = (aliasInput ?: intentInput)?.substringBefore(" ") ?: command
                        val control = effective in CONTROL_COMMANDS
                        val isPrivate = msgChatId.isNotEmpty() && msgChatId == senderId
                        val messageDate = message?.date ?: 0L
                        val lock = if (control) controlLock else commandLock
                        offsetDeferred = true
                        dispatchTracked(update.updateId, "Command dispatch error") {
                            var adoptionDoneSec = 0L
                            var followFailedSilently = false
                            var followTimedOut = false
                            if (followsLogin && !control) {
                                kotlinx.coroutines.withTimeoutOrNull(ADOPTION_WAIT_DEADLINE_MS) { loginJobSnapshot?.join() }
                                if (loginJobSnapshot?.isCompleted != true) {
                                    followTimedOut = true
                                    android.util.Log.w("MonitoringService", "Perintah pengikut ditolak: join login melewati tenggat tunggu adopsi")
                                    try { sendDirect(senderId, "\u23F3\uFE0F Perintah ditolak: login belum selesai terlalu lama. Pastikan /login berhasil, lalu kirim perintah lagi.") } catch (_: Exception) { }
                                } else if (loginJobSnapshot?.isCompleted == true && isOwner(senderId)) {
                                    adoptionDoneSec = serverNowSec()
                                } else if (!ownerOk && loginJobSnapshot?.isCompleted == true && !isOwner(senderId) &&
                                    (effective in MUTATING_COMMANDS || effective in SENSITIVE_COMMANDS)) {
                                    followFailedSilently = true
                                    android.util.Log.w("MonitoringService", "Perintah pengikut dilewati diam-diam: login yang diikuti selesai tanpa adopsi (kegagalan sudah diberitahukan oleh jalur login)")
                                }
                            }
                            var adoptionRejected = false
                            val adoptionWait = followsLogin && control && !ownerOk
                            if (adoptionWait) {
                                synchronized(adoptionGateLock) { adoptionWaiters.add(update.updateId to senderId) }
                            }
                            try {
                                if (adoptionWait) {
                                    val waitDeadline = android.os.SystemClock.elapsedRealtime() + ADOPTION_WAIT_DEADLINE_MS
                                    while (true) {
                                        val loginPending = loginJobSnapshot?.isActive == true && !isOwner(senderId)
                                        val myTurn = synchronized(adoptionGateLock) { adoptionWaiters.firstOrNull()?.first == update.updateId }
                                        if (!loginPending && myTurn) break
                                        if (android.os.SystemClock.elapsedRealtime() >= waitDeadline) {
                                            adoptionRejected = true
                                            break
                                        }
                                        delay(50)
                                    }
                                    if (adoptionRejected && isOwner(senderId) &&
                                        synchronized(adoptionGateLock) { adoptionWaiters.firstOrNull()?.first == update.updateId }) {
                                        adoptionRejected = false
                                    }
                                    if (!adoptionRejected && loginJobSnapshot?.isCompleted == true && isOwner(senderId)) {
                                        adoptionDoneSec = serverNowSec()
                                    }
                                    if (!adoptionRejected && !isOwner(senderId) && loginJobSnapshot?.isCompleted == true) {
                                        followFailedSilently = true
                                        android.util.Log.w("MonitoringService", "Perintah kontrol dilewati diam-diam: login yang diikuti selesai tanpa adopsi (kegagalan sudah diberitahukan oleh jalur login)")
                                    }
                                    if (adoptionRejected) {
                                        if (isOwner(senderId)) {
                                            android.util.Log.w("MonitoringService", "Perintah kontrol ditolak: tenggat tunggu habis di antrean sesama penunggu adopsi")
                                            try { sendDirect(senderId, "\u23F3\uFE0F Perintah ditolak: antrean perintah sesudah login terlalu lama. Login sebenarnya sudah berhasil — kirim perintah lagi.") } catch (_: Exception) { }
                                        } else {
                                            android.util.Log.w("MonitoringService", "Perintah kontrol ditolak: adopsi login melewati tenggat tunggu")
                                            try { sendDirect(senderId, "\u23F3\uFE0F Perintah ditolak: login belum selesai terlalu lama. Pastikan /login berhasil, lalu kirim perintah lagi.") } catch (_: Exception) { }
                                        }
                                    }
                                }
                                if (control && !adoptionWait && !followFailedSilently && !followTimedOut) {
                                    val courtesyDeadline = android.os.SystemClock.elapsedRealtime() + ADOPTION_WAIT_DEADLINE_MS
                                    while (synchronized(adoptionGateLock) { adoptionWaiters.any { it.second == senderId && it.first < update.updateId } }) {
                                        if (android.os.SystemClock.elapsedRealtime() >= courtesyDeadline) {
                                            android.util.Log.w("MonitoringService", "Perintah kontrol owner mendahului penunggu adopsi lama sesudah tenggat kesopanan gerbang")
                                            break
                                        }
                                        delay(50)
                                    }
                                }
                                if (!adoptionRejected && !followFailedSilently && !followTimedOut) lock.withLock {
                                    val ownerNow = isOwner(senderId)
                                    val privateNow = ownerNow && isPrivate
                                    val restoreHandled = privateNow && message != null && handleRestoreIncoming(message, msgChatId)
                                    if (!restoreHandled) {
                                        val balasAngka = consumePendingInput(rawText, messageDate, ownerNow, chatOk, senderId, isPrivate, msgChatId, adoptionDoneSec)
                                        if (!balasAngka && command.startsWith("/")) {
                                            val input = aliasInput ?: if (arg.isEmpty()) command else "$command $arg"
                                            handleTelegramCommand(input, messageDate, ownerNow, chatOk, senderId = senderId, privateChat = isPrivate, adoptionDoneSec = adoptionDoneSec)
                                        } else if (!balasAngka) {
                                            if (aliasInput != null && privateNow) {
                                                handleTelegramCommand(aliasInput, messageDate, ownerNow, chatOk, senderId, true, adoptionDoneSec)
                                            } else if (intentInput != null && privateNow) {
                                                handleTelegramCommand(intentInput, messageDate, ownerNow, chatOk, senderId, true, adoptionDoneSec)
                                            }
                                        }
                                    }
                                    Unit
                                }
                            } finally {
                                if (adoptionWait) {
                                    synchronized(adoptionGateLock) { adoptionWaiters.removeAll { it.first == update.updateId } }
                                }
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                delivered = false
                throw e
            } catch (e: Exception) {
                delivered = false
                android.util.Log.e("MonitoringService", "Update dispatch error: ${redactToken(e.message)}")
            } finally {
                if (!offsetDeferred) {
                    if (!delivered) forgetSeen(update.updateId)
                    finishUpdateTrack(update.updateId, delivered)
                }
            }
        }
        return true
    }

    private val updateOffsetTracker = com.redeye.parentalmonitor.utils.UpdateOffsetTracker()

    private fun dispatchTracked(updateId: Long, logTag: String, block: suspend () -> Unit): kotlinx.coroutines.Job {
        return serviceScope.launch {
            var handled = false
            try {
                try {
                    block()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "$logTag: ${redactToken(e.message)}")
                }
                handled = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MonitoringService", "$logTag: ${redactToken(e.message)}")
            } finally {
                if (!handled) forgetSeen(updateId)
                finishUpdateTrack(updateId, handled)
            }
        }
    }


    private fun beginUpdateTrack(updateId: Long) {
        updateOffsetTracker.begin(updateId)
    }

    private fun finishUpdateTrack(updateId: Long, delivered: Boolean) {
        updateOffsetTracker.finish(updateId, delivered)?.let { persistUpdateOffset(it) }
    }

    private fun persistUpdateOffset(updateId: Long) {
        synchronized(offsetPersistLock) {
            try {
                if (skipUpdateOffsetOnce) {
                    skipUpdateOffsetOnce = false
                } else if (updateId > preferencesManager.lastUpdateId) {
                    preferencesManager.setLastUpdateIdSync(updateId)
                    if (preferencesManager.lastUpdateId < updateId) {
                        commandBackoffUntil = maxOf(commandBackoffUntil, android.os.SystemClock.elapsedRealtime() + 60_000L)
                        android.util.Log.w("MonitoringService", "offset persist not sticking, backing off polling")
                    }
                }
                Unit
            } catch (_: Exception) {
                try {
                    commandBackoffUntil = maxOf(commandBackoffUntil, android.os.SystemClock.elapsedRealtime() + 60_000L)
                } catch (_: Exception) {
                }
                android.util.Log.w("MonitoringService", "offset persist failed")
            }
        }
    }

    private suspend fun handleCallbackQuery(query: com.redeye.parentalmonitor.network.TelegramCallbackQuery) {
        val sender = query.from?.id?.toString() ?: run {
            try { answerCallback(query.id) } catch (_: Exception) { }
            return
        }
        val chatId = try { preferencesManager.chatId } catch (_: Exception) { "" }
        val originChat = try { query.message?.chat?.id?.toString().orEmpty() } catch (_: Exception) { "" }
        val ownerOk = isOwner(sender)
        if (cachedAccessPinActive && cachedOwnerId == 0L && !ownerOk) {
            answerCallback(query.id)
            return
        }
        if (sender != chatId && originChat != chatId && !ownerOk) {
            answerCallback(query.id)
            return
        }
        if ((chatId.startsWith("-") || originChat.startsWith("-")) && !ownerOk && cachedOwnerId != 0L) {
            answerCallback(query.id)
            return
        }
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            try { answerCallback(query.id) } catch (_: Exception) { }
            val noticeKey = "authblock:$sender"
            val noticeNow = android.os.SystemClock.elapsedRealtime()
            val noticeLast = try { pairHintAt[noticeKey] ?: 0L } catch (_: Exception) { 0L }
            if (noticeLast == 0L || noticeNow - noticeLast >= 3_600_000L) {
                try {
                    if (pairHintAt.size > 200) pairHintAt.clear()
                    pairHintAt[noticeKey] = noticeNow
                } catch (_: Exception) {
                }
                try {
                    if (messageQueue.addMessage("⚠️ Sebuah tombol menu ditekan saat kredensial bot sedang diblokir (token ditolak Telegram), jadi tombol itu tidak dijalankan. Tunggu sampai blokir berakhir (maks ±30 menit sejak token ditolak), lalu tekan lagi.")) {
                        MessageScheduler.scheduleMessageSend(this)
                    }
                } catch (_: Exception) {
                }
            }
            return
        }
        val dupCallback = markSeen(handledCallbackIds, query.id)
        try { answerCallback(query.id) } catch (_: Exception) { }
        if (dupCallback) return
        val aksi = com.redeye.parentalmonitor.utils.MenuCallback.parse(query.data.orEmpty()) ?: return
        if (aksi is com.redeye.parentalmonitor.utils.MenuCallback.Navigate) {
            if (!ownerOk) return
            val layar = menuScreen(aksi.view)
            val pesanId = try { query.message?.messageId ?: 0L } catch (_: Exception) { 0L }
            if (aksi.editInPlace && pesanId > 0L) {
                val targetChat = originChat.ifEmpty { chatId }
                if (editMenuMessage(targetChat, pesanId, layar)) return
            }
            sendToTelegram(layar.text, layar.markup)
            return
        }
        val pressedAt = try {
            query.message?.date ?: 0L
        } catch (_: Exception) {
            0L
        }
        if (aksi is com.redeye.parentalmonitor.utils.MenuCallback.AskInput) {
            if (!ownerOk) return
            if (pressedAt <= 0L || serverNowSec() - pressedAt > MUTATING_MAX_AGE_SEC) {
                sendToTelegram("\u231B Menu kedaluwarsa, ini yang baru.", mainMenu())
                return
            }
            controlLock.withLock { armInput("/" + aksi.command, aksi.view, originChat) }
            return
        }
        val command = "/" + (aksi as com.redeye.parentalmonitor.utils.MenuCallback.Run).command
        val originOk = originChat.isNotEmpty() && originChat == chatId
        val gateCommand = command.substringBefore(" ")
        if (gateCommand in MUTATING_COMMANDS || gateCommand in SENSITIVE_COMMANDS) {
            if (pressedAt <= 0L || serverNowSec() - pressedAt > MUTATING_MAX_AGE_SEC) {
                if (ownerOk) sendToTelegram("\u231B Menu kedaluwarsa, ini yang baru.", mainMenu())
                return
            }
        }
        controlLock.withLock {
            handleTelegramCommand(command, pressedAt, ownerOk, originOk, sender, originChat.isNotEmpty() && originChat == sender)
        }
    }

    private suspend fun editMenuMessage(chatId: String, messageId: Long, layar: MenuScreen): Boolean {
        try {
            if (chatId.isEmpty() || messageId <= 0L) return false
            val botToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
            if (botToken.isEmpty() || !hasNetwork()) return false
            val body = com.redeye.parentalmonitor.network.EditMessageRequest(
                chatId = chatId,
                messageId = messageId,
                text = layar.text,
                replyMarkup = layar.markup
            )
            val url = "https://api.telegram.org/bot${botToken}/editMessageText"
            val response = TelegramClient.api.editMessageText(url, body)
            if (response.isSuccessful && response.body()?.ok == true) return true
            val galat = try { response.errorBody()?.string().orEmpty() } catch (_: Exception) { "" }
            return galat.contains("not modified", ignoreCase = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
    }

    private fun postAuthFailureReminder(code: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val lastReminder = authReminderAt.get()
        if (lastReminder != 0L && now - lastReminder < 6 * 60 * 60_000L) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            authReminderAt.set(now)
            val tap = setupTapIntent()
            val title = when (code) {
                400 -> "Chat tak ditemukan (400)"
                403 -> "Bot diblokir/dikeluarkan dari chat (403)"
                else -> "Token bot ditolak ($code)"
            }
            val text = when (code) {
                400 -> "Buka aplikasi, lalu perbaiki Chat ID di halaman Setup."
                403 -> "Buka Telegram: buka blokir bot atau undang lagi bot ke chat, lalu kirim /status (perintah dibaca lagi dalam ±5 menit)."
                else -> "Buka aplikasi, lalu perbaiki token di halaman Setup."
            }
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
            com.redeye.parentalmonitor.network.BotCommand("help", "Bantuan / menu tombol"),
            com.redeye.parentalmonitor.network.BotCommand("ping", "Cek delay")
        )
    }

    private fun publicCommandList(includeLogin: Boolean = false): List<com.redeye.parentalmonitor.network.BotCommand> {
        val commands = mutableListOf(
            com.redeye.parentalmonitor.network.BotCommand("ping", "Cek delay"),
            com.redeye.parentalmonitor.network.BotCommand("start", "Mulai / tautkan HP ini"),
            com.redeye.parentalmonitor.network.BotCommand("help", "Bantuan")
        )
        if (includeLogin) {
            commands.add(0, com.redeye.parentalmonitor.network.BotCommand("login", "Login PIN saat owner belum tertaut"))
        }
        return commands
    }

    private suspend fun clearChatCommands(chatId: Long) {
        if (chatId == 0L) return
        try {
            val botToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
            if (botToken.isEmpty()) return
            TelegramClient.api.deleteMyCommands(
                "https://api.telegram.org/bot$botToken/deleteMyCommands",
                com.redeye.parentalmonitor.network.DeleteCommandsRequest(
                    com.redeye.parentalmonitor.network.BotCommandScope("chat", chatId)
                )
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun registerBotCommands(force: Boolean = false) {
        try {
            val botToken = preferencesManager.botToken
            if (botToken.isEmpty()) return
            val ownerId = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
            val accessPinActive = try { preferencesManager.accessPin.isNotEmpty() } catch (_: Exception) { false }
            val tokenHash = Sha256Hex.hex(botToken + ":" + ownerId + ":" + accessPinActive + ":" + com.redeye.parentalmonitor.BuildConfig.VERSION_CODE)
            if (!force) {
                try {
                    if (preferencesManager.commandsTokenHash == tokenHash) return
                } catch (_: Exception) {
                }
            }
            val url = "https://api.telegram.org/bot$botToken/setMyCommands"
            val publicResp = TelegramClient.api.setMyCommands(url, com.redeye.parentalmonitor.network.SetMyCommandsRequest(publicCommandList(ownerId == 0L && accessPinActive)))
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
                preferencesManager.commandsTokenHash = tokenHash
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Bot command menu registered")
            } else {
                commandsRegFailAt = System.currentTimeMillis()
                android.util.Log.w("MonitoringService", "Command menu registration failed: ${publicResp.code()}")
            }
        } catch (e: Exception) {
            commandsRegFailAt = System.currentTimeMillis()
            android.util.Log.w("MonitoringService", "Command menu registration error: ${redactToken(e.message)}")
        }
    }

    private suspend fun maybeRetryBotCommands() {
        try {
            val failAt = commandsRegFailAt
            if (failAt <= 0L) return
            val now = System.currentTimeMillis()
            if (failAt > now || now - failAt < 3_600_000L) return
            commandsRegFailAt = 0L
            registerBotCommands()
        } catch (_: Exception) {
        }
    }

    private data class MenuScreen(
        val text: String,
        val markup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup
    )

    private fun mainMenu(): com.redeye.parentalmonitor.network.InlineKeyboardMarkup {
        return menuScreen("main").markup
    }

    private fun quickMenuBar(): com.redeye.parentalmonitor.network.InlineKeyboardMarkup {
        fun button(text: String, data: String) =
            com.redeye.parentalmonitor.network.InlineButton(text, data)
        return com.redeye.parentalmonitor.network.InlineKeyboardMarkup(
            listOf(
                listOf(button("\uD83C\uDFE0 Menu utama", "menu:open"))
            )
        )
    }

    private val menuBarGson = com.google.gson.Gson()

    private fun menuBarBody(): okhttp3.RequestBody {
        return menuBarGson.toJson(quickMenuBar()).toRequestBody("text/plain".toMediaTypeOrNull())
    }

    private fun menuScreen(id: String): MenuScreen {
        fun button(text: String, data: String) =
            com.redeye.parentalmonitor.network.InlineButton(text, data)
        fun papan(vararg baris: List<com.redeye.parentalmonitor.network.InlineButton>) =
            com.redeye.parentalmonitor.network.InlineKeyboardMarkup(baris.toList())
        val menuUtama = button("\uD83C\uDFE0 Menu utama", "m:main")
        return when (id) {
            "media" -> MenuScreen(
                "\uD83D\uDCF7 <b>Foto &amp; rekam</b>\nTombol kamera memilih lensa untuk foto berikutnya.",
                papan(
                    listOf(button("\uD83D\uDCF8 Foto", "photo"), button("\uD83D\uDDA5\uFE0F Tangkap layar", "shot")),
                    listOf(button("\uD83E\uDD33 Pakai kamera depan", "camfront"), button("\uD83D\uDCF7 Pakai kamera belakang", "camback")),
                    listOf(button("\uD83C\uDF99 Rekam audio", "num:media:record"), button("\uD83C\uDFAC Rekam video", "num:media:recordvideo")),
                    listOf(menuUtama)
                )
            )
            "lokasi" -> MenuScreen(
                "\uD83D\uDCCD <b>Lokasi &amp; jejak</b>\nGeofence rumah/sekolah + jemput.",
                papan(
                    listOf(button("\uD83D\uDCCD Lokasi", "location"), button("\uD83E\uDDB6 Jejak hari ini", "x:jejak")),
                    listOf(button("\uD83C\uDFE0 Jemput", "num:lokasi:jemput"), button("\uD83D\uDE97 Kendara", "x:kendara status")),
                    listOf(button("\uD83D\uDDFA\uFE0F Rumah", "i:lokasi:home"), button("\uD83C\uDFEB Sekolah", "i:lokasi:school")),
                    listOf(menuUtama)
                )
            )
            "komu" -> MenuScreen(
                "\uD83D\uDCF1 <b>Komunikasi</b>\nPanggilan, SMS, kontak, notifikasi.",
                papan(
                    listOf(button("\uD83D\uDCDE Panggilan", "lastcalls"), button("\uD83D\uDCAC SMS terakhir", "lastsms")),
                    listOf(button("\u2709\uFE0F Kirim SMS", "i:komu:sms"), button("\u2705 SMS tertunda", "x:smsconfirm")),
                    listOf(button("\uD83D\uDD14 Notifikasi", "x:notif status"), button("\uD83D\uDCE8 Notifikasi terakhir", "x:lastnotif")),
                    listOf(button("\uD83D\uDC65 Cari kontak", "i:komu:contacts"), button("\uD83D\uDCC7 Semua kontak", "x:contacts 1")),
                    listOf(button("\uD83D\uDCDC Riwayat nomor", "i:komu:history")),
                    listOf(menuUtama)
                )
            )
            "info" -> MenuScreen(
                "\u2139\uFE0F <b>Info &amp; data</b>\nStatus, izin, penyimpanan, aplikasi.",
                papan(
                    listOf(button("\uD83D\uDCCA Status", "status"), button("\uD83D\uDCCB Ringkas", "x:ringkas")),
                    listOf(button("\uD83D\uDD0B Baterai", "battery"), button("\u23F1\uFE0F Uptime", "x:uptime")),
                    listOf(button("\uD83C\uDFF7\uFE0F Versi", "x:version"), button("\uD83D\uDD10 Izin", "x:perms")),
                    listOf(button("\uD83D\uDCBE Penyimpanan", "x:storage"), button("\uD83D\uDCE6 Aplikasi", "x:apps")),
                    listOf(button("\uD83D\uDCC8 Pemakaian layar", "i:info:layar"), button("\uD83D\uDC93 Sinyal hidup", "alive")),
                    listOf(button("\uD83E\uDDEA Uji anti-mati", "cekmati"), button("\uD83E\uDDFE Audit", "x:audit")),
                    listOf(menuUtama)
                )
            )
            "pantau" -> MenuScreen(
                "\uD83D\uDEE1\uFE0F <b>Pengawasan</b>\nTombol nyala/mati langsung jalan.",
                papan(
                    listOf(button("\u25B6\uFE0F Patroli nyala", "x:patrol on"), button("\u23F9\uFE0F Patroli mati", "x:patrol off")),
                    listOf(button("\uD83C\uDF19 Malam nyala", "x:night on"), button("\uD83C\uDF11 Malam mati", "x:night off")),
                    listOf(button("\uD83D\uDD24 Awasi kata", "i:pantau:watch"), button("\uD83D\uDD22 Awasi nomor", "i:pantau:watchnomor")),
                    listOf(button("\uD83D\uDCF1 Alert app", "x:appalert status"), button("\uD83D\uDD75\uFE0F Pantau app", "x:pantauapp status")),
                    listOf(button("\uD83D\uDEE1\uFE0F Proteksi", "x:lindungi status"), button("\uD83D\uDD58 Jam pulang", "i:pantau:jampulang")),
                    listOf(button("\uD83D\uDCF6 Hemat data", "x:hematdata status"), button("\uD83D\uDC9A Pemulihan", "x:sembuh")),
                    listOf(button("\uD83D\uDCCA Kewaspadaan", "x:waspada"), button("\uD83C\uDD98 HP hilang", "x:hilang")),
                    listOf(menuUtama)
                )
            )
            "alat" -> MenuScreen(
                "⚙️ <b>Alat &amp; kontrol</b>\nJeda, kunci, restart, antrean.",
                papan(
                    listOf(button("⏸️ Jeda 60 mnt", "pause60"), button("▶️ Lanjut", "resume")),
                    listOf(button("\uD83D\uDD12 Kunci layar", "x:lock"), button("\uD83D\uDD14 Bunyi HP", "num:alat:ring")),
                    listOf(button("\uD83D\uDE80 Kirim antrean", "x:flush"), button("\uD83D\uDDD1\uFE0F Buang antrean", "x:clearqueue")),
                    listOf(button("\uD83D\uDD04 Restart", "x:restart"), button("\u23F9\uFE0F Hentikan", "stop")),
                    listOf(button("\uD83E\uDDF9 Bersihkan media 30 hari", "x:bersihkan media 30")),
                    listOf(button("\uD83D\uDCE5 Pulihkan setelan", "x:pulihkan"), button("\uD83D\uDEAA Logout owner", "x:logout")),
                    listOf(menuUtama)
                )
            )
            else -> MenuScreen(
                "\uD83E\uDD16 <b>Menu utama</b>\nPilih tombol cepat, atau buka kategori di bawah.",
                papan(
                    listOf(button("\uD83D\uDCF8 Foto", "photo"), button("\uD83D\uDDA5\uFE0F Tangkap layar", "shot")),
                    listOf(button("\uD83D\uDCCD Lokasi", "location"), button("\uD83D\uDCCA Status", "status")),
                    listOf(button("\uD83D\uDD0B Baterai", "battery"), button("\uD83D\uDEA8 Darurat", "x:darurat")),
                    listOf(button("\uD83D\uDCF7 Media", "m:media"), button("\uD83D\uDCF1 Komunikasi", "m:komu")),
                    listOf(button("\uD83D\uDDFA\uFE0F Lokasi & jejak", "m:lokasi"), button("\uD83D\uDEE1\uFE0F Pengawasan", "m:pantau")),
                    listOf(button("\u2139\uFE0F Info & data", "m:info"), button("⚙️ Alat & kontrol", "m:alat")),
                )
            )
        }
    }

    private data class NumberInput(
        val command: String,
        val button: String,
        val range: String,
        val unit: String,
        val required: Boolean,
        val note: String
    )

    private val numberInputs = listOf(
        NumberInput("/photointerval", "\uD83D\uDCF8 Interval foto", "0-60", "menit", true, "Atur jeda foto otomatis. 0 = foto hanya lewat /photo."),
        NumberInput("/patrolinterval", "\uD83D\uDEB6 Interval patroli", "5-180", "menit", true, "Atur jeda patroli otomatis (foto + lokasi)."),
        NumberInput("/pause", "⏸️ Jeda foto", "1-480", "menit", true, "Jeda foto otomatis sekian menit; foto manual /photo tetap bisa."),
        NumberInput("/syncinterval", "\uD83D\uDD04 Interval sinkron", "1-1440", "menit", true, "Atur jeda putaran sinkronisasi data ke Telegram."),
        NumberInput("/record", "\uD83C\uDF99 Rekam audio", "5-600", "detik", true, "Rekam suara sekitar HP sekian detik lalu kirim ke sini."),
        NumberInput("/recordvideo", "\uD83C\uDFAC Rekam video", "5-180", "detik", true, "Rekam video sekian detik lalu kirim ke sini."),
        NumberInput("/ring", "\uD83D\uDD14 Bunyi HP", "5-60", "detik", false, "Bunyikan HP sekian detik (bawaan 15)."),
        NumberInput("/heartbeat", "\uD83D\uDC93 Denyut kabar", "0-48", "jam", false, "Kabar masih hidup tiap sekian jam; 0 mematikan."),
        NumberInput("/jemput", "\uD83C\uDFE0 Jemput", "50-5000", "meter", false, "Kabari sekali saat anak dekat rumah sekian meter (butuh /home). Ketik /jemput off untuk mematikan, /jemput untuk status.")
    )

    @Volatile
    private var pendingInputCommand = ""

    @Volatile
    private var pendingInputAt = 0L

    @Volatile
    private var pendingInputChat = ""

    private val inputLock = Any()

    private fun numberInputFor(command: String): NumberInput? {
        val head = command.trim().substringBefore(" ").lowercase(java.util.Locale.ROOT)
        return numberInputs.firstOrNull { it.command == head }
    }

    private data class TextInput(
        val command: String,
        val view: String,
        val prompt: String,
        val example: String,
        val note: String
    )

    private val textInputs = listOf(
        TextInput("/sms", "komu", "Kirim <b>nomor</b> lalu <b>isi pesannya</b>.", "08123456789 Halo", "Kirim SMS dari HP ini. Nomor minimal 7 digit."),
        TextInput("/contacts", "komu", "Kirim nama/nomor kontak, atau angka halaman untuk daftar semua.", "budi", "Cari kontak; ketik 1 untuk halaman pertama daftar semua (butuh izin Kontak)."),
        TextInput("/history", "komu", "Kirim nomor telepon (7-20 digit).", "08123456789", "Tampilkan 5 panggilan + 5 SMS terakhir nomor itu."),
        TextInput("/layar", "info", "Kirim jumlah jam 1-24 (kosongkan untuk 24 jam).", "6", "Ringkasan pemakaian layar per aplikasi."),
        TextInput("/watch", "pantau", "Kirim kata yang diawasi (pisahkan koma, min 3 huruf).", "judol,pinjol", "Pantau kata terlarang. Ketik /watch off untuk mematikan."),
        TextInput("/watchnomor", "pantau", "Kirim nomor yang diawasi (pisahkan koma, min 7 digit).", "08123456789", "Kabar bila nomor itu muncul. Ketik /watchnomor off untuk mematikan."),
        TextInput("/jampulang", "pantau", "Kirim jam format HH:MM.", "21:30", "Kabar bila di luar rumah lewat jam itu. Ketik /jampulang off untuk mematikan."),
        TextInput("/home", "lokasi", "Kirim lat,lon,radius (radius 50-5000 m).", "-6.2,106.8,200", "Geofence rumah. Ketik /home off untuk mematikan, /home untuk status."),
        TextInput("/school", "lokasi", "Kirim lat,lon,radius (radius 50-5000 m).", "-6.2,106.8,300", "Geofence sekolah. Ketik /school off untuk mematikan, /school untuk status.")
    )

    private fun textInputFor(command: String): TextInput? {
        val head = command.trim().substringBefore(" ").lowercase(java.util.Locale.ROOT)
        return textInputs.firstOrNull { it.command == head }
    }

    private fun textInputPrompt(input: TextInput): String {
        return buildString {
            appendLine("\u270F\uFE0F <b>" + input.command + "</b>")
            appendLine(input.prompt)
            appendLine("Contoh: <code>" + input.example + "</code>")
            appendLine("Balas <code>help</code> untuk keterangan tombol ini.")
        }.trim()
    }

    private fun textInputHelp(input: TextInput): String {
        return buildString {
            appendLine("\u2139\uFE0F <b>" + input.command + "</b>")
            appendLine(input.note)
            appendLine("Pakai: <code>" + input.command + " ...</code>")
            appendLine("Contoh: <code>" + input.command + " " + input.example + "</code>")
        }.trim()
    }

    private fun numberInputCurrent(input: NumberInput): String {
        return try {
            when (input.command) {
                "/photointerval" -> {
                    val menit = preferencesManager.cameraInterval
                    if (menit <= 0) "mati (manual)" else "$menit mnt"
                }
                "/patrolinterval" -> {
                    val menit = preferencesManager.patrolInterval
                    "$menit mnt" + (if (preferencesManager.patrolEnabled) "" else " (patroli mati)")
                }
                "/pause" -> {
                    val sisa = preferencesManager.photoPausedUntil - System.currentTimeMillis()
                    if (sisa > 0L) "jeda " + (sisa / 60_000L + 1L) + " mnt lagi" else "sedang tidak dijeda"
                }
                "/syncinterval" -> preferencesManager.syncInterval.toString() + " mnt"
                "/heartbeat" -> {
                    val jam = preferencesManager.heartbeatHours
                    if (jam <= 0) "mati" else "tiap $jam jam"
                }
                "/jemput" -> {
                    val meter = preferencesManager.jemputRadiusM
                    if (meter <= 0) "mati" else "±$meter m"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun numberInputPrompt(input: NumberInput): String {
        val kini = numberInputCurrent(input)
        return buildString {
            appendLine("\uD83D\uDD22 <b>" + input.button + "</b>")
            appendLine("Kirim angkanya (" + input.range + " " + input.unit + ").")
            appendLine("Balas <code>help</code> untuk keterangan tombol ini.")
            if (kini.isNotEmpty()) appendLine("Sekarang: " + kini)
        }.trim()
    }

    private val buttonNumberCommands = setOf("/record", "/recordvideo", "/jemput", "/ring")

    private fun numberInputHelp(input: NumberInput): String {
        val kini = numberInputCurrent(input)
        return buildString {
            appendLine("\u2139\uFE0F <b>" + input.button + "</b>")
            appendLine(input.note)
            appendLine("Pakai: <code>" + input.command + " &lt;" + input.range + "&gt;</code> (" + input.unit + ")")
            if (kini.isNotEmpty()) appendLine("Sekarang: " + kini)
            if (input.command in buttonNumberCommands) {
                appendLine("Cara cepat: tekan tombolnya, lalu balas angkanya (contoh <code>" + numberInputExample(input) + "</code>).")
            } else {
                appendLine("Balas angkanya saja (contoh <code>" + numberInputExample(input) + "</code>). Perintah ini tidak punya tombol di menu; prompt ini muncul setelah kamu mengetik perintahnya.")
            }
        }.trim()
    }

    private fun numberInputExample(input: NumberInput): String {
        return when (input.command) {
            "/photointerval" -> "15"
            "/patrolinterval" -> "30"
            "/pause" -> "60"
            "/syncinterval" -> "5"
            "/record" -> "30"
            "/recordvideo" -> "10"
            "/ring" -> "15"
            "/heartbeat" -> "6"
            "/jemput" -> "500"
            else -> "10"
        }
    }

    private suspend fun armInput(command: String, view: String, chatKey: String) {
        val angka = numberInputFor(command)
        if (angka != null) {
            synchronized(inputLock) {
                pendingInputCommand = angka.command
                pendingInputAt = android.os.SystemClock.elapsedRealtime()
                pendingInputChat = chatKey
            }
            val layar = com.redeye.parentalmonitor.utils.MenuCallback.viewName(view)
            sendToTelegram(numberInputPrompt(angka), menuScreen(layar).markup)
            return
        }
        val teks = textInputFor(command) ?: return
        synchronized(inputLock) {
            pendingInputCommand = teks.command
            pendingInputAt = android.os.SystemClock.elapsedRealtime()
            pendingInputChat = chatKey
        }
        val layar = com.redeye.parentalmonitor.utils.MenuCallback.viewName(view.ifEmpty { teks.view })
        sendToTelegram(textInputPrompt(teks), menuScreen(layar).markup)
    }

    private suspend fun consumePendingInput(text: String, sentAtSec: Long, ownerOk: Boolean, chatOk: Boolean, senderId: String, privateChat: Boolean, chatKey: String, adoptionDoneSec: Long = 0L): Boolean {
        if (!ownerOk) return false
        val bersih = text.trim()
        if (bersih.isEmpty()) return false
        val pending = synchronized(inputLock) {
            val current = pendingInputCommand
            if (current.isEmpty()) return false
            if (android.os.SystemClock.elapsedRealtime() - pendingInputAt > NUMBER_INPUT_TTL_MS) {
                pendingInputCommand = ""
                pendingInputChat = ""
                return false
            }
            if (pendingInputChat.isNotEmpty() && pendingInputChat != chatKey) return false
            current
        }
        if (bersih.startsWith("/")) {
            synchronized(inputLock) {
                if (pendingInputCommand == pending) {
                    pendingInputCommand = ""
                    pendingInputChat = ""
                }
            }
            return false
        }
        val mintaHelp = bersih.equals("help", ignoreCase = true) || bersih.equals("bantuan", ignoreCase = true) || bersih == "?" || bersih.equals("cara", ignoreCase = true)
        val angka = numberInputFor(pending)
        if (angka != null) {
            if (mintaHelp) {
                sendToTelegram(numberInputHelp(angka))
                return true
            }
            val nilai = com.redeye.parentalmonitor.utils.NumberReply.parse(bersih)
            if (nilai == null) {
                synchronized(inputLock) {
                    if (pendingInputCommand == pending) pendingInputAt = android.os.SystemClock.elapsedRealtime()
                }
                sendToTelegram("Kirim angkanya saja, contoh <code>" + numberInputExample(angka) + "</code>.\nBalas <code>help</code> untuk keterangan.", mainMenu())
                return true
            }
            if (!com.redeye.parentalmonitor.utils.NumberReply.inRange(nilai, angka.range)) {
                synchronized(inputLock) {
                    if (pendingInputCommand == pending) pendingInputAt = android.os.SystemClock.elapsedRealtime()
                }
                sendToTelegram("Angka " + nilai + " di luar jangkauan (" + angka.range + " " + angka.unit + "). Kirim lagi, contoh <code>" + numberInputExample(angka) + "</code>.", mainMenu())
                return true
            }
            val taken = synchronized(inputLock) {
                if (pendingInputCommand != pending) {
                    false
                } else {
                    pendingInputCommand = ""
                    pendingInputChat = ""
                    true
                }
            }
            if (!taken) return false
            handleTelegramCommand(angka.command + " " + nilai, sentAtSec, ownerOk, chatOk, senderId, privateChat, adoptionDoneSec)
            return true
        }
        val teks = textInputFor(pending)
        if (teks == null) {
            synchronized(inputLock) {
                if (pendingInputCommand == pending) {
                    pendingInputCommand = ""
                    pendingInputChat = ""
                }
            }
            return false
        }
        if (mintaHelp) {
            sendToTelegram(textInputHelp(teks))
            return true
        }
        val taken = synchronized(inputLock) {
            if (pendingInputCommand != pending) {
                false
            } else {
                pendingInputCommand = ""
                pendingInputChat = ""
                true
            }
        }
        if (!taken) return false
        handleTelegramCommand(teks.command + " " + bersih, sentAtSec, ownerOk, chatOk, senderId, privateChat, adoptionDoneSec)
        return true
    }

    private suspend fun handleTelegramCommand(raw: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, senderId: String = "", privateChat: Boolean = false, adoptionDoneSec: Long = 0L) {
        val nowSec = serverNowSec()
        if (sentAtSec > 0 && nowSec - sentAtSec > COMMAND_SILENT_AGE_SEC) return
        if (sentAtSec > 0 && nowSec - sentAtSec > COMMAND_MAX_AGE_SEC) {
            serviceScope.launch {
                sendToTelegram("\u23F3\uFE0F Command kedaluwarsa, kirim lagi.")
            }
            return
        } else if (sentAtSec > 0 && sentAtSec - nowSec > COMMAND_MAX_AGE_SEC) {
            serviceScope.launch {
                sendToTelegram("⏳️ Waktu perintah ada di masa depan. Periksa jam perangkat, lalu kirim ulang.")
            }
            return
        }
        val parts = raw.split(CMD_SPLIT_REGEX, limit = 2)
        val command = parts[0]
        val arg = parts.getOrNull(1)?.trim().orEmpty()
        try {
            handleTelegramCommandInner(command, arg, sentAtSec, senderOk, chatOk, senderId = senderId, privateChat = privateChat, adoptionDoneSec = adoptionDoneSec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val redacted = redactToken(e.message)
            android.util.Log.e("MonitoringService", "Command failed: $command ($redacted)")
            try {
                val detail = redacted.ifEmpty { "galat tak dikenal" }
                sendToTelegram("⚠️ Perintah ${Html.escape(command)} gagal (${Html.escape(detail)}). Coba lagi atau kirim /help.")
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleTelegramCommandInner(command: String, arg: String, sentAtSec: Long = 0L, senderOk: Boolean = false, chatOk: Boolean = false, senderId: String = "", privateChat: Boolean = false, adoptionDoneSec: Long = 0L) {
        if (command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS) {
            val ageSec = when {
                adoptionDoneSec > 0L -> serverNowSec() - adoptionDoneSec
                sentAtSec <= 0L -> 0L
                else -> serverNowSec() - sentAtSec
            }
            if (ageSec > MUTATING_MAX_AGE_SEC) {
                sendToTelegram("\u23F3\uFE0F Command ${Html.escape(command)} kedaluwarsa, kirim lagi.")
                return
            }
            if (!senderOk) {
                if (privateChat) {
                    sendToTelegram("\u26D4 Hanya owner yang bisa pakai ${Html.escape(command)}.")
                } else {
                    sendToTelegram("\u26D4 ${Html.escape(command)} hanya bisa dijalankan owner dari private chat. Kirim /login &lt;PIN&gt; dari private chat owner bila PIN akses aktif, atau isi Chat ID dengan ID private chat.")
                }
                return
            }
        } else if (!senderOk && (!chatOk || (cachedChatId.startsWith("-") && cachedOwnerId != 0L))) {
            return
        }
        if (command in AUDIT_COMMANDS && senderOk) {
            audit(command.removePrefix("/") + " diterima", if (privateChat) "private" else "chat")
        }
        val numberInput = numberInputFor(command)
        if (numberInput != null && numberInput.required && arg.isBlank()) {
            armInput(numberInput.command, "", if (privateChat) senderId else cachedChatId)
            return
        }
        when (command) {
            "/photo" -> handlePhotoCommand(arg)
            "/screenshot" -> {
                if (preferencesManager.monitoringPaused) {
                    sendToTelegram("⏸️ Monitoring dijeda. Kirim /resume dulu.")
                } else {
                    sendToTelegram("\uD83D\uDDA5 Ambil layar…")
                    captureAndSendScreenshot()
                }
            }
            "/status" -> handleStatusCommand()
            "/izinshot" -> requestShotGrant()
            "/perms" -> {
                val notifOn = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
                sendToTelegram(PermReport.inventoryText(permissionRawStates(), notifOn, permLabels))
            }
            "/ringkas" -> handleRingkasCommand()
            "/lastcalls" -> handleLastCallsCommand()
            "/lastsms" -> handleLastSmsCommand()
            "/photointerval" -> handlePhotoIntervalCommand(arg)
            "/patrol" -> handlePatrolCommand(arg)
            "/patrolinterval" -> {
                val minutes = arg.toIntOrNull()
                if (minutes == null || minutes !in 5..180) {
                    sendToTelegram("Cara pakai: /patrolinterval &lt;5-180&gt;")
                } else {
                    preferencesManager.patrolInterval = minutes
                    sendToTelegram("\uD83D\uDEF0 Interval patroli diatur $minutes mnt.")
                }
            }
            "/night" -> handleNightCommand(arg)
            "/pause" -> handlePauseCommand(arg)
            "/battery" -> {
                val level = batteryLevel()
                val levelText = if (level in 0..100) "$level%" else "tak tahu"
                sendToTelegram("🔋 <b>Baterai</b>\nLevel: $levelText")
            }
            "/stop" -> {
                preferencesManager.monitoringPaused = true
                sendToTelegram("⏸️ Monitoring dijeda (panggilan, SMS, foto, dan teruskan notifikasi). Kirim /resume untuk lanjut.")
            }
            "/resume" -> handleResumeCommand()
            "/location" -> handleLocationCommand()
            "/camera" -> handleCameraCommand(arg)
            "/notif" -> handleNotifCommand(arg)
            "/log" -> handleLogCommand()
            "/syncinterval" -> handleSyncIntervalCommand(arg)
            "/restart" -> {
                if (restartAllLoops()) {
                    replyLater("\u267B\uFE0F Loops restarted.")
                } else {
                    sendToTelegram("⏳️ Restart dibatasi, coba lagi sebentar.")
                }
            }
            "/flush" -> handleFlushCommand()
            "/clearqueue" -> handleClearQueueCommand()
            "/lock" -> handleLockCommand()
            "/ring" -> handleRingCommand(arg)
            "/ping" -> handlePingCommand(arg, sentAtSec, senderOk, chatOk, senderId, privateChat)
            "/record" -> handleRecordCommand(arg)
            "/recordvideo" -> handleRecordVideoCommand(arg)
            "/sms" -> handleSmsCommand(arg, senderId)
            "/smsconfirm" -> handleSmsConfirmCommand(senderId)
            "/lastnotif" -> handleLastNotifCommand()
            "/version" -> handleVersionCommand()
            "/uptime" -> handleUptimeCommand()
            "/alive" -> {
                sendToTelegram(aliveReportText(), mainMenu())
            }
            "/cekmati" -> {
                runLivenessSelfTest()
            }
            "/sembuh" -> {
                runFullRecovery()
            }
            "/layar" -> {
                val raw = arg.trim()
                val parsed = raw.toIntOrNull()
                if (raw.isNotEmpty() && (parsed == null || parsed !in 1..24)) {
                    sendToTelegram("Cara pakai: /layar &lt;1-24&gt; (jam, bawaan 24)")
                } else {
                    sendToTelegram(screenTimeFull(parsed ?: 24))
                }
            }
            "/kendara" -> handleKendaraCommand(arg)
            "/hematdata" -> handleHematDataCommand(arg)
            "/heartbeat" -> handleHeartbeatCommand(arg)
            "/contacts" -> handleContactsCommand(arg)
            "/apps" -> handleAppsCommand(arg)
            "/storage" -> handleStorageCommand()
            "/history" -> handleHistoryCommand(arg)
            "/watch" -> handleWatchCommand(arg, privateChat)
            "/appalert" -> handleAppAlertCommand(arg)
            "/lindungi" -> handleProtectCommand(arg)
            "/pantauapp" -> handleAppWatchCommand(arg, privateChat)
            "/home", "/school" -> handleFenceCommand(command, arg, privateChat)
            "/digest" -> {
                sendDailyDigest(manual = true)
            }
            "/darurat" -> handleDaruratCommand()
            "/jampulang" -> handleJampulangCommand(arg, privateChat)
            "/minggu" -> {
                sendWeeklyDigest()
            }
            "/watchnomor" -> handleWatchNomorCommand(arg, privateChat)
            "/jejak" -> handleJejakCommand()
            "/waspada" -> {
                sendWaspada()
            }
            "/hilang" -> handleHilangCommand()
            "/jemput" -> handleJemputCommand(arg)
            "/audit" -> handleAuditCommand(privateChat)
            "/logout" -> handleLogoutCommand(privateChat)
            "/bersihkan" -> handleCleanMediaCommand(arg)
            "/pulihkan" -> handleRestoreCommand(arg, privateChat)
            "/help", "/start" -> handleHelpCommand(senderOk)
            else -> {
                if (!senderOk) return
                val hint = try { CommandSuggest.suggestCommand(command) } catch (_: Exception) { null }
                val safeCommand = try { Html.escape(command) } catch (_: Exception) { command }
                if (hint != null) sendToTelegram("❓ Perintah tak dikenal: $safeCommand. Maksudnya $hint? Kirim /help untuk daftar.")
                else sendToTelegram("❓ Perintah tak dikenal: $safeCommand. Kirim /help untuk daftar.")
            }
        }
    }

    private suspend fun handleJejakCommand() {
        val trail = trailList()
        if (trail.isEmpty()) {
            sendToTelegram("👣 Jejak kosong. Lokasi tercatat tiap patroli/perintah lokasi.")
        } else {
            val last = trail.takeLast(10)
            sendToTelegram(buildString {
                appendLine("👣 <b>Jejak (${trail.size} titik tersimpan, ${last.size} terakhir)</b>")
                for ((ts, la, lo) in last) {
                    appendLine("${TimeFmt.short(ts)} — $la,$lo")
                }
                append("https://www.google.com/maps/dir/" + last.joinToString("/") { "${it.second},${it.third}" })
            })
        }
    }

    private suspend fun handleHilangCommand() {
        val level = batteryLevel()
        if (!ringBusy.compareAndSet(false, true)) {
            sendToTelegram("⏳ Sudah bunyi, tunggu selesai — tetap kirim lokasi + foto…")
        } else if (recordBusy.get() || recorderActive.get()) {
            ringBusy.set(false)
            sendToTelegram("⏳ Merekam jalan: bunyi dilewati, kirim lokasi + foto…")
        } else {
            sendToTelegram("🚨 <b>Hilang</b>: bunyikan + lokasi + foto…")
            try { ringJob?.cancel() } catch (_: Exception) { }
            ringJob = serviceScope.launch {
                try {
                    ringDevice(60)
                } finally {
                    ringBusy.set(false)
                }
            }
        }
        val loc = try { fetchLocation(allowStale = true) } catch (_: Exception) { null }
        if (loc != null) {
            val stale = isStaleFix(loc)
            if (!stale) {
                try { rememberSentLatLon(loc.latitude, loc.longitude, loc.accuracy) } catch (_: Exception) { }
                try { checkGeofence(loc.latitude, loc.longitude, loc.accuracy) } catch (_: Exception) { }
            }
            sendToTelegram("📍 https://maps.google.com/?q=${loc.latitude},${loc.longitude}\nBaterai: ${if (level in 0..100) "$level%" else "?"}" + fixAgeNote(loc))
        } else {
            bumpLocFailCount()
            sendToTelegram("📍 Lokasi tak didapat: " + locationFailureHint() + "\nBaterai: ${if (level in 0..100) "$level%" else "?"}")
        }
        try { captureAndSendPhoto(reportResult = true) } catch (_: Exception) { }
    }

    private suspend fun handleJemputCommand(arg: String) {
        val sub = arg.trim()
        if (sub.equals("off", ignoreCase = true)) {
            preferencesManager.jemputRadiusM = 0
            sendToTelegram("🏠 Jemput dimatikan.")
        } else if (sub.isEmpty() || sub.equals("status", ignoreCase = true)) {
            val cur = try { preferencesManager.jemputRadiusM } catch (_: Exception) { 0 }
            val homeReady = try { preferencesManager.homeLatE6 != Long.MIN_VALUE && preferencesManager.homeRadiusM > 0 } catch (_: Exception) { false }
            sendToTelegram(
                when {
                    cur <= 0 -> "🏠 Jemput: mati (contoh: /jemput 500)"
                    !homeReady -> "🏠 Jemput: tersimpan ±${cur} m, tapi TIDAK aktif — geofence /home sedang mati/kosong. Hidupkan /home dulu."
                    else -> "🏠 Jemput: aktif ±${cur} m dari rumah (sekali bunyi)"
                }
            )
        } else {
            val rad = sub.toIntOrNull()
            if (rad == null || rad !in 50..5000) {
                sendToTelegram("Cara pakai: /jemput &lt;50-5000&gt; (meter) atau /jemput off")
            } else if (preferencesManager.homeLatE6 == Long.MIN_VALUE || preferencesManager.homeRadiusM <= 0) {
                sendToTelegram("Nyalakan geofence /home dulu sebelum /jemput.")
            } else {
                preferencesManager.jemputRadiusM = rad
                sendToTelegram("🏠 Jemput aktif: kabari sekali saat dalam ±${rad} m rumah.")
            }
        }
    }

    private fun audit(event: String, source: String) {
        try { preferencesManager.appendAudit(event, source) } catch (_: Exception) { }
    }

    private suspend fun handleAuditCommand(privateChat: Boolean) {
        if (!privateChat) {
            sendToTelegram("🔐 /audit hanya bisa dipakai dari private chat owner.")
            return
        }
        val raw = try { preferencesManager.auditLog } catch (_: Exception) { "" }
        if (raw.isBlank()) {
            sendToTelegram("🧾 Belum ada catatan audit.")
            return
        }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
        val body = buildString {
            appendLine("🧾 <b>Audit perintah (max 50)</b>")
            raw.split('\n').takeLast(50).forEach { line ->
                val cols = line.split('\t')
                val at = cols.getOrNull(0)?.toLongOrNull() ?: 0L
                val event = cols.getOrNull(1).orEmpty()
                val source = cols.getOrNull(2).orEmpty()
                if (event.isNotEmpty()) {
                    append(fmt.format(java.util.Date(at)))
                    append(" · ")
                    append(Html.escape(event))
                    if (source.isNotEmpty()) {
                        append(" · ")
                        append(Html.escape(source))
                    }
                    append('\n')
                }
            }
        }
        sendToTelegram(body.trim())
    }

    private suspend fun handleCleanMediaCommand(arg: String) {
        val parts = arg.trim().lowercase(java.util.Locale.ROOT).split(CMD_SPLIT_REGEX)
        val target = parts[0]
        val wantsPhoto = target in setOf("media", "semua", "all", "foto", "photo")
        val wantsVideo = target in setOf("media", "semua", "all", "video")
        val wantsAudio = target in setOf("media", "semua", "all", "rekaman", "audio", "suara")
        if (!wantsPhoto && !wantsVideo && !wantsAudio) {
            sendToTelegram("Cara pakai: /bersihkan media|foto|video|rekaman &lt;1-365&gt; (hari)")
            return
        }
        val dayArg = parts.getOrNull(1)
        val days = dayArg?.toIntOrNull() ?: 0
        if (days !in 1..365) {
            sendToTelegram("Cara pakai: /bersihkan media|foto|video|rekaman &lt;1-365&gt; (hari)")
            return
        }
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
        var count = 0
        var bytes = 0L
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (!file.isFile || isMediaActive(file)) return@forEach
                val name = file.name
                val photo = wantsPhoto && (name.startsWith("camera_") || name.startsWith("screenshot_")) && (name.endsWith(".jpg") || name.endsWith(".tmp"))
                val video = wantsVideo && name.startsWith("video_") && (name.endsWith(".mp4") || name.endsWith(".tmp"))
                val audio = wantsAudio && name.startsWith("audio_") && (name.endsWith(".m4a") || name.endsWith(".tmp"))
                val media = photo || video || audio
                if (media && file.lastModified() < cutoff) {
                    val len = file.length()
                    if (file.delete()) {
                        count++
                        bytes += len
                    }
                }
            }
        } catch (_: Exception) {
        }
        val mb = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1_048_576.0)
        sendToTelegram("🧹 Bersihkan media: $count berkas dihapus (±$mb MB), lebih lama dari $days hari.")
    }

    private suspend fun handleRestoreCommand(arg: String, privateChat: Boolean) {
        if (!privateChat) {
            sendToTelegram("🔐 /pulihkan hanya bisa dipakai dari private chat owner.")
            return
        }
        val mode = arg.trim()
        val lower = mode.lowercase(java.util.Locale.ROOT)
        if (lower == "batal" || lower == "cancel" || lower == "off") {
            val had = pendingRestore != null
            pendingRestore = null
            audit("restore batal", "private")
            sendToTelegram(if (had) "Pemulihan dibatalkan." else "Tidak ada sesi pemulihan yang berjalan.")
            return
        }
        val current = pendingRestore
        if (current?.stage == RESTORE_STAGE_CONFIRM) {
            sendToTelegram("⏳ Restore sedang menunggu konfirmasi 60 detik. Kirim /pulihkan batal untuk membatalkan.")
            return
        }
        val strict = try { preferencesManager.restoreStrict } catch (_: Exception) { false }
        if (strict) {
            val secret = try { preferencesManager.ensureRestoreCodeSecret() } catch (_: Exception) { "" }
            if (mode.isEmpty()) {
                sendToTelegram(
                    "🔐 <b>Mode restore ketat aktif</b>\n" +
                        "Kirim <code>/pulihkan &lt;kode 6 digit&gt;</code>. Kode ada di Setup → tombol <b>Kode restore ketat</b>."
                )
                return
            }
            if (secret.isEmpty() || !RestoreCode.isValid(secret, mode, System.currentTimeMillis())) {
                audit("restore kode salah", "private")
                sendToTelegram("❌ Kode restore salah/kedaluwarsa. Buka Setup untuk kode terbaru, lalu kirim /pulihkan &lt;kode&gt;.")
                return
            }
        } else if (mode.isNotEmpty()) {
            sendToTelegram("Cara pakai: /pulihkan lalu kirim file <code>.redeyebak</code>, atau /pulihkan batal.")
            return
        }
        pendingRestore = PendingRestore(RESTORE_STAGE_DOC)
        audit("restore mulai", "private")
        sendToTelegram(
            "📥 <b>Pulihkan setelan</b>\n" +
                "🔔 Permintaan restore dimulai. Kalau bukan Anda, kirim /pulihkan batal sekarang.\n\n" +
                "Kirim file backup <code>.redeyebak</code> sebagai dokumen di chat ini, lalu balas PIN backup-nya.\n\n" +
                "File berisi token bot, Chat ID, owner ID, interval sync, dan interval foto. " +
                "Sebelum restore, buka bot baru dan kirim /start sekali dari chat tujuan agar bot baru boleh mengirim ke sana. " +
                "Setelan baru hanya dipakai setelah token, chat, dan tes kirim lolos, lalu ada jeda batal 60 detik. " +
                "Kalau gagal, setelan lama tetap. Sesi berlaku 10 menit.\n\n" +
                "Ketik /pulihkan batal untuk membatalkan."
        )
    }

    private suspend fun handleRestoreIncoming(
        message: com.redeye.parentalmonitor.network.TelegramIncomingMessage,
        chatId: String
    ): Boolean {
        val pending = pendingRestore ?: return false
        if (SystemClock.elapsedRealtime() - pending.at > RESTORE_TTL_MS) {
            pendingRestore = null
            audit("restore kedaluwarsa", "private")
            sendToTelegram("⏳ Sesi pulihkan kedaluwarsa. Kirim /pulihkan lagi.")
            return true
        }
        if (pending.stage == RESTORE_STAGE_CONFIRM) {
            val text = message.text?.trim().orEmpty()
            if (text.startsWith("/")) return false
            sendToTelegram("⏳ Restore sedang menunggu konfirmasi 60 detik. Kirim /pulihkan batal untuk membatalkan.")
            return true
        }
        if (pending.stage == RESTORE_STAGE_DOC) {
            val document = message.document
            if (document == null) {
                val text = message.text?.trim().orEmpty()
                if (text.startsWith("/")) return false
                if (text.isNotEmpty()) {
                    sendToTelegram("Kirim file <code>.redeyebak</code> sebagai dokumen, atau /pulihkan batal.")
                    return true
                }
                return false
            }
            handleRestoreDocument(pending, document, message, chatId)
            return true
        }
        if (pending.stage == RESTORE_STAGE_PIN) {
            if (message.document != null) {
                sendToTelegram("Balas PIN backup sebagai teks, atau /pulihkan batal.")
                return true
            }
            val pin = message.text?.trim().orEmpty()
            if (pin.isEmpty() || pin.startsWith("/")) return false
            handleRestorePin(pending, pin, message, chatId)
            return true
        }
        return false
    }

    private suspend fun handleRestoreDocument(
        pending: PendingRestore,
        document: com.redeye.parentalmonitor.network.TelegramDocument,
        message: com.redeye.parentalmonitor.network.TelegramIncomingMessage,
        chatId: String
    ) {
        val token = try { preferencesManager.botToken } catch (_: Exception) { "" }
        if (token.isEmpty()) {
            pendingRestore = null
            sendToTelegram("Token bot saat ini kosong; buka Setup untuk memulihkan.")
            return
        }
        if (document.fileSize > RestoreRules.MAX_BACKUP_BYTES) {
            pendingRestore = null
            sendToTelegram("File terlalu besar (maks 64 KB).")
            return
        }
        val name = document.fileName.orEmpty()
        if (!RestoreRules.isBackupFileName(name)) {
            sendToTelegram("File harus berekstensi <code>.redeyebak</code>.")
            return
        }
        var blob: String? = null
        try {
            val fileId = try { URLEncoder.encode(document.fileId, "UTF-8") } catch (_: Exception) { document.fileId }
            val fileResp = TelegramClient.api.getFile("https://api.telegram.org/bot$token/getFile?file_id=$fileId")
            val filePath = fileResp.body()?.result?.filePath
            if (!fileResp.isSuccessful || filePath.isNullOrBlank()) {
                sendToTelegram("Gagal mengambil file dari Telegram. Coba kirim ulang.")
                return
            }
            val safePath = filePath.replace(" ", "%20")
            val download = TelegramMediaClient.api.downloadFile("https://api.telegram.org/file/bot$token/$safePath")
            val body = download.body()
            if (!download.isSuccessful || body == null) {
                sendToTelegram("Gagal mengunduh file backup. Coba kirim ulang.")
                return
            }
            val contentLength = body.contentLength()
            if (contentLength > RestoreRules.MAX_BACKUP_BYTES) {
                body.close()
                sendToTelegram("File terlalu besar (maks 64 KB).")
                return
            }
            val out = ByteArrayOutputStream()
            var tooBig = false
            try {
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size() + read > RestoreRules.MAX_BACKUP_BYTES) {
                            tooBig = true
                            break
                        }
                        out.write(buffer, 0, read)
                    }
                }
            } finally {
                body.close()
            }
            if (tooBig) {
                pendingRestore = null
                sendToTelegram("File terlalu besar (maks 64 KB).")
                return
            }
            blob = out.toByteArray().toString(Charsets.UTF_8).trim()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            sendToTelegram("Gagal memproses file backup. Coba lagi.")
            return
        }
        val text = blob ?: return
        try { deleteIncomingMessage(chatId, message.messageId, token) } catch (_: Exception) { }
        if (!SettingsBackup.looksStructurallyValid(text)) {
            pendingRestore = null
            sendToTelegram("File bukan backup Red Eye yang valid.")
            return
        }
        pending.blob = text
        pending.stage = RESTORE_STAGE_PIN
        pending.at = SystemClock.elapsedRealtime()
        pending.attempts = 0
        sendToTelegram("🔐 File diterima. Balas PIN/frasa sandi backup (minimal 4 karakter), atau /pulihkan batal.")
    }

    private suspend fun handleRestorePin(
        pending: PendingRestore,
        pin: String,
        message: com.redeye.parentalmonitor.network.TelegramIncomingMessage,
        chatId: String
    ) {
        val token = try { preferencesManager.botToken } catch (_: Exception) { "" }
        val data = try {
            pending.blob?.let { SettingsBackup.import(it, pin) }
        } catch (_: Exception) {
            null
        }
        try { deleteIncomingMessage(chatId, message.messageId, token) } catch (_: Exception) { }
        if (data == null) {
            pending.attempts++
            if (pending.attempts >= RESTORE_MAX_PIN_TRIES) {
                pendingRestore = null
                sendToTelegram("❌ PIN salah 3 kali. Sesi pulihkan ditutup; kirim /pulihkan lagi.")
            } else {
                sendToTelegram("❌ PIN salah. Sisa ${RESTORE_MAX_PIN_TRIES - pending.attempts} percobaan.")
            }
            return
        }
        pendingRestore = null
        val restoreEpoch = ownerEpoch
        if (!validateRestoreTarget(data)) {
            audit("restore validasi gagal", "private")
            return
        }
        if (restoreEpoch != ownerEpoch) {
            audit("restore dibatalkan setelah logout", "private")
            return
        }
        val confirm = PendingRestore(RESTORE_STAGE_CONFIRM, data = data)
        pendingRestore = confirm
        audit("restore validasi lolos", "private")
        sendToTelegram(
            "✅ Token/chat baru lolos validasi dan tes kirim.\n" +
                "Setelan akan dipakai dalam 60 detik. Kirim /pulihkan batal untuk membatalkan."
        )
        serviceScope.launch {
            delay(RESTORE_CANCEL_WINDOW_MS)
            if (pendingRestore === confirm && confirm.stage == RESTORE_STAGE_CONFIRM && restoreEpoch == ownerEpoch) {
                pendingRestore = null
                applyRestoredSettings(data)
            }
        }
    }

    private suspend fun validateRestoreTarget(data: SettingsBackup.BackupData): Boolean {
        val newToken = data.token.trim()
        val newChat = data.chatId.trim()
        if (!RestoreRules.isValidToken(newToken) || !RestoreRules.isValidChatId(newChat)) {
            sendToTelegram("⚠️ File berisi token atau Chat ID yang tidak valid. Setelan lama tidak diubah.")
            return false
        }
        try {
            val meResp = TelegramClient.api.getMe("https://api.telegram.org/bot$newToken/getMe")
            if (!meResp.isSuccessful || meResp.body()?.ok != true) {
                val code = meResp.code()
                sendToTelegram("⚠️ Token bot baru ditolak Telegram (kode $code). Setelan lama tidak diubah.")
                return false
            }
            val encodedChat = try { URLEncoder.encode(newChat, "UTF-8") } catch (_: Exception) { newChat }
            val chatResp = TelegramClient.api.getChat("https://api.telegram.org/bot$newToken/getChat?chat_id=$encodedChat")
            if (!chatResp.isSuccessful || chatResp.body()?.ok != true) {
                val code = chatResp.code()
                sendToTelegram("⚠️ Chat ID baru tidak bisa diakses bot baru (kode $code). Setelan lama tidak diubah.")
                return false
            }
            val testResp = TelegramClient.api.sendMessage(
                "https://api.telegram.org/bot$newToken/sendMessage",
                TelegramMessage(chatId = newChat, text = "🔐 Tes koneksi restore Red Eye. Setelan akan dipakai dalam 60 detik.", parseMode = null)
            )
            if (!testResp.isSuccessful || testResp.body()?.ok != true) {
                val code = testResp.code()
                sendToTelegram("⚠️ Tes kirim ke chat baru gagal (kode $code). Setelan lama tidak diubah.")
                return false
            }
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            sendToTelegram("⚠️ Validasi token/chat baru gagal (jaringan?). Setelan lama tidak diubah.")
            return false
        }
    }

    private suspend fun applyRestoredSettings(data: SettingsBackup.BackupData) {
        val oldToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
        val oldChat = try { preferencesManager.chatId } catch (_: Exception) { "" }
        val oldOwner = try { preferencesManager.ownerUserId } catch (_: Exception) { 0L }
        val newToken = data.token.trim()
        val newChat = data.chatId.trim()
        try {
            val meResp = TelegramClient.api.getMe("https://api.telegram.org/bot$newToken/getMe")
            if (!meResp.isSuccessful || meResp.body()?.ok != true) {
                audit("restore batal validasi ulang", "private")
                sendToTelegram("⚠️ Token baru tidak valid lagi saat jatuh tempo. Setelan lama tetap dipakai.")
                return
            }
            val encodedChat = try { URLEncoder.encode(newChat, "UTF-8") } catch (_: Exception) { newChat }
            val chatResp = TelegramClient.api.getChat("https://api.telegram.org/bot$newToken/getChat?chat_id=$encodedChat")
            if (!chatResp.isSuccessful || chatResp.body()?.ok != true) {
                audit("restore batal validasi ulang", "private")
                sendToTelegram("⚠️ Chat baru tidak bisa diakses saat jatuh tempo. Setelan lama tetap dipakai.")
                return
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            audit("restore batal validasi ulang", "private")
            sendToTelegram("⚠️ Validasi ulang gagal (jaringan?). Setelan lama tetap dipakai.")
            return
        }
        val saved = try {
            preferencesManager.saveCredentials(newToken, newChat, data.syncInterval, data.cameraInterval)
        } catch (_: Exception) {
            false
        }
        if (!saved) {
            audit("restore simpan gagal", "private")
            sendToTelegram("⚠️ Gagal menyimpan setelan baru. Setelan lama tetap dipakai.")
            return
        }
        if (data.accessPin.isNotEmpty()) {
            try { preferencesManager.accessPin = data.accessPin } catch (_: Exception) { }
        }
        if (oldToken != newToken || oldChat != newChat) skipUpdateOffsetOnce = true
        audit("restore diterapkan", "private")
        val targetOwner = RestoreRules.ownerFallback(data.ownerId, newChat, oldOwner)
        if (targetOwner > 0L) {
            try { preferencesManager.setOwnerIdSync(targetOwner) } catch (_: Exception) { }
        }
        refreshCreds()
        serviceScope.launch {
            try { registerBotCommands(force = true) } catch (_: Exception) { }
        }
        sendToTelegram(
            "✅ <b>Setelan dipulihkan</b>\n" +
                "Token bot, Chat ID, owner ID, interval sync, dan interval foto sudah diganti.\n" +
                "Bot lama tidak dipakai lagi. Kirim /status untuk memastikan."
        )
        if (oldToken.isNotEmpty() && oldChat.isNotEmpty() && (oldToken != newToken || oldChat != newChat)) {
            try {
                TelegramClient.api.sendMessage(
                    "https://api.telegram.org/bot$oldToken/sendMessage",
                    TelegramMessage(
                        chatId = oldChat,
                        text = "⚠️ Token/Chat ID perangkat diganti lewat /pulihkan. Bot ini tidak lagi memantau perangkat.",
                        parseMode = null
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun deleteIncomingMessage(chatId: String, messageId: Long, token: String) {
        if (chatId.isEmpty() || messageId <= 0L || token.isEmpty()) return
        try {
            TelegramClient.api.deleteMessage(
                "https://api.telegram.org/bot$token/deleteMessage",
                mapOf("chat_id" to chatId, "message_id" to messageId.toString())
            )
        } catch (_: Exception) {
        }
    }

    private suspend fun handleHelpCommand(senderOk: Boolean) {
        if (!senderOk) {
            sendToTelegram(
                buildString {
                    appendLine("🤖 <b>Perintah</b>")
                    appendLine("/help - daftar ini")
                    if (cachedOwnerId == 0L && cachedAccessPinActive) {
                        appendLine("/login &lt;PIN&gt; - masuk sebagai owner")
                    }
                }
            )
            return
        }
        val layar = menuScreen("main")
        sendToTelegram(layar.text, layar.markup)
    }


    private suspend fun handleContactsCommand(arg: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("⚠️ Izin Kontak belum diberikan. Buka Setup dan berikan izin Kontak.")
            return
        }
        val page = ContactsPaging.pageArg(arg)
        if (page != null) {
            showContactsPage(page)
            return
        }
        val trimmedArg = arg.trim()
        if (trimmedArg.isNotEmpty() && trimmedArg.all { it.isDigit() }) {
            sendToTelegram("Halaman kontak 1-999. Contoh: /contacts 2")
            return
        }
        val query = trimmedArg.take(40)
        if (query.isEmpty()) {
            sendToTelegram("Cara pakai: /contacts &lt;nama&gt;")
            return
        }
        val found = searchContacts(query)
        if (found.isEmpty()) {
            sendToTelegram("\uD83D\uDC64 Tak ada kontak yang cocok dengan nama itu.")
        } else {
            sendToTelegram(
                buildString {
                    appendLine("\uD83D\uDC64 <b>Kontak (${found.size})</b>")
                    for (entry in found) {
                        appendLine("\u2022 " + Html.escape(entry.first) + " \u2014 " + Html.escape(entry.second))
                    }
                }
            )
        }
    }

    private suspend fun showContactsPage(page: Int) {
        val pageSize = ContactsPaging.PAGE_SIZE
        val total = countContacts()
        if (total <= 0) {
            sendToTelegram("\uD83D\uDC64 Tak ada kontak tersimpan.")
            return
        }
        val totalPages = (total + pageSize - 1) / pageSize
        if (page > totalPages) {
            sendToTelegram(
                "\uD83D\uDC65 Halaman $page tidak ada. Total $totalPages halaman ($total nomor kontak).",
                com.redeye.parentalmonitor.network.InlineKeyboardMarkup(
                    listOf(
                        listOf(
                            com.redeye.parentalmonitor.network.InlineButton(
                                "\u2b05\ufe0f Halaman $totalPages",
                                "x:contacts $totalPages"
                            )
                        )
                    )
                )
            )
            return
        }
        val items = listContactsPage(page, pageSize)
        if (items.isEmpty()) {
            sendToTelegram("\uD83D\uDC64 Tak ada kontak di halaman $page.")
            return
        }
        val nav = mutableListOf<com.redeye.parentalmonitor.network.InlineButton>()
        if (page > 1) {
            nav.add(com.redeye.parentalmonitor.network.InlineButton("\u2b05\ufe0f Sebelumnya", "x:contacts ${page - 1}"))
        }
        if (page < totalPages) {
            nav.add(com.redeye.parentalmonitor.network.InlineButton("Berikutnya \u27a1\ufe0f", "x:contacts ${page + 1}"))
        }
        sendToTelegram(
            buildString {
                appendLine("\uD83D\uDC65 <b>Kontak</b> \u00b7 halaman $page/$totalPages ($total nomor)")
                for ((name, number) in items) {
                    appendLine("\u2022 " + Html.escape(name) + " \u2014 " + Html.escape(number))
                }
            },
            if (nav.isEmpty()) null else com.redeye.parentalmonitor.network.InlineKeyboardMarkup(listOf(nav))
        )
    }

    private fun countContacts(): Int {
        return try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone._ID)
            contentResolver.query(uri, projection, null, null, null)?.use { it.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun listContactsPage(page: Int, pageSize: Int): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val offset = (page - 1).toLong() * pageSize
        var skipped = 0L
        try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val order = android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC, " +
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " ASC"
            contentResolver.query(uri, projection, null, null, order)?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx < 0 || numIdx < 0) return@use
                while (c.moveToNext() && out.size < pageSize) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    val name = try { c.getString(nameIdx)?.trim().orEmpty() } catch (_: Exception) { "" }
                    val number = try { c.getString(numIdx)?.trim().orEmpty() } catch (_: Exception) { "" }
                    out.add(name.ifEmpty { "(tanpa nama)" }.take(60) to number.ifEmpty { "(tanpa nomor)" }.take(40))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private suspend fun handleAppsCommand(arg: String) {
        val tokens = arg.lowercase(java.util.Locale.ROOT).split(' ').filter { it.isNotEmpty() }
        val includeAll = tokens.any { it == "all" || it == "semua" }
        val parsed = tokens.firstOrNull { it.toIntOrNull() != null }?.toIntOrNull()
        if (arg.isNotEmpty() && parsed == null && !includeAll) {
            sendToTelegram("Cara pakai: /apps [5-50] atau /apps all [5-50] (bawaan 30)")
            return
        }
        val limit = parsed?.coerceIn(5, 50) ?: 30
        val apps = if (includeAll) allApps(limit) else listLaunchableApps(limit)
        if (apps.isEmpty()) {
            sendToTelegram("\uD83D\uDCE6 Tak ada aplikasi.")
        } else {
            sendToTelegram(
                buildString {
                    appendLine("\uD83D\uDCE6 <b>Aplikasi (${apps.size})</b>" + if (includeAll) " · semua terpasang" else "")
                    for (label in apps) {
                        appendLine("\u2022 " + Html.escape(label))
                    }
                }
            )
        }
    }

    private fun allApps(limit: Int): List<String> {
        return try {
            val infos = if (android.os.Build.VERSION.SDK_INT >= 33) {
                packageManager.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                packageManager.getInstalledApplications(0)
            }
            infos.asSequence()
                .filter { it.packageName != packageName }
                .map { info ->
                    val label = try { packageManager.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName }
                    "$label (${info.packageName})" + if (info.enabled) "" else " [nonaktif]"
                }
                .sortedBy { it.lowercase(java.util.Locale.ROOT) }
                .take(limit.coerceIn(1, 200))
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun handleStorageCommand() {
        val stats = withContext(Dispatchers.IO) { cacheStats() }
        sendToTelegram(
            buildString {
                appendLine("\uD83D\uDCBE <b>Penyimpanan</b>")
                appendLine("Cache: " + ReportFormat.formatBytes(stats.bytes) + " (" + stats.photos + " foto, " + stats.audios + " audio, " + stats.videos + " video)")
                appendLine("Antrean: ${messageQueue.getQueueSize()}")
            }
        )
    }

    private suspend fun handleHistoryCommand(arg: String) {
        if (!hasSmsPermission() || !hasCallLogPermission()) {
            sendToTelegram("⚠️ Izin SMS/log panggilan hilang. Berikan di Setup.")
            return
        }
        val digits = arg.filter { it.isDigit() }
        if (digits.length < 7 || digits.length > 20) {
            sendToTelegram("Cara pakai: /history &lt;nomor&gt; (7-20 digit)")
        } else {
            val callsResult: com.redeye.parentalmonitor.repository.NumberHistoryResult<com.redeye.parentalmonitor.data.models.CallData> = try {
                callLogRepository.getCallsForNumber(digits, 50)
            } catch (_: Exception) {
                com.redeye.parentalmonitor.repository.NumberHistoryResult(emptyList())
            }
            val smsResult: com.redeye.parentalmonitor.repository.NumberHistoryResult<com.redeye.parentalmonitor.data.models.SmsData> = try {
                smsRepository.getSmsForNumber(digits, 50)
            } catch (_: Exception) {
                com.redeye.parentalmonitor.repository.NumberHistoryResult(emptyList())
            }
            val calls = callsResult.items.take(5)
            val sms = smsResult.items.take(5)
            if (calls.isEmpty() && sms.isEmpty()) {
                val message = if (callsResult.truncated || smsResult.truncated) {
                    "⚠️ Nomor $digits tidak ditemukan dalam 2.000 entri terbaru. Pencarian dibatasi; riwayat lain mungkin belum diperiksa."
                } else {
                    "\uD83D\uDD0E Tak ada riwayat untuk $digits (pencarian sekitar 12 bulan terakhir)."
                }
                sendToTelegram(message)
            } else {
                val knownH = try { (calls.map { it.number } + sms.map { it.address }).distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
                val freqH = try { freqForNumbers(null) } catch (_: Exception) { null }
                if (calls.isNotEmpty()) sendToTelegram(formatCallMessage(calls, knownH))
                if (sms.isNotEmpty()) sendToTelegram(formatSmsMessage(sms, freqH, knownH))
                if (callsResult.truncated || smsResult.truncated) {
                    sendToTelegram("⚠️ Pencarian berhenti setelah 2.000 entri terbaru; mungkin masih ada riwayat yang lebih lama.")
                }
            }
        }
    }

    private suspend fun handleWatchCommand(arg: String, privateChat: Boolean) {
        val sub = arg.lowercase(java.util.Locale.ROOT).trim()
        if (sub.isEmpty() || sub == "status") {
            if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
            val cur = watchList()
            sendToTelegram(if (cur.isEmpty()) "👁️ Pantau: mati (contoh: /watch judi,pinjol,bully)" else "👁️ Pantau: " + cur.joinToString(", ") { Html.escape(it) })
        } else if (sub == "off" || sub == "clear") {
            preferencesManager.watchKeywords = ""
            sendToTelegram("👁️ Watch dimatikan.")
        } else {
            val tokens = WATCH_SPLIT_REGEX.split(sub).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val wordPool = tokens.map { it.lowercase(java.util.Locale.ROOT) }.filter { it.length >= 3 }.distinct()
            val words = wordPool.take(10)
            val dropped = tokens.filter { it.length < 3 }
            if (words.isEmpty()) {
                sendToTelegram("Cara pakai: /watch &lt;kata1,kata2&gt; (min 3 huruf, maks 10) atau /watch off")
            } else {
                preferencesManager.watchKeywords = words.joinToString(",")
                val over = if (wordPool.size > words.size) "\nDiabaikan (>10 kata): " + wordPool.drop(10).joinToString(", ") { Html.escape(it) } else ""
                sendToTelegram("👁️ Watch aktif: " + words.joinToString(", ") { Html.escape(it) } + (if (dropped.isNotEmpty()) "\nDiabaikan (&lt;3 huruf): " + dropped.joinToString(", ") { Html.escape(it) } else "") + over)
            }
        }
    }

    private suspend fun handleAppAlertCommand(arg: String) {
        val sub = arg.trim().lowercase(java.util.Locale.ROOT)
        if (sub.isEmpty() || sub == "status") {
            val on = try { preferencesManager.a11yAppAlert } catch (_: Exception) { true }
            val rows = a11yUsageRows()
            val total = rows.sumOf { it.millis }
            val now = A11yMonitor.currentAppLabel()
            sendToTelegram(buildString {
                appendLine("\uD83D\uDCF1 <b>Alert aplikasi</b>")
                appendLine("Status: " + if (on) "nyala" else "mati")
                appendLine("Sekarang: " + if (now.isEmpty()) "-" else Html.escape(now))
                appendLine("Hari ini: " + ReportFormat.durationText(total) + " \u00b7 " + rows.size + " app")
                appendLine("Atur: /appalert on|off")
            })
        } else if (sub == "on" || sub == "off") {
            val on = sub == "on"
            preferencesManager.a11yAppAlert = on
            if (on && !ShotAccessibilityService.isEnabled(this)) {
                sendToTelegram("\uD83D\uDCF1 Alert app nyala, tetapi Aksesibilitas MATI. Aktifkan di Setup agar alert bisa jalan.")
                return
            }
            sendToTelegram(if (on) "\uD83D\uDCF1 Alert app baru dan app malam nyala." else "\uD83D\uDCF1 Alert app dimatikan.")
        } else {
            sendToTelegram("Cara pakai: /appalert on|off|status")
        }
    }

    private suspend fun handleProtectCommand(arg: String) {
        val sub = arg.trim().lowercase(java.util.Locale.ROOT)
        if (sub.isEmpty() || sub == "status") {
            val on = try { preferencesManager.a11yTamperEnabled } catch (_: Exception) { true }
            sendToTelegram(buildString {
                appendLine("\uD83D\uDEE1\uFE0F <b>Proteksi tamper</b>")
                appendLine("Status: " + if (on) "nyala" else "mati")
                appendLine("Aksesibilitas: " + if (ShotAccessibilityService.isEnabled(this@MonitoringService)) "aktif" else "mati")
                appendLine("Admin perangkat: " + if (isDeviceAdminActive()) "aktif" else "mati")
                appendLine("Diblokir: Aksesibilitas, admin perangkat, hapus aplikasi, sumber tak dikenal, opsi pengembang.")
                appendLine("Atur: /lindungi on|off")
            })
        } else if (sub == "on" || sub == "off") {
            val on = sub == "on"
            preferencesManager.a11yTamperEnabled = on
            if (on && !ShotAccessibilityService.isEnabled(this)) {
                sendToTelegram("\uD83D\uDEE1\uFE0F Proteksi tamper nyala, tetapi Aksesibilitas MATI. Aktifkan di Setup agar proteksi berjalan.")
                return
            }
            sendToTelegram(if (on) "\uD83D\uDEE1\uFE0F Proteksi tamper nyala." else "\uD83D\uDEE1\uFE0F Proteksi tamper mati. Setelan HP bisa diubah sampai dinyalakan lagi.")
        } else {
            sendToTelegram("Cara pakai: /lindungi on|off|status")
        }
    }

    private fun isDeviceAdminActive(): Boolean {
        return try {
            val dpm = devicePolicySvc ?: return false
            val admin = android.content.ComponentName(this, com.redeye.parentalmonitor.receiver.AdminReceiver::class.java)
            dpm.isAdminActive(admin)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun handleAppWatchCommand(arg: String, privateChat: Boolean) {
        val text = arg.trim()
        val batas = text.indexOfFirst { it.isWhitespace() }
        val action = (if (batas < 0) text else text.substring(0, batas)).lowercase(java.util.Locale.ROOT)
        val rest = if (batas < 0) "" else text.substring(batas).trim()
        when (action) {
            "", "status" -> {
                if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                sendAppWatchStatus()
            }
            "on" -> enableAppWatch()
            "off" -> {
                preferencesManager.a11yAppWatchEnabled = false
                sendToTelegram("\uD83D\uDD75\uFE0F Pantau app dimatikan. Pemakaian layar tetap tercatat.")
            }
            "daftar" -> {
                if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
                sendAppWatchList()
            }
            "tambah" -> updateAppWatchPackages(rest, true)
            "hapus" -> updateAppWatchPackages(rest, false)
            else -> sendToTelegram("Cara pakai: /pantauapp on|off|status|daftar|tambah <app>|hapus <app>")
        }
    }

    private fun pruneAppWatchPackages(): MutableSet<String> {
        val packages = try { preferencesManager.a11yAppWatchPackageSet() } catch (_: Exception) { HashSet() }
        val alive = packages.filter { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
        if (alive.size != packages.size) {
            try { preferencesManager.setA11yAppWatchPackageSet(alive.toMutableSet()) } catch (_: Exception) { }
        }
        return alive.toMutableSet()
    }

    private suspend fun sendAppWatchStatus() {
        val enabled = try { preferencesManager.a11yAppWatchEnabled } catch (_: Exception) { false }
        val packages = pruneAppWatchPackages()
        val keywords = AppWatch.parseKeywords(preferencesManager.watchKeywords)
        sendToTelegram(buildString {
            appendLine("\uD83D\uDD75\uFE0F <b>Pantau app</b>")
            appendLine("Status: " + if (enabled) "nyala" else "mati")
            appendLine("Aplikasi: ${packages.size}")
            val shown = packages.take(8)
            if (shown.isNotEmpty()) appendLine(shown.joinToString("\n") { "\u2022 " + Html.escape(appLabelFor(it)) })
            if (packages.size > shown.size) appendLine("\u2026 dan ${packages.size - shown.size} lagi (/pantauapp daftar)")
            appendLine("Kata pantau: " + if (keywords.isEmpty()) "belum ada" else keywords.joinToString(", ") { Html.escape(it) })
            appendLine("Aksesibilitas: " + if (ShotAccessibilityService.isEnabled(this@MonitoringService)) "aktif" else "mati")
            appendLine("Privasi: password, banking, pembayaran, browser, dan aplikasi ini dilewati. Isi pesan tidak disimpan.")
            appendLine("Atur: /pantauapp on|off|daftar|tambah <app>|hapus <app>")
        }.trimEnd())
    }

    private suspend fun enableAppWatch() {
        val keywords = AppWatch.parseKeywords(preferencesManager.watchKeywords)
        if (keywords.isEmpty()) {
            sendToTelegram("\uD83D\uDD75\uFE0F Isi kata pantau dulu: /watch judi,pinjol,bully")
            return
        }
        val packages = preferencesManager.a11yAppWatchPackageSet()
        if (packages.isEmpty()) {
            packages.addAll(DEFAULT_APP_WATCH_PACKAGES)
            preferencesManager.setA11yAppWatchPackageSet(packages)
        }
        preferencesManager.a11yAppWatchEnabled = true
        val a11y = ShotAccessibilityService.isEnabled(this)
        sendToTelegram(buildString {
            appendLine("\uD83D\uDD75\uFE0F <b>Pantau app nyala</b>")
            appendLine("Aplikasi: ${packages.size}")
            appendLine("Kata: " + keywords.joinToString(", ") { Html.escape(it) })
            if (!a11y) appendLine("\u26A0\uFE0F Aksesibilitas belum aktif; nyalakan di Setup agar pemantauan jalan.")
            appendLine("Privasi: password, banking, pembayaran, browser dilewati. Isi pesan tidak disimpan.")
            appendLine("Matikan: /pantauapp off")
        }.trimEnd())
    }

    private suspend fun sendAppWatchList() {
        val packages = pruneAppWatchPackages()
        if (packages.isEmpty()) {
            sendToTelegram("\uD83D\uDD75\uFE0F Belum ada aplikasi dipantau. Nyalakan: /pantauapp on")
            return
        }
        sendToTelegram(buildString {
            appendLine("\uD83D\uDD75\uFE0F <b>Daftar pantau app (${packages.size})</b>")
            for (pkg in packages.sorted()) appendLine("\u2022 " + Html.escape(appLabelFor(pkg)) + " <code>" + pkg + "</code>")
        }.trimEnd())
    }

    private suspend fun updateAppWatchPackages(raw: String, add: Boolean) {
        if (raw.isEmpty()) {
            sendToTelegram("Cara pakai: /pantauapp " + (if (add) "tambah" else "hapus") + " <nama app|paket>")
            return
        }
        val tokens = if (raw.contains(",")) raw.split(',').map { it.trim() }.filter { it.isNotEmpty() } else listOf(raw.trim())
        val resolved = LinkedHashMap<String, String>()
        val failed = ArrayList<String>()
        val blocked = ArrayList<String>()
        for (token in tokens) {
            val pkg = resolveAppPackage(token)
            if (pkg == null) {
                failed.add(token)
            } else if (add && AppWatch.isSensitivePackage(pkg)) {
                blocked.add(appLabelFor(pkg))
            } else {
                resolved[pkg] = appLabelFor(pkg)
            }
        }
        val packages = preferencesManager.a11yAppWatchPackageSet()
        if (add) packages.addAll(resolved.keys) else packages.removeAll(resolved.keys.toSet())
        preferencesManager.setA11yAppWatchPackageSet(packages)
        sendToTelegram(buildString {
            appendLine("\uD83D\uDD75\uFE0F <b>Pantau app</b>")
            if (resolved.isNotEmpty()) {
                appendLine((if (add) "Ditambah: " else "Dihapus: ") + resolved.values.joinToString(", ") { Html.escape(it) })
            }
            if (failed.isNotEmpty()) appendLine("Tak ditemukan: " + failed.joinToString(", ") { Html.escape(it) })
            if (blocked.isNotEmpty()) appendLine("Dilewati (sensitif): " + blocked.joinToString(", ") { Html.escape(it) })
            appendLine("Total: ${packages.size} app")
            appendLine(if (preferencesManager.a11yAppWatchEnabled) "Status: nyala" else "Aktifkan: /pantauapp on")
        }.trimEnd())
    }

    private fun resolveAppPackage(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (value.contains(".")) {
            return try {
                packageManager.getApplicationInfo(value, 0)
                value
            } catch (_: Exception) {
                null
            }
        }
        val lower = value.lowercase(java.util.Locale.ROOT)
        val apps = launchableAppPairs()
        return apps.firstOrNull { it.first.lowercase(java.util.Locale.ROOT) == lower }?.second
            ?: apps.firstOrNull { it.first.lowercase(java.util.Locale.ROOT).contains(lower) }?.second
    }

    private fun appLabelFor(pkg: String): String {
        appLabelCache[pkg]?.let { return it.second }
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val label = packageManager.getApplicationLabel(info).toString().ifEmpty { pkg }
            val launchable = try { packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
            if (appLabelCache.size > 300) appLabelCache.clear()
            appLabelCache[pkg] = launchable to label
            label
        } catch (_: Exception) {
            pkg
        }
    }

    private fun launchableAppPairs(): List<Pair<String, String>> {
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
                    val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
                    label to pkg
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.second }.sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun handleFenceCommand(command: String, arg: String, privateChat: Boolean) {
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
            val tokens = sub.replace(",", " ").split(CMD_SPLIT_REGEX).filter { it.isNotEmpty() }
            val nums = tokens.map { it.toDoubleOrNull() }
            if (nums.size !in 2..3 || nums.any { it == null }) {
                sendToTelegram("Cara pakai: $command &lt;lat,lon,radius&gt; (radius 50-5000 m, default 200)")
            } else {
                val lat = nums[0]!!
                val lon = nums[1]!!
                val rad = nums.getOrNull(2)?.let { Math.round(it).toInt() } ?: 200
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

    private suspend fun handleDaruratCommand() {
        val level = batteryLevel()
        sendToTelegram("🚨 <b>Darurat</b>: ambil lokasi + foto…")
        val loc = try { fetchLocation(allowStale = true) } catch (_: Exception) { null }
        if (loc != null) {
            val stale = isStaleFix(loc)
            if (!stale) {
                try { rememberSentLatLon(loc.latitude, loc.longitude, loc.accuracy) } catch (_: Exception) { }
                try { checkGeofence(loc.latitude, loc.longitude, loc.accuracy) } catch (_: Exception) { }
            }
            sendToTelegram(buildString {
                appendLine("📍 <b>Lokasi darurat</b>")
                appendLine("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
                appendLine("Akurasi: ±${loc.accuracy.toInt()} m")
                val ageNote = fixAgeNote(loc)
                if (ageNote.isNotEmpty()) appendLine(ageNote.trim())
                appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
                appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
            })
        } else {
            bumpLocFailCount()
            sendToTelegram("📍 Lokasi tak didapat: " + locationFailureHint() + "\nBaterai: ${if (level in 0..100) "$level%" else "?"}")
        }
        try { captureAndSendPhoto(reportResult = true) } catch (_: Exception) { }
    }

    private suspend fun handleJampulangCommand(arg: String, privateChat: Boolean) {
        val sub = arg.trim()
        if (sub.isEmpty() || sub.equals("status", ignoreCase = true)) {
            if (!privateChat) { sendToTelegram("\uD83D\uDD12 Rincian hanya via private chat dengan bot."); return }
            val cur = try { preferencesManager.curfewTime } catch (_: Exception) { "" }
            val school = fenceStatusText(false)
            sendToTelegram("🌙 Jam malam: " + (cur.ifEmpty { "mati" }) + "\n🏫 Cek sekolah 08–15 (Senin–Jumat): " + school)
        } else if (sub.equals("off", ignoreCase = true)) {
            preferencesManager.curfewTime = ""
            sendToTelegram("🌙 Jam malam dimatikan.")
        } else if (CurfewLogic.isValidTime(sub)) {
            val startHour = sub.substringBefore(":").toIntOrNull() ?: -1
            if (startHour in 5..11) {
                sendToTelegram("🌙 Jam malam harus mulai pukul 12:00 atau lebih (berakhir 05:00), atau sebelum 05:00. Contoh: 21:30.")
                return
            }
            preferencesManager.curfewTime = sub
            sendToTelegram("🌙 Jam malam disetel $sub. Bila di luar rumah lewat jam itu, owner dikabari (maks 1x/hari).")
        } else {
            sendToTelegram("Cara pakai: /jampulang &lt;HH:MM&gt; (contoh 21:30) atau /jampulang off")
        }
    }

    private suspend fun handleWatchNomorCommand(arg: String, privateChat: Boolean) {
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
                sendToTelegram("Cara pakai: /watchnomor &lt;nomor1,nomor2&gt; (min 7 digit, maks 10) atau /watchnomor off")
            } else {
                preferencesManager.watchNumbers = nums.joinToString(",")
                sendToTelegram("📵 Watch nomor aktif: " + nums.joinToString(", "))
            }
        }
    }


    private suspend fun handleRecordCommand(arg: String) {
        val seconds = arg.toIntOrNull()
        if (seconds == null || seconds !in 5..600) {
            sendToTelegram("Cara pakai: /record &lt;5-600&gt; (detik)")
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("\u26A0\uFE0F Izin Mikrofon belum diberikan. Buka Setup dan berikan izin Mikrofon.")
        } else if (!recordBusy.compareAndSet(false, true)) {
            sendToTelegram(if (recordingKind == "video") "⏱️ Sedang merekam video, tunggu sebentar." else "⏱️ Sedang merekam/mengunggah rekaman, tunggu sebentar.")
        } else if (ringBusy.get()) {
            recordBusy.set(false)
            sendToTelegram("⏱️ Sedang berdering, tunggu sebentar.")
        } else {
            sendToTelegram("\uD83C\uDF99\uFE0F Merekam $seconds dtk\u2026")
            try { recordJob?.cancel() } catch (_: Exception) { }
            recordingKind = "audio"
            recordJob = serviceScope.launch {
                try {
                    recordAndSendAudio(seconds)
                } finally {
                    recordingKind = ""
                    recordBusy.set(false)
                }
            }
        }
    }

    private suspend fun handleRecordVideoCommand(arg: String) {
        val seconds = arg.toIntOrNull()
        if (seconds == null || seconds !in 5..180) {
            sendToTelegram("Cara pakai: /recordvideo &lt;5-180&gt; (seconds)")
        } else if (seconds * VIDEO_BYTES_PER_SEC > MAX_VIDEO_BYTES) {
            val maxSecs = (MAX_VIDEO_BYTES / VIDEO_BYTES_PER_SEC).toInt().coerceIn(5, 180)
            sendToTelegram("⚠️ /recordvideo $seconds dtk ≈ ${(seconds * VIDEO_BYTES_PER_SEC) / (1024L * 1024L)} MB, melebihi batas ${MAX_VIDEO_BYTES / (1024L * 1024L)} MB. Coba ≤$maxSecs dtk.")
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("\u26A0\uFE0F Izin Mikrofon belum diberikan. Buka Setup dan berikan izin Mikrofon.")
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("\u26A0\uFE0F Izin Kamera belum diberikan. Buka Setup dan berikan izin Kamera.")
        } else if (!recordBusy.compareAndSet(false, true)) {
            sendToTelegram(if (recordingKind == "audio") "⏱️ Sedang merekam/mengunggah rekaman, tunggu sebentar." else "⏱️ Sedang merekam video, tunggu sebentar.")
        } else if (ringBusy.get()) {
            recordBusy.set(false)
            sendToTelegram("⏱️ Sedang berdering, tunggu sebentar.")
        } else {
            sendToTelegram("\uD83C\uDFA5 Recording video $seconds s\u2026")
            try { recordJob?.cancel() } catch (_: Exception) { }
            recordingKind = "video"
            recordJob = serviceScope.launch {
                try {
                    recordAndSendVideo(seconds)
                } catch (e: Throwable) {
                    recordBusy.set(false)
                    recorderActive.set(false)
                    throw e
                } finally {
                    recordingKind = ""
                }
            }
        }
    }

    private suspend fun handleSmsCommand(arg: String, senderId: String) {
        val trimmedArg = arg.trim()
        val parsedArg = SmsCommandParser.parse(trimmedArg)
        val number = parsedArg?.number ?: ""
        val smsText = parsedArg?.text ?: ""
        val normalized = parsedArg?.normalized ?: ""
        val wholeCandidate = trimmedArg.filter { !it.isWhitespace() && it != '-' && it != '(' && it != ')' && it != '.' }
        val wholeIsNumber = wholeCandidate.all { it.isDigit() || it == '+' } && SMS_NUMBER_REGEX.matchEntire(wholeCandidate) != null
        if (trimmedArg.isEmpty() || !trimmedArg.any { it.isWhitespace() } || wholeIsNumber) {
            sendToTelegram("Cara pakai: /sms &lt;nomor&gt; &lt;pesan&gt;")
        } else if (number.isEmpty() || smsText.isEmpty()) {
            sendToTelegram("\u26A0\uFE0F Nomor tidak valid. Cara pakai: /sms &lt;nomor&gt; &lt;pesan&gt;")
        } else if (SmsNumbers.isPremium(normalized)) {
            sendToTelegram("\uD83D\uDEAB Nomor premium tidak boleh dipakai /sms.")
        } else if (SmsNumbers.isEmergency(normalized)) {
            sendToTelegram("\uD83D\uDEAB Nomor darurat tidak boleh dipakai /sms. Hubungi langsung dari HP.")
        } else if (smsText.length > 500) {
            sendToTelegram("⚠️ Pesan terlalu panjang (maks 500 karakter).")
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("⚠️ Izin SMS belum diberikan. Buka Setup dan berikan izin SMS.")
        } else {
            val stagedNumber = try { preferencesManager.pendingSmsNumber } catch (_: Exception) { "" }
            val stagedAt = try { preferencesManager.pendingSmsAt } catch (_: Exception) { 0L }
            val stagedBy = try { preferencesManager.pendingSmsOwner } catch (_: Exception) { "" }
            val nowSms = System.currentTimeMillis()
            val lastSentAt = try { preferencesManager.lastSmsSendAt } catch (_: Exception) { 0L }
            if (lastSentAt <= nowSms && nowSms - lastSentAt < 60_000L) {
                sendToTelegram("⚠️ Tunggu sebentar sebelum mengirim SMS lagi.")
            } else if (stagedNumber.isNotEmpty() && stagedAt > 0L && stagedAt <= nowSms && nowSms - stagedAt <= 300_000L && stagedBy.isNotEmpty() && stagedBy != senderId) {
                sendToTelegram("⛔ Hanya peminta yang bisa mengganti SMS tertunda ini. Balas /smsconfirm untuk mengonfirmasi dulu, atau tunggu sampai kedaluwarsa.")
            } else {
                val stagedText = try { preferencesManager.pendingSmsText } catch (_: Exception) { "" }
                val oldPending = if (stagedNumber.isNotEmpty() && (stagedNumber != normalized || stagedText != smsText)) stagedNumber else ""
                preferencesManager.writeSmsPendingSync(normalized, smsText, System.currentTimeMillis(), senderId)
                sendToTelegram("\uD83D\uDCE9 SMS ke <code>$normalized</code> siap dikirim. Balas /smsconfirm untuk konfirmasi (berlaku 5 menit)." + if (oldPending.isNotEmpty()) "\nPending lama ke <code>${Html.escape(oldPending)}</code> dibuang." else "")
            }
        }
    }

    private suspend fun handleSmsConfirmCommand(senderId: String) {
        val pending = try { preferencesManager.readSmsPendingSync() } catch (_: Exception) { null }
        val number = pending?.number ?: ""
        val smsText = pending?.text ?: ""
        val stagedAt = pending?.at ?: 0L
        val stagedBy = pending?.owner ?: ""
        val nowConfirm = System.currentTimeMillis()
        if (number.isEmpty() || smsText.isEmpty() || stagedAt <= 0L || stagedAt > nowConfirm || nowConfirm - stagedAt > 300_000L) {
            preferencesManager.clearSmsPendingIf(stagedAt)
            sendToTelegram("⏱️ Tak ada SMS tertunda. Kirim /sms &lt;nomor&gt; &lt;pesan&gt; dulu.")
        } else if (stagedBy.isNotEmpty() && stagedBy != senderId) {
            sendToTelegram("⛔ Hanya peminta yang bisa mengonfirmasi SMS ini.")
        } else if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sendToTelegram("\u26A0\uFE0F Izin SMS hilang. Berikan di Setup lalu stage ulang.")
        } else if (SmsNumbers.isPremium(number) || SmsNumbers.isEmergency(number)) {
            preferencesManager.clearSmsPendingIf(stagedAt)
            sendToTelegram("\uD83D\uDEAB Nomor kini diblokir, pending dibuang. Stage ulang nomor lain.")
        } else if (preferencesManager.lastSmsSendAt <= nowConfirm && nowConfirm - preferencesManager.lastSmsSendAt < 60_000L) {
            sendToTelegram("⚠️ Tunggu sebentar sebelum mengirim SMS lagi.")
        } else if (!smsBusy.compareAndSet(false, true)) {
            sendToTelegram("⏱️ SMS masih dikirim, tunggu sebentar.")
        } else {
            sendToTelegram("\uD83D\uDCE9 Mengirim SMS\u2026")
            try { smsJob?.cancel() } catch (_: Exception) { }
            smsJob = serviceScope.launch {
                try {
                    sendSmsPending(number, smsText, stagedAt)
                } finally {
                    smsBusy.set(false)
                }
            }
        }
    }

    private suspend fun handleLastNotifCommand() {
        val items = try {
            NotificationForwarderService.history()
        } catch (_: Exception) {
            emptyList()
        }
        if (items.isEmpty()) {
            sendToTelegram("\uD83D\uDD14 Belum ada notifikasi tercatat.")
        } else {
            sendToTelegram(
                buildString {
                    appendLine("\uD83D\uDD14 <b>Notifikasi terakhir</b>")
                    for (item in items.takeLast(10)) {
                        appendLine("\u2022 " + Html.escape(item.app) + ": " + Html.escape(item.title).let { it.take(safeCut(it, 80)) } + " \u2014 " + Html.escape(item.text).let { it.take(safeCut(it, 120)) })
                    }
                }
            )
        }
    }

    private suspend fun handleVersionCommand() {
        sendToTelegram(
            buildString {
                appendLine("\u2139\uFE0F <b>Versi</b>")
                appendLine("Aplikasi: " + com.redeye.parentalmonitor.BuildConfig.VERSION_NAME + " (" + com.redeye.parentalmonitor.BuildConfig.VERSION_CODE + ")")
                appendLine("Android: " + Html.escape(Build.VERSION.SDK_INT.toString()) + " (" + Html.escape(Build.VERSION.RELEASE) + ")")
                appendLine("HP: " + Html.escape(Build.MANUFACTURER) + " " + Html.escape(Build.MODEL))
            }
        )
    }

    private suspend fun handleUptimeCommand() {
        val started = serviceStartAt
        if (started <= 0) {
            sendToTelegram("\u23F1\uFE0F Uptime: tak tahu.")
        } else {
            val elapsed = android.os.SystemClock.elapsedRealtime() - serviceStartElapsed
            val wall = System.currentTimeMillis() - started
            val millis = if (serviceStartElapsed > 0L && elapsed >= 0L) elapsed else wall
            val minutes = (millis / 60_000L).coerceAtLeast(0L)
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

    private suspend fun handleKendaraCommand(arg: String) {
        when (arg.lowercase(java.util.Locale.ROOT).trim()) {
            "on", "nyala" -> {
                preferencesManager.vehicleAlertEnabled = true
                sendToTelegram("🚗 Kabar berkendara: nyala.")
            }
            "off", "mati" -> {
                preferencesManager.vehicleAlertEnabled = false
                sendToTelegram("🚗 Kabar berkendara: mati.")
            }
            "status" -> {
                val on = preferencesManager.vehicleAlertEnabled
                val effMin = try { effectiveSyncMillis() / 60_000L } catch (_: Exception) { 0L }
                val note = if (on && effMin > 30) " (catatan: interval lokasi efektif $effMin mnt >30 mnt, deteksi via jarak/waktu tidak jalan — kecilkan lewat /syncinterval)" else ""
                sendToTelegram("🚗 Kabar berkendara: " + (if (on) "nyala" else "mati") + note)
            }
            else -> sendToTelegram("Cara pakai: /kendara &lt;on|off|status&gt;\nKabar dikirim kalau lokasi menunjukkan kecepatan tinggi (≥30 km/jam) — jadi tahu kalau anak sedang naik motor/mobil. Maksimal sekali per 20 menit; butuh interval lokasi ≤30 mnt.")
        }
        try { refreshLoopConfig() } catch (_: Exception) { }
    }

    private suspend fun handleHematDataCommand(arg: String) {
        when (arg.lowercase(java.util.Locale.ROOT).trim()) {
            "on", "nyala" -> {
                preferencesManager.dataSaverEnabled = true
                sendToTelegram("📶 Hemat kuota: nyala. Foto/video/rekaman ditahan saat pakai data seluler, dikirim otomatis begitu tersambung Wi-Fi.")
            }
            "off", "mati" -> {
                preferencesManager.dataSaverEnabled = false
                sendToTelegram("📶 Hemat kuota: mati (semua media dikirim kapan saja).")
            }
            "status" -> sendToTelegram("📶 Hemat kuota: " + if (preferencesManager.dataSaverEnabled) "nyala" else "mati")
            else -> sendToTelegram("Cara pakai: /hematdata &lt;on|off|status&gt;\nKalau nyala, foto/video/rekaman hanya dikirim saat tidak pakai kuota (Wi-Fi); SMS/lokasi/notifikasi tetap jalan seperti biasa.")
        }
        try { refreshLoopConfig() } catch (_: Exception) { }
    }

    private suspend fun handleHeartbeatCommand(arg: String) {
        if (arg.isBlank()) {
            val hours = try { preferencesManager.heartbeatHours } catch (_: Exception) { 0 }
            sendToTelegram("💚 Heartbeat: " + if (hours <= 0) "mati. Pakai /heartbeat &lt;1-48&gt; untuk menyalakan." else "tiap $hours jam.")
        } else {
            val hours = arg.trim().toIntOrNull()
            if (hours == null || hours < 0 || hours > 48) {
                sendToTelegram("Cara pakai: /heartbeat &lt;0-48&gt; (0 = matikan).")
            } else {
                try { preferencesManager.heartbeatHours = hours } catch (_: Exception) { }
                try { preferencesManager.heartbeatAt = System.currentTimeMillis() } catch (_: Exception) { }
                sendToTelegram(if (hours <= 0) "💚 Heartbeat dimatikan." else "💚 Heartbeat menyala: laporan tiap $hours jam.")
            }
        }
    }


    private suspend fun handleLocationCommand() {
        if (!hasLocationPermission()) {
            sendToTelegram("⚠️ Izin lokasi hilang. Buka Setup dan berikan izin Lokasi.")
        } else {
            sendToTelegram("📍 Mencari lokasi…")
            val location = fetchLocation(allowStale = true)
            if (location == null) {
                bumpLocFailCount()
                sendToTelegram("⚠️ Lokasi tak didapat: " + locationFailureHint())
            } else {
                val stale = isStaleFix(location)
                val prev = if (stale) null else lastSentLatLon()
                val dist = if (prev == null) -1f else ReportFormat.distanceMeters(prev.first, prev.second, location.latitude, location.longitude)
                if (!stale) {
                    rememberSentLatLon(location.latitude, location.longitude, location.accuracy)
                    try { checkGeofence(location.latitude, location.longitude, location.accuracy) } catch (_: Exception) { }
                }
                val near = if (dist >= 0f) "\nJarak dari laporan terakhir: ${dist.toInt()} m" else ""
                sendToTelegram("📍 <b>Lokasi</b>\nhttps://maps.google.com/?q=${location.latitude},${location.longitude}\nAkurasi: ±${location.accuracy.toInt()} m$near" + fixAgeNote(location))
            }
        }
    }

    private suspend fun handleCameraCommand(arg: String) {
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
                sendToTelegram("Cara pakai: /camera &lt;depan|belakang&gt; (kini: ${preferencesManager.cameraFacing})")
            }
        }
    }

    private suspend fun handleNotifCommand(arg: String) {
        val mode = arg.lowercase(java.util.Locale.ROOT).trim()
        when {
            mode == "otp on" || mode == "otp nyala" -> {
                preferencesManager.otpMaskEnabled = true
                sendToTelegram("🔐 Masking OTP/bank NYALA. Angka 4-8 digit disamarkan saat notifikasi mengandung kata OTP/kode/verifikasi.")
            }
            mode == "otp off" || mode == "otp mati" -> {
                preferencesManager.otpMaskEnabled = false
                sendToTelegram("🔓 Masking OTP/bank MATI. Isi notifikasi dikirim apa adanya.")
            }
            mode == "otp status" || mode == "otp" -> {
                sendToTelegram("🔐 Masking OTP/bank: ${if (preferencesManager.otpMaskEnabled) "nyala" else "mati"}")
            }
            mode == "on" || mode == "nyala" -> {
                preferencesManager.notifForwardEnabled = true
                if (isNotifForwarding()) {
                    sendToTelegram("\uD83D\uDD14 Penerusan notifikasi NYALA.")
                } else {
                    sendToTelegram("Penerusan notifikasi nyala, tapi akses notifikasi sistem belum ada. Buka Setup, tekan Read Notifications untuk mengizinkan.")
                }
            }
            mode == "off" || mode == "mati" -> {
                preferencesManager.notifForwardEnabled = false
                sendToTelegram("\uD83D\uDD15 Penerusan notifikasi MATI.")
            }
            mode == "status" || mode.isEmpty() -> {
                val state = if (isNotifForwarding()) "diteruskan" else "mati"
                sendToTelegram("🔔 Notifikasi: $state (setelan: ${if (preferencesManager.notifForwardEnabled) "nyala" else "mati"})\n🔐 Masking OTP: ${if (preferencesManager.otpMaskEnabled) "nyala" else "mati"}")
            }
            else -> {
                sendToTelegram("Cara pakai: /notif &lt;on|off|status&gt; atau /notif otp &lt;on|off|status&gt;")
            }
        }
    }

    private suspend fun handleLogCommand() {
        val crash = try {
            CrashReporter.pendingReport(this)
        } catch (_: Exception) {
            null
        }
        if (crash != null) {
            if (sendFitted(crash) != SendStatus.DROPPED) {
                CrashReporter.clearPending(this, crash)
            }
        } else {
            sendToTelegram("\uD83E\uDDFE Belum ada crash.")
        }
        sendToTelegram(
            buildString {
                appendLine("\uD83E\uDDFE <b>Log galat</b>")
                val credErr = try {
                    preferencesManager.credentialError
                } catch (_: Exception) {
                    ""
                }
                appendLine("Auth: " + if (credErr.isEmpty()) "OK" else "GAGAL ($credErr)")
                appendLine("Antrean: ${messageQueue.getQueueSize()}")
                val storageReason = try { PreferencesManager.storageFailureReason } catch (_: Exception) { "" }
                appendLine("Penyimpanan: " + when {
                    preferencesManager.isStorageEncrypted -> "terenkripsi"
                    storageReason.isNotEmpty() -> "volatil ($storageReason)"
                    else -> "volatil"
                })
                val camErr = try {
                    preferencesManager.lastCameraErrorNotice
                } catch (_: Exception) {
                    0L
                }
                appendLine("Galat kamera terakhir: " + if (camErr > 0) formatDate(camErr) else "tak ada")
                val upErr = try {
                    preferencesManager.lastUploadErrorNotice
                } catch (_: Exception) {
                    0L
                }
                appendLine("Galat unggah terakhir: " + if (upErr > 0) formatDate(upErr) else "tak ada")
            }
        )
    }

    private suspend fun handleSyncIntervalCommand(arg: String) {
        val minutes = arg.toIntOrNull()
        if (minutes == null || minutes !in 1..1440) {
            sendToTelegram("Cara pakai: /syncinterval &lt;1-1440&gt; (menit)")
        } else {
            preferencesManager.syncInterval = minutes
            sendToTelegram("\u23F1\uFE0F Interval sinkron diatur $minutes mnt.")
            try {
                restartAllLoops()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun handleFlushCommand() {
        val queued = messageQueue.getQueueSize()
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            val code = try { preferencesManager.credentialError } catch (_: Exception) { "" }
            val hint = when (code) {
                "403" -> "bot diblokir atau dikeluarkan dari chat — buka Telegram lalu buka blokir/undang ulang bot"
                "401" -> "token bot ditolak — perbaiki token di Setup"
                else -> "pengiriman ditolak Telegram"
            }
            sendToTelegram("⚠️ Flush ditunda: $hint (kode $code).")
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
            sendToTelegram("\uD83D\uDCE4 Flush dijadwalkan ($queued menunggu). Dikirim saat online.")
        } else {
            sendToTelegram("⚠️ Tak bisa menjadwalkan flush ($queued menunggu).")
        }
    }

    private suspend fun handleClearQueueCommand() {
        val queued = messageQueue.getQueueSize()
        val cleared = try {
            messageQueue.clearQueue()
        } catch (_: Exception) {
            false
        }
        val message = if (cleared) {
            "\uD83D\uDDD1\uFE0F Antrean dibersihkan ($queued dibuang). Catatan: pembaruan SMS/panggilan yang sudah ditandai terbaca tidak dikirim ulang."
        } else {
            "⚠️ Antrean gagal dibersihkan ($queued pesan). Penyimpanan belum memastikan antrean kosong; coba lagi."
        }
        sendToTelegram(message, null, true)
    }

    private suspend fun handleLockCommand() {
        try {
            val dpm = devicePolicySvc
            val admin = android.content.ComponentName(this, com.redeye.parentalmonitor.receiver.AdminReceiver::class.java)
            if (dpm == null || !dpm.isAdminActive(admin)) {
                sendToTelegram("⚠️ Device Admin belum aktif. Aktifkan dulu di Setup.")
            } else {
                dpm.lockNow()
                sendToTelegram("\uD83D\uDD12 Perangkat dikunci.")
            }
        } catch (e: SecurityException) {
            sendToTelegram("⚠️ Tak bisa mengunci: Device Admin belum aktif.")
        } catch (e: Exception) {
            sendToTelegram("⚠️ Gagal mengunci.")
        }
    }

    private suspend fun handleRingCommand(arg: String) {
        val seconds = if (arg.isEmpty()) 15 else arg.toIntOrNull()
        if (seconds == null || seconds !in 5..60) {
            sendToTelegram("Cara pakai: /ring [5-60] (seconds)")
        } else if (!ringBusy.compareAndSet(false, true)) {
            sendToTelegram("⏱️ Sedang berdering, tunggu sebentar.")
        } else if (recordBusy.get() || recorderActive.get()) {
            ringBusy.set(false)
            sendToTelegram("⏱️ Sedang merekam, tunggu sebentar.")
        } else {
            try { ringJob?.cancel() } catch (_: Exception) { }
            ringJob = serviceScope.launch {
                try {
                    ringDevice(seconds)
                } finally {
                    ringBusy.set(false)
                }
            }
        }
    }

    private suspend fun handlePingCommand(arg: String, sentAtSec: Long, senderOk: Boolean, chatOk: Boolean, senderId: String, privateChat: Boolean) {
        if (!senderOk) return
        val pingKey = senderId.ifEmpty { "unknown" }
        val nowPing = android.os.SystemClock.elapsedRealtime()
        val lastPing = try { synchronized(pingRate) { pingRate[pingKey] } } catch (_: Exception) { null }
        if (lastPing != null && nowPing - lastPing < PING_RATE_WINDOW_MS) {
            val waitSec = ((PING_RATE_WINDOW_MS - (nowPing - lastPing)) / 1000L).coerceAtLeast(1L)
            sendToTelegram("⏳ Tunggu $waitSec detik sebelum /ping lagi.")
            return
        }
        try { synchronized(pingRate) { pingRate[pingKey] = nowPing } } catch (_: Exception) { }
        when (arg.substringBefore(" ").lowercase(java.util.Locale.ROOT)) {
            "camera", "photo" -> {
                if (cameraJob?.isActive != true && pingRestartDue()) {
                    restartCameraLoop()
                    pingRestartMark()
                }
                handleTelegramCommand("/photo", sentAtSec, senderOk, chatOk, senderId, privateChat)
            }
            "location", "loc", "gps" -> {
                handleTelegramCommand("/location", sentAtSec, senderOk, chatOk, senderId, privateChat)
            }
            "" -> {
                val initialStuck = initialSyncRunning.get() && initialSyncJob?.isActive != true
                val loopsOk = monitoringJob?.isActive == true && cameraJob?.isActive == true && commandJob?.isActive == true && patrolJob?.isActive == true && !initialStuck
                var restarted = false
                if (!loopsOk && pingRestartDue()) {
                    restarted = restartAllLoops()
                    if (restarted) pingRestartMark()
                }
                val tail = if (restarted) " ⏰ Loop dijalankan ulang." else ""
                val pong = if (sentAtSec > 0) {
                    val lag = serverNowSec() - sentAtSec
                    "\uD83C\uDFD3 Pong! Delay ${lag.coerceAtLeast(0)} s." + tail
                } else {
                    "\uD83C\uDFD3 Pong! " + TimeFmt.full(System.currentTimeMillis()) + tail
                }
                if (restarted) replyLater(pong) else sendToTelegram(pong)
            }
            else -> {
                sendToTelegram("Cara pakai: /ping [camera|location]")
            }
        }
    }


    private suspend fun handlePhotoCommand(arg: String) {
        if (preferencesManager.monitoringPaused) {
            sendToTelegram("⏸️ Monitoring dijeda. Kirim /resume dulu.")
            return
        }
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
        sendToTelegram("📸 Ambil foto sekarang…")
        captureAndSendPhoto(reportResult = true)
    }

    private suspend fun handleStatusCommand() {
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
                val effMin = effectiveSyncMillis() / 60_000L
                appendLine("Interval data: tiap ${preferencesManager.syncInterval} mnt" + if (idleDataCycles > 0 && effMin > preferencesManager.syncInterval) " (sepi adaptif, kini tiap $effMin mnt)" else "")
                appendLine("Foto: $photoState")
                appendLine("Patroli: ${if (preferencesManager.patrolEnabled) "JALAN tiap ${preferencesManager.patrolInterval} mnt" else "mati"}")
                appendLine("Kamera: ${preferencesManager.cameraFacing}")
                appendLine("Malam: " + if (preferencesManager.nightMode) "JALAN 23–05" + if (isNightPaused()) " (sunyi sekarang)" else "" else "mati")
                appendLine("Pantau: " + (watchList().takeIf { it.isNotEmpty() }?.joinToString(",") { Html.escape(it) } ?: "mati"))
                appendLine("Rumah: " + fenceStatusText(true) + " · Sekolah: " + fenceStatusText(false))
                appendLine("Izin kamera: ${if (hasCameraPermission()) "ada" else "HILANG"}")
                appendLine("Izin lokasi: ${if (hasLocationPermission()) "ada" else "HILANG"}")
                appendLine("Lokasi presisi: ${if (hasFineLocation()) "ada" else "HILANG (hanya perkiraan)"}")
                appendLine("Lokasi latar: ${if (hasBackgroundLocation()) "ada" else "HILANG"}")
                appendLine("Izin SMS masuk: ${if (hasReceiveSmsPermission()) "ada" else "tidak"}")
                appendLine("Izin status telepon: ${if (hasPhoneStatePermission()) "ada" else "tidak"}")
                appendLine("Jaringan: " + networkSummary())
                appendLine("Baterai: " + batterySummary())
                appendLine("SIM: " + (try { currentSimIdentity().ifEmpty { "tak terbaca" } } catch (_: Exception) { "?" }))
                try {
                    val gagal = adminFailedAttempts()
                    if (gagal > 0) appendLine("Buka kunci gagal: $gagal× sejak buka terakhir")
                } catch (_: Exception) {
                }
                appendLine("Notifikasi: ${if (isNotifForwarding()) "teruskan" else "mati"}")
                appendLine("Aksesibilitas: " + when {
                    ShotAccessibilityService.isEnabled(this@MonitoringService) -> "aktif"
                    preferencesManager.accessibilityEverEnabled -> "MATI"
                    else -> "belum diaktifkan"
                } + " · alert app: " + (if (preferencesManager.a11yAppAlert) "nyala" else "mati") +
                    " · proteksi: " + (if (preferencesManager.a11yTamperEnabled) "nyala" else "mati"))
                appendLine("Pantau app: " + (if (preferencesManager.a11yAppWatchEnabled) "nyala (" + preferencesManager.a11yAppWatchPackageSet().size + " app)" else "mati"))
                appendLine("Sekarang: " + (A11yMonitor.currentAppLabel().ifEmpty { "-" }))
                appendLine("Foto terakhir: ${if (preferencesManager.lastPhotoTime > 0) formatDate(preferencesManager.lastPhotoTime) else "belum pernah"}")
                appendLine("Jam malam: ${try { preferencesManager.curfewTime.takeIf { it.isNotEmpty() } ?: "mati" } catch (_: Exception) { "?" }}")
            },
            mainMenu()
        )
    }

    private suspend fun handleRingkasCommand() {
        val level = batteryLevel()
        val notifOn = try { preferencesManager.notifForwardEnabled } catch (_: Exception) { false }
        val snap = try { preferencesManager.permSnapshot } catch (_: Exception) { -1 }
        val missing = try { PermReport.missingLabels(PermReport.mask(permissionRawStates(), notifOn, snap), permLabels) } catch (_: Exception) { ArrayList() }
        sendToTelegram(buildString {
            appendLine("🧾 <b>Ringkas</b>")
            appendLine("Pemantauan: ${if (preferencesManager.monitoringPaused) "DIJEDA" else "JALAN"}")
            appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
            appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
            appendLine("Izin: " + if (missing.isEmpty()) "semua OK ✅" else "hilang: " + missing.joinToString(", "))
            appendLine("Aksesibilitas: " + (if (ShotAccessibilityService.isEnabled(this@MonitoringService)) "aktif" else "mati") + " · proteksi " + (if (preferencesManager.a11yTamperEnabled) "nyala" else "mati"))
            appendLine("Sync: tiap ${preferencesManager.syncInterval} mnt" + if (isNightPaused()) " · malam 🌙" else "")
            try { usageEventSummary(6).forEach { appendLine(it) } } catch (_: Exception) { }
        },
            mainMenu()
        )
    }

    private suspend fun handleLastCallsCommand() {
        if (!hasCallLogPermission()) {
            sendToTelegram("⚠️ Izin log panggilan hilang. Berikan di Setup.")
            return
        }
        val calls = callLogRepository.getAllCalls(5)
        if (calls.isEmpty()) {
            sendToTelegram("📞 Tak ada riwayat panggilan.")
        } else {
            val knownCalls = try { calls.map { it.number }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
            sendToTelegram(formatCallMessage(calls, knownCalls))
        }
    }

    private suspend fun handleLastSmsCommand() {
        if (!hasSmsPermission()) {
            sendToTelegram("⚠️ Izin SMS hilang. Berikan di Setup.")
            return
        }
        val sms = smsRepository.getRecentSms(5)
        if (sms.isEmpty()) {
            sendToTelegram("💬 Tak ada SMS.")
        } else {
            val knownSms = try { sms.map { it.address }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
            val freqSms = try { freqForNumbers(null) } catch (_: Exception) { null }
            sendToTelegram(formatSmsMessage(sms, freqSms, knownSms))
        }
    }

    private suspend fun handlePhotoIntervalCommand(arg: String) {
        val minutes = arg.toIntOrNull()
        if (minutes == null || minutes !in 0..60) {
            sendToTelegram("Cara pakai: /photointerval &lt;0-60&gt; (0 = manual saja)")
        } else if (minutes == 0) {
            preferencesManager.cameraInterval = 0
            sendToTelegram("📸 Foto otomatis MATI. Pakai /photo untuk manual.")
        } else {
            preferencesManager.cameraInterval = minutes
            sendToTelegram("📸 Interval foto: $minutes mnt.")
        }
    }

    private suspend fun handlePatrolCommand(arg: String) {
        when (arg.lowercase(java.util.Locale.ROOT)) {
            "on", "nyala" -> {
                preferencesManager.patrolEnabled = true
                sendToTelegram("\uD83D\uDEF0 Patroli NYALA \u2014 foto + lokasi tiap ${preferencesManager.patrolInterval} mnt.")
            }
            "off", "mati" -> {
                preferencesManager.patrolEnabled = false
                sendToTelegram("\uD83D\uDEF0 Patroli MATI.")
            }
            else -> {
                val state = if (preferencesManager.patrolEnabled) "NYALA tiap ${preferencesManager.patrolInterval} mnt" else "MATI"
                sendToTelegram("Cara pakai: /patrol &lt;on|off&gt; (kini $state)")
            }
        }
    }

    private suspend fun handleNightCommand(arg: String) {
        when (arg.lowercase(java.util.Locale.ROOT)) {
            "on", "nyala" -> {
                preferencesManager.nightMode = true
                try { restartCameraLoop() } catch (_: Exception) { }
                try { restartPatrolLoop() } catch (_: Exception) { }
                sendToTelegram("🌙 Mode malam NYALA — foto/lokasi otomatis dijeda 23:00–05:00.")
            }
            "off", "mati" -> {
                preferencesManager.nightMode = false
                try { restartCameraLoop() } catch (_: Exception) { }
                try { restartPatrolLoop() } catch (_: Exception) { }
                sendToTelegram("🌙 Mode malam MATI.")
            }
            else -> {
                val state = if (preferencesManager.nightMode) "NYALA 23:00–05:00" else "MATI"
                sendToTelegram("Cara pakai: /night &lt;on|off&gt; (kini $state)")
            }
        }
    }

    private suspend fun handlePauseCommand(arg: String) {
        val minutes = arg.toIntOrNull()
        if (minutes == null || minutes !in 1..480) {
            sendToTelegram("Cara pakai: /pause &lt;menit&gt; (1-480)")
        } else {
            preferencesManager.photoPausedUntil = System.currentTimeMillis() + minutes * 60_000L
            preferencesManager.photoPauseSource = PHOTO_PAUSE_NONE
            sendToTelegram("⏸️ Foto otomatis dijeda $minutes mnt. (manual /photo tetap bisa)")
        }
    }

    private suspend fun handleResumeCommand() {
        preferencesManager.monitoringPaused = false
        val photoPaused = isPhotoPaused()
        try {
            preferencesManager.photoPausedUntil = 0L
            preferencesManager.photoPauseSource = PHOTO_PAUSE_NONE
        } catch (_: Exception) {
        }
        cachedPhotoPausedUntil = 0L
        sendToTelegram(if (photoPaused) "▶️ Monitoring jalan lagi. Jeda foto otomatis dibatalkan." else "▶️ Monitoring jalan lagi.")
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
                preferencesManager.photoPauseSource = PHOTO_PAUSE_NONE
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

    private fun hasFineLocation(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun lastKnownFix(maxAgeMs: Long): android.location.Location? {
        if (!hasLocationPermission()) return null
        return try {
            val manager = locationManagerSvc ?: return null
            val now = System.currentTimeMillis()
            val found = ArrayList<android.location.Location>(3)
            for (provider in listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.PASSIVE_PROVIDER
            )) {
                val loc = try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
                if (loc.latitude == 0.0 && loc.longitude == 0.0) continue
                val age = now - loc.time
                if (age < -60_000L || age > maxAgeMs) continue
                found.add(loc)
            }
            val usable = found.filter { it.accuracy > 0f && it.accuracy <= 1_000f }
            if (usable.isNotEmpty()) usable.maxByOrNull { it.time } else found.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    private fun isStaleFix(location: android.location.Location): Boolean {
        return System.currentTimeMillis() - location.time > LOCATION_STALE_MS
    }

    private fun fixAgeNote(location: android.location.Location): String {
        val age = System.currentTimeMillis() - location.time
        if (age <= LOCATION_STALE_MS) return ""
        val mins = age / 60_000L
        return if (mins < 60L) "\n\u26a0\ufe0f Data lama: ${mins} menit lalu" else "\n\u26a0\ufe0f Data lama: ${mins / 60L} jam lalu"
    }

    private fun locationFailureHint(): String {
        val manager = locationManagerSvc
        val providersOn = try {
            manager != null && listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER
            ).any { manager.isProviderEnabled(it) }
        } catch (_: Exception) {
            false
        }
        return when {
            !hasLocationPermission() -> "izin lokasi belum diberikan."
            !providersOn -> "Lokasi/GPS di HP sedang mati."
            !hasFineLocation() -> "izin lokasi hanya \"Perkiraan\" \u2014 setel ke \"Presisi\"."
            !hasBackgroundLocation() -> "izin \"Lokasi sepanjang waktu\" belum ada \u2014 buka Setup."
            else -> "GPS belum dapat perbaikan \u2014 coba lagi di tempat terbuka."
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun fetchLocation(allowStale: Boolean = false): android.location.Location? {
        if (!hasLocationPermission()) return null
        ensureForegroundTypes()
        return withContext(Dispatchers.IO) {
            try {
                val locationManager = locationManagerSvc ?: return@withContext null
                val wanted = if (hasFineLocation()) listOf(
                    android.location.LocationManager.GPS_PROVIDER,
                    android.location.LocationManager.NETWORK_PROVIDER
                ) else listOf(
                    android.location.LocationManager.NETWORK_PROVIDER
                )
                val providers = wanted.filter {
                    try {
                        locationManager.isProviderEnabled(it)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (providers.isEmpty()) {
                    return@withContext if (allowStale) lastKnownFix(LOCATION_LASTKNOWN_MAX_MS) else null
                }
                lastKnownFix(LOCATION_LASTKNOWN_FRESH_MS)?.let { return@withContext it }
                val fix = try {
                    coroutineScope {
                        val collected = java.util.concurrent.ConcurrentLinkedQueue<android.location.Location>()
                        val settled = CompletableDeferred<Unit>()
                        val remaining = java.util.concurrent.atomic.AtomicInteger(providers.size)
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
                                val got = try {
                                    result.await()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    try {
                                        locationManager.removeUpdates(listener)
                                    } catch (_: Exception) {
                                    }
                                }
                                if (got != null) {
                                    collected.add(got)
                                    if (got.accuracy > 0f && got.accuracy <= LOCATION_GOOD_ACCURACY_M) {
                                        settled.complete(Unit)
                                    }
                                }
                                if (remaining.decrementAndGet() <= 0) {
                                    settled.complete(Unit)
                                }
                                got
                            }
                        }
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(20_000L) { settled.await() }
                        } catch (_: Exception) {
                        }
                        for (task in pending) {
                            try {
                                task.cancel()
                            } catch (_: Exception) {
                            }
                        }
                        bestFix(collected.toList())
                    }
                } catch (_: Exception) {
                    null
                }
                if (fix != null) return@withContext fix
                if (allowStale) lastKnownFix(LOCATION_LASTKNOWN_MAX_MS) else null
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
            if (!hasNetwork()) {
                initialSyncRetryAt = android.os.SystemClock.elapsedRealtime() + 5 * 60_000L
                return
            }
            if (NetworkUtils.isAuthBlocked(preferencesManager)) {
                initialSyncRetryAt = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
                return
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting SMS history...")
            repairCursorsAfterProviderReset()
            val entrySmsId = try { preferencesManager.lastSmsId } catch (_: Exception) { 0L }
            val entrySmsDate = try { preferencesManager.lastSmsTimestamp } catch (_: Exception) { 0L }
            val pendingSms = mutableListOf<com.redeye.parentalmonitor.data.models.SmsData>()
            var smsReadFailed = false
            var smsCursor = entrySmsId
            var smsDate = entrySmsDate
            var smsPages = 0
            var smsLastFull = false
            while (smsPages < 5) {
                val page = try { smsRepository.getNewSms(smsCursor, smsDate) } catch (_: Exception) {
                    smsReadFailed = true
                    emptyList()
                }
                if (page.isEmpty()) break
                pendingSms.addAll(page)
                if (page.size >= 100) {
                    smsCursor = page.last().id
                    smsDate = page.last().date
                } else {
                    smsCursor = maxOf(smsCursor, page.maxOf { it.id })
                    smsDate = maxOf(smsDate, page.maxOf { it.date })
                }
                smsPages++
                smsLastFull = page.size >= 100
                if (page.size < 100) break
            }
            var smsTruncated = smsPages >= 5 && smsLastFull
            if (smsTruncated) {
                val probe = try { smsRepository.getNewSms(smsCursor, smsDate) } catch (_: Exception) {
                    smsReadFailed = true
                    emptyList()
                }
                smsTruncated = probe.isNotEmpty()
            }
            pendingSms.sortBy { it.id }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Found ${pendingSms.size} SMS messages")

            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Collecting call history...")
            val entryCallTs = try { preferencesManager.lastCallTimestamp } catch (_: Exception) { 0L }
            val entryCallId = try { preferencesManager.lastCallId } catch (_: Exception) { 0L }
            val pendingCalls = mutableListOf<com.redeye.parentalmonitor.data.models.CallData>()
            var callReadFailed = false
            var callTs = entryCallTs
            var callId = entryCallId
            var callPages = 0
            var callLastFull = false
            while (callPages < 5) {
                val page = try { callLogRepository.getNewCalls(callTs, callId) } catch (_: Exception) {
                    callReadFailed = true
                    emptyList()
                }
                if (page.isEmpty()) break
                pendingCalls.addAll(page)
                if (page.size >= 100) {
                    callTs = page.last().date
                    callId = page.last().id
                } else {
                    callTs = maxOf(callTs, page.maxOf { it.date })
                    callId = maxOf(callId, page.maxOf { it.id })
                }
                callPages++
                callLastFull = page.size >= 100
                if (page.size < 100) break
            }
            var callTruncated = callPages >= 5 && callLastFull
            if (callTruncated) {
                val probe = try { callLogRepository.getNewCalls(callTs, callId) } catch (_: Exception) {
                    callReadFailed = true
                    emptyList()
                }
                callTruncated = probe.isNotEmpty()
            }
            pendingCalls.sortWith(compareBy({ it.id }, { it.date }))
            if (pendingSms.isEmpty() && pendingCalls.isEmpty()) {
                if (smsReadFailed || callReadFailed) {
                    initialSyncRetryAt = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
                    android.util.Log.w("MonitoringService", "Sinkron awal ditunda: riwayat SMS/panggilan tak bisa dibaca")
                    return
                }
                val wasDone = try { preferencesManager.initialSyncDone } catch (_: Exception) { true }
                val queuedNow = try { messageQueue.getQueueSize() } catch (_: Exception) { 0 }
                preferencesManager.setInitialSyncDoneSync(true)
                if (!wasDone && queuedNow == 0) sendToTelegram("Monitoring jalan. Belum ada riwayat SMS/panggilan di HP ini.")
                return
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending start message...")
            val startMessage = buildString {
                appendLine("📱 <b>Pemantauan dimulai</b>")
                appendLine()
                appendLine("⏰ Waktu: ${formatDate(System.currentTimeMillis())}")
                appendLine()
                appendLine("📊 Ditemukan di perangkat:")
                appendLine("• SMS: ${pendingSms.size}")
                appendLine("• Panggilan: ${pendingCalls.size}")
                appendLine()
                appendLine("Mengirim riwayat...")
            }
            if (!initialStartSent) {
                initialStartSent = sendToTelegram(startMessage)
                delay(500)
            }
            var initialOk = true

            if (pendingSms.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${pendingSms.size} SMS...")
                val smsTotal = pendingSms.size
                var smsTop: com.redeye.parentalmonitor.data.models.SmsData? = null
                var smsSemuaTerkirim = true
                for ((index, part) in pendingSms.chunked(10).withIndex()) {
                    if (messageQueue.getQueueSize() >= DATA_QUEUE_SOFT_LIMIT) {
                        initialOk = false
                        smsSemuaTerkirim = false
                        break
                    }
                    val message = buildString {
                        appendLine("💬 <b>Riwayat SMS (${index * 10 + 1}-${index * 10 + part.size} dari $smsTotal)</b>")
                        appendLine()
                        part.forEach { sms ->
                            appendLine("📞 Nomor: ${Html.escape(sms.address)}")
                            val full = Html.escape(sms.body)
                            val body = full.take(safeCut(full, 200))
                            appendLine("📝 Isi: $body${if (full.length > body.length) "..." else ""}")
                            appendLine("🔄 Jenis: ${sms.getTypeString()}")
                            appendLine("⏰ Waktu: ${formatDate(sms.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    val sentSmsPart = sendFitted(message, queueOnFail = true, queuePriority = true)
                    if (sentSmsPart != SendStatus.DROPPED) {
                        val topPart = part.maxWithOrNull(compareBy({ it.date }, { it.id }))
                        val topPrev = smsTop
                        if (topPart != null && (topPrev == null || topPart.date > topPrev.date || (topPart.date == topPrev.date && topPart.id > topPrev.id))) {
                            smsTop = topPart
                        }
                        val t = smsTop
                        if (t != null) {
                            try { preferencesManager.setSmsCursorSync(t.id, t.date) } catch (_: Exception) { }
                        }
                    } else {
                        initialOk = false
                        smsSemuaTerkirim = false
                        break
                    }
                    delay(500)
                }
                if (smsSemuaTerkirim && !smsTruncated) {
                    val tuntasSms = com.redeye.parentalmonitor.utils.CursorAdvance.penyelesaian(
                        try { preferencesManager.lastSmsId } catch (_: Exception) { 0L },
                        try { preferencesManager.lastSmsTimestamp } catch (_: Exception) { 0L },
                        pendingSms.map { it.id to it.date }
                    )
                    try { preferencesManager.setSmsCursorSync(tuntasSms.first, tuntasSms.second) } catch (_: Exception) { }
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All SMS sent")
            }

            if (pendingCalls.isNotEmpty()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending ${pendingCalls.size} calls...")
                val callTotal = pendingCalls.size
                var callTop: com.redeye.parentalmonitor.data.models.CallData? = null
                var callSemuaTerkirim = true
                for ((index, part) in pendingCalls.chunked(10).withIndex()) {
                    if (messageQueue.getQueueSize() >= DATA_QUEUE_SOFT_LIMIT) {
                        initialOk = false
                        callSemuaTerkirim = false
                        break
                    }
                    val message = buildString {
                        appendLine("📞 <b>Riwayat Panggilan (${index * 10 + 1}-${index * 10 + part.size} dari $callTotal)</b>")
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
                    val sentCallPart = sendFitted(message, queueOnFail = true, queuePriority = true)
                    if (sentCallPart != SendStatus.DROPPED) {
                        val topPart = part.maxWithOrNull(compareBy({ it.date }, { it.id }))
                        val topPrev = callTop
                        if (topPart != null && (topPrev == null || topPart.date > topPrev.date || (topPart.date == topPrev.date && topPart.id > topPrev.id))) {
                            callTop = topPart
                        }
                        val t = callTop
                        if (t != null) {
                            try { preferencesManager.setCallCursorSync(t.date, t.id) } catch (_: Exception) { }
                        }
                    } else {
                        initialOk = false
                        callSemuaTerkirim = false
                        break
                    }
                    delay(500)
                }
                if (callSemuaTerkirim && !callTruncated) {
                    val tuntasCall = com.redeye.parentalmonitor.utils.CursorAdvance.penyelesaian(
                        try { preferencesManager.lastCallId } catch (_: Exception) { 0L },
                        try { preferencesManager.lastCallTimestamp } catch (_: Exception) { 0L },
                        pendingCalls.map { it.id to it.date }
                    )
                    try { preferencesManager.setCallCursorSync(tuntasCall.second, tuntasCall.first) } catch (_: Exception) { }
                }
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "All calls sent")
            }

            // Final message
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Sending completion message...")
            val photoState = if (isPhotoPaused()) {
                val leftMin = ((photoPausedElapsed() - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1L)
                "dijeda (sisa $leftMin mnt)"
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
            if (initialOk && !smsReadFailed && !callReadFailed && !smsTruncated && !callTruncated) {
                sendToTelegram(completeMessage)
                initialSyncRetryAt = 0L
                initialStartSent = false
                initialPartialNotified = false
                preferencesManager.setInitialSyncDoneSync(true)
            } else {
                initialSyncRetryAt = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
                if (!initialPartialNotified) {
                    initialPartialNotified = sendToTelegram("Sinkron riwayat terkirim sebagian. Sisanya menyusul otomatis.")
                }
            }
            idleDataCycles = 0
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "=== Initial data sending complete ===")

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error in initial sync: ${redactToken(e.message)}")
            try {
                if (!preferencesManager.initialSyncDone) initialSyncRetryAt = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
            } catch (_: Exception) {
            }
        }
    }

    private fun repairCursorsAfterProviderReset() {
        try {
            val smsCursor = preferencesManager.lastSmsId
            if (smsCursor > 0L) {
                val newest = smsRepository.newestSmsId()
                if (com.redeye.parentalmonitor.utils.CursorRepair.shouldReset(smsCursor, newest)) {
                    preferencesManager.resetSmsCursorSync()
                    android.util.Log.w("MonitoringService", "Kursor SMS direset: basis data pesan berganti (id $newest < $smsCursor)")
                }
            }
            val currentSms = preferencesManager.lastSmsId
            if (currentSms > 0L && preferencesManager.lastSmsTimestamp <= 0L) {
                val ts = smsRepository.smsDateForId(currentSms)
                if (ts > 0L) preferencesManager.setSmsCursorSync(currentSms, ts)
            }
        } catch (_: Exception) {
        }
        try {
            val callCursor = preferencesManager.lastCallId
            if (callCursor > 0L) {
                val newest = callLogRepository.newestCallId()
                if (com.redeye.parentalmonitor.utils.CursorRepair.shouldReset(callCursor, newest)) {
                    preferencesManager.resetCallCursorSync()
                    android.util.Log.w("MonitoringService", "Kursor panggilan direset: basis data panggilan berganti (id $newest < $callCursor)")
                }
            }
        } catch (_: Exception) {
        }
    }

    private val dataCheckLock = java.util.concurrent.Semaphore(1)

    private suspend fun checkAndSendNewData(): Boolean {
        if (!dataCheckLock.tryAcquire()) return false
        try {
            return checkAndSendNewDataLocked()
        } finally {
            dataCheckLock.release()
        }
    }

    private var lastImmediateCheckAt = 0L

    private suspend fun triggerImmediateCheck() {
        try {
            if (!preferencesManager.isConfigured() || preferencesManager.userDisabledMonitoring || preferencesManager.monitoringPaused) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastImmediateCheckAt > 0L && now - lastImmediateCheckAt < 15_000L) return
            lastImmediateCheckAt = now
            delay(1_500)
            checkAndSendNewData()
        } catch (_: Exception) {
        }
    }

    private suspend fun checkAndSendNewDataLocked(): Boolean {
        if (initialSyncRunning.get()) return false
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return false
        if (!hasNetwork()) return false
        if (NetworkUtils.rateLimitedRemainMs() > 0L) return false
        repairCursorsAfterProviderReset()
        var found = false
        try {
            try { checkPermissionHealth() } catch (_: Exception) { }
            try { checkSimIdentity() } catch (_: Exception) { }
            val lastSms = try {
                preferencesManager.lastSmsId
            } catch (_: Exception) {
                0L
            }
            val lastSmsTs = try {
                preferencesManager.lastSmsTimestamp
            } catch (_: Exception) {
                0L
            }
            val smsPage = smsRepository.getNewSms(lastSms, lastSmsTs)
            if (smsPage.isNotEmpty()) {
                var smsCursor = lastSms
                var smsDate = lastSmsTs
                var smsSentAny = false
                val freqMap = try { freqForNumbers(null) } catch (_: Exception) { null }
                val knownMap = try { smsPage.map { it.address }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
                var smsHabis = true
                for (part in smsPage.chunked(10)) {
                    if (messageQueue.getQueueSize() >= DATA_QUEUE_SOFT_LIMIT) {
                        smsHabis = false
                        break
                    }
                    val sentSms = sendFitted(formatSmsMessage(part, freqMap, knownMap), queueOnFail = true, queuePriority = true)
                    if (sentSms != SendStatus.DROPPED) {
                        smsCursor = part.last().id
                        smsDate = part.last().date
                        try { preferencesManager.setSmsCursorSync(smsCursor, smsDate) } catch (_: Exception) { }
                        smsSentAny = true
                        found = true
                    } else {
                        smsHabis = false
                        break
                    }
                }
                if (smsHabis) {
                    val tuntas = com.redeye.parentalmonitor.utils.CursorAdvance.penyelesaian(lastSms, lastSmsTs, smsPage.map { it.id to it.date })
                    if (tuntas.first != smsCursor || tuntas.second != smsDate) {
                        try { preferencesManager.setSmsCursorSync(tuntas.first, tuntas.second) } catch (_: Exception) { }
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
                var callSentAny = false
                val callKnown = try { callPage.map { it.number }.distinct().associateWith { isKnownContact(it) } } catch (_: Exception) { null }
                var callHabis = true
                for (part in callPage.chunked(10)) {
                    if (messageQueue.getQueueSize() >= DATA_QUEUE_SOFT_LIMIT) {
                        callHabis = false
                        break
                    }
                    val sentCall = sendFitted(formatCallMessage(part, callKnown), queueOnFail = true, queuePriority = true)
                    if (sentCall != SendStatus.DROPPED) {
                        try {
                            preferencesManager.setCallCursorSync(part.last().date, part.last().id)
                        } catch (_: Exception) {
                        }
                        callSentAny = true
                        found = true
                    } else {
                        callHabis = false
                        break
                    }
                }
                if (callHabis) {
                    val tuntas = com.redeye.parentalmonitor.utils.CursorAdvance.penyelesaian(lastCallId, lastCallTs, callPage.map { it.id to it.date })
                    try { preferencesManager.setCallCursorSync(tuntas.second, tuntas.first) } catch (_: Exception) { }
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
            sendToTelegram("⚠️ SMS gagal.")
            return
        }
        val parts: java.util.ArrayList<String> = try {
            smsManager.divideMessage(smsText).ifEmpty { java.util.ArrayList(listOf(smsText)) }
        } catch (e: Exception) {
            sendToTelegram("⚠️ SMS gagal.")
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
                        if (okCount.incrementAndGet() + failCount.get() >= expected) delivered.complete(failCount.get() == 0)
                    } else {
                        if (failCount.incrementAndGet() + okCount.get() >= expected) delivered.complete(false)
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
            sendToTelegram("⚠️ SMS gagal.")
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
                                android.content.Intent(sentAction).putExtra("part", it),
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
                    sendToTelegram("\uD83D\uDCE9 SMS terkirim ke $number.")
                } else if (okCount.get() > 0) {
                    preferencesManager.setLastSmsSendAtSync(System.currentTimeMillis())
                    val smsPreview = try { Html.escape(smsText).let { it.take(safeCut(it, 120)) } } catch (_: Exception) { "" }
                    val smsNotice = if (smsPreview.isEmpty()) "\u26A0\uFE0F SMS terkirim sebagian (${okCount.get()}/$expected bagian). Yang tertunda disimpan; cek ke penerima dulu sebelum coba lagi karena bisa terkirim dobel." else "\u26A0\uFE0F SMS terkirim sebagian (${okCount.get()}/$expected bagian) ke $number. Yang tertunda disimpan; cek ke penerima dulu sebelum coba lagi karena bisa terkirim dobel. Pratinjau teks: $smsPreview"
                    sendToTelegram(smsNotice)
                } else {
                    sendToTelegram("⚠️ SMS belum terkonfirmasi terkirim (timeout total). Yang tertunda disimpan; SMS-nya bisa jadi sebenarnya sudah sampai — cek ke penerima dulu sebelum /smsconfirm lagi karena bisa terkirim dobel.")
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
            sendToTelegram("⚠️ Izin SMS belum diberikan. Buka Setup dan berikan izin SMS.")
        } catch (e: Exception) {
            sendToTelegram("⚠️ SMS gagal.")
        }
    }

    private fun formatSmsMessage(smsList: List<com.redeye.parentalmonitor.data.models.SmsData>, freqMap: Map<String, Int>? = null, knownMap: Map<String, Boolean>? = null): String {
        val watchNums = watchNumbers()
        val todayCounts = HashMap<String, Int>()
        var freqFallback: Map<String, Int>? = null
        return buildString {
            appendLine("💬 <b>SMS baru (${smsList.size})</b>")
            appendLine()

            smsList.forEach { sms ->
                appendLine("📞 Nomor: ${Html.escape(sms.address)}")
                val tag = smsTag(sms.address, sms.body)
                if (tag.isNotEmpty()) appendLine(tag)
                try {
                    if (watchNumberHit(sms.address, watchNums) != null) appendLine("🚨 Nomor diawasi")
                } catch (_: Exception) {
                }
                try {
                    val key = sms.address.filter { it.isDigit() }.takeLast(10)
                    val freq = freqMap?.get(key) ?: run {
                        val exact = freqFallback ?: freqForNumbers(smsList.map { it.address }).also { freqFallback = it }
                        todayCounts.getOrPut(key) { exact[key] ?: 0 }
                    }
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
        val watchNums = watchNumbers()
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
                    if (watchNumberHit(call.number, watchNums) != null) appendLine("🚨 Nomor diawasi")
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

    private enum class SendStatus { SENT, QUEUED, DROPPED }

    private suspend fun sendFitted(message: String, replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null, queueOnFail: Boolean = true, queuePriority: Boolean = false): SendStatus {
        if (awaitingOwnerLogin()) return SendStatus.DROPPED
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            if (queueOnFail) {
                val queued = messageQueue.addMessage(message, queuePriority)
                if (queued) MessageScheduler.scheduleMessageSend(this)
                return if (queued) SendStatus.QUEUED else SendStatus.DROPPED
            }
            return SendStatus.DROPPED
        }
        if (message.length <= 4000) {
            var permanent = false
            if (sendToTelegram(message, replyMarkup, false) { permanent = true }) return SendStatus.SENT
            if (permanent) return SendStatus.DROPPED
            if (queueOnFail) {
                val queued = messageQueue.addMessage(message, queuePriority)
                if (replyMarkup != null) messageQueue.addMessage("Tap /help untuk menampilkan tombol menu.")
                if (queued) MessageScheduler.scheduleMessageSend(this)
                return if (queued) SendStatus.QUEUED else SendStatus.DROPPED
            }
            return SendStatus.DROPPED
        }
        var sentAny = false
        var permanentLoss = false
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
                val markup = if (idx == chunks.lastIndex) replyMarkup else NO_MARKUP
                var permanent = false
                if (!sendToTelegram(chunk, markup, false) { permanent = true }) {
                    if (!permanent) {
                        failed.add(chunk)
                        for (restIdx in idx + 1 until chunks.size) failed.add(chunks[restIdx])
                        break
                    }
                    permanentLoss = true
                } else {
                    lastSentIdx = idx
                    sentAny = true
                }
                if (idx != chunks.lastIndex) delay(300)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (queueOnFail) {
                val pending = ArrayList<String>()
                if (lastSentIdx + 1 < chunks.size) pending.addAll(chunks.subList(lastSentIdx + 1, chunks.size))
                for (f in failed) {
                    if (!pending.contains(f)) pending.add(f)
                }
                if (pending.isNotEmpty()) {
                    try {
                        messageQueue.addMessages(pending, queuePriority)
                        MessageScheduler.scheduleMessageSend(this)
                    } catch (_: Exception) {
                    }
                }
            }
            throw e
        }
        if (permanentLoss) {
            if (queueOnFail && !queuePriority && failed.isNotEmpty()) {
                val queuedTail = messageQueue.addMessages(failed, queuePriority)
                if (replyMarkup != null && failed.last() == chunks.last()) messageQueue.addMessage("Tap /help untuk menampilkan tombol menu.")
                if (queuedTail) MessageScheduler.scheduleMessageSend(this)
            }
            return SendStatus.DROPPED
        }
        if (failed.isNotEmpty()) {
            if (queueOnFail) {
                val queued = messageQueue.addMessages(failed, queuePriority)
                if (replyMarkup != null && failed.last() == chunks.last()) messageQueue.addMessage("Tap /help untuk menampilkan tombol menu.")
                if (queued) MessageScheduler.scheduleMessageSend(this)
                return if (queued) SendStatus.QUEUED else SendStatus.DROPPED
            }
            return SendStatus.DROPPED
        }
        return if (sentAny) SendStatus.SENT else SendStatus.DROPPED
    }

    private fun scheduleMsgDropFlush() {
        synchronized(msgDropLock) {
            if (msgDropJob?.isActive == true) return
            lateinit var job: Job
            job = serviceScope.launch {
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
                synchronized(msgDropLock) {
                    if (msgDropJob === job) msgDropJob = null
                }
                if (msgDropCount.get() > 0) scheduleMsgDropFlush()
            }
            msgDropJob = job
        }
    }

    private fun noteDroppedMessage() {
        msgDropCount.incrementAndGet()
        try {
            preferencesManager.setPendingMsgDropsSync(msgDropCount.get())
        } catch (_: Exception) {
        }
        scheduleMsgDropFlush()
    }

    private fun flushMsgDropNotice() {
        val count = msgDropCount.getAndSet(0)
        try {
            preferencesManager.setPendingMsgDropsSync(0)
        } catch (_: Exception) {
        }
        if (count <= 0) return
        try {
            val text = if (count == 1) "1 pesan dibuang karena ditolak Telegram (permanen) — kosong atau tak valid."
                else "$count pesan dibuang karena ditolak Telegram (permanen) — kosong atau tak valid."
            if (!messageQueue.addMessage(text, true)) throw IllegalStateException("addMessage gagal")
            MessageScheduler.scheduleMessageSend(this@MonitoringService)
        } catch (_: Exception) {
            msgDropCount.addAndGet(count)
            try {
                preferencesManager.setPendingMsgDropsSync(msgDropCount.get())
            } catch (_: Exception) {
            }
        }
    }

    private fun replyLater(message: String) {
        serviceScope.launch {
            try {
                sendToTelegram(message)
            } catch (_: Exception) {
            }
        }
    }

    private fun classifySend(response: retrofit2.Response<com.redeye.parentalmonitor.network.TelegramResponse>, chatId: String): TelegramSendPolicy.Outcome {
        val code = response.code()
        val body = if (code == 429 || code == 400) {
            try { response.errorBody()?.string() } catch (_: Exception) { null }
        } else {
            null
        }
        val migrated = if (code == 400) adoptMigratedChat(body, chatId) else false
        return TelegramSendPolicy.classify(response.isSuccessful, response.body()?.ok == true, code, body, migrated)
    }

    private suspend fun sendToTelegram(
        message: String,
        replyMarkup: com.redeye.parentalmonitor.network.InlineKeyboardMarkup? = null,
        queueOnFail: Boolean = true,
        onPermanent: (() -> Unit)? = null
    ): Boolean {
        try {
            if (awaitingOwnerLogin()) return false
            if (NetworkUtils.isAuthBlocked(preferencesManager)) {
                queueForRetry(message, queueOnFail)
                return false
            }
            val rateRemain = NetworkUtils.rateLimitedRemainMs()
            if (rateRemain > 0L) {
                queueForRetryLater(message, queueOnFail, rateRemain)
                return false
            }
            if (message.length > 4000) {
                android.util.Log.e("MonitoringService", "Message too long: ${message.length} chars, splitting")
                return sendFitted(message, replyMarkup, queueOnFail) != SendStatus.DROPPED
            }

            val (botToken, chatId) = sendCreds()

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.w("MonitoringService", "Bot credentials unavailable, queuing message for later")
                queueForRetry(message, queueOnFail)
                return false
            }

            val hasNetwork = hasNetwork()
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MonitoringService", "Network available: $hasNetwork")

            if (!hasNetwork) {
                android.util.Log.w("MonitoringService", "No network, adding to queue")
                queueForRetry(message, queueOnFail)
                return false
            }

            val telegramMessage = TelegramMessage(
                chatId = chatId,
                text = message,
                parseMode = "HTML",
                replyMarkup = replyMarkup ?: quickMenuBar()
            )

            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(url, telegramMessage)

            val outcome = classifySend(response, chatId)
            when (outcome.kind) {
                TelegramSendPolicy.Kind.SENT -> {
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "Message sent")
                    preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, "", 0L)
                    noteSendOk()
                    return true
                }
                TelegramSendPolicy.Kind.RATE_LIMITED -> {
                    NetworkUtils.noteRateLimited(outcome.retryAfterSecs)
                    android.util.Log.w("MonitoringService", "Rate limited, will retry via queue after ${outcome.retryAfterSecs}s")
                    queueForRetryLater(message, queueOnFail, outcome.retryAfterSecs * 1000L)
                    return false
                }
                TelegramSendPolicy.Kind.AUTH_FAILED -> {
                    android.util.Log.e("MonitoringService", "Send rejected (${outcome.code}), queuing")
                    preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis())
                    queueForRetry(message, queueOnFail)
                    return false
                }
                else -> {
                    if (outcome.code == 400) {
                        return sendStatus400(outcome, message, botToken, chatId, queueOnFail, onPermanent)
                    }
                    android.util.Log.e("MonitoringService", "✗ Failed to send: ${outcome.code}")
                    queueForRetry(message, queueOnFail)
                    return false
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${redactToken(e.message)}")
            queueForRetry(message, queueOnFail)
            return false
        }
    }

    private suspend fun sendStatus400(
        outcome: TelegramSendPolicy.Outcome,
        message: String,
        botToken: String,
        chatId: String,
        queueOnFail: Boolean,
        onPermanent: (() -> Unit)? = null
    ): Boolean {
        if (outcome.kind == TelegramSendPolicy.Kind.MIGRATED) {
            queueForRetry(message, queueOnFail)
            return false
        }
        if (outcome.kind == TelegramSendPolicy.Kind.CHAT_MISSING) {
            preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis())
            try {
                postAuthFailureReminder(400)
            } catch (_: Exception) {
            }
            queueForRetry(message, queueOnFail)
            return false
        }
        if (outcome.kind == TelegramSendPolicy.Kind.RIGHTS_LIMITED) {
            android.util.Log.w("MonitoringService", "Insufficient rights (400), queuing without auth block")
            queueForRetry(message, queueOnFail)
            return false
        }
        val plain = Html.unescape(message)
        if (plain != message || outcome.kind == TelegramSendPolicy.Kind.PARSE_ENTITIES) {
            try {
                return sendPlainFallback(botToken, chatId, plain, queueOnFail, onPermanent)
            } catch (_: Exception) {
                queueForRetry(plain, queueOnFail)
                return false
            }
        } else {
            android.util.Log.w("MonitoringService", "Message permanently rejected (400), not queued")
            try {
                noteDroppedMessage()
            } catch (_: Exception) {
            }
            try { onPermanent?.invoke() } catch (_: Exception) { }
            return false
        }
    }

    private suspend fun sendPlainFallback(
        botToken: String,
        chatId: String,
        plain: String,
        queueOnFail: Boolean,
        onPermanent: (() -> Unit)? = null
    ): Boolean {
        val fallbackUrl = "https://api.telegram.org/bot${botToken}/sendMessage"
        val fallbackResp = TelegramClient.api.sendMessage(fallbackUrl, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
        val outcome = classifySend(fallbackResp, chatId)
        return when (outcome.kind) {
            TelegramSendPolicy.Kind.SENT -> {
                preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, "", 0L)
                noteSendOk()
                true
            }
            TelegramSendPolicy.Kind.RATE_LIMITED -> {
                NetworkUtils.noteRateLimited(outcome.retryAfterSecs)
                queueForRetryLater(plain, queueOnFail, outcome.retryAfterSecs * 1000L)
                false
            }
            TelegramSendPolicy.Kind.AUTH_FAILED -> {
                preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis())
                queueForRetry(plain, queueOnFail)
                false
            }
            TelegramSendPolicy.Kind.TRANSIENT, TelegramSendPolicy.Kind.MIGRATED, TelegramSendPolicy.Kind.RIGHTS_LIMITED -> {
                queueForRetry(plain, queueOnFail)
                false
            }
            TelegramSendPolicy.Kind.CHAT_MISSING -> {
                preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, outcome.code.toString(), System.currentTimeMillis())
                queueForRetry(plain, queueOnFail)
                false
            }
            TelegramSendPolicy.Kind.PARSE_ENTITIES, TelegramSendPolicy.Kind.REJECTED -> {
                android.util.Log.w("MonitoringService", "Message permanently rejected (${outcome.code}), not queued")
                try {
                    noteDroppedMessage()
                } catch (_: Exception) {
                }
                try { onPermanent?.invoke() } catch (_: Exception) { }
                false
            }
        }
    }

    private fun queueForRetry(text: String, queueOnFail: Boolean) {
        if (queueOnFail) {
            messageQueue.addMessage(text)
            MessageScheduler.scheduleMessageSend(this)
        }
    }

    private fun queueForRetryLater(text: String, queueOnFail: Boolean, delayMs: Long) {
        if (queueOnFail) {
            messageQueue.addMessage(text)
            MessageScheduler.scheduleMessageSendNext(this, delayMs)
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
        val factor = (idleDataCycles.coerceAtLeast(0) + 1).coerceAtMost(4).toLong()
        val stretched = try { base * factor } catch (_: Exception) { base }
        return stretched.coerceIn(1L, maxOf(base, 30 * 60_000L))
    }

    private fun isScreenOn(): Boolean {
        return try {
            powerManagerSvc?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun isNightTime(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = DigestSchedule.localHour(now, off.toLong())
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
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun isCharging(): Boolean {
        return try {
            batteryManager?.isCharging == true
        } catch (_: Exception) {
            false
        }
    }

    private fun lowPowerActive(): Boolean {
        val level = batteryLevel()
        return level in 0..LOW_BATTERY_LEVEL && !isCharging()
    }

    private fun usageRows(hours: Int): List<Pair<String, Long>>? {
        val end = System.currentTimeMillis()
        val start = end - hours.coerceIn(1, 24) * 60 * 60_000L
        return usageRowsBetween(start, end)
    }

    private fun usageRowsBetween(start: Long, end: Long): List<Pair<String, Long>>? {
        if (!com.redeye.parentalmonitor.utils.UsageAccess.granted(this)) return null
        return try {
            val manager = usageStatsManager ?: return null
            val stats = manager.queryAndAggregateUsageStats(start, end) ?: return null
            val pm = packageManager
            val rows = mutableListOf<Pair<String, Long>>()
            for ((pkg, stat) in stats) {
                if (pkg == packageName) continue
                val ms = stat.totalTimeInForeground
                if (ms <= 0L) continue
                val cached = appLabelCache[pkg]
                val launchable: Boolean
                val label: String
                if (cached != null) {
                    launchable = cached.first
                    label = cached.second
                } else {
                    launchable = try { pm.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }
                    var labelResolved = true
                    label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { labelResolved = false; pkg }
                    if (labelResolved) {
                        if (appLabelCache.size > 300) appLabelCache.clear()
                        appLabelCache[pkg] = launchable to label
                    }
                }
                if (!launchable) continue
                rows.add(label to ms)
            }
            rows.sortByDescending { it.second }
            rows
        } catch (_: Exception) {
            null
        }
    }

    private fun screenTimeFull(hours: Int): String {
        if (!com.redeye.parentalmonitor.utils.UsageAccess.granted(this)) {
            val a11y = a11yUsageRows()
            if (a11y.isNotEmpty()) return a11yScreenTime(hours, a11y)
            return "📱 Izin \"Usage access\" belum aktif dan belum ada data aksesibilitas. Buka Setup → tombol \"Screen time access\", aktifkan untuk aplikasi ini, lalu coba lagi."
        }
        val rows = usageRows(hours)
            ?: return "📱 Data pemakaian layar tak bisa dibaca sekarang. Coba lagi sebentar; bila tetap gagal, matikan lalu hidupkan lagi izin \"Usage access\" aplikasi ini."
        if (rows.isEmpty()) return "📱 Belum ada data pemakaian layar $hours jam terakhir."
        val total = rows.sumOf { it.second }
        return buildString {
            appendLine("📱 <b>Ringkasan layar</b> ($hours jam terakhir)")
            appendLine("Total: " + ReportFormat.durationText(total))
            for (row in rows.take(6)) appendLine("• " + Html.escape(row.first) + " — " + ReportFormat.durationText(row.second))
            val a11y = a11yUsageRows()
            if (a11y.isNotEmpty()) {
                val night = a11y.sumOf { it.nightMillis }
                if (night > 0L) appendLine("Malam ini (aksesibilitas): " + ReportFormat.durationText(night))
            }
            val now = A11yMonitor.currentAppLabel()
            if (now.isNotEmpty()) appendLine("Sekarang: " + Html.escape(now))
        }.trimEnd()
    }

    private fun a11yScreenTime(hours: Int, rows: List<A11yMonitor.AppUsage>): String {
        val total = rows.sumOf { it.millis }
        val night = rows.sumOf { it.nightMillis }
        return buildString {
            appendLine("📱 <b>Ringkasan layar</b> (aksesibilitas, hari ini)")
            appendLine("Total: " + ReportFormat.durationText(total))
            for (row in rows.take(6)) {
                appendLine("• " + Html.escape(row.label) + " — " + ReportFormat.durationText(row.millis) +
                    if (row.nightMillis > 0L) " · malam " + ReportFormat.durationText(row.nightMillis) else "")
            }
            if (night > 0L) appendLine("Pemakaian malam: " + ReportFormat.durationText(night))
            val now = A11yMonitor.currentAppLabel()
            if (now.isNotEmpty()) appendLine("Sekarang: " + Html.escape(now))
            if (hours < 24) appendLine("Catatan: data aksesibilitas dihitung sejak 00:00, bukan $hours jam terakhir.")
        }.trimEnd()
    }

    private fun a11yUsageRows(): List<A11yMonitor.AppUsage> {
        return try { A11yMonitor.todayUsage(this) } catch (_: Exception) { emptyList() }
    }

    @Suppress("DEPRECATION")
    private fun lastCachedLocation(): android.location.Location? {
        if (!hasLocationPermission()) return null
        return try {
            val manager = locationManagerSvc ?: return null
            var best: android.location.Location? = null
            for (provider in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                val loc = try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null } ?: continue
                if (best == null || loc.time > best.time) best = loc
            }
            val fix = best ?: return null
            if (System.currentTimeMillis() - fix.time > 30 * 60_000L) return null
            fix
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkVehicle() {
        if (!cachedVehicleEnabled || cachedMonitoringPaused) return
        val fix = lastCachedLocation() ?: return
        val now = System.currentTimeMillis()
        if (now - fix.time > 10 * 60_000L) {
            vehicleFastSamples = 0
            return
        }
        val prev = vehiclePrevFix
        if (fix.accuracy > 80f) {
            vehicleFastSamples = 0
            return
        }
        vehiclePrevFix = Triple(fix.latitude, fix.longitude, fix.time)
        var speed = if (fix.hasSpeed()) fix.speed else -1f
        var hardwareSpeed = speed >= 0f
        if (speed < 0f && prev != null) {
            val dt = fix.time - prev.third
            if (dt in 30_000L..30 * 60_000L) {
                val dist = ReportFormat.distanceMeters(prev.first, prev.second, fix.latitude, fix.longitude)
                if (dist >= 20f) {
                    speed = dist / (dt / 1000f)
                    hardwareSpeed = false
                }
            }
        }
        if (speed < VEHICLE_MIN_SPEED_MPS || speed > VEHICLE_MAX_SPEED_MPS) {
            vehicleFastSamples = 0
            return
        }
        vehicleFastSamples += 1
        if (!hardwareSpeed && vehicleFastSamples < 2) return
        vehicleFastSamples = 0
        val last = try { preferencesManager.lastVehicleAlertAt } catch (_: Exception) { 0L }
        if (last > 0L && now > last && now - last < VEHICLE_COOLDOWN_MS) return
        try { preferencesManager.lastVehicleAlertAt = now } catch (_: Exception) { }
        val kmh = (speed * 3.6f).toInt()
        val level = batteryLevel()
        val battery = if (level in 0..100) "\nBaterai: $level%" else ""
        sendToTelegram("🚗 <b>Sepertinya sedang berkendara</b>\nKecepatan ±$kmh km/jam\n📍 https://maps.google.com/?q=${fix.latitude},${fix.longitude}\nAkurasi: ±${fix.accuracy.toInt()} m$battery")
        try { checkGeofence(fix.latitude, fix.longitude, fix.accuracy) } catch (_: Exception) { }
    }

    private fun isMeteredNetwork(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (_: Exception) {
            false
        }
    }

    private fun markMediaActive(file: File?) {
        if (file != null) activeMediaFiles.add(file)
    }

    private fun clearMediaActive(file: File?) {
        if (file != null) activeMediaFiles.remove(file)
    }

    private fun isMediaActive(file: File?): Boolean {
        if (file == null) return false
        return activeMediaFiles.contains(file)
    }

    private fun pendingMediaCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.length() > 0L && (
                    (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg") ||
                        file.name.startsWith("video_") && file.name.endsWith(".mp4") ||
                        file.name.startsWith("audio_") && file.name.endsWith(".m4a")
                    )
            }?.count { !isMediaActive(it) } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun dataSaverDeferMedia(): Boolean {
        if (!cachedDataSaver) return false
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (nowElapsed - dataSaverCheckedAtElapsed < DATA_SAVER_CHECK_WINDOW_MS) return dataSaverDeferResult
        val deferred = dataSaverHoldNow(0)
        dataSaverCheckedAtElapsed = nowElapsed
        dataSaverDeferResult = deferred
        return deferred
    }

    private suspend fun dataSaverHoldNow(extraPending: Int): Boolean {
        if (!cachedDataSaver) return false
        try {
            if (!isMeteredNetwork()) return false
        } catch (_: Exception) {
            return false
        }
        val pending = pendingMediaCount() + extraPending
        if (pending <= 0) return false
        try {
            val now = System.currentTimeMillis()
            val last = preferencesManager.dataSaverNoticeAt
            if (last > now) {
                preferencesManager.dataSaverNoticeAt = now
            } else if (last <= 0L || now - last >= 6 * 60 * 60_000L) {
                preferencesManager.dataSaverNoticeAt = now
                sendToTelegram("📶 <b>Hemat kuota</b>: $pending foto/video/rekaman ditahan dulu, dikirim otomatis begitu tersambung Wi-Fi.")
            }
        } catch (_: Exception) {
        }
        return true
    }

    private suspend fun syncLowPowerNotice(lowPower: Boolean) {
        try {
            if (lowPower || !lowPowerNotified) return
            lowPowerNotified = false
            val noticeAt = try { preferencesManager.batteryAlertAt } catch (_: Exception) { 0L }
            val now = System.currentTimeMillis()
            if (noticeAt > 0L && now > noticeAt && now - noticeAt < 24 * 60 * 60_000L) {
                try { preferencesManager.batteryAlertAt = 0L } catch (_: Exception) { }
                sendToTelegram("🔋 Baterai kembali normal — foto otomatis & patroli berjalan seperti biasa.")
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun checkBatteryAlert() {
        val level = batteryLevel()
        if (level < 0 || level > LOW_BATTERY_LEVEL) return
        val now = System.currentTimeMillis()
        val last = try { preferencesManager.batteryAlertAt } catch (_: Exception) { 0L }
        if (last > now) {
            try { preferencesManager.batteryAlertAt = now } catch (_: Exception) { }
            return
        }
        if (last <= now && now - last < 24 * 60 * 60_000L) return
        try { preferencesManager.batteryAlertAt = now } catch (_: Exception) { }
        val throttled = lowPowerActive()
        if (throttled) lowPowerNotified = true
        sendToTelegram("🔋 Baterai perangkat tinggal $level%." +
            (if (throttled) " Foto otomatis & patroli diperlambat sampai di-charge." else "") +
            " Colok charger agar monitoring tak putus.")
    }

    private suspend fun maybeSendDigest() {
        if (awaitingOwnerLogin()) return
        val now = System.currentTimeMillis()
        val last = try { preferencesManager.digestAt } catch (_: Exception) { 0L }
        when (DigestSchedule.periodicDecision(last, now)) {
            DigestSchedule.Verdict.RESET -> {
                try { preferencesManager.digestAt = now } catch (_: Exception) { }
                return
            }
            DigestSchedule.Verdict.WAIT -> return
            DigestSchedule.Verdict.SEND -> try { preferencesManager.digestAt = now } catch (_: Exception) { }
        }
        val since = now - 6 * 60 * 60_000L
        val smsCount = try { smsRepository.getRecentSmsSince(since, 200).size } catch (_: Exception) { -1 }
        val callCount = try { callLogRepository.getCallsSince(since, 200).size } catch (_: Exception) { -1 }
        val level = batteryLevel()
        sendToTelegram(buildString {
            appendLine("📰 <b>Ringkasan 6 jam</b>")
            appendLine("SMS: ${if (smsCount < 0) "?" else if (smsCount >= 200) "200+" else "$smsCount"}${if (smsCount >= 200) " (dari 200 terakhir)" else ""}")
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

    private val prefsRmwLock = Any()

    private fun bumpLocFailCount() {
        try {
            synchronized(prefsRmwLock) {
                preferencesManager.locFailCount = (preferencesManager.locFailCount + 1).coerceAtMost(100)
            }
        } catch (_: Exception) {
        }
    }

    private fun resetLocFailCount() {
        try {
            synchronized(prefsRmwLock) {
                preferencesManager.locFailCount = 0
            }
        } catch (_: Exception) {
        }
    }

    private fun rememberSentLatLon(lat: Double, lon: Double, accuracyM: Float = -1f) {
        try {
            synchronized(prefsRmwLock) {
                val now = System.currentTimeMillis()
                val cutoff = now - 24 * 60 * 60_000L
                val raw = preferencesManager.trail24
                val pts = if (raw == trailMirrorRaw && trailMirrorPts != null) {
                    ArrayList(trailMirrorPts!!)
                } else {
                    ArrayList(raw.split(";").filter { it.contains(":") })
                }
                pts.add("$now:${(lat * 1_000_000).toLong()},${(lon * 1_000_000).toLong()}")
                var fresh = pts.filter { (it.substringBefore(":").toLongOrNull() ?: 0L) >= cutoff }
                if (fresh.size > 300) fresh = fresh.subList(fresh.size - 300, fresh.size)
                val joined = fresh.joinToString(";")
                trailMirrorRaw = joined
                trailMirrorPts = fresh
                preferencesManager.recordSentLocation(lat, lon, joined, accuracyM)
            }
        } catch (_: Exception) {
        }
    }

    private fun trailDigestLines(): List<String> {
        val pts = trailList().map { com.redeye.parentalmonitor.utils.TrailStats.Point(it.first, it.second, it.third) }
        if (pts.size < 2) return emptyList()
        val patrol = try { preferencesManager.patrolInterval } catch (_: Exception) { 30 }
        val maxGap = patrol.coerceIn(5, 180) * 60_000L + 10 * 60_000L
        val out = mutableListOf<String>()
        val dist = com.redeye.parentalmonitor.utils.TrailStats.distanceMeters(pts, maxGap)
        if (dist >= 200f) {
            val tenth = Math.round(com.redeye.parentalmonitor.utils.TrailStats.kilometers(dist) * 10f)
            out.add("Jarak 24 jam: ${tenth / 10}.${tenth % 10} km")
        }
        val stay = com.redeye.parentalmonitor.utils.TrailStats.longestStay(pts, maxGapMs = maxGap)
        if (stay != null && stay.millis >= 45 * 60_000L) {
            out.add("Diam ${ReportFormat.durationText(stay.millis)} di https://maps.google.com/?q=${stay.lat},${stay.lon}")
        }
        try {
            val la = preferencesManager.schoolLatE6
            val lo = preferencesManager.schoolLonE6
            val rad = preferencesManager.schoolRadiusM
            if (la != Long.MIN_VALUE && lo != Long.MIN_VALUE && rad > 0) {
                val inside = com.redeye.parentalmonitor.utils.TrailStats.millisInside(
                    pts, la / 1_000_000.0, lo / 1_000_000.0, rad.toFloat(), maxGap
                )
                if (inside >= 10 * 60_000L) out.add("Di sekolah: ${ReportFormat.durationText(inside)}")
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun usageSummary(hours: Int): com.redeye.parentalmonitor.utils.UsageEventsSummary.Summary? {
        return usageSummaryScan(hours, 0L).first
    }

    private fun usageSummaryScan(hours: Int, dayStartMs: Long): Pair<com.redeye.parentalmonitor.utils.UsageEventsSummary.Summary?, Long> {
        if (!com.redeye.parentalmonitor.utils.UsageAccess.granted(this)) return null to 0L
        val manager = usageStatsManager ?: return null to 0L
        val end = System.currentTimeMillis()
        val start = end - hours.coerceIn(1, 24) * 3_600_000L
        val events = ArrayList<com.redeye.parentalmonitor.utils.UsageEventsSummary.Event>()
        try {
            val cursor = manager.queryEvents(start, end) ?: return null to 0L
            val event = android.app.usage.UsageEvents.Event()
            while (cursor.hasNextEvent()) {
                cursor.getNextEvent(event)
                val type = event.eventType
                if (type != com.redeye.parentalmonitor.utils.UsageEventsSummary.TYPE_RESUMED &&
                    type != com.redeye.parentalmonitor.utils.UsageEventsSummary.TYPE_KEYGUARD_HIDDEN
                ) continue
                val pkg = try { event.packageName } catch (_: Exception) { null }
                if (pkg == packageName) continue
                events.add(com.redeye.parentalmonitor.utils.UsageEventsSummary.Event(type, event.timeStamp, pkg))
            }
        } catch (_: Exception) {
            return null to 0L
        }
        val summary = com.redeye.parentalmonitor.utils.UsageEventsSummary.build(events)
        val firstToday = if (dayStartMs > 0L) com.redeye.parentalmonitor.utils.UsageEventsSummary.firstUnlockToday(events, dayStartMs) else 0L
        return summary to firstToday
    }

    private fun startOfTodayMs(now: Long): Long {
        val off = java.util.TimeZone.getDefault().getOffset(now)
        return ((now + off) / 86_400_000L) * 86_400_000L - off
    }

    private fun firstUnlockTodayMs(now: Long): Long {
        if (!com.redeye.parentalmonitor.utils.UsageAccess.granted(this)) return 0L
        val manager = usageStatsManager ?: return 0L
        val start = startOfTodayMs(now)
        var first = 0L
        try {
            val cursor = manager.queryEvents(start, now) ?: return 0L
            val event = android.app.usage.UsageEvents.Event()
            while (cursor.hasNextEvent()) {
                cursor.getNextEvent(event)
                if (event.eventType != com.redeye.parentalmonitor.utils.UsageEventsSummary.TYPE_KEYGUARD_HIDDEN) continue
                val pkg = try { event.packageName } catch (_: Exception) { null }
                if (pkg == packageName) continue
                val at = event.timeStamp
                if (at > 0L) return at
            }
        } catch (_: Exception) {
            return 0L
        }
        return first
    }

    private fun usageEventSummary(hours: Int): List<String> {
        return usageEventSummary(usageSummary(hours))
    }

    private fun usageEventSummary(summary: com.redeye.parentalmonitor.utils.UsageEventsSummary.Summary?): List<String> {
        if (summary == null) return emptyList()
        val out = mutableListOf<String>()
        if (summary.unlocks > 0) {
            out.add("HP dibuka: ${summary.unlocks}x, pertama ${com.redeye.parentalmonitor.utils.TimeFmt.short(summary.firstUnlockAt)}")
        }
        val labels = summary.topOpens.map { (pkg, count) -> "${appLabelFor(pkg)} ${count}x" }
        if (labels.isNotEmpty()) out.add("Paling sering dibuka: " + labels.joinToString(", "))
        return out
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
            }.filter { it.first > System.currentTimeMillis() - 24 * 60 * 60_000L }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun smsTag(address: String, body: String): String {
        return try {
            val lower = body.lowercase(java.util.Locale.ROOT)
            val watch = watchHit(body)
            if (watch != null) return "⚠️ Watch: " + Html.escape(watch)
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
            AppWatch.parseKeywords(preferencesManager.watchKeywords)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun watchHit(text: String): String? {
        return try {
            AppWatch.hit(watchList(), text)
        } catch (_: Exception) {
            null
        }
    }

    private fun fenceStatusText(isHome: Boolean): String {
        return try {
            if (isHome) {
                GeofenceLogic.statusText(preferencesManager.homeLatE6, preferencesManager.homeLonE6, preferencesManager.homeRadiusM)
            } else {
                GeofenceLogic.statusText(preferencesManager.schoolLatE6, preferencesManager.schoolLonE6, preferencesManager.schoolRadiusM)
            }
        } catch (_: Exception) {
            "?"
        }
    }

    private fun recordFenceFact(arrive: Boolean) {
        try {
            val now = System.currentTimeMillis()
            val key = todayKey(now)
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val minute = (((now + off) / 60_000L) % 1440L).toInt()
            synchronized(prefsRmwLock) {
                val days = DailyFacts.parse(preferencesManager.dailyFacts)
                val existing = days.firstOrNull { it.day == key }
                val merged = when {
                    existing == null -> DailyFacts.Day(key, -1, -1, -1, if (arrive) minute else -1, if (arrive) -1 else minute)
                    arrive -> if (existing.arriveMin >= 0) existing else existing.copy(arriveMin = minute)
                    else -> if (existing.departMin >= 0) existing else existing.copy(departMin = minute)
                }
                preferencesManager.dailyFacts = DailyFacts.serialize(DailyFacts.upsert(days, merged))
            }
        } catch (_: Exception) { }
    }

    private suspend fun checkGeofence(lat: Double, lon: Double, accuracyM: Float = -1f) {
        try {
            val fences = listOf(
                Triple(preferencesManager.homeLatE6, preferencesManager.homeLonE6, preferencesManager.homeRadiusM) to true,
                Triple(preferencesManager.schoolLatE6, preferencesManager.schoolLonE6, preferencesManager.schoolRadiusM) to false
            )
            for ((cfg, isHome) in fences) {
                val (laE6, loE6, rad) = cfg
                if (!GeofenceLogic.configured(laE6, loE6, rad)) continue
                val dist = ReportFormat.distanceMeters(laE6 / 1_000_000.0, loE6 / 1_000_000.0, lat, lon)
                if (dist < 0f) continue
                val inside = dist <= rad
                if (isHome) {
                    val jemputHit = try {
                        synchronized(fenceLock) {
                            val jemput = preferencesManager.jemputRadiusM
                            if (GeofenceLogic.jemputHit(jemput, dist, accuracyM)) {
                                preferencesManager.jemputRadiusM = 0
                                true
                            } else {
                                false
                            }
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (jemputHit) sendToTelegram("🏠 Anak dalam ±${dist.toInt()} m dari rumah — siap dijemput.")
                }
                val transition = try {
                    synchronized(fenceLock) {
                        val state = if (isHome) preferencesManager.homeState else preferencesManager.schoolState
                        val t = GeofenceLogic.transition(state, inside, accuracyM, rad)
                        val ws = t.writeState
                        if (ws != null) {
                            if (isHome) preferencesManager.homeState = ws else preferencesManager.schoolState = ws
                        }
                        t.alert
                    }
                } catch (_: Exception) {
                    false
                }
                if (transition) {
                    val name = if (isHome) "Rumah" else "Sekolah"
                    val verb = if (inside) "masuk" else "keluar"
                    sendToTelegram("📍 $name: perangkat $verb area (±${dist.toInt()} m dari titik).")
                    if (isHome) recordFenceFact(inside)
                }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun checkGeofenceFallback() {
        try {
            if (preferencesManager.monitoringPaused) return
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (lastGeofenceCheckAt != 0L && nowElapsed - lastGeofenceCheckAt < 15 * 60_000L) return
            val hasHome = try {
                GeofenceLogic.configured(preferencesManager.homeLatE6, preferencesManager.homeLonE6, preferencesManager.homeRadiusM)
            } catch (_: Exception) {
                false
            }
            val hasSchool = try {
                GeofenceLogic.configured(preferencesManager.schoolLatE6, preferencesManager.schoolLonE6, preferencesManager.schoolRadiusM)
            } catch (_: Exception) {
                false
            }
            if (!hasHome && !hasSchool) return
            lastGeofenceCheckAt = nowElapsed
            val locAge = System.currentTimeMillis() - try { preferencesManager.lastLocAt } catch (_: Exception) { 0L }
            val cached = lastSentLatLon()
            var accuracyM = -1f
            val pos = if (cached != null && locAge in 0..(30 * 60_000L)) {
                accuracyM = try { preferencesManager.lastLocAccM } catch (_: Exception) { -1f }
                cached
            } else {
                val fix = try { fetchLocation() } catch (_: Exception) { null } ?: return
                accuracyM = fix.accuracy
                try { rememberSentLatLon(fix.latitude, fix.longitude, fix.accuracy) } catch (_: Exception) { }
                Pair(fix.latitude, fix.longitude)
            }
            checkGeofence(pos.first, pos.second, accuracyM)
        } catch (_: Exception) {
        }
    }

    private fun todayKey(ts: Long = System.currentTimeMillis()): String {
        return com.redeye.parentalmonitor.utils.TimeFmt.dayKey(ts)
    }

    private class UsageDayData(
        val rows: List<Pair<String, Long>>?,
        val a11yRows: List<A11yMonitor.AppUsage>,
        val firstUnlockMs: Long
    )

    private fun usageDayData(now: Long, firstUnlockOverride: Long? = null): UsageDayData {
        val rows = try { usageRows(24) } catch (_: Exception) { null }
        val a11yRows = a11yUsageRows()
        val fu = firstUnlockOverride ?: try { firstUnlockTodayMs(now) } catch (_: Exception) { 0L }
        return UsageDayData(rows, a11yRows, fu)
    }

    private fun screenTimeSummaryFrom(data: UsageDayData): String? {
        val rows = data.rows
        if (rows == null || rows.isEmpty()) {
            val a11y = data.a11yRows
            if (a11y.isEmpty()) return null
            val total = a11y.sumOf { it.millis }
            val top = a11y.first()
            return "Layar hari ini: " + ReportFormat.durationText(total) + " · teratas " + Html.escape(top.label) + " " + ReportFormat.durationText(top.millis)
        }
        val total = rows.sumOf { it.second }
        val top = rows.first()
        return "Layar 24 jam: " + ReportFormat.durationText(total) + " · teratas " + Html.escape(top.first) + " " + ReportFormat.durationText(top.second)
    }

    private fun digestHighlights(now: Long, data: UsageDayData): List<String> {
        var todayMs = 0L
        var yesterdayMs = 0L
        var topLabel = ""
        var topMs = 0L
        var topYesterdayMs = 0L
        val a11yRows = data.a11yRows
        val rows = data.rows
        if (rows != null && rows.isNotEmpty()) {
            todayMs = rows.sumOf { it.second }
            val top = rows.firstOrNull()
            if (top != null) {
                topLabel = top.first
                topMs = top.second
                val rowsY = try { usageRowsBetween(now - 48 * 3_600_000L, now - 24 * 3_600_000L) } catch (_: Exception) { null }
                if (rowsY != null) {
                    yesterdayMs = rowsY.sumOf { it.second }
                    topYesterdayMs = rowsY.firstOrNull { it.first == topLabel }?.second ?: 0L
                }
            }
        } else if (a11yRows.isNotEmpty()) {
            todayMs = a11yRows.sumOf { it.millis }
            topLabel = a11yRows.first().label
            topMs = a11yRows.first().millis
        }
        val nightMs = if (a11yRows.isEmpty()) -1L else a11yRows.sumOf { it.nightMillis }
        val fuAt = data.firstUnlockMs
        val fuMinute = if (fuAt > 0L) {
            val off = java.util.TimeZone.getDefault().getOffset(fuAt)
            (((fuAt + off) / 60_000L) % 1440L).toInt()
        } else {
            -1
        }
        return DigestHighlights.build(DigestHighlights.Facts(todayMs, yesterdayMs, topLabel, topMs, topYesterdayMs, nightMs, fuMinute))
    }

    private fun todayFacts(now: Long, data: UsageDayData): DailyFacts.Day? {
        val key = todayKey(now)
        val stored = try { DailyFacts.parse(preferencesManager.dailyFacts).firstOrNull { it.day == key } } catch (_: Exception) { null }
        var screenMin = -1
        val rows = data.rows
        val a11yRows = data.a11yRows
        if (rows != null && rows.isNotEmpty()) {
            screenMin = (rows.sumOf { it.second } / 60_000L).toInt()
        } else if (a11yRows.isNotEmpty()) {
            screenMin = (a11yRows.sumOf { it.millis } / 60_000L).toInt()
        }
        if (screenMin < 0) return null
        val nightMin = if (a11yRows.isEmpty()) -1 else (a11yRows.sumOf { it.nightMillis } / 60_000L).toInt()
        val fuAt = data.firstUnlockMs
        val fuMinute = if (fuAt > 0L) {
            val off = java.util.TimeZone.getDefault().getOffset(fuAt)
            (((fuAt + off) / 60_000L) % 1440L).toInt()
        } else {
            -1
        }
        return DailyFacts.Day(key, screenMin, nightMin, fuMinute, stored?.arriveMin ?: -1, stored?.departMin ?: -1)
    }

    private suspend fun recordDailyFacts(now: Long, data: UsageDayData) {
        val today = todayFacts(now, data) ?: return
        val updated = synchronized(prefsRmwLock) {
            val days = try { DailyFacts.parse(preferencesManager.dailyFacts) } catch (_: Exception) { emptyList() }
            val storedToday = days.firstOrNull { it.day == today.day }
            val merged = if (storedToday != null) {
                today.copy(
                    arriveMin = if (storedToday.arriveMin >= 0) storedToday.arriveMin else today.arriveMin,
                    departMin = if (storedToday.departMin >= 0) storedToday.departMin else today.departMin
                )
            } else {
                today
            }
            val upd = DailyFacts.upsert(days, merged)
            try { preferencesManager.dailyFacts = DailyFacts.serialize(upd) } catch (_: Exception) { }
            upd
        }
        val history = updated.filter { it.day != today.day }
        val strong = PatternBaseline.assess(history, today).filter { it.strong }
        if (strong.isEmpty()) return
        val fresh = synchronized(prefsRmwLock) {
            val stamp = try { preferencesManager.anomalyAlerts } catch (_: Exception) { "" }
            val stampDay = stamp.substringBefore(":", "")
            val already = if (stampDay == today.day) {
                stamp.substringAfter(":", "").split(",").filter { it.isNotEmpty() }.toSet()
            } else {
                emptySet()
            }
            val freshNow = strong.filter { it.type !in already }
            if (freshNow.isNotEmpty()) {
                try {
                    preferencesManager.anomalyAlerts = today.day + ":" + (already + freshNow.map { it.type }).joinToString(",")
                } catch (_: Exception) { }
            }
            freshNow
        }
        if (fresh.isEmpty()) return
        val status = sendFitted(buildString {
            appendLine("🚨 <b>Di luar pola biasanya</b>")
            for (f in fresh) appendLine("• " + f.text)
        })
        if (status == SendStatus.DROPPED) {
            synchronized(prefsRmwLock) {
                try {
                    val stamp = preferencesManager.anomalyAlerts
                    if (stamp.substringBefore(":", "") == today.day) {
                        val droppedTypes = fresh.map { it.type }.toSet()
                        val keep = stamp.substringAfter(":", "").split(",").filter { it.isNotEmpty() && it !in droppedTypes }
                        preferencesManager.anomalyAlerts = if (keep.isEmpty()) "" else today.day + ":" + keep.joinToString(",")
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private suspend fun sendDailyDigest(manual: Boolean = false): Boolean {
        if (awaitingOwnerLogin()) return false
        try {
            val now = System.currentTimeMillis()
            val eventScan = try { usageSummaryScan(24, startOfTodayMs(now)) } catch (_: Exception) { null to 0L }
            val eventSummary = eventScan.first
            val dayData = usageDayData(now, eventSummary?.let { eventScan.second })
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
            val watchNums = watchNumbers()
            var hits = 0
            for (s in sms) {
                if (watchNums.isNotEmpty() && watchNumberHit(s.address, watchNums) != null) hits++
                else if (watch.isNotEmpty()) {
                    val lower = try { s.body.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                    if (watch.any { lower.contains(it) }) hits++
                }
            }
            try {
                for (c in calls) if (watchNumberHit(c.number, watchNums) != null) hits++
            } catch (_: Exception) {
            }
            val level = batteryLevel()
            val sent = sendToTelegram(buildString {
                appendLine("🌙 <b>Ringkasan harian</b>")
                try {
                    val sorotan = digestHighlights(now, dayData)
                    if (sorotan.isNotEmpty()) {
                        appendLine("⭐ <b>Sorotan</b>")
                        for (line in sorotan) appendLine("• $line")
                    }
                } catch (_: Exception) { }
                appendLine("SMS 24 jam: ${if (sms.size >= 200) "200+" else "${sms.size}"}, Panggilan: ${if (calls.size >= 200) "200+" else "${calls.size}"}${if (sms.size >= 200 || calls.size >= 200) " (dari 200 terakhir)" else ""}")
                if (top.isEmpty()) appendLine("Kontak teratas: -") else appendLine("Teratas: " + top.joinToString(", ") { it.key + " (${it.value}x)" })
                if (watch.isNotEmpty() || hits > 0) appendLine("Kena pantau: $hits")
                try { screenTimeSummaryFrom(dayData)?.let { appendLine(it) } } catch (_: Exception) { }
                try { usageEventSummary(eventSummary).forEach { appendLine(it) } } catch (_: Exception) { }
                try { trailDigestLines().forEach { appendLine(it) } } catch (_: Exception) { }
                A11yMonitor.currentAppLabel().takeIf { it.isNotEmpty() }?.let { appendLine("Sekarang: " + Html.escape(it)) }
                appendLine("Antrean: ${try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }}")
                appendLine("Baterai: ${if (level in 0..100) "$level%" else "?"}")
                if (!manual) appendLine("Kirim /digest kapan saja untuk versi manual.")
            },
                mainMenu()
            )
            try { recordDailyFacts(now, dayData) } catch (_: Exception) { }
            if (manual && sent) {
                try { preferencesManager.digestAt = now } catch (_: Exception) { }
            }
            return sent
        } catch (_: Exception) {
            if (manual) {
                try { sendToTelegram("\u26A0\uFE0F Ringkasan harian gagal dibuat.") } catch (_: Exception) { }
            }
            return false
        }
    }

    private suspend fun maybeSendNightDigest() {
        try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = DigestSchedule.localHour(now, off.toLong())
            val key = todayKey()
            val done = try { preferencesManager.nightDigestDate } catch (_: Exception) { "" }
            if (DigestSchedule.nightDue(h, done, key)) {
                if (awaitingOwnerLogin()) return
                try { preferencesManager.nightDigestDate = key } catch (_: Exception) { }
                sendDailyDigest(manual = false)
                return
            }
            val yesterday = todayKey(now - 86_400_000L)
            if (DigestSchedule.nightCatchUpDue(done, yesterday, key)) {
                if (awaitingOwnerLogin()) return
                try { preferencesManager.nightDigestDate = yesterday } catch (_: Exception) { }
                sendDailyDigest(manual = false)
            }
        } catch (_: Exception) {
        }
    }

    private val knownContactCache = HashMap<String, Boolean>()
    private var knownContactCacheAt = 0L

    private fun isKnownContact(number: String): Boolean {
        val digits = canonicalPhoneDigits(number)
        if (digits.isEmpty()) return true
        val tail = digits.takeLast(7)
        if (tail.length < 7) return true
        synchronized(knownContactCache) {
            if (System.currentTimeMillis() - knownContactCacheAt > 6 * 60 * 60_000L) {
                knownContactCache.clear()
                knownContactCacheAt = System.currentTimeMillis()
            }
            knownContactCache[digits]?.let { return it }
        }
        val result = try {
            val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val c = contentResolver.query(uri, arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER), android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?", arrayOf("%$tail%"), null) ?: return true
            var found = false
            c.use { cur ->
                while (cur.moveToNext()) {
                    val nb = try { cur.getString(0) ?: "" } catch (_: Exception) { "" }
                    if (contactNumberMatches(nb, digits)) {
                        found = true
                        break
                    }
                }
            }
            found
        } catch (e: SecurityException) {
            return true
        } catch (_: Exception) {
            return true
        }
        synchronized(knownContactCache) {
            if (knownContactCache.size >= 512) knownContactCache.clear()
            knownContactCache[digits] = result
        }
        return result
    }

    private fun freqForNumbers(numbers: Collection<String>?): Map<String, Int> {
        val m = HashMap<String, Int>()
        try {
            val keys: HashSet<String>? = numbers?.let { nums ->
                val set = HashSet<String>()
                for (n in nums) {
                    val d = n.filter { it.isDigit() }.takeLast(10)
                    if (d.length >= 7) set.add(d)
                }
                set
            }
            if (keys != null && keys.isEmpty()) return m
            val since = System.currentTimeMillis() - 24 * 60 * 60_000L
            try {
                for (s in smsRepository.getRecentSmsSince(since, 200)) {
                    val d = s.address.filter { it.isDigit() }.takeLast(10)
                    if (keys == null || d in keys) m[d] = (m[d] ?: 0) + 1
                }
            } catch (_: Exception) {
            }
            try {
                for (c in callLogRepository.getCallsSince(since, 200)) {
                    val d = c.number.filter { it.isDigit() }.takeLast(10)
                    if (keys == null || d in keys) m[d] = (m[d] ?: 0) + 1
                }
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        return m
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
                resetLocFailCount()
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

    private fun watchNumberHit(number: String, list: List<String> = watchNumbers()): String? {
        return try {
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

    private suspend fun sendWaspada() {
        try {
            val now = System.currentTimeMillis()
            val since = now - 24 * 60 * 60_000L
            val sms = try { smsRepository.getRecentSmsSince(since, 200) } catch (_: Exception) { emptyList() }
            val calls = try { callLogRepository.getCallsSince(since, 200) } catch (_: Exception) { emptyList() }
            val watch = watchList()
            val watchNums = watchNumbers()
            var hits = 0
            var nights = 0
            val unknown = HashSet<String>()
            val knownCache = HashMap<String, Boolean>()
            fun knownCached(number: String): Boolean {
                return knownCache.getOrPut(number) {
                    try { isKnownContact(number) } catch (_: Exception) { true }
                }
            }
            for (s in sms) {
                val lower = try { s.body.lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
                val numHit = watchNumberHit(s.address, watchNums) != null
                if (numHit) hits++ else if (watch.isNotEmpty() && watch.any { lower.contains(it) }) hits++
                val h = hourOf(s.date)
                if (h >= 23 || h in 0..4) nights++
                if (!knownCached(s.address)) unknown.add(s.address.filter { it.isDigit() }.takeLast(10))
            }
            for (c in calls) {
                if (watchNumberHit(c.number, watchNums) != null) hits++
                val h = hourOf(c.date)
                if (h >= 23 || h in 0..4) nights++
                if (c.name == null && !knownCached(c.number)) unknown.add(c.number.filter { it.isDigit() }.takeLast(10))
            }
            var score = 0
            score += (hits * 25).coerceAtMost(50)
            score += (unknown.size * 10).coerceAtMost(20)
            score += (nights * 2).coerceAtMost(20)
            var locNote = ""
            try {
                val pos = lastSentLatLon()
                val ageH = (now - try { preferencesManager.lastLocAt } catch (_: Exception) { 0L }) / 3_600_000L
                val la = preferencesManager.homeLatE6
                val lo = preferencesManager.homeLonE6
                val rad = preferencesManager.homeRadiusM
                if (pos == null || ageH > 6) {
                    locNote = "\nPosisi tak segar — skor luar rumah diabaikan."
                } else if (la != Long.MIN_VALUE && rad > 0 &&
                    ReportFormat.distanceMeters(la / 1_000_000.0, lo / 1_000_000.0, pos.first, pos.second) > rad
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
                appendLine("Begadang (23–05): $nights$locNote")
                try {
                    val allDays = DailyFacts.parse(preferencesManager.dailyFacts)
                    val todayF = todayFacts(now, usageDayData(now)) ?: allDays.firstOrNull { it.day == todayKey(now) }
                    val hist = allDays.filter { it.day != todayKey(now) }
                    val temuan = if (todayF != null) PatternBaseline.assess(hist, todayF) else emptyList()
                    if (temuan.isNotEmpty()) {
                        appendLine("📊 <b>Dibanding pola biasanya</b> (${hist.size} hari)")
                        for (f in temuan) appendLine("• " + f.text + if (f.strong) " ⚠️" else "")
                    } else if (hist.size < 5) {
                        appendLine("📊 Pola: masih belajar (${hist.size}/5 hari tercatat)")
                    }
                } catch (_: Exception) { }
            },
                mainMenu()
            )
        } catch (_: Exception) {
            try { sendToTelegram("\u26A0\uFE0F Laporan waspada gagal dibuat.") } catch (_: Exception) { }
        }
    }

    private suspend fun checkBegadang() {
        try {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val h = DigestSchedule.localHour(now, off.toLong())
            if (h !in 5..11) return
            val key = todayKey()
            if (key.isEmpty()) return
            if (try { preferencesManager.begadangDate } catch (_:Exception) { "" } == key) return
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
            if (nights < 10) {
                try { preferencesManager.begadangDate = key } catch (_: Exception) { }
                return
            }
            sendToTelegram("🌙 Begadang: $nights aktivitas 23–05 semalam. Cek /minggu untuk pola.")
            try { preferencesManager.begadangDate = key } catch (_: Exception) { }
        } catch (_: Exception) {
        }
    }

    private fun hourOf(ts: Long): Int {
        return try {
            DigestSchedule.localHour(ts, java.util.TimeZone.getDefault().getOffset(ts).toLong())
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
                if (watchNums.isNotEmpty() && watchNumberHit(s.address, watchNums) != null) watchHits++
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
                if (watchNums.isNotEmpty() && watchNumberHit(c.number, watchNums) != null) watchHits++
            }
            val top = freq.entries.sortedByDescending { it.value }.take(5)
            val busy = hours.indices.maxByOrNull { hours[it] } ?: -1
            sendToTelegram(buildString {
                appendLine("📊 <b>Laporan 7 hari</b>")
                appendLine("SMS: ${if (sms.size >= 200) "200+" else "${sms.size}"}, Panggilan: ${if (calls.size >= 200) "200+" else "${calls.size}"}")
                if (top.isEmpty()) appendLine("Kontak teratas: -") else appendLine("Teratas: " + top.joinToString(", ") { it.key + " (${it.value}x)" })
                if (busy >= 0 && hours[busy] > 0) appendLine("Jam tersibuk: ${busy}:00 (${hours[busy]} aktivitas)")
                appendLine("Aktif 00–23: " + Sparkline.render(hours))
                appendLine("Begadang (23–05): $nights")
                if (watch.isNotEmpty() || watchNums.isNotEmpty()) appendLine("Kena pantau: $watchHits")
                if (capped) appendLine("(dari 200 data terakhir)")
            },
                mainMenu()
            )
        } catch (_: Exception) {
            try { sendToTelegram("\u26A0\uFE0F Laporan mingguan gagal dibuat.") } catch (_: Exception) { }
        }
    }

    private suspend fun checkCurfew() {
        try {
            val now = System.currentTimeMillis()
            val cal = java.util.Calendar.getInstance()
            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val day = cal.get(java.util.Calendar.DAY_OF_WEEK)
            val curfew = try { preferencesManager.curfewTime } catch (_: Exception) { "" }
            val inCurfew = CurfewLogic.inWindow(curfew, h, cal.get(java.util.Calendar.MINUTE))
            val homeLat = try { preferencesManager.homeLatE6 } catch (_: Exception) { Long.MIN_VALUE }
            val homeLon = try { preferencesManager.homeLonE6 } catch (_: Exception) { Long.MIN_VALUE }
            val homeRad = try { preferencesManager.homeRadiusM } catch (_: Exception) { 0 }
            val homeFence = GeofenceLogic.configured(homeLat, homeLon, homeRad)
            val schoolLat = try { preferencesManager.schoolLatE6 } catch (_: Exception) { Long.MIN_VALUE }
            val schoolLon = try { preferencesManager.schoolLonE6 } catch (_: Exception) { Long.MIN_VALUE }
            val schoolRad = try { preferencesManager.schoolRadiusM } catch (_: Exception) { 0 }
            val schoolFence = GeofenceLogic.configured(schoolLat, schoolLon, schoolRad)
            val inSchool = schoolFence && CurfewLogic.schoolHours(day, h)
            if (!(inCurfew && homeFence) && !inSchool) return
            val key = todayKey(if (h < 5) now - 24 * 60 * 60_000L else now)
            if (key.isEmpty()) return
            val curfewDone = try { preferencesManager.curfewAlertDate } catch (_: Exception) { "" }
            val schoolDone = try { preferencesManager.schoolAlertDate } catch (_: Exception) { "" }
            val needCurfew = inCurfew && homeFence && curfewDone != key
            val needSchool = inSchool && schoolDone != key
            if (!needCurfew && !needSchool) return
            var pos = lastSentLatLon()
            val locAge = now - try { preferencesManager.lastLocAt } catch (_: Exception) { 0L }
            if (pos == null || locAge > 30 * 60_000L) {
                val nowEl = android.os.SystemClock.elapsedRealtime()
                if (curfewFetchFailAt != 0L && nowEl - curfewFetchFailAt < 15 * 60_000L) return
                val fix = try { fetchLocation() } catch (_: Exception) { null }
                if (fix == null) {
                    curfewFetchFailAt = android.os.SystemClock.elapsedRealtime()
                    bumpLocFailCount()
                    return
                }
                curfewFetchFailAt = 0L
                resetLocFailCount()
                try { rememberSentLatLon(fix.latitude, fix.longitude, fix.accuracy) } catch (_: Exception) { }
                pos = Pair(fix.latitude, fix.longitude)
            }
            if (needCurfew) {
                val dist = ReportFormat.distanceMeters(homeLat / 1_000_000.0, homeLon / 1_000_000.0, pos.first, pos.second)
                if (dist >= 0f && dist > homeRad) {
                    try { preferencesManager.curfewAlertDate = key } catch (_: Exception) { }
                    sendToTelegram("🌙 Jam malam $curfew: perangkat masih di luar rumah (±${dist.toInt()} m).")
                    return
                }
            }
            if (needSchool) {
                val dist = ReportFormat.distanceMeters(schoolLat / 1_000_000.0, schoolLon / 1_000_000.0, pos.first, pos.second)
                if (dist >= 0f && dist > schoolRad) {
                    try { preferencesManager.schoolAlertDate = key } catch (_: Exception) { }
                    sendToTelegram("🏫 Jam sekolah: perangkat di luar area sekolah (±${dist.toInt()} m).")
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun aliveReportText(): String {
        val now = System.currentTimeMillis()
        val started = serviceStartAt
        val lastAlive = try { preferencesManager.lastAliveAt } catch (_: Exception) { 0L }
        val lastAliveElapsed = try { preferencesManager.lastAliveElapsed } catch (_: Exception) { 0L }
        val kills = try { preferencesManager.livenessKills } catch (_: Exception) { 0 }
        val unreported = try { preferencesManager.livenessKillsUnreported } catch (_: Exception) { 0 }
        val beat = try { preferencesManager.heartbeatHours } catch (_: Exception) { 0 }
        val level = batteryLevel()
        val beatAge = if (lastAliveElapsed > 0L) SystemClock.elapsedRealtime() - lastAliveElapsed
        else if (lastAlive > 0L) now - lastAlive else -1L
        return buildString {
            appendLine("💚 <b>Alive</b>")
            appendLine("Monitoring: ${if (preferencesManager.monitoringPaused) "JEDA" else "JALAN"}")
            appendLine("Lama nyala: ${if (started > 0) ReportFormat.durationText(now - started) else "?"}")
            if (started > 0) appendLine("Sejak: " + formatDate(started))
            appendLine("Denyut terakhir: " + if (beatAge >= 0L) {
                val at = if (lastAlive > 0L) " pukul " + Liveness.clockText(lastAlive) else ""
                ReportFormat.durationText(beatAge) + " lalu" + at
            } else "?")
            appendLine("Dipulihkan: ${kills}x sejak dihentikan sistem" +
                if (unreported > 0) " (belum dilaporkan: ${unreported}x)" else "")
            appendLine("Watchdog: " + Liveness.intervalText(this@MonitoringService))
            val sendAge = silentLinkAgeMs()
            val heals = try { preferencesManager.silentHeals } catch (_: Exception) { 0 }
            val sendText = when {
                sendAge < 0L -> "?"
                sendAge < 30 * 60_000L -> "OK (" + ReportFormat.durationText(sendAge) + " lalu)"
                else -> "BISU " + ReportFormat.durationText(sendAge) + " lalu"
            }
            appendLine("Jalur kirim: $sendText" + (if (heals > 0) " · dipulihkan sendiri ${heals}x" else ""))
            appendLine("Heartbeat: ${if (beat > 0) "tiap $beat jam" else "mati (/heartbeat)"}")
            appendLine("Tipe FGS aktif: " + FgsTypes.describe(appliedFgsTypes))
            if (level in 0..100) appendLine("Baterai: $level%" +
                (if (lowPowerActive()) " (hemat daya: foto/patroli diperlambat)" else ""))
        }
    }

    private suspend fun maybeSendHeartbeat() {
        val hours = try { preferencesManager.heartbeatHours } catch (_: Exception) { 0 }
        if (hours <= 0) return
        val now = System.currentTimeMillis()
        val last = try { preferencesManager.heartbeatAt } catch (_: Exception) { 0L }
        if (last > 0L && now > last && now - last < hours * 3_600_000L) return
        if (awaitingOwnerLogin()) return
        try { preferencesManager.heartbeatAt = now } catch (_: Exception) { }
        sendToTelegram(aliveReportText())
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
        photoWatchdog?.cancel()
        videoWatchdog?.cancel()
        try { releaseVideoWakeLock(videoWakeLock) } catch (_: Exception) { }
        shotWatchdog?.cancel()
        cameraAttempt.set(0)
        videoAttempt.set(0)
        shotAttempt.set(0)
        shotBusy.set(false)
        cameraBusy.set(false)
        smsBusy.set(false)
        ringBusy.set(false)
        recordBusy.set(false)
        recorderActive.set(false)
        audioFlushBusy.set(false)
        photoFlushBusy.set(false)
        videoFlushBusy.set(false)
        try {
            cameraService.forceReset()
        } catch (_: Exception) {
        }
    }

    private fun restoreRingVolumeNow() {
        restoreRingVolumeShared()
    }

    private fun stopMonitoring() {
        teardownJobs()
        cachedSetupTap = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun selfTestPending(): Boolean {
        return try { preferencesManager.liveTestPending } catch (_: Exception) { false }
    }

    private suspend fun handleAdminAlert() {
        try {
            val loc = try { fetchLocation(allowStale = true) } catch (_: Exception) { null }
            val level = batteryLevel()
            val battery = if (level in 0..100) "\nBaterai: $level%" else ""
            if (loc != null) {
                sendToTelegram("🚨 <b>Percobaan mematikan Device Admin</b>\n📍 https://maps.google.com/?q=${loc.latitude},${loc.longitude}\nAkurasi: ±${loc.accuracy.toInt()} m$battery" + fixAgeNote(loc))
            } else {
                sendToTelegram("🚨 <b>Percobaan mematikan Device Admin</b>\nLokasi tak didapat: " + locationFailureHint() + battery)
            }
            try { captureAndSendPhoto(reportResult = false) } catch (_: Exception) { }
        } catch (_: Exception) {
        }
    }

    private var lastUnlockAlertAt = 0L
    private var unlockAlertWindowStart = 0L
    private var unlockAlertCount = 0

    private fun currentSimIdentity(): String {
        return try {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java) ?: return ""
            if (tm.simState != android.telephony.TelephonyManager.SIM_STATE_READY) return ""
            val op = tm.simOperator ?: ""
            val country = tm.simCountryIso ?: ""
            if (op.isEmpty() && country.isEmpty()) "" else "$op-$country"
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun checkSimIdentity() {
        try {
            if (!preferencesManager.isConfigured() || preferencesManager.userDisabledMonitoring) return
            val current = currentSimIdentity()
            if (current.isEmpty()) return
            val stored = try { preferencesManager.simIdentity } catch (_: Exception) { "" }
            if (stored.isEmpty()) {
                try { preferencesManager.simIdentity = current } catch (_: Exception) { }
                return
            }
            if (stored == current) return
            try { preferencesManager.simIdentity = current } catch (_: Exception) { }
            sendToTelegram("📵 <b>Kartu SIM berubah</b>\nSebelum: $stored\nSekarang: $current\nCek apakah SIM dicabut atau diganti.")
        } catch (_: Exception) {
        }
    }

    private suspend fun handleUnlockFailed(attempts: Int) {
        try {
            if (!preferencesManager.isConfigured() || preferencesManager.userDisabledMonitoring) return
            val streak = attempts.coerceAtLeast(1)
            if (streak != 1 && streak % 3 != 0) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastUnlockAlertAt > 0L && now - lastUnlockAlertAt < 2 * 60_000L) return
            if (unlockAlertWindowStart == 0L || now - unlockAlertWindowStart > 3_600_000L) {
                unlockAlertWindowStart = now
                unlockAlertCount = 0
            }
            if (unlockAlertCount >= 3) return
            unlockAlertCount++
            lastUnlockAlertAt = now
            val loc = try { fetchLocation(allowStale = true) } catch (_: Exception) { null }
            val level = batteryLevel()
            val battery = if (level in 0..100) "\nBaterai: $level%" else ""
            val where = if (loc != null) {
                "📍 https://maps.google.com/?q=${loc.latitude},${loc.longitude}\nAkurasi: ±${loc.accuracy.toInt()} m" + fixAgeNote(loc)
            } else {
                "Lokasi tak didapat: " + locationFailureHint()
            }
            sendToTelegram("🔐 <b>Buka kunci gagal</b> (#$streak)\n$where$battery")
            try { captureAndSendPhoto(reportResult = false) } catch (_: Exception) { }
        } catch (_: Exception) {
        }
    }

    private fun noteSendOk() {
        try {
            preferencesManager.setLastSendOkMarker(android.os.SystemClock.elapsedRealtime())
        } catch (_: Exception) {
        }
        try {
            if (preferencesManager.silentHealStage != 0) preferencesManager.silentHealStage = 0
        } catch (_: Exception) {
        }
    }

    private fun silentLinkAgeMs(): Long {
        return try {
            val last = preferencesManager.lastSendOkElapsed
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (last <= 0L || last > nowElapsed) -1L else nowElapsed - last
        } catch (_: Exception) {
            -1L
        }
    }

    private fun seedSendOkMarker() {
        try {
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val last = preferencesManager.lastSendOkElapsed
            if (last <= 0L || last > nowElapsed) {
                preferencesManager.setLastSendOkMarker(nowElapsed)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun clearWebhook() {
        try {
            val botToken = try { preferencesManager.botToken } catch (_: Exception) { "" }
            if (botToken.isEmpty()) return
            val url = "https://api.telegram.org/bot$botToken/deleteWebhook"
            TelegramClient.api.deleteWebhook(url, emptyMap())
        } catch (_: Exception) {
        }
    }

    private suspend fun checkSilentLink() {
        if (cachedMonitoringPaused) return
        val age = silentLinkAgeMs()
        if (age < SILENT_LINK_MS) return
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (!hasNetwork()) return
        val hasWork = try { messageQueue.hasMessages() } catch (_: Exception) { false }
        if (!hasWork) return
        var stage = try { preferencesManager.silentHealStage } catch (_: Exception) { 0 }
        if (stage >= SILENT_MAX_STAGE) {
            val lastHealMax = try { preferencesManager.silentHealAt } catch (_: Exception) { 0L }
            val nowMax = System.currentTimeMillis()
            if (lastHealMax > 0L && nowMax > lastHealMax && nowMax - lastHealMax >= 24 * 3_600_000L) {
                try { preferencesManager.silentHealStage = 0 } catch (_: Exception) { }
                stage = 0
            } else {
                return
            }
        }
        val now = System.currentTimeMillis()
        val lastHeal = try { preferencesManager.silentHealAt } catch (_: Exception) { 0L }
        if (lastHeal > 0L && now > lastHeal && now - lastHeal < SILENT_HEAL_SPACING_MS) return
        try { preferencesManager.silentHealAt = now } catch (_: Exception) { }
        val healed = try {
            healSilentLink(stage + 1)
            true
        } catch (_: Exception) {
            false
        }
        if (!healed) return
        try { preferencesManager.silentHealStage = stage + 1 } catch (_: Exception) { }
        try {
            val heals = preferencesManager.silentHeals
            preferencesManager.silentHeals = heals + 1
        } catch (_: Exception) {
        }
        val minutes = (age / 60_000L).coerceAtLeast(1L)
        replyLater("🩺 <b>Jalur kirim tampak bisu</b>\nSudah ±$minutes menit tanpa kiriman berhasil padahal ada antrean. Pemulihan tahap ${stage + 1}/$SILENT_MAX_STAGE sudah saya jalankan (${silentHealLabel(stage + 1)}). Kalau pesan ini sampai, jalur sudah normal lagi.")
    }

    private fun silentHealLabel(stage: Int): String {
        return when (stage) {
            1 -> "putar ulang antrean + segarkan kredensial"
            2 -> "hapus webhook + daftar ulang menu command"
            else -> "hidupkan ulang semua loop"
        }
    }

    private suspend fun healSilentLink(stage: Int) {
        when (stage) {
            1 -> {
                try { MessageScheduler.cancelMessageSend(this) } catch (_: Exception) { }
                refreshCreds()
                try { MessageScheduler.scheduleMessageSend(this) } catch (_: Exception) { }
            }
            2 -> {
                clearWebhook()
                registerBotCommands(true)
            }
            else -> {
                restartAllLoops()
            }
        }
    }

    private suspend fun runFullRecovery() {
        val hadQueue = try { messageQueue.getQueueSize() } catch (_: Exception) { "?" }
        refreshCreds()
        clearWebhook()
        registerBotCommands(true)
        try { MessageScheduler.cancelMessageSend(this) } catch (_: Exception) { }
        try { MessageScheduler.scheduleBootRestart(this) } catch (_: Exception) { }
        try { MessageScheduler.scheduleWatchdog(this) } catch (_: Exception) { }
        try { Liveness.schedule(this) } catch (_: Exception) { }
        try { NotificationForwarderService.requestRebindFrom(this) } catch (_: Exception) { }
        restartAllLoops(true)
        try {
            preferencesManager.silentHealStage = 0
            preferencesManager.silentHealAt = 0L
        } catch (_: Exception) { }
        replyLater("🩺 <b>Sembuh total dijalankan</b>\n• Kredensial disegarkan, webhook dibersihkan, menu command didaftar ulang\n• Semua loop dihidupkan ulang, jam bangun + watchdog dipasang ulang\n• Akses notifikasi diminta ulang bila perlu\nAntrean sebelum pemulihan: $hadQueue pesan.")
        try {
            MessageScheduler.scheduleMessageSend(this)
        } catch (_: Exception) {
        }
    }

    private fun runLivenessSelfTest() {
        try {
            preferencesManager.liveTestPending = true
            preferencesManager.liveTestStartedElapsed = android.os.SystemClock.elapsedRealtime()
            preferencesManager.liveTestByAlarm = false
        } catch (_: Exception) {
        }
        try {
            Liveness.scheduleSoon(this, SELF_TEST_ALARM_DELAY_MS)
        } catch (_: Exception) {
        }
        serviceScope.launch {
            try {
                sendToTelegram("🧪 <b>Uji anti-mati dimulai</b>\nServis saya hentikan paksa sekarang, lalu jam bangun (watchdog) harus menghidupkannya lagi dalam ±1 menit. Hasilnya saya laporkan ke sini.")
            } catch (_: Exception) {
            }
            try {
                delay(1_500L)
            } catch (_: Exception) {
            }
            try {
                Liveness.scheduleSoon(this@MonitoringService, SELF_TEST_ALARM_DELAY_MS)
            } catch (_: Exception) {
            }
            try {
                stopMonitoring()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun reportLivenessSelfTest() {
        val pending = try { preferencesManager.liveTestPending } catch (_: Exception) { false }
        if (!pending) return
        val startedElapsed = try { preferencesManager.liveTestStartedElapsed } catch (_: Exception) { 0L }
        val byAlarm = try { preferencesManager.liveTestByAlarm } catch (_: Exception) { false }
        try { preferencesManager.liveTestPending = false } catch (_: Exception) { }
        try { preferencesManager.liveTestStartedElapsed = 0L } catch (_: Exception) { }
        try { preferencesManager.liveTestByAlarm = false } catch (_: Exception) { }
        if (startedElapsed <= 0L) return
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        if (startedElapsed > nowElapsed) {
            try {
                sendToTelegram("🧪 Uji anti-mati terputus oleh reboot HP — ulangi bila ingin hasil yang jelas.")
            } catch (_: Exception) {
            }
            return
        }
        val secs = (nowElapsed - startedElapsed) / 1000L
        try { preferencesManager.liveTestLastSec = secs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } catch (_: Exception) { }
        val text = if (secs > 10 * 60L) {
            "❌ <b>Uji anti-mati gagal</b>\nServis baru hidup lagi setelah $secs detik (lebih dari 10 menit). Jam bangun tampaknya tidak berjalan — buka Setup &gt; Anti-kill dan kerjakan langkahnya, lalu uji lagi."
        } else if (byAlarm) {
            "🧪 <b>Hasil uji anti-mati</b>\nServis hidup lagi dalam <b>$secs detik</b>.\n✅ Dipulihkan jam bangun (watchdog) — anti-mati di HP ini <b>bekerja</b>."
        } else {
            "🧪 <b>Hasil uji anti-mati</b>\nServis hidup lagi dalam <b>$secs detik</b>.\n⚠️ Jam bangun belum terbukti — yang menghidupkan jalur lain (worker/boot). Kerjakan langkah Anti-kill di Setup lalu uji ulang."
        }
        try {
            sendToTelegram(text)
        } catch (_: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (shouldAutoResume()) {
                try {
                    val restart = Intent(this, MonitoringService::class.java).apply {
                        action = ACTION_START_MONITORING
                    }
                    ServiceStart.startForeground(this, restart)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (shouldAutoResume()) {
                MessageScheduler.scheduleBootRestart(this)
                MessageScheduler.scheduleWatchdog(this)
                Liveness.schedule(this)
            }
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleFgsTimeout()
    }

    override fun onTimeout(startId: Int) {
        handleFgsTimeout()
    }

    private fun handleFgsTimeout() {
        try {
            MessageScheduler.scheduleBootRestart(this)
        } catch (_: Exception) {
        }
        try {
            MessageScheduler.scheduleWatchdog(this)
        } catch (_: Exception) {
        }
        try {
            Liveness.schedule(this)
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

    override fun onTrimMemory(level: Int) {
        try {
            val memoryPressure = level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            if (memoryPressure) {
                try { messageQueue.flushSync() } catch (_: Exception) { }
                try { Liveness.schedule(this) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        try { super.onTrimMemory(level) } catch (_: Exception) { }
    }

    override fun onLowMemory() {
        try { messageQueue.flushSync() } catch (_: Exception) { }
        try { Liveness.schedule(this) } catch (_: Exception) { }
        try { super.onLowMemory() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        try {
            if (shouldAutoResume() || needsCredsRetry()) {
                if (selfTestPending()) {
                    try {
                        Liveness.scheduleSoon(this, SELF_TEST_ALARM_DELAY_MS)
                    } catch (_: Exception) {
                    }
                    try {
                        MessageScheduler.scheduleWatchdog(this)
                    } catch (_: Exception) {
                    }
                } else {
                    try {
                        MessageScheduler.scheduleBootRestart(this)
                    } catch (_: Exception) {
                    }
                    try {
                        MessageScheduler.scheduleWatchdog(this)
                    } catch (_: Exception) {
                    }
                    try {
                        Liveness.schedule(this)
                    } catch (_: Exception) {
                    }
                }
            } else {
                try {
                    Liveness.noteStopped(preferencesManager)
                } catch (_: Exception) {
                }
                try {
                    Liveness.cancel(this)
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
            releaseShotSession()
        } catch (_: Exception) {
        }
        try {
            teardownJobs()
        } catch (_: Exception) {
        }
        cachedSetupTap = null
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA MONITORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════

    private fun captureAndSendPhoto(reportResult: Boolean = false) {
        if (!photoPreconditions(reportResult)) return
        if (recordingKind == "video") {
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("⚠️ Sedang merekam video. Coba /photo lagi setelah selesai.")
                }
            }
            return
        }
        if (!cameraBusy.compareAndSet(false, true)) {
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("⚠️ Kamera sedang dipakai, coba /photo lagi sebentar.")
                }
            }
            return
        }
        photoWatchdog?.cancel()
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
                    sendToTelegram("⚠️ Pemotretan tak menjawab (timeout). Coba /photo lagi.")
                }
            }
        }
        photoWatchdog = wd
        try {
            ensureForegroundTypes()
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "📸 Starting camera capture...")
            cameraService.capturePhoto(
                lensFacing = selectedLensFacing(),
                onPhotoTaken = { photoFile ->
                    handleCapturedPhoto(photoFile, attempt, wd, reportResult)
                },
                onError = { exception ->
                    handleCaptureError(exception, attempt, wd, reportResult)
                }
            )
        } catch (e: Exception) {
            finishCaptureAttempt(wd)
            android.util.Log.e("MonitoringService", "✗ Error in captureAndSendPhoto: ${redactToken(e.message)}")
            val policyBlocked = ReportFormat.isCameraPolicyError(e.message)
            if (policyBlocked) autoPausePhotosOnPolicyBlock()
            reportPhotoResult(reportResult, "⚠️ Gagal memotret: " + sanitizedCameraError(e.message) + ".", sanitizedCameraError(e.message))
        }
    }

    private fun photoPreconditions(reportResult: Boolean): Boolean {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            reportPhotoResult(reportResult, "Token ditolak — foto ditahan sampai token diperbaiki di Setup.")
            return false
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            reportPhotoResult(reportResult, "Dibatasi Telegram (rate limit) — foto ditahan lalu dicoba lagi.")
            return false
        }
        val photosBefore = pendingPhotoCount()
        if (photosBefore >= MAX_PHOTO_KEPT) {
            val pruned = try {
                prunePhotoCache(MAX_PHOTO_KEPT - 1)
            } catch (_: Exception) {
                -1
            }
            val photosAfter = if (pruned >= 0) pruned else pendingPhotoCount()
            if (photosAfter >= MAX_PHOTO_KEPT) {
                reportPhotoResult(reportResult, if (photosAfter < photosBefore) "⚠️ Antrean foto penuh (offline). Yang paling lama yang belum terkirim dibuang; foto baru dilewati." else "⚠️ Antrean foto penuh (offline). Foto baru dilewati sampai antrean terkirim.")
                return false
            }
        }
        if (!hasCameraPermission()) {
            reportPhotoResult(reportResult, "⚠️ Gagal memotret: izin Kamera belum diberikan. Buka Setup dan berikan izin Kamera.", "camera permission missing")
            return false
        }
        if (isCameraDisabledByPolicy()) {
            autoPausePhotosOnPolicyBlock()
            val blockers = cameraBlockingAdmins()
            reportPhotoResult(reportResult, "⚠️ Gagal memotret: " + sanitizedCameraError("CAMERA_DISABLED") + " " + policyFailureHint(blockers), "camera disabled by device policy (CAMERA_DISABLED)")
            return false
        }
        return true
    }

    private fun handleCapturedPhoto(photoFile: File, attempt: Int, wd: Job, reportResult: Boolean) {
        if (cameraAttempt.get() != attempt) {
            try {
                photoFile.delete()
            } catch (_: Exception) {
            }
            return
        }
        if (!photoFile.exists() || photoFile.length() == 0L) {
            finishCaptureAttempt(wd)
            deleteQuietly(photoFile)
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("⚠️ Foto yang dihasilkan kosong. Coba /photo lagi.")
                }
            }
            return
        }
        val (photoLumaNow, photoHashNow) = PhotoFingerprint.analyze(photoFile)
        if (photoLumaNow < 12) {
            finishCaptureAttempt(wd)
            deleteQuietly(photoFile)
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("🌑 Foto terlalu gelap, dilewati. Nyalakan lampu atau coba /photo lagi.")
                }
            } else {
                val nowElapsed = android.os.SystemClock.elapsedRealtime()
                if (nowElapsed - lastDarkPhotoAlertAt >= 60 * 60_000L) {
                    lastDarkPhotoAlertAt = nowElapsed
                    serviceScope.launch {
                        sendToTelegram("🌑 Foto otomatis terlalu gelap beberapa kali (kamera mungkin tertutup).")
                    }
                }
            }
            return
        }
        val prevPhotoHash = try { preferencesManager.lastPhotoHash } catch (_: Exception) { 0L }
        if (photoHashNow != 0L && prevPhotoHash != 0L && PhotoFingerprint.hammingDistance(photoHashNow, prevPhotoHash) <= 6) {
            finishCaptureAttempt(wd)
            deleteQuietly(photoFile)
            if (reportResult) {
                serviceScope.launch {
                    sendToTelegram("♻️ Foto sama dengan yang terakhir, dilewati.")
                }
            }
            return
        }
        finishCaptureAttempt(wd)
        markMediaActive(photoFile)
        sendCapturedPhoto(photoFile, reportResult, photoHashNow)
    }

    private fun handleCaptureError(exception: Exception, attempt: Int, wd: Job, reportResult: Boolean) {
        if (cameraAttempt.get() != attempt) return
        finishCaptureAttempt(wd)
        android.util.Log.e("MonitoringService", "✗ Camera capture failed: ${exception.message}")
        val policyBlocked = ReportFormat.isCameraPolicyError(exception.message) || isCameraDisabledByPolicy()
        if (policyBlocked) autoPausePhotosOnPolicyBlock()
        serviceScope.launch {
            if (reportResult) {
                val hint = if (policyBlocked) policyFailureHint(cameraBlockingAdmins()) else cameraFailureHint(exception.message)
                sendToTelegram("⚠️ Gagal memotret: " + Html.escape(sanitizedCameraError(exception.message)) + " " + hint)
            } else {
                notifyCameraFailure(sanitizedCameraError(exception.message))
            }
        }
    }

    private fun sendCapturedPhoto(photoFile: File, reportResult: Boolean, photoHashNow: Long) {
        serviceScope.launch {
            try {
                if (!reportResult && dataSaverHoldNow(1)) {
                    try { preferencesManager.setLastPhotoHashSync(photoHashNow) } catch (_: Exception) { }
                    android.util.Log.i("MonitoringService", "Hemat kuota: foto otomatis ditahan sampai tersambung Wi-Fi")
                    return@launch
                }
                when (sendPhotoFile(photoFile)) {
                    MediaSendOutcome.SENT -> {
                        try { preferencesManager.setLastPhotoHashSync(photoHashNow) } catch (_: Exception) { }
                        try {
                            val used = cameraService.lastLensFacing
                            val asked = selectedLensFacing()
                            if (used != -1 && used != asked) {
                                val name = when (used) {
                                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "belakang"
                                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "depan"
                                    else -> "lain"
                                }
                                sendToTelegram("📷 Lensa yang diminta tak ada, dipakai kamera $name.")
                            }
                        } catch (_: Exception) {
                        }
                        flushPendingPhotos()
                    }
                    MediaSendOutcome.DROPPED -> {
                        prunePhotoCache()
                        if (reportResult) {
                            sendToTelegram("⚠️ Foto ditolak Telegram (400) — berkas kosong/tak didukung, dibuang.")
                        }
                    }
                    MediaSendOutcome.KEPT -> {
                        prunePhotoCache()
                        if (reportResult) {
                            sendToTelegram("⚠️ Foto berhasil diambil tapi gagal diunggah. Berkas disimpan untuk dicoba lagi.")
                        }
                    }
                }
            } finally {
                clearMediaActive(photoFile)
            }
        }
    }

    private fun finishCaptureAttempt(wd: Job) {
        cameraAttempt.incrementAndGet()
        cameraBusy.set(false)
        try {
            wd.cancel()
        } catch (_: Exception) {
        }
    }

    private fun reportPhotoResult(reportResult: Boolean, message: String, notifyMessage: String? = null) {
        serviceScope.launch {
            if (reportResult) {
                sendToTelegram(message)
            } else if (notifyMessage != null) {
                notifyCameraFailure(notifyMessage)
            }
        }
    }

    private fun hasScreenshotConsent(): Boolean {
        return try { com.redeye.parentalmonitor.utils.ScreenCapture.granted() } catch (_: Exception) { false }
    }

    private fun screenshotTypeActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return (appliedFgsTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0
    }

    private fun shotViaAccessibility(): Boolean {
        return try { com.redeye.parentalmonitor.service.ShotAccessibilityService.ready() } catch (_: Exception) { false }
    }

    private fun screenshotFailureHint(): String {
        if (shotViaAccessibility()) {
            return "Jalur Aksesibilitas aktif tapi pengambilan gagal \u2014 coba /screenshot lagi; bila terus gagal, buka Setup lalu matikan-nyalakan lagi layanan Aksesibilitasnya."
        }
        val types = FgsTypes.describe(appliedFgsTypes)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !screenshotTypeActive()) {
            "Tipe latar 'screen' belum aktif (kini: $types). Buka aplikasi sekali agar layanan memulihkan tipe latar di depan, lalu kirim /screenshot lagi. Bila tetap gagal, berikan izinnya sekali lagi: Setup → Capture Screen."
        } else {
            "Tipe latar 'screen' aktif ($types), jadi izinnya kemungkinan sudah tak berlaku (Android mengizinkan satu sesi per izin). Berikan sekali lagi: buka Setup → Capture Screen."
        }
    }

    private fun shotGrantMarkup(): com.redeye.parentalmonitor.network.InlineKeyboardMarkup {
        return com.redeye.parentalmonitor.network.InlineKeyboardMarkup(
            listOf(
                listOf(com.redeye.parentalmonitor.network.InlineButton("\uD83D\uDDA5\uFE0F Beri izin sekali", "x:izinshot"))
            )
        )
    }

    private fun requestShotGrant() {
        if (!com.redeye.parentalmonitor.utils.AutoStart.canDrawOverlays(this)) {
            serviceScope.launch {
                sendToTelegram("⚠️ Butuh izin <b>Tampil di atas aplikasi lain</b> agar dialog izin bisa dibuka dari Telegram. Buka Setup \u2192 aktifkan izin itu, lalu kirim /screenshot lagi.")
            }
            return
        }
        try {
            startActivity(
                android.content.Intent(this, com.redeye.parentalmonitor.ui.ShotConsentActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
            )
        } catch (_: Exception) {
            serviceScope.launch {
                sendToTelegram("⚠️ Gagal membuka dialog izin. Buka aplikasi kalkulator \u2192 1234= \u2192 Setup \u2192 Capture Screen.")
            }
        }
    }

    private fun captureAndSendScreenshot() {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            serviceScope.launch {
                sendToTelegram("Token ditolak — tangkap layar ditahan sampai token diperbaiki di Setup.")
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            serviceScope.launch {
                sendToTelegram("Dibatasi Telegram (rate limit) — tangkap layar ditahan lalu dicoba lagi.")
            }
            return
        }
        if (!hasScreenshotConsent() && !shotViaAccessibility()) {
            val ended = try { com.redeye.parentalmonitor.utils.ScreenCapture.sessionEnded() } catch (_: Exception) { false }
            serviceScope.launch {
                sendToTelegram(
                    (if (ended) "⚠️ Sesi izin tangkap layar sudah berakhir (Android hanya mengizinkan satu sesi per izin, dan sesinya ditutup saat layanan monitoring berhenti)."
                    else "⚠️ Izin tangkap layar belum diberikan.") +
                        " Tekan <b>Beri izin sekali</b> di bawah \u2014 dialog izin terbuka lalu layar langsung diambil." +
                        "\n\nSupaya tak perlu izin lagi walau HP restart: Setup \u2192 <b>Aktifkan Aksesibilitas</b>.",
                    shotGrantMarkup()
                )
            }
            return
        }
        if (!shotBusy.compareAndSet(false, true)) {
            serviceScope.launch {
                sendToTelegram("⚠️ Tangkap layar masih berjalan, coba /screenshot lagi sebentar.")
            }
            return
        }
        val shotSeq = shotAttempt.incrementAndGet()
        shotWatchdog?.cancel()
        val wd = serviceScope.launch {
            delay(45_000)
            if (shotAttempt.get() == shotSeq && shotBusy.compareAndSet(true, false)) {
                shotAttempt.incrementAndGet()
                android.util.Log.w("MonitoringService", "Screenshot watchdog: capture did not finish, flag reset")
                sendToTelegram("⚠️ Tangkap layar tak menjawab (timeout). Coba /screenshot lagi.")
            }
        }
        shotWatchdog = wd
        serviceScope.launch {
            try {
                ensureForegroundTypes()
                val shotFile = try {
                    takeScreenshotFrame()
                } catch (e: SecurityException) {
                    if (shotAttempt.get() == shotSeq) {
                        try { releaseShotSurface() } catch (_: Exception) { }
                    }
                    try { wd.cancel() } catch (_: Exception) { }
                    clearShotBusy(shotSeq)
                    android.util.Log.w("MonitoringService", "Tangkap layar ditolak sistem: " + redactToken(e.message))
                    sendToTelegram("⚠️ Tangkap layar ditolak sistem. " + screenshotFailureHint())
                    return@launch
                }
                if (shotAttempt.get() != shotSeq) {
                    try { shotFile?.delete() } catch (_: Exception) { }
                    clearShotBusy(shotSeq)
                    return@launch
                }
                try { wd.cancel() } catch (_: Exception) { }
                val wdUp = serviceScope.launch {
                    delay(660_000)
                    if (shotAttempt.get() == shotSeq && shotBusy.compareAndSet(true, false)) {
                        shotAttempt.incrementAndGet()
                        android.util.Log.w("MonitoringService", "Screenshot watchdog: upload did not finish, flag reset")
                        sendToTelegram("⚠️ Unggah tangkap layar tak menjawab (timeout). Coba /screenshot lagi.")
                    }
                }
                shotWatchdog = wdUp
                if (shotFile == null) {
                    clearShotBusy(shotSeq)
                    sendToTelegram(
                        "⚠️ Tangkap layar gagal. " + screenshotFailureHint(),
                        if (!hasScreenshotConsent()) shotGrantMarkup() else null
                    )
                    return@launch
                }
                markMediaActive(shotFile)
                try {
                    when (sendPhotoFile(shotFile)) {
                        MediaSendOutcome.SENT -> flushPendingPhotos()
                        MediaSendOutcome.DROPPED -> {
                            prunePhotoCache()
                            sendToTelegram("⚠️ Tangkap layar ditolak Telegram (400) — berkas kosong/tak didukung, dibuang.")
                        }
                        MediaSendOutcome.KEPT -> {
                            prunePhotoCache()
                            sendToTelegram("⚠️ Tangkap layar berhasil diambil tapi gagal diunggah. Berkas disimpan untuk dicoba lagi.")
                        }
                    }
                } finally {
                    clearMediaActive(shotFile)
                }
                try { wdUp.cancel() } catch (_: Exception) { }
                clearShotBusy(shotSeq)
            } catch (e: kotlinx.coroutines.CancellationException) {
                clearShotBusy(shotSeq)
                try { wd.cancel() } catch (_: Exception) { }
                throw e
            } catch (e: Exception) {
                clearShotBusy(shotSeq)
                try { wd.cancel() } catch (_: Exception) { }
                android.util.Log.e("MonitoringService", "✗ Error in captureAndSendScreenshot: ${redactToken(e.message)}")
                serviceScope.launch {
                    sendToTelegram(
                        "⚠️ Tangkap layar gagal. Bila sistem mencabut izinnya, berikan lagi di Setup (Capture Screen).",
                        if (!hasScreenshotConsent()) shotGrantMarkup() else null
                    )
                }
            }
        }
    }

    private fun clearShotBusy(seq: Int) {
        if (shotAttempt.get() == seq) shotBusy.compareAndSet(true, false)
    }

    private fun releaseShotSurface() {
        val reader: ImageReader?
        val display: VirtualDisplay?
        synchronized(shotSessionLock) {
            reader = shotReader
            display = shotDisplay
            shotReader = null
            shotDisplay = null
            shotProjection = null
            shotWidth = 0
            shotHeight = 0
        }
        try { display?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
    }

    private fun releaseShotSession() {
        var callbackRef: MediaProjection.Callback? = null
        var projectionRef: MediaProjection? = null
        synchronized(shotSessionLock) {
            callbackRef = shotStopCallback
            projectionRef = shotCallbackProjection
            shotStopCallback = null
            shotCallbackProjection = null
        }
        val callback = callbackRef
        val projection = projectionRef
        releaseShotSurface()
        try {
            if (projection != null && callback != null) projection.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        val pending = try { com.redeye.parentalmonitor.utils.ScreenCapture.request() } catch (_: Exception) { null }
        if (pending != null) {
            try { com.redeye.parentalmonitor.utils.ScreenCapture.dropProjection() } catch (_: Exception) { }
        } else {
            com.redeye.parentalmonitor.utils.ScreenCapture.revoke()
        }
    }

    private fun resolveShotProjection(): MediaProjection? {
        com.redeye.parentalmonitor.utils.ScreenCapture.current()?.let { return it }
        val request = com.redeye.parentalmonitor.utils.ScreenCapture.request() ?: return null
        val mpm = mediaProjectionSvc ?: return null
        val acquired = try {
            mpm.getMediaProjection(request.first, request.second)
        } catch (e: SecurityException) {
            android.util.Log.w("MonitoringService", "getMediaProjection ditolak (izin disimpan): " + redactToken(e.message))
            try { com.redeye.parentalmonitor.utils.ScreenCapture.revoke() } catch (_: Exception) { }
            return null
        }
        if (acquired == null) {
            com.redeye.parentalmonitor.utils.ScreenCapture.revoke()
            return null
        }
        val stopCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                var current = false
                synchronized(shotSessionLock) { current = shotCallbackProjection === acquired }
                if (current) releaseShotSession()
            }
        }
        try {
            acquired.registerCallback(stopCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            android.util.Log.w("MonitoringService", "registerCallback tangkap layar gagal: " + redactToken(e.message))
            try { acquired.stop() } catch (_: Exception) { }
            com.redeye.parentalmonitor.utils.ScreenCapture.revoke()
            return null
        }
        synchronized(shotSessionLock) {
            shotStopCallback = stopCallback
            shotCallbackProjection = acquired
        }
        com.redeye.parentalmonitor.utils.ScreenCapture.adopt(acquired)
        return acquired
    }

    private fun attachShotReader(display: VirtualDisplay, width: Int, height: Int, density: Int): ImageReader? {
        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (_: Exception) {
            return null
        }
        try {
            display.resize(width, height, density)
            display.setSurface(reader.surface)
        } catch (_: Exception) {
            try { reader.close() } catch (_: Exception) { }
            return null
        }
        val previous = synchronized(shotSessionLock) {
            val old = shotReader
            shotReader = reader
            shotWidth = width
            shotHeight = height
            old
        }
        try { previous?.close() } catch (_: Exception) { }
        return reader
    }

    private fun ensureShotSession(): ImageReader? {
        val metrics = DisplayMetrics()
        val wm = windowManagerSvc ?: return null
        try {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        } catch (_: Exception) {
            try {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(metrics)
            } catch (_: Exception) {
            }
        }
        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels
        if (rawWidth <= 0 || rawHeight <= 0) return null
        val scale = if (rawWidth > 720) 720f / rawWidth else 1f
        val width = (rawWidth * scale).toInt()
        val height = (rawHeight * scale).toInt()
        val density = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)
        synchronized(shotSessionLock) {
            val existing = shotReader
            if (existing != null && shotProjection != null && shotProjection === liveShotProjection() && shotWidth == width && shotHeight == height) {
                return existing
            }
        }
        val live = synchronized(shotSessionLock) {
            if (shotProjection != null && shotProjection === liveShotProjection()) shotDisplay else null
        }
        if (live != null) {
            val attached = attachShotReader(live, width, height, density)
            if (attached != null) return attached
            return null
        }
        releaseShotSurface()
        val projection = resolveShotProjection() ?: return null
        val reader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        } catch (_: Exception) {
            return null
        }
        val display = try {
            projection.createVirtualDisplay("redeye-shot", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        } catch (e: SecurityException) {
            try { reader.close() } catch (_: Exception) { }
            releaseShotSession()
            return null
        } catch (_: Exception) {
            try { reader.close() } catch (_: Exception) { }
            releaseShotSession()
            return null
        }
        synchronized(shotSessionLock) {
            shotDisplay = display
            shotReader = reader
            shotProjection = projection
            shotWidth = width
            shotHeight = height
        }
        return reader
    }

    private fun liveShotProjection(): MediaProjection? {
        return try { com.redeye.parentalmonitor.utils.ScreenCapture.current() } catch (_: Exception) { null }
    }

    private fun writeScreenshotJpeg(bmp: Bitmap): File? {
        val out = File(cacheDir, "screenshot_" + TimeFmt.fileStamp(System.currentTimeMillis()) + "_" + java.util.UUID.randomUUID() + ".jpg")
        val tmp = File(cacheDir, out.name + ".tmp")
        return try {
            val compressed = java.io.FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (!compressed || !tmp.exists() || tmp.length() == 0L) {
                deleteQuietly(tmp)
                null
            } else {
                deleteQuietly(out)
                if (tmp.renameTo(out)) out else {
                    deleteQuietly(tmp)
                    null
                }
            }
        } catch (_: Exception) {
            deleteQuietly(tmp)
            null
        } finally {
            try { bmp.recycle() } catch (_: Exception) { }
        }
    }

    private suspend fun takeScreenshotFrameViaAccessibility(): File? {
        if (!shotViaAccessibility()) return null
        val result = kotlinx.coroutines.CompletableDeferred<Bitmap?>()
        try {
            com.redeye.parentalmonitor.service.ShotAccessibilityService.capture { shot ->
                if (!result.complete(shot) && shot != null) {
                    try { shot.recycle() } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
            result.complete(null)
        }
        val bmp = try {
            kotlinx.coroutines.withTimeout(20_000L) { result.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            result.cancel()
            return null
        } catch (e: kotlinx.coroutines.CancellationException) {
            result.cancel()
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        return writeScreenshotJpeg(bmp)
    }

    private suspend fun takeScreenshotFrame(): File? {
        takeScreenshotFrameViaAccessibility()?.let { return it }
        if (!hasScreenshotConsent()) {
            releaseShotSession()
            return null
        }
        val reader = try { ensureShotSession() } catch (_: Exception) { null } ?: return null
        val width: Int
        val height: Int
        synchronized(shotSessionLock) {
            width = shotWidth
            height = shotHeight
        }
        if (width <= 0 || height <= 0) {
            releaseShotSurface()
            return null
        }
        try {
            try { reader.acquireLatestImage()?.close() } catch (_: Exception) { }
            var image: android.media.Image? = null
            for (i in 0 until 20) {
                image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
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
                try {
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
                return writeScreenshotJpeg(bmp)
                } catch (e: Exception) {
                    try { bmp.recycle() } catch (_: Exception) { }
                    return null
                }
            } finally {
                try { img.close() } catch (_: Exception) { }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
    }

    private fun sanitizedCameraError(reason: String?): String {
        if (ReportFormat.isCameraPolicyError(reason)) {
            return if (isCameraDisabledByPolicy()) "kamera dimatikan kebijakan perangkat (CAMERA_DISABLED)"
            else "kamera menolak akses latar (CAMERA_DISABLED)"
        }
        val known = when ((reason ?: "").trim()) {
            "camera permission missing" -> "izin kamera belum diberikan"
            else -> null
        }
        if (known != null) return known
        val firstLine = (reason ?: "galat tak dikenal").lineSequence().firstOrNull()?.trim().orEmpty()
        if (firstLine.isEmpty()) return "galat tak dikenal"
        val cleaned = firstLine.replace(CONNECT_HELPER_REGEX, "")
        return cleaned.take(160)
    }

    private fun autoPausePhotosOnPolicyBlock() {
        try {
            val admins = cameraBlockingAdmins()
            val source = if (admins.isNotEmpty() || isCameraDisabledByPolicy()) PHOTO_PAUSE_ADMIN else PHOTO_PAUSE_UNKNOWN
            if (isPhotoPaused()) {
                if (source == PHOTO_PAUSE_ADMIN && photoPauseSource() != PHOTO_PAUSE_ADMIN) {
                    preferencesManager.photoPauseSource = PHOTO_PAUSE_ADMIN
                }
                return
            }
            val minutes = if (source == PHOTO_PAUSE_ADMIN) 120L else 15L
            preferencesManager.photoPausedUntil = System.currentTimeMillis() + minutes * 60_000L
            preferencesManager.photoPauseSource = source
            cachedPhotoPausedUntil = photoPausedElapsed()
        } catch (_: Exception) {
        }
    }

    private fun photoPauseSource(): Int {
        return try { preferencesManager.photoPauseSource } catch (_: Exception) { PHOTO_PAUSE_NONE }
    }

    private suspend fun resumePhotosAfterPolicyLift() {
        try {
            preferencesManager.photoPausedUntil = 0L
            preferencesManager.photoPauseSource = PHOTO_PAUSE_NONE
            cachedPhotoPausedUntil = 0L
            sendToTelegram("✅ Blokir kamera dicabut — foto otomatis jalan lagi.")
        } catch (_: Exception) {
        }
    }

    private fun cameraBlockingAdmins(): List<String> {
        val names = ArrayList<String>()
        try {
            val dpm = devicePolicySvc ?: return names
            val admins = try { dpm.activeAdmins } catch (_: Exception) { null }
            for (admin in admins ?: emptyList()) {
                try {
                    if (dpm.getCameraDisabled(admin)) names.add(admin.packageName)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return names
    }

    private fun policyFailureHint(blockers: List<String>): String {
        if (blockers.isNotEmpty()) {
            return "Kamera diblokir kebijakan perangkat: " + blockers.joinToString(", ") +
                ". Buka Setelan > Keamanan > Aplikasi admin perangkat lalu izinkan kamera untuk aplikasi itu, atau jeda foto dengan /pause." +
                " Foto otomatis dijeda 120 mnt; jalan sendiri begitu blokir dicabut."
        }
        if (isCameraDisabledByPolicy()) {
            return "Sistem melaporkan kamera dimatikan kebijakan perangkat, tapi aplikasi admin penyebabnya tak bisa didaftar (biasanya profil kerja, device owner, atau kontrol orang tua bawaan pabrikan)." +
                " Periksa Setelan > Keamanan > Aplikasi admin perangkat, atau jeda foto dengan /pause." +
                " Foto otomatis dijeda 120 mnt; jalan sendiri begitu blokir dicabut."
        }
        return "Kamera menolak akses walau tak ada admin perangkat yang mematikannya — biasanya izin kamera latar hilang: Android 14+ hanya memasang tipe latar \"camera\" saat aplikasi dibuka, dan tipe latar aktif sekarang \"" +
            FgsTypes.describe(appliedFgsTypes) + "\". Aplikasi mencoba memasangnya sendiri lewat jendela transparan; bila tetap gagal, buka aplikasi sekali dan pastikan izin \"tampilkan di atas aplikasi lain\" diberikan di Setup." +
            " Kemungkinan lain saklar privasi kamera bawaan HP (Xiaomi/Oppo/Samsung/OEM) atau alat kontrol orang tua lain." +
            " Dicoba lagi tiap 15 menit; kirim /photointerval 0 untuk mematikan foto otomatis."
    }

    private suspend fun notifyCameraFailure(reason: String) {
        try {
            val clean = sanitizedCameraError(reason)
            val policyError = ReportFormat.isCameraPolicyError(clean)
            if (policyError) autoPausePhotosOnPolicyBlock()
            val now = System.currentTimeMillis()
            val last = preferencesManager.lastCameraErrorNotice
            if (last > now) {
                preferencesManager.lastCameraErrorNotice = now
                return
            }
            if (last > 0 && now >= last && now - last < 30 * 60_000L) return
            preferencesManager.lastCameraErrorNotice = now
            val hint = if (policyError) policyFailureHint(cameraBlockingAdmins()) else cameraFailureHint(clean)
            sendToTelegram("⚠️ Gagal memotret: " + Html.escape(clean) + ". $hint")
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending camera notice: ${redactToken(e.message)}")
        }
    }

    private suspend fun notifyMediaSendFailure(jenis: String, detail: String) {
        try {
            val now = System.currentTimeMillis()
            val last = preferencesManager.lastUploadErrorNotice
            if (last > now) {
                preferencesManager.lastUploadErrorNotice = now
                return
            }
            if (last > 0 && now >= last && now - last < 30 * 60_000L) return
            preferencesManager.lastUploadErrorNotice = now
            sendToTelegram("⚠️ Unggah $jenis gagal (" + Html.escape(detail) + "). Akan dicoba otomatis.")
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error sending upload notice: ${redactToken(e.message)}")
        }
    }

    private fun mediaRateLimitBackoff(errorFull: String, label: String): MediaSendOutcome {
        val waitSecs = NetworkUtils.parseRetryAfter(errorFull).coerceIn(1L, 3600L)
        val until = android.os.SystemClock.elapsedRealtime() + waitSecs * 1000L
        if (until > mediaBackoffUntil) mediaBackoffUntil = until
        NetworkUtils.noteRateLimited(minOf(waitSecs, 60L))
        android.util.Log.w("MonitoringService", "$label rate limited, backing off ${waitSecs}s without blocking")
        try {
            MessageScheduler.scheduleMessageSendNext(this, waitSecs * 1000L)
        } catch (_: Exception) {
        }
        return MediaSendOutcome.KEPT
    }

    private suspend fun classifyMediaRejection(
        file: File,
        code: Int,
        errorFull: String,
        botToken: String,
        chatId: String,
        label: String,
        jenis: String
    ): MediaSendOutcome {
        if (code == 401 || code == 403) {
            android.util.Log.e("MonitoringService", "$label auth rejected ($code), keeping file for retry")
            preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, code.toString(), System.currentTimeMillis())
            return MediaSendOutcome.KEPT
        }
        if (code == 400) {
            if (adoptMigratedChat(errorFull, chatId)) return MediaSendOutcome.KEPT
            if (NetworkUtils.isChatMissing(errorFull)) {
                preferencesManager.setCredentialErrorIfCredentialsMatch(botToken, chatId, code.toString(), System.currentTimeMillis())
                return MediaSendOutcome.KEPT
            }
            if (NetworkUtils.isRightsLimited(errorFull)) {
                android.util.Log.w("MonitoringService", "$label rights limited (400), keeping file without auth block")
                return MediaSendOutcome.KEPT
            }
            android.util.Log.w("MonitoringService", "$label rejected (400), dropping file")
            deleteQuietly(file)
            return MediaSendOutcome.DROPPED
        }
        if (code == 413) {
            android.util.Log.w("MonitoringService", "$label too large (413), dropping file")
            deleteQuietly(file)
            return MediaSendOutcome.DROPPED
        }
        val errorBody = errorFull.take(200)
        android.util.Log.e("MonitoringService", "✗ Failed to send ${label.lowercase()}: $code $errorBody")
        notifyMediaSendFailure(jenis, "HTTP $code $errorBody".trim())
        return MediaSendOutcome.KEPT
    }

    private fun applyForegroundTypes(notification: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return try {
                startForeground(NOTIFICATION_ID, notification)
                true
            } catch (_: Exception) {
                false
            }
        }
        var lastError: Exception? = null
        val fullTypes = try {
            FgsTypes.compute(Build.VERSION.SDK_INT, hasCameraPermission(), hasMicPermission(), hasLocationPermission(), hasScreenshotConsent())
        } catch (_: Exception) {
            FgsTypes.base(Build.VERSION.SDK_INT)
        }
        for (types in FgsTypes.ladder(fullTypes, FgsTypes.base(Build.VERSION.SDK_INT))) {
            try {
                startForeground(NOTIFICATION_ID, notification, types)
                appliedFgsTypes = types
                return true
            } catch (e: Exception) {
                lastError = e
            }
        }
        android.util.Log.w("MonitoringService", "startForeground gagal: " + redactToken(lastError?.message))
        return false
    }

    private fun ensureForegroundTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val types = FgsTypes.compute(Build.VERSION.SDK_INT, hasCameraPermission(), hasMicPermission(), hasLocationPermission(), hasScreenshotConsent())
        if (types == appliedFgsTypes) return
        if (types == rejectedFgsTypes && android.os.SystemClock.elapsedRealtime() - rejectedFgsAt < 5 * 60_000L) return
        val notification = try {
            buildForegroundNotification()
        } catch (_: Exception) {
            return
        }
        val before = appliedFgsTypes
        if (!applyForegroundTypes(notification)) {
            rejectedFgsTypes = types
            rejectedFgsAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        rejectedFgsTypes = 0
        if (appliedFgsTypes != types && appliedFgsTypes != before) {
            android.util.Log.w("MonitoringService", "Tipe FGS dikurangi jadi " + FgsTypes.describe(appliedFgsTypes) + " (diminta " + FgsTypes.describe(types) + ")")
        }
        if ((types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0 && !hasCameraFgsType()) {
            wakeForegroundForCameraType()
        }
    }

    private fun hasCameraFgsType(): Boolean {
        return FgsTypes.hasCamera(appliedFgsTypes)
    }

    private fun wakeForegroundForCameraType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (!isRunning || !hasCameraPermission()) return
        if (!com.redeye.parentalmonitor.utils.AutoStart.canDrawOverlays(this)) return
        val now = SystemClock.elapsedRealtime()
        val last = cameraWakeAt
        if (last != 0L) {
            if (now - last < FgsTypes.wakeCooldownMs(cameraWakeFails)) return
            cameraWakeFails += 1
        }
        cameraWakeAt = now
        android.util.Log.w("MonitoringService", "Tipe FGS camera belum terpasang; coba lewat jendela transparan")
        try {
            startActivity(
                Intent(this, com.redeye.parentalmonitor.ui.FgsWakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        } catch (_: Exception) {
        }
    }

    private fun confirmCameraWakeRestored() {
        val at = cameraWakeAt
        if (at == 0L) return
        if (!hasCameraFgsType()) return
        cameraWakeAt = 0L
        cameraWakeFails = 0
        val now = SystemClock.elapsedRealtime()
        if (now - at > CAMERA_WAKE_FRESH_MS) return
        if (now - cameraWakeNotifiedAt < CAMERA_WAKE_NOTIFY_GAP_MS) return
        cameraWakeNotifiedAt = now
        serviceScope.launch {
            try {
                sendToTelegram("✅ Izin latar kamera dipasang ulang otomatis (tipe FGS: " + FgsTypes.describe(appliedFgsTypes) + "). Foto otomatis jalan lagi.")
            } catch (_: Exception) {
            }
        }
    }

    private fun hasMicPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
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
            val pm = powerManagerSvc ?: return true
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
            val cur = PermReport.mask(raw, notifEnabled, stored)
            if (stored == -1) {
                try { preferencesManager.setPermSnapshotSync(cur) } catch (_: Exception) { }
                sendToTelegram(PermReport.inventoryText(raw, notifEnabled, permLabels))
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
            val missing = PermReport.missingLabels(cur, permLabels)
            if (missing.isEmpty()) return
            val nowMs = System.currentTimeMillis()
            val last = try { preferencesManager.permHealthAlertAt } catch (_: Exception) { 0L }
            if (nowMs >= last && nowMs - last < 24 * 60 * 60_000L) return
            try { preferencesManager.setPermHealthAlertAtSync(nowMs) } catch (_: Exception) { }
            sendToTelegram("⚠️ Izin dicabut: " + missing.joinToString(", ") + ". Monitoring buta sebagian — buka Setup di perangkat dan berikan lagi.")
        } catch (_: Exception) {
        }
    }

    private suspend fun checkAccessibilityHealth() {
        try {
            if (!preferencesManager.a11yTamperEnabled) return
            if (!preferencesManager.accessibilityEverEnabled) return
            if (ShotAccessibilityService.isEnabled(this)) {
                if (preferencesManager.a11yDisabledAlertAt != 0L) preferencesManager.a11yDisabledAlertAt = 0L
                return
            }
            val now = System.currentTimeMillis()
            val last = try { preferencesManager.a11yDisabledAlertAt } catch (_: Exception) { 0L }
            if (last > now) {
                try { preferencesManager.a11yDisabledAlertAt = now } catch (_: Exception) { }
                return
            }
            if (now >= last && now - last < 6 * 60 * 60_000L) return
            preferencesManager.a11yDisabledAlertAt = now
            sendToTelegram("\u267F <b>Aksesibilitas mati</b>\nPemakaian layar, pantau app, dan proteksi tamper berhenti. Buka Setup di perangkat \u2192 Aktifkan Aksesibilitas, lalu nyalakan lagi \"Pendukung Layar\".")
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

    private fun hasReceiveSmsPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECEIVE_SMS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasPhoneStatePermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun networkSummary(): String {
        return try {
            val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return "?"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
            val parts = ArrayList<String>()
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> parts.add("Wi-Fi")
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> parts.add("seluler")
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> parts.add("ethernet")
                else -> parts.add("tersambung")
            }
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) parts.add("VPN")
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) parts.add("tanpa internet")
            parts.joinToString(" · ")
        } catch (_: Exception) {
            "?"
        }
    }

    private fun batterySummary(): String {
        return try {
            val bm = getSystemService(android.os.BatteryManager::class.java) ?: return "?"
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            (if (level in 0..100) "$level%" else "?") + if (bm.isCharging) " · charging" else ""
        } catch (_: Exception) {
            "?"
        }
    }

    private fun adminFailedAttempts(): Int {
        return try {
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java) ?: return 0
            val admin = android.content.ComponentName(this, com.redeye.parentalmonitor.receiver.AdminReceiver::class.java)
            if (!dpm.isAdminActive(admin)) return 0
            dpm.currentFailedPasswordAttempts
        } catch (_: Exception) {
            0
        }
    }

    private fun hasCameraPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isCameraDisabledByPolicy(): Boolean {
        try {
            val dpm = devicePolicySvc ?: return false
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
        if (!hasCameraPermission()) return "Buka Setup dan berikan izin Kamera."
        if (ReportFormat.isCameraPolicyError(reason) || isCameraDisabledByPolicy()) return "Kamera diblokir kebijakan perangkat (aplikasi admin lain, profil kerja, atau kontrol orang tua mematikannya). Periksa Setelan > Keamanan > Aplikasi admin perangkat, cabut pembatasan kamera, atau jeda foto dengan /pause."
        return "Kamera mungkin sedang dipakai aplikasi lain."
    }

    private fun pendingVideoCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.length() > 0L && file.name.startsWith("video_") && file.name.endsWith(".mp4") && !isMediaActive(file)
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun pendingPhotoCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.length() > 0L && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg") && !isMediaActive(file)
            }?.size ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun pendingAudioCount(): Int {
        return try {
            cacheDir.listFiles { file ->
                file.isFile && file.length() > 0L && file.name.startsWith("audio_") && file.name.endsWith(".m4a") && !isMediaActive(file)
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
        val oldJobs = listOfNotNull(monitoringJob, cameraJob, commandJob, patrolJob)
        for (job in oldJobs) {
            try {
                job.cancel()
            } catch (_: Exception) {
            }
        }
        idlePolls = 0
        refreshCreds()
        refreshLoopConfig()
        serviceScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    for (job in oldJobs) job.join()
                }
            } catch (_: Exception) {
            }
            startPeriodicLoops()
            startCommandPolling()
            startLoopWatchdog()
            startInitialSync(force = true)
        }
        return true
    }

    private fun startLoopWatchdog() {
        try {
            loopWatchdogJob?.cancel()
        } catch (_: Exception) {
        }
        val watchdog = serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            while (isActive && (loopWatchdogJob == null || loopWatchdogJob === self)) {
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
                    val commandStuck = commandJob?.isActive == true && commandBeatAt > 0L && commandCycleMs in 1L..1440 * 60_000L && nowBeat - commandBeatAt > commandCycleMs + 10 * 60_000L
                    val patrolStuck = patrolJob?.isActive == true && patrolBeatAt > 0L && patrolCycleMs in 1L..1440 * 60_000L && nowBeat - patrolBeatAt > patrolCycleMs + 10 * 60_000L
                    if (monitoringJob?.isActive != true || cameraJob?.isActive != true || commandJob?.isActive != true || patrolJob?.isActive != true || initialStuck || monitorStuck || cameraStuck || commandStuck || patrolStuck) {
                        android.util.Log.w("MonitoringService", "Loop watchdog: restarting dead loops")
                        if (initialStuck) initialSyncRunning.set(false)
                        restartAllLoops(fromWatchdog = true)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (loopWatchdogNoticeAt == 0L || now - loopWatchdogNoticeAt > 3_600_000L) {
                            loopWatchdogNoticeAt = now
                            replyLater("\u267B\uFE0F Watchdog restarted dead loops.")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Loop watchdog error: ${redactToken(e.message)}")
                } catch (_: Throwable) {
                    android.util.Log.e("MonitoringService", "Fatal watchdog error, watchdog survives")
                }
            }
        }
        loopWatchdogJob = watchdog
        watchdog.start()
    }

    private fun finishedRingText(): String {
        return "\uD83D\uDD14 Ring finished."
    }

    private fun acquireOpWakeLock(tag: String, timeoutMs: Long): android.os.PowerManager.WakeLock? {
        return try {
            val pm = powerManagerSvc ?: return null
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RedEye:$tag").apply { setReferenceCounted(false) }.also {
                try {
                    it.acquire(timeoutMs.coerceAtLeast(60_000L))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseOpWakeLock(lock: android.os.PowerManager.WakeLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {
        }
    }

    private fun releaseVideoWakeLock(lock: android.os.PowerManager.WakeLock?) {
        releaseOpWakeLock(lock)
        if (videoWakeLock === lock) videoWakeLock = null
    }

    private suspend fun ringDevice(seconds: Int) {
        val audioManager = audioManagerSvc
        if (audioManager == null) {
            sendToTelegram("⚠️ Volume tak terbaca, /ring dibatalkan agar tak macet maksimal.")
            return
        }
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
        val ringLock = acquireOpWakeLock("ring", seconds * 1000L + 60_000L)
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
                    sendToTelegram("⚠️ Gagal berdering (suara alarm tak tersedia).")
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
            sendToTelegram("\uD83D\uDD14 Berdering $seconds dtk\u2026")
            kotlinx.coroutines.delay(seconds * 1000L)
            currentCoroutineContext().ensureActive()
            sendToTelegram(finishedRingText())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            sendToTelegram("\u26A0\uFE0F Gagal berdering.")
        } finally {
            releaseOpWakeLock(ringLock)
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
            sendToTelegram("⚠️ Izin Mikrofon belum diberikan. Buka Setup dan berikan izin Mikrofon.")
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            sendToTelegram("Dibatasi Telegram (rate limit) — rekaman ditahan lalu dicoba lagi.")
            return
        }
        val pendingAudio = pendingAudioCount()
        if (pendingAudio >= MAX_AUDIO_KEPT) {
            try {
                pruneAudioCache(MAX_AUDIO_KEPT - 1)
            } catch (_: Exception) {
            }
            val pendingAudioNow = pendingAudioCount()
            if (pendingAudioNow >= MAX_AUDIO_KEPT) {
                sendToTelegram("Antrean rekaman penuh (offline). Yang paling lama yang belum terkirim dibuang; rekaman baru dilewati.")
                return
            }
        }
        ensureForegroundTypes()
        val audioLock = acquireOpWakeLock("record", seconds * 1000L + MEDIA_UPLOAD_GRACE_MS + 60_000L)
        val audioFile = File(cacheDir, "audio_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID() + ".m4a")
        val audioTmp = File(cacheDir, audioFile.name + ".tmp")
        var recorder: android.media.MediaRecorder? = null
        var keepForRetry = false
        var audioOutcome: MediaSendOutcome? = null
        markMediaActive(audioFile)
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                android.media.MediaRecorder()
            }
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(audioTmp.absolutePath)
            recorder.prepare()
            recorderActive.set(true)
            recorder.start()
            kotlinx.coroutines.delay(seconds * 1000L)
            var stopOk = true
            try {
                recorder.stop()
            } catch (_: Exception) {
                stopOk = false
            }
            if (!stopOk) {
                try {
                    deleteQuietly(audioTmp)
                } catch (_: Exception) {
                }
                sendToTelegram("Gagal merekam (rekaman tidak final). Coba lagi.")
                return
            }
            if (!audioTmp.exists() || audioTmp.length() == 0L) {
                try {
                    deleteQuietly(audioTmp)
                } catch (_: Exception) {
                }
                sendToTelegram("Gagal merekam (audio kosong). Coba lagi.")
                return
            }
            val finalized = try {
                deleteQuietly(audioFile)
                audioTmp.renameTo(audioFile)
            } catch (_: Exception) {
                false
            }
            if (!finalized) {
                try {
                    deleteQuietly(audioTmp)
                } catch (_: Exception) {
                }
                sendToTelegram("Gagal menyiapkan rekaman. Coba lagi.")
                return
            }
            recorderActive.set(false)
            val uploadOutcome = kotlinx.coroutines.withTimeoutOrNull(AUDIO_UPLOAD_TIMEOUT_MS) { sendAudioFile(audioFile) }
            if (uploadOutcome == null) {
                keepForRetry = true
                audioOutcome = MediaSendOutcome.KEPT
                try {
                    messageQueue.addMessage("\u26A0\uFE0F Unggah rekaman melebihi batas waktu. Berkas disimpan untuk dicoba otomatis.")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
                return
            }
            audioOutcome = uploadOutcome
            when (audioOutcome) {
                MediaSendOutcome.SENT -> {
                    try {
                        sendToTelegram("\uD83C\uDF99\uFE0F Rekaman terkirim (${seconds} dtk).")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    try {
                        flushPendingAudio()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                MediaSendOutcome.DROPPED -> {
                    keepForRetry = false
                    try {
                        flushPendingAudio()
                    } catch (_: Exception) {
                    }
                    sendToTelegram("\u26A0\uFE0F Rekaman ditolak Telegram (400) — berkas kosong/tak didukung, dibuang.")
                }
                MediaSendOutcome.KEPT -> {
                    keepForRetry = true
                    try {
                        flushPendingAudio()
                    } catch (_: Exception) {
                    }
                    sendToTelegram("\u26A0\uFE0F Rekaman jadi tapi gagal dikirim. Berkas disimpan untuk dicoba otomatis.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            var stopped = false
            try {
                recorder?.stop()
                stopped = true
            } catch (_: Exception) {
            }
            if (audioOutcome == MediaSendOutcome.SENT) {
                try {
                    messageQueue.addMessage("\uD83C\uDF99\uFE0F Rekaman terkirim (${seconds} dtk).")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            } else if (audioOutcome == MediaSendOutcome.KEPT) {
                keepForRetry = true
                try {
                    messageQueue.addMessage("\u26A0\uFE0F Rekaman jadi tapi gagal dikirim. Berkas disimpan untuk dicoba otomatis.")
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            } else {
                val ready = try { audioFile.exists() && audioFile.length() > 0L } catch (_: Exception) { false }
                val finalized = if (ready) {
                    true
                } else if (stopped) {
                    try {
                        deleteQuietly(audioFile)
                        audioTmp.renameTo(audioFile)
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                keepForRetry = finalized && try { audioFile.exists() && audioFile.length() > 0L } catch (_: Exception) { false }
                if (!keepForRetry) {
                    try { deleteQuietly(audioTmp) } catch (_: Exception) { }
                }
            }
            throw e
        } catch (e: SecurityException) {
            try { deleteQuietly(audioTmp) } catch (_: Exception) { }
            try { deleteQuietly(audioFile) } catch (_: Exception) { }
            sendToTelegram("\u26A0\uFE0F Izin Mikrofon belum diberikan. Buka Setup dan berikan izin Mikrofon.")
        } catch (e: Exception) {
            try { deleteQuietly(audioTmp) } catch (_: Exception) { }
            try { deleteQuietly(audioFile) } catch (_: Exception) { }
            sendToTelegram("\u26A0\uFE0F Gagal merekam.")
        } finally {
            recorderActive.set(false)
            releaseOpWakeLock(audioLock)
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            clearMediaActive(audioFile)
            if (!keepForRetry) {
                try { deleteQuietly(audioTmp) } catch (_: Exception) { }
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
            if (awaitingOwnerLogin()) return MediaSendOutcome.KEPT
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
            val response = TelegramMediaClient.api.sendAudio(url, chatIdBody, caption, audioPart, menuBarBody())
            if (response.isSuccessful && response.body()?.ok == true) {
                noteSendOk()
                deleteQuietly(audioFile)
                return MediaSendOutcome.SENT
            }
            val errorFull = try {
                response.errorBody()?.string() ?: ""
            } catch (_: Exception) {
                ""
            }
            if (response.code() == 429) return mediaRateLimitBackoff(errorFull, "Audio")
            return classifyMediaRejection(audioFile, response.code(), errorFull, botToken, chatId, "Audio", "audio")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending audio to Telegram: ${redactToken(e.message)}")
            return MediaSendOutcome.KEPT
        }
    }

    private fun recordAndSendVideo(seconds: Int) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("Token ditolak — video ditahan sampai token diperbaiki di Setup.")
            }
            return
        }
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("Dibatasi Telegram (rate limit) — video ditahan lalu dicoba lagi.")
            }
            return
        }
        ensureForegroundTypes()
        if (!hasCameraPermission()) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("⚠️ Izin Kamera belum diberikan. Buka Setup dan berikan izin Kamera.")
            }
            return
        }
        if (!hasMicPermission()) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("⚠️ Izin Mikrofon belum diberikan. Buka Setup dan berikan izin Mikrofon.")
            }
            return
        }
        if (cameraBusy.get()) {
            recordBusy.set(false)
            serviceScope.launch {
                sendToTelegram("⚠️ Kamera sedang dipakai untuk foto, coba /recordvideo lagi sebentar.")
            }
            return
        }
        if (isCameraDisabledByPolicy()) {
            recordBusy.set(false)
            autoPausePhotosOnPolicyBlock()
            val blockers = cameraBlockingAdmins()
            serviceScope.launch {
                sendToTelegram("⚠️ Video diblokir kebijakan perangkat (CAMERA_DISABLED). " + policyFailureHint(blockers))
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
                    if (videoFlushBusy.get()) {
                        sendToTelegram("⏳ Sedang mengirim video tertunda, coba lagi sebentar.")
                    } else {
                        sendToTelegram("⚠️ Antrean video penuh (offline). Yang paling lama yang belum terkirim dibuang; video baru dilewati.")
                    }
                }
                return
            }
        }
        val attempt = videoAttempt.incrementAndGet()
        val videoLock = acquireOpWakeLock("recordvideo", seconds * 1000L + MEDIA_UPLOAD_GRACE_MS + 60_000L)
        videoWakeLock = videoLock
        videoWatchdog?.cancel()
        val wd = serviceScope.launch {
            delay(seconds * 1000L + 120_000L)
            if (videoAttempt.get() == attempt && recordBusy.compareAndSet(true, false)) {
                recorderActive.set(false)
                videoAttempt.incrementAndGet()
                try { cameraService.forceReset() } catch (_: Exception) { }
                releaseVideoWakeLock(videoLock)
                android.util.Log.w("MonitoringService", "Video watchdog: recording did not finish, flag reset")
                sendToTelegram("⚠️ Perekaman video tak menjawab (timeout). Coba /recordvideo lagi.")
            }
        }
        videoWatchdog = wd
        recorderActive.set(true)
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
                    recorderActive.set(false)
                    markMediaActive(videoFile)
                    serviceScope.launch {
                        try {
                            if (videoFile.length() > MAX_VIDEO_BYTES) {
                                deleteQuietly(videoFile)
                                sendToTelegram("⚠️ Video terlalu besar (>${MAX_VIDEO_BYTES / (1024L * 1024L)} MB). Coba /recordvideo dengan detik lebih sedikit.")
                            } else {
                                val uploadOutcome = kotlinx.coroutines.withTimeoutOrNull(VIDEO_UPLOAD_TIMEOUT_MS) { sendVideoFile(videoFile) }
                                when (uploadOutcome ?: MediaSendOutcome.KEPT) {
                                    MediaSendOutcome.SENT -> {
                                        sendToTelegram("🎥 Video terkirim (${seconds} dtk).")
                                        flushPendingVideos()
                                    }
                                    MediaSendOutcome.DROPPED -> {
                                        try { flushPendingVideos() } catch (_: Exception) { }
                                        sendToTelegram("⚠️ Video ditolak Telegram (400) — berkas kosong/tak didukung, dibuang.")
                                    }
                                    MediaSendOutcome.KEPT -> {
                                        try { flushPendingVideos() } catch (_: Exception) { }
                                        sendToTelegram("⚠️ Video jadi tapi gagal dikirim. Berkas disimpan untuk dicoba otomatis.")
                                    }
                                }
                            }
                        } finally {
                            clearMediaActive(videoFile)
                            releaseVideoWakeLock(videoLock)
                            recordBusy.set(false)
                        }
                    }
                },
                onError = { e ->
                    if (videoAttempt.get() != attempt) return@captureVideo
                    videoAttempt.incrementAndGet()
                    try { wd.cancel() } catch (_: Exception) { }
                    releaseVideoWakeLock(videoLock)
                    recordBusy.set(false)
                    recorderActive.set(false)
                    serviceScope.launch {
                        sendToTelegram("⚠️ Gagal merekam video: " + sanitizedCameraError(e.message))
                    }
                },
                timeoutMs = seconds * 1000L + 60_000L
            )
        } catch (e: Exception) {
            videoAttempt.incrementAndGet()
            try { wd.cancel() } catch (_: Exception) { }
            releaseVideoWakeLock(videoLock)
            recordBusy.set(false)
            recorderActive.set(false)
            android.util.Log.e("MonitoringService", "✗ Error in recordAndSendVideo: ${redactToken(e.message)}")
            serviceScope.launch {
                sendToTelegram("⚠️ Gagal merekam video.")
            }
        }
    }

    private suspend fun sendVideoFile(videoFile: File): MediaSendOutcome {
        try {
            if (!videoFile.exists() || videoFile.length() == 0L) {
                deleteQuietly(videoFile)
                return MediaSendOutcome.DROPPED
            }
            if (awaitingOwnerLogin()) return MediaSendOutcome.KEPT
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
            val response = TelegramMediaClient.api.sendVideo(url, chatIdBody, caption, videoPart, menuBarBody())
            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "✓ Video sent successfully!")
                noteSendOk()
                deleteQuietly(videoFile)
                return MediaSendOutcome.SENT
            }
            val errorFull = try {
                response.errorBody()?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            if (response.code() == 429) return mediaRateLimitBackoff(errorFull, "Video")
            return classifyMediaRejection(videoFile, response.code(), errorFull, botToken, chatId, "Video", "video")
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
        if (dataSaverDeferMedia()) return
        if (!videoFlushBusy.compareAndSet(false, true)) return
        var droppedVideos = 0
        var videoProgress = false
        var lastFullListing: List<File>? = null
        try {
            if (!hasNetwork()) return
            val flushDeadline = android.os.SystemClock.elapsedRealtime() + MEDIA_FLUSH_DEADLINE_MS
            var batches = 0
            val attempted = HashSet<String>()
            drain@ while (batches < 3) {
                val pending = try {
                    val all = cacheDir.listFiles { file ->
                        file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4")
                    }?.sortedBy { it.lastModified() } ?: break@drain
                    lastFullListing = all
                    all.filter { it.absolutePath !in attempted }.take(max)
                } catch (e: Exception) {
                    break@drain
                }
                if (pending.isEmpty()) break@drain
                val now = System.currentTimeMillis()
                var batchProgress = false
                for (file in pending) {
                    if (isMediaActive(file)) continue
                    if (now - file.lastModified() < 10_000L) continue
                    if (!file.exists() || file.length() == 0L) {
                        deleteQuietly(file)
                        batchProgress = true
                        videoProgress = true
                        continue
                    }
                    val remainMs = flushDeadline - android.os.SystemClock.elapsedRealtime()
                    if (remainMs < MEDIA_FLUSH_MIN_START_MS) break@drain
                    attempted.add(file.absolutePath)
                    val videoOutcome = kotlinx.coroutines.withTimeoutOrNull(remainMs) { sendVideoFile(file) } ?: MediaSendOutcome.KEPT
                    if (videoOutcome == MediaSendOutcome.KEPT) continue
                    monitorBeatAt = android.os.SystemClock.elapsedRealtime()
                    batchProgress = true
                    videoProgress = true
                    if (videoOutcome == MediaSendOutcome.DROPPED) droppedVideos++
                    kotlinx.coroutines.delay(500)
                }
                if (!batchProgress) break@drain
                batches++
            }
            if (droppedVideos > 0) {
                try {
                    messageQueue.addMessage("⚠️ $droppedVideos berkas video tak bisa dikirim (kosong/tak didukung/terlalu besar) — dibuang.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } finally {
            videoFlushBusy.set(false)
            if (videoProgress || droppedVideos > 0) pruneVideoCache(MAX_VIDEO_KEPT, lastFullListing)
        }
    }

    private fun pruneVideoCache(maxKept: Int, known: List<File>? = null) {
        if (videoFlushBusy.get()) return
        try {
            try {
                cacheDir.listFiles { file ->
                    file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4.tmp") && System.currentTimeMillis() - file.lastModified() > 3_600_000L
                }?.forEach { deleteQuietly(it) }
            } catch (_: Exception) {
            }
            val files = (known ?: cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("video_") && file.name.endsWith(".mp4")
            }?.sortedBy { it.lastModified() })?.filter { !isMediaActive(it) } ?: return
            val dropped = files.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("⚠️ ${dropped.size} berkas video dibuang (cache penuh).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun flushPendingAudio(max: Int = 5) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
        if (dataSaverDeferMedia()) return
        if (!audioFlushBusy.compareAndSet(false, true)) return
        var droppedAudio = 0
        var audioProgress = false
        var lastFullListing: List<File>? = null
        try {
            if (!hasNetwork()) return
            val flushDeadline = android.os.SystemClock.elapsedRealtime() + MEDIA_FLUSH_DEADLINE_MS
            var batches = 0
            val attempted = HashSet<String>()
            drain@ while (batches < 3) {
                val pending = try {
                    val all = cacheDir.listFiles { file ->
                        file.isFile && file.name.startsWith("audio_") && file.name.endsWith(".m4a")
                    }?.sortedBy { it.lastModified() } ?: break@drain
                    lastFullListing = all
                    all.filter { it.absolutePath !in attempted }.take(max)
                } catch (e: Exception) {
                    break@drain
                }
                if (pending.isEmpty()) break@drain
                val now = System.currentTimeMillis()
                var batchProgress = false
                for (file in pending) {
                    if (isMediaActive(file)) continue
                    if (now - file.lastModified() < 10_000L) continue
                    if (!file.exists() || file.length() == 0L) {
                        deleteQuietly(file)
                        batchProgress = true
                        audioProgress = true
                        continue
                    }
                    val remainMs = flushDeadline - android.os.SystemClock.elapsedRealtime()
                    if (remainMs < MEDIA_FLUSH_MIN_START_MS) break@drain
                    attempted.add(file.absolutePath)
                    val audioOutcome = kotlinx.coroutines.withTimeoutOrNull(remainMs) { sendAudioFile(file) } ?: MediaSendOutcome.KEPT
                    if (audioOutcome == MediaSendOutcome.KEPT) continue
                    monitorBeatAt = android.os.SystemClock.elapsedRealtime()
                    batchProgress = true
                    audioProgress = true
                    if (audioOutcome == MediaSendOutcome.DROPPED) droppedAudio++
                    kotlinx.coroutines.delay(500)
                }
                if (!batchProgress) break@drain
                batches++
            }
            if (droppedAudio > 0) {
                try {
                    messageQueue.addMessage("\u26A0\uFE0F $droppedAudio berkas rekaman tak bisa dikirim (kosong/tak didukung/terlalu besar) — dibuang.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } finally {
            audioFlushBusy.set(false)
            if (audioProgress || droppedAudio > 0) pruneAudioCache(MAX_AUDIO_KEPT, lastFullListing)
        }
    }

    private fun pruneAudioCache(maxKept: Int, known: List<File>? = null) {
        if (audioFlushBusy.get()) return
        try {
            val staleTmpCutoff = System.currentTimeMillis() - 6 * 60 * 60_000L
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("audio_") && file.name.endsWith(".tmp") && file.lastModified() < staleTmpCutoff
            }?.forEach { deleteQuietly(it) }
            val files = (known ?: cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("audio_") && file.name.endsWith(".m4a")
            }?.sortedBy { it.lastModified() })?.filter { !isMediaActive(it) } ?: return
            val dropped = files.dropLast(maxKept)
            if (dropped.isEmpty()) return
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} berkas rekaman dibuang (cache penuh).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun canonicalPhoneDigits(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("62") && d.length >= 10) d = "0" + d.substring(2)
        else if (d.startsWith("8") && d.length >= 9) d = "0" + d
        return d
    }

    private fun contactNumberMatches(haveRaw: String, wantDigits: String): Boolean {
        return try {
            val have = canonicalPhoneDigits(haveRaw)
            val want = canonicalPhoneDigits(wantDigits)
            if (have.isEmpty() || want.isEmpty()) return false
            if (have == want) return true
            if (want.length < 9 || have.length < 9) return false
            have.endsWith(want.takeLast(9)) || want.endsWith(have.takeLast(9))
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
        if (cached.isNotEmpty()) return cached.take(limit)
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

    private suspend fun sendPhotoFile(photoFile: File): MediaSendOutcome =
        photoSendMutex.withLock { sendPhotoFileUnlocked(photoFile) }

    private suspend fun sendPhotoFileUnlocked(photoFile: File): MediaSendOutcome {
        try {
            if (!photoFile.exists() || photoFile.length() == 0L) {
                deleteQuietly(photoFile)
                return MediaSendOutcome.DROPPED
            }
            if (awaitingOwnerLogin()) return MediaSendOutcome.KEPT
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

            val response = TelegramMediaClient.api.sendPhoto(url, chatIdBody, caption, photoPart, menuBarBody())

            if (response.isSuccessful && response.body()?.ok == true) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MonitoringService", "✓ Photo sent successfully!")
                noteSendOk()
                try { preferencesManager.lastPhotoTime = System.currentTimeMillis() } catch (_: Exception) { }
                deleteQuietly(photoFile)
                return MediaSendOutcome.SENT
            }
            val errorFull = try {
                response.errorBody()?.string() ?: ""
            } catch (e: Exception) {
                ""
            }
            if (response.code() == 429) return mediaRateLimitBackoff(errorFull, "Photo")
            return classifyMediaRejection(photoFile, response.code(), errorFull, botToken, chatId, "Photo", "foto")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Error sending photo to Telegram: ${redactToken(e.message)}")
            return MediaSendOutcome.KEPT
        }
    }

    private suspend fun flushPendingPhotos(max: Int = MAX_PHOTO_KEPT) {
        if (NetworkUtils.isAuthBlocked(preferencesManager)) return
        if (android.os.SystemClock.elapsedRealtime() < mediaBackoffUntil) return
        if (dataSaverDeferMedia()) return
        if (!photoFlushBusy.compareAndSet(false, true)) return
        var droppedPhotos = 0
        var photoProgress = false
        var lastFullListing: List<File>? = null
        try {
            if (!hasNetwork()) return
            val flushDeadline = android.os.SystemClock.elapsedRealtime() + MEDIA_FLUSH_DEADLINE_MS
            var batches = 0
            val attempted = HashSet<String>()
            drain@ while (batches < 3) {
                val pending = try {
                    val all = cacheDir.listFiles { file ->
                        file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg")
                    }?.sortedBy { it.lastModified() } ?: break@drain
                    lastFullListing = all
                    all.filter { it.absolutePath !in attempted }.take(max)
                } catch (e: Exception) {
                    break@drain
                }
                if (pending.isEmpty()) break@drain
                val now = System.currentTimeMillis()
                var batchProgress = false
                for (file in pending) {
                    if (isMediaActive(file)) continue
                    if (now - file.lastModified() < 10_000L) continue
                    if (!file.exists() || file.length() == 0L) {
                        deleteQuietly(file)
                        batchProgress = true
                        photoProgress = true
                        continue
                    }
                    val remainMs = flushDeadline - android.os.SystemClock.elapsedRealtime()
                    if (remainMs < MEDIA_FLUSH_MIN_START_MS) break@drain
                    attempted.add(file.absolutePath)
                    val photoOutcome = kotlinx.coroutines.withTimeoutOrNull(remainMs) { sendPhotoFile(file) } ?: MediaSendOutcome.KEPT
                    if (photoOutcome == MediaSendOutcome.KEPT) continue
                    monitorBeatAt = android.os.SystemClock.elapsedRealtime()
                    batchProgress = true
                    photoProgress = true
                    if (photoOutcome == MediaSendOutcome.DROPPED) droppedPhotos++
                    kotlinx.coroutines.delay(500)
                }
                if (!batchProgress) break@drain
                batches++
            }
            if (droppedPhotos > 0) {
                try {
                    messageQueue.addMessage("\u26A0\uFE0F $droppedPhotos berkas foto tak bisa dikirim (kosong/tak didukung/terlalu besar) — dibuang.", true)
                    MessageScheduler.scheduleMessageSend(this)
                } catch (_: Exception) {
                }
            }
        } finally {
            photoFlushBusy.set(false)
            if (photoProgress || droppedPhotos > 0) prunePhotoCache(known = lastFullListing)
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            if (!file.delete() && file.exists()) file.writeBytes(ByteArray(0))
        } catch (_: Exception) {
        }
    }
    private fun prunePhotoCache(maxKept: Int = MAX_PHOTO_KEPT, known: List<File>? = null): Int {
        if (photoFlushBusy.get()) return -1
        try {
            try {
                cacheDir.listFiles { file ->
                    file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) &&
                        file.name.endsWith(".tmp") && System.currentTimeMillis() - file.lastModified() > 3_600_000L
                }?.forEach { deleteQuietly(it) }
            } catch (_: Exception) {
            }
            val photos = (known ?: cacheDir.listFiles { file ->
                file.isFile && (file.name.startsWith("camera_") || file.name.startsWith("screenshot_")) && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() })?.filter { !isMediaActive(it) } ?: return -1
            if (photos.size <= maxKept) return photos.size
            val dropped = photos.dropLast(maxKept)
            val remaining = photos.size - dropped.size
            dropped.forEach { deleteQuietly(it) }
            try {
                messageQueue.addMessage("\u26A0\uFE0F ${dropped.size} berkas foto lama dibuang (cache penuh).", true)
                MessageScheduler.scheduleMessageSend(this)
            } catch (_: Exception) {
            }
            return remaining
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache: ${redactToken(e.message)}")
        }
        return -1
    }
}
