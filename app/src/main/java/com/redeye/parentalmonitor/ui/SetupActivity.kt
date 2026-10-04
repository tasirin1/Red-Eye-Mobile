package com.redeye.parentalmonitor.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.receiver.AdminReceiver
import com.redeye.parentalmonitor.service.MonitoringService
import kotlinx.coroutines.launch

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var botTokenInput: TextInputEditText
    private lateinit var chatIdInput: TextInputEditText
    private lateinit var syncIntervalInput: TextInputEditText
    private lateinit var cameraIntervalInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var permissionButton: MaterialButton
    private lateinit var notifButton: MaterialButton
    private var saveJob: kotlinx.coroutines.Job? = null
    private var testJob: kotlinx.coroutines.Job? = null
    private val saveSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val testSeq = java.util.concurrent.atomic.AtomicInteger(0)

    private val requiredPermissions: Array<String>
        get() = com.redeye.parentalmonitor.utils.AppPermissions.requiredPermissions

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if ((fine || coarse) && !hasBackgroundLocation()) {
                try {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (_: Exception) {
                }
            }
        }
        updateStatus()
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateStatus() }

    companion object {
        private const val STORED_MASK = "••••••••"
        private val TOKEN_REGEX = Regex("^[0-9]{5,15}:[A-Za-z0-9_-]{20,}$")
        private val CHAT_ID_REGEX = Regex("^-?[0-9]+$")
    }

    private fun isChatIdValid(chatId: String): Boolean {
        if (!chatId.matches(CHAT_ID_REGEX)) return false
        val v = chatId.toLongOrNull() ?: return false
        return v != 0L
    }

    private fun resolveStored(raw: String, stored: String): String {
        return if (raw.isEmpty() || raw == STORED_MASK) stored else raw
    }

    private fun redactToken(value: String?): String {
        val raw = value ?: return ""
        if (raw.isEmpty()) return ""
        val secrets = mutableSetOf<String>()
        try {
            prefs.botToken.takeIf { it.isNotEmpty() }?.let { secrets.add(it) }
        } catch (_: Exception) {
        }
        if (::botTokenInput.isInitialized) {
            val typed = botTokenInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty() && typed != STORED_MASK) secrets.add(typed)
        }
        if (secrets.isEmpty()) return raw
        var out = raw
        for (secret in secrets) out = out.replace(secret, "***")
        return out
    }

    private fun hasForegroundLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }
        if (!hasBackgroundLocation()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (e: Exception) {
                    openAppSettings()
                }
            }
            return
        }
        Toast.makeText(this, getString(R.string.setup_bg_granted), Toast.LENGTH_SHORT).show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = PreferencesManager.getInstance(this)
        supportActionBar?.title = getString(R.string.setup_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        botTokenInput = findViewById(R.id.setupBotTokenInput)
        chatIdInput = findViewById(R.id.setupChatIdInput)
        syncIntervalInput = findViewById(R.id.setupSyncIntervalInput)
        cameraIntervalInput = findViewById(R.id.setupCameraIntervalInput)
        statusText = findViewById(R.id.setupStatusText)
        toggleButton = findViewById(R.id.setupToggleButton)
        permissionButton = findViewById(R.id.setupPermissionButton)
        notifButton = findViewById(R.id.setupNotifButton)

        try {
            botTokenInput.setText("")
        } catch (_: Exception) {
        }
        try {
            chatIdInput.setText("")
        } catch (_: Exception) {
        }
        try {
            syncIntervalInput.setText("")
        } catch (_: Exception) {
        }
        try {
            cameraIntervalInput.setText("")
        } catch (_: Exception) {
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                PreferencesManager.refreshInstance(this@SetupActivity)
            } catch (_: Exception) {
            }
            try {
                prefs = PreferencesManager.getInstance(this@SetupActivity)
            } catch (_: Exception) {
            }
            val tokenMasked = try { prefs.botToken.isNotEmpty() } catch (_: Exception) { false }
            val chatMasked = try { prefs.chatId.isNotEmpty() } catch (_: Exception) { false }
            val syncVal = try { prefs.syncInterval.toString() } catch (_: Exception) { com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL.toString() }
            val camVal = try { prefs.cameraInterval.toString() } catch (_: Exception) { "1" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                try {
                    if (botTokenInput.text.toString().isEmpty() && tokenMasked) botTokenInput.setText(STORED_MASK)
                    if (chatIdInput.text.toString().isEmpty() && chatMasked) chatIdInput.setText(STORED_MASK)
                    if (syncIntervalInput.text.toString().isEmpty()) syncIntervalInput.setText(syncVal)
                    if (cameraIntervalInput.text.toString().isEmpty()) cameraIntervalInput.setText(camVal)
                } catch (_: Exception) {
                }
                try {
                    updateStatus()
                } catch (_: Exception) {
                }
            }
        }

        findViewById<MaterialButton>(R.id.setupSaveButton).setOnClickListener { saveSettings() }
        findViewById<MaterialButton>(R.id.setupSaveButton).setOnLongClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_clear_title))
                .setMessage(getString(R.string.setup_clear_text))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> clearCredentials() }
                .show()
            true
        }
        findViewById<MaterialButton>(R.id.setupTestButton).setOnClickListener { testConnection() }
        findViewById<MaterialButton>(R.id.setupPermissionButton).setOnClickListener {
            requestLocationPermissions()
        }
        findViewById<MaterialButton>(R.id.setupAdminButton).setOnClickListener { activateDeviceAdmin() }
        findViewById<MaterialButton>(R.id.setupToggleButton).setOnClickListener { toggleMonitoring() }
        findViewById<MaterialButton>(R.id.setupBatteryButton).setOnClickListener { requestBatteryExemption() }
        findViewById<MaterialButton>(R.id.setupSendStatusButton).setOnClickListener { sendStatusNow() }
        findViewById<MaterialButton>(R.id.setupNotifButton).setOnClickListener { toggleNotifForwarding() }

        updateStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        reviveMonitoringIfNeeded()
        updateStatus()
    }

    private fun reviveMonitoringIfNeeded() {
        try {
            if (prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && hasAllPermissions() && hasBackgroundLocation()) {
                val intent = Intent(this, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_START_MONITORING
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun clearCredentials() {
        try { stopService(Intent(this, MonitoringService::class.java)) } catch (_: Exception) { }
        try {
            botTokenInput.setText("")
            chatIdInput.setText("")
        } catch (_: Exception) {
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { prefs.botToken = "" } catch (_: Exception) { }
            try { prefs.chatId = "" } catch (_: Exception) { }
            try { prefs.ownerUserId = 0L } catch (_: Exception) { }
            try { prefs.credentialError = "" } catch (_: Exception) { }
            try { prefs.credentialErrorAt = 0L } catch (_: Exception) { }
            try { prefs.pendingSmsNumber = "" } catch (_: Exception) { }
            try { prefs.pendingSmsText = "" } catch (_: Exception) { }
            try { prefs.pendingSmsAt = 0L } catch (_: Exception) { }
            try { prefs.lastSmsSendAt = 0L } catch (_: Exception) { }
            try { prefs.commandsTokenHash = "" } catch (_: Exception) { }
            try { prefs.setLastUpdateIdSync(0L) } catch (_: Exception) { }
            try { prefs.wakeUpdateId = 0L } catch (_: Exception) { }
            try { prefs.lastSmsId = 0L } catch (_: Exception) { }
            try { prefs.lastCallTimestamp = 0L } catch (_: Exception) { }
            try { prefs.lastCallId = 0L } catch (_: Exception) { }
            try { prefs.lastSyncTime = 0L } catch (_: Exception) { }
            try { prefs.lastPhotoTime = 0L } catch (_: Exception) { }
            try { prefs.initialSyncDone = false } catch (_: Exception) { }
            try { prefs.initialSyncStarted = false } catch (_: Exception) { }
            try { prefs.clearWakePingIds() } catch (_: Exception) { }
            try { prefs.setMonitoringActive(false) } catch (_: Exception) { }
            try { com.redeye.parentalmonitor.data.MessageQueue.getInstance(this@SetupActivity).clearQueue() } catch (_: Exception) { }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                Toast.makeText(this@SetupActivity, getString(R.string.setup_credentials_cleared), Toast.LENGTH_SHORT).show()
                try {
                    updateStatus()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun saveSettings() {
        val rawToken = botTokenInput.text.toString().trim()
        val rawChat = chatIdInput.text.toString().trim()
        val intervalRaw = syncIntervalInput.text.toString().trim()
        val cameraRaw = cameraIntervalInput.text.toString().trim()
        val intervalParsed = intervalRaw.toIntOrNull()
        val cameraParsed = cameraRaw.toIntOrNull()
        if (intervalParsed == null || cameraParsed == null || intervalParsed !in 1..1440 || cameraParsed !in 0..60) {
            Toast.makeText(this, getString(R.string.setup_bad_interval), Toast.LENGTH_LONG).show()
            return
        }
        val interval = intervalParsed
        val cameraInterval = cameraParsed
        val mySave = saveSeq.incrementAndGet()
        try { saveJob?.cancel() } catch (_: Exception) { }
        saveJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val token = resolveStored(rawToken, try { prefs.botToken } catch (_: Exception) { "" })
            val chatId = resolveStored(rawChat, try { prefs.chatId } catch (_: Exception) { "" })
            if (token.isEmpty() || chatId.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!token.matches(TOKEN_REGEX)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_bad_token), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!isChatIdValid(chatId)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_bad_chat), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (mySave != saveSeq.get()) return@launch
            try {
                prefs.saveCoreConfig(token, chatId, interval, cameraInterval)
            } catch (_: Exception) {
            }
            if (mySave != saveSeq.get()) return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                try {
                    botTokenInput.setText(STORED_MASK)
                    chatIdInput.setText(STORED_MASK)
                } catch (_: Exception) {
                }
                try {
                    Toast.makeText(this@SetupActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                }
                try {
                    reviveMonitoringIfNeeded()
                } catch (_: Exception) {
                }
                try {
                    updateStatus()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun testConnection() {
        val rawToken = botTokenInput.text.toString().trim()
        val rawChat = chatIdInput.text.toString().trim()
        val probeSyncParsed = syncIntervalInput.text.toString().trim().toIntOrNull()
        val probeCameraParsed = cameraIntervalInput.text.toString().trim().toIntOrNull()
        if (probeSyncParsed == null || probeCameraParsed == null || probeSyncParsed !in 1..1440 || probeCameraParsed !in 0..60) {
            Toast.makeText(this, getString(R.string.setup_bad_interval), Toast.LENGTH_LONG).show()
            return
        }
        val probeSync = probeSyncParsed
        val probeCamera = probeCameraParsed
        val probeText = getString(R.string.setup_test_ok)
        Toast.makeText(this, getString(R.string.setup_testing), Toast.LENGTH_SHORT).show()
        val myTest = testSeq.incrementAndGet()
        try { testJob?.cancel() } catch (_: Exception) { }
        testJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val token = resolveStored(rawToken, try { prefs.botToken } catch (_: Exception) { "" })
            val chatId = resolveStored(rawChat, try { prefs.chatId } catch (_: Exception) { "" })
            if (token.isEmpty() || chatId.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!token.matches(TOKEN_REGEX) || !isChatIdValid(chatId)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_bad_token), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            try {
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val resp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = probeText))
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    if (myTest != testSeq.get()) return@launch
                    persistTestSettings(token, chatId, probeSync, probeCamera, myTest)
                    if (myTest != testSeq.get()) return@launch
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        botTokenInput.setText(STORED_MASK)
                        chatIdInput.setText(STORED_MASK)
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_success), Toast.LENGTH_LONG).show()
                        if (myTest == testSeq.get()) reviveMonitoringIfNeeded()
                    }
                } else {
                    val code = resp.code()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, code), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val detail = redactToken(e.message)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, detail), Toast.LENGTH_LONG).show()
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                updateStatus()
            }
        }
    }

    private fun persistTestSettings(token: String, chatId: String, syncInterval: Int, cameraInterval: Int, myTest: Int) {
        if (myTest != testSeq.get()) return
        prefs.saveCoreConfig(token, chatId, syncInterval, cameraInterval)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleMonitoring() {
        if (prefs.isMonitoringEnabled) {
            stopMonitoringConfirmed()
            return
        }
        if (!prefs.isConfigured()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAllPermissions()) {
            Toast.makeText(this, getString(R.string.grant_permissions), Toast.LENGTH_SHORT).show()
            requestLocationPermissions()
            return
        }
        if (!hasBackgroundLocation()) {
            Toast.makeText(this, getString(R.string.setup_bg_request), Toast.LENGTH_LONG).show()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    requestLocationPermissions()
                }
            } catch (_: Exception) {
            }
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_consent_title))
            .setMessage(getString(R.string.setup_consent_text))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> startMonitoringConfirmed() }
            .show()
    }

    private fun stopMonitoringConfirmed() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { prefs.setMonitoringActive(false) } catch (_: Exception) { }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                try {
                    stopService(Intent(this@SetupActivity, MonitoringService::class.java))
                    Toast.makeText(this@SetupActivity, getString(R.string.monitoring_inactive), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SetupActivity, getString(R.string.msg_service_failed), Toast.LENGTH_LONG).show()
                }
                updateStatus()
            }
        }
    }

    private fun startMonitoringConfirmed() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { prefs.setMonitoringActive(true) } catch (_: Exception) { }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                val intent = Intent(this@SetupActivity, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_START_MONITORING
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this@SetupActivity, getString(R.string.monitoring_active), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@SetupActivity, getString(R.string.msg_service_failed), Toast.LENGTH_LONG).show()
                }
                updateStatus()
            }
        }
    }

    private fun isBatteryExempt(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationAccessGranted(): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleNotifForwarding() {
        if (!isNotificationAccessGranted()) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val next = try { !prefs.notifForwardEnabled } catch (_: Exception) { true }
            try { prefs.notifForwardEnabled = next } catch (_: Exception) { }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                Toast.makeText(
                    this@SetupActivity,
                    getString(if (next) R.string.setup_notif_on else R.string.setup_notif_off),
                    Toast.LENGTH_SHORT
                ).show()
                updateStatus()
            }
        }
    }

    private fun requestBatteryExemption() {
        if (isBatteryExempt()) {
            Toast.makeText(this, getString(R.string.setup_battery_on), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendStatusNow() {
        if (!prefs.isConfigured()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val blockedErr = prefs.credentialError
            if (blockedErr.isNotEmpty()) {
                val nowAuth = System.currentTimeMillis()
                val errAt = prefs.credentialErrorAt
                if (nowAuth < errAt) {
                    prefs.credentialError = ""
                    prefs.credentialErrorAt = 0L
                } else if (nowAuth - errAt < 30 * 60_000L) {
                    Toast.makeText(this, getString(R.string.setup_test_fail, blockedErr), Toast.LENGTH_LONG).show()
                    return
                }
            }
        } catch (_: Exception) {
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = prefs.botToken
                val chatId = prefs.chatId
                val lastSync = prefs.lastSyncTime
                val text = buildString {
                    appendLine("📊 <b>Status</b> (direct)")
                    appendLine("Monitoring flag: ${if (prefs.isMonitoringEnabled) "ON" else "OFF"}")
                    appendLine("Last sync: ${if (lastSync > 0) formatStatusTime(lastSync) else getString(R.string.never)}")
                    appendLine("Last photo: ${if (prefs.lastPhotoTime > 0) formatStatusTime(prefs.lastPhotoTime) else getString(R.string.never)}")
                    appendLine("Battery restriction: ${if (isBatteryExempt()) "off" else "ON — tap Disable Battery Restriction"}")
                }
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val resp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = text))
                val ok = resp.isSuccessful && resp.body()?.ok == true
                if (ok) {
                    prefs.credentialError = ""
                    prefs.credentialErrorAt = 0L
                }
                val toastRes = if (ok) getString(R.string.setup_status_sent) else getString(R.string.setup_test_fail, resp.code())
                val toastLen = if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, toastRes, toastLen).show()
                    if (ok) reviveMonitoringIfNeeded()
                }
            } catch (e: Exception) {
                val failure = getString(R.string.setup_test_fail, redactToken(e.message))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun formatStatusTime(timestamp: Long): String {
        return try {
            com.redeye.parentalmonitor.utils.TimeFmt.full(timestamp)
        } catch (e: Exception) {
            timestamp.toString()
        }
    }

    private fun activateDeviceAdmin() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            Toast.makeText(this, getString(R.string.setup_admin_on), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val snap = try { prefs.snapshot() } catch (_: Exception) { emptyMap<String, Any?>() }
            fun s(key: String): String = snap[key] as? String ?: ""
            fun b(key: String, def: Boolean): Boolean = snap[key] as? Boolean ?: def
            val configured = s(PreferencesManager.KEY_BOT_TOKEN).isNotEmpty() && s(PreferencesManager.KEY_CHAT_ID).isNotEmpty()
            val perms = hasAllPermissions()
            val enabled = b(PreferencesManager.KEY_MONITORING_ENABLED, false)
            val running = enabled && MonitoringService.isRunning
            val paused = b(PreferencesManager.KEY_MONITORING_PAUSED, false)
            val bg = hasBackgroundLocation()
            val fg = hasForegroundLocation()
            val exempt = isBatteryExempt()
            val encrypted = try { prefs.isStorageEncrypted } catch (_: Exception) { false }
            val credErr = s(PreferencesManager.KEY_CRED_ERROR)
            val notifOn = b(PreferencesManager.KEY_NOTIF_FORWARD, true)
            val listener = isNotificationAccessGranted()
            val authLine = if (credErr.isNotEmpty()) "\nAuth: FAILED ($credErr) - check bot token" else ""
            val body = getString(
                R.string.setup_status_fmt,
                if (configured) "OK" else "-",
                if (perms) "OK" else "-",
                if (running && paused) getString(R.string.monitoring_paused) else if (running) getString(R.string.monitoring_active) else getString(R.string.monitoring_inactive)
            ) + "\nBattery: " + (if (exempt) "unrestricted" else "restricted") + "\nStorage: " + (if (encrypted) "encrypted" else "volatile (keystore unavailable)") + authLine + "\nNotifications: " + (if (listener && notifOn) "forwarding" else "off") + "\n" + getString(
            R.string.setup_location_fmt,
            if (fg) "OK" else "-",
            if (bg) "OK" else "-"
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
            statusText.text = body
            toggleButton.text = if (enabled) getString(R.string.disable_monitoring) else getString(R.string.enable_monitoring)
            toggleButton.isEnabled = configured || enabled
            permissionButton.text = when {
                !perms -> getString(R.string.grant_permissions)
                !bg -> getString(R.string.setup_bg_request)
                else -> getString(R.string.msg_permissions_granted)
            }
            notifButton.text = when {
                !listener -> getString(R.string.setup_notif)
                notifOn -> getString(R.string.setup_notif_on)
                else -> getString(R.string.setup_notif_off)
            }
            }
        }
    }
}
