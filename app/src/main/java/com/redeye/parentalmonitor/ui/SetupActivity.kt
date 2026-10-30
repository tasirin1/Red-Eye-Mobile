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
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.network.TelegramClient
import com.redeye.parentalmonitor.utils.AccessPin
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.Html
import com.redeye.parentalmonitor.utils.NetworkUtils
import com.redeye.parentalmonitor.network.TelegramMessage
import com.redeye.parentalmonitor.receiver.AdminReceiver
import com.redeye.parentalmonitor.service.MonitoringService
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.redeye.parentalmonitor.utils.ServiceStart

class SetupActivity : AppCompatActivity() {

    private val prefs: PreferencesManager
        get() = PreferencesManager.peekInstance() ?: PreferencesManager.getInstance(this)
    private lateinit var botTokenInput: TextInputEditText
    private lateinit var chatIdInput: TextInputEditText
    private lateinit var accessPinInput: TextInputEditText
    private lateinit var syncIntervalInput: TextInputEditText
    private lateinit var cameraIntervalInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var toggleButton: MaterialButton
    private lateinit var permissionButton: MaterialButton
    private lateinit var notifButton: MaterialButton
    private lateinit var shotButton: MaterialButton
    private lateinit var a11yButton: MaterialButton
    private var testJob: kotlinx.coroutines.Job? = null
    private val startGuard = Any()
    private var startingMonitoring = false
    private var lastNotifToggleAt = 0L
    private val testSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val statusSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private var statusJob: kotlinx.coroutines.Job? = null
    private val configMutex = kotlinx.coroutines.sync.Mutex()
    private val ioExceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> }
    private var pendingBackupBlob: String? = null
    private var pendingRestoreOwnerId = 0L
    private var pendingRestorePinExpected = false
    private var pendingRestoreAccessPin: String
        get() = RestorePinHolder.value
        set(value) {
            RestorePinHolder.value = value
        }
    private var fieldsLoaded = false
    private var loadedConfigured = false
    private var loadedSyncInterval = com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL
    private var loadedCameraInterval = 1

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val blob = pendingBackupBlob
        pendingBackupBlob = null
        if (uri == null) return@registerForActivityResult
        if (blob == null) {
            Toast.makeText(this, getString(R.string.backup_fail), Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val ok = try {
                val out = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("openOutputStream null")
                out.use { it.write(blob.toByteArray(Charsets.UTF_8)) }
                true
            } catch (_: Exception) {
                false
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                Toast.makeText(this@SetupActivity, getString(if (ok) R.string.backup_done else R.string.backup_fail), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val text = readBackupText(uri)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SetupActivity, getString(R.string.restore_bad), Toast.LENGTH_LONG).show()
                    return@withContext
                }
                if (!com.redeye.parentalmonitor.utils.SettingsBackup.looksStructurallyValid(text)) {
                    Toast.makeText(this@SetupActivity, getString(R.string.restore_bad), Toast.LENGTH_LONG).show()
                    return@withContext
                }
                askPin(getString(R.string.restore_pin_title), false) { pin ->
                    if (pin == null) return@askPin
                    lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
                        val data = try {
                            com.redeye.parentalmonitor.utils.SettingsBackup.import(text, pin)
                        } catch (_: Exception) {
                            null
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (data == null) {
                                Toast.makeText(this@SetupActivity, getString(R.string.restore_bad), Toast.LENGTH_LONG).show()
                                return@withContext
                            }
                            try {
                                botTokenInput.setText(data.token)
                                chatIdInput.setText(data.chatId)
                                syncIntervalInput.setText(data.syncInterval.toString())
                                cameraIntervalInput.setText(data.cameraInterval.toString())
                                pendingRestoreOwnerId = data.ownerId
                                pendingRestoreAccessPin = data.accessPin
                                pendingRestorePinExpected = data.accessPin.isNotEmpty()
                                if (data.accessPin.isNotEmpty()) accessPinInput.setText(STORED_MASK)
                            } catch (_: Exception) {
                            }
                            Toast.makeText(this@SetupActivity, getString(R.string.restore_done), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private val requiredPermissions: Array<String>
        get() = com.redeye.parentalmonitor.utils.AppPermissions.requiredPermissions

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        try {
            if (result.entries.any { !it.value && it.key != Manifest.permission.POST_NOTIFICATIONS && !com.redeye.parentalmonitor.utils.AppPermissions.isOptional(it.key) && !shouldShowRequestPermissionRationale(it.key) }) {
                Toast.makeText(this, getString(R.string.setup_open_settings), Toast.LENGTH_LONG).show()
                openAppSettings()
            }
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if ((fine || coarse) && !hasBackgroundLocation()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Toast.makeText(this, getString(R.string.setup_bg_request), Toast.LENGTH_LONG).show()
                    openAppSettings()
                } else {
                    try {
                        backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        updateStatus()
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        try {
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Toast.makeText(this, getString(R.string.setup_open_settings), Toast.LENGTH_LONG).show()
                openAppSettings()
            }
        } catch (_: Exception) {
        }
        updateStatus()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        try {
            updateStatus()
        } catch (_: Exception) {
        }
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            Toast.makeText(this, getString(R.string.setup_admin_ok), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.setup_admin_denied), Toast.LENGTH_SHORT).show()
        }
        updateStatus()
    }

    private val shotRequestBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        try { shotRequestBusy.set(false) } catch (_: Exception) { }
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            try { com.redeye.parentalmonitor.utils.ScreenCapture.grant(res.resultCode, data) } catch (_: Exception) { }
            Toast.makeText(this, getString(R.string.setup_shot_ok), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.setup_shot_denied), Toast.LENGTH_SHORT).show()
        }
        try { updateStatus() } catch (_: Exception) { }
    }

    companion object {
        private val persistScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        @Volatile
        private var saveJob: kotlinx.coroutines.Job? = null
        private val saveSeq = java.util.concurrent.atomic.AtomicInteger(0)
        private const val STORED_MASK = "••••••••"
        private val TOKEN_REGEX = Regex("^[0-9]+:[A-Za-z0-9_-]{35,}$")
        private val CHAT_ID_REGEX = Regex("^-?[0-9]+$")
        private val USERNAME_REGEX = Regex("^@[A-Za-z0-9_]{5,32}$")
        private const val MIN_BACKUP_PIN = 10
        private const val MIN_RESTORE_PIN = 4
        private const val MAX_BACKUP_BYTES = 1_000_000L
        private const val STATE_RESTORE_OWNER = "state_restore_owner"
        private const val STATE_RESTORE_PIN = "state_restore_pin"
        private const val STATE_BACKUP_BLOB = "state_backup_blob"

        private object RestorePinHolder {
            @Volatile
            var value: String = ""
        }
    }

    private fun isChatIdValid(chatId: String): Boolean {
        if (chatId.matches(USERNAME_REGEX)) return true
        if (!chatId.matches(CHAT_ID_REGEX)) return false
        val v = chatId.toLongOrNull() ?: return false
        if (v == 0L) return false
        return chatId.trimStart('-').length >= 4
    }

    private fun resolveStored(raw: String, stored: String): String {
        return if (raw == STORED_MASK) stored else raw
    }

    private data class CredentialInput(
        val token: String,
        val chatId: String,
        val storedToken: String,
        val storedChatId: String
    )

    private suspend fun resolveCredentialInput(rawToken: String, rawChat: String): CredentialInput? {
        val storedToken = try { prefs.botToken } catch (_: Exception) { "" }
        val storedChatId = try { prefs.chatId } catch (_: Exception) { "" }
        val token = resolveStored(rawToken, storedToken)
        val chatId = resolveStored(rawChat, storedChatId)
        if (token.isEmpty() || chatId.isEmpty()) {
            showToast(R.string.setup_fill_all, Toast.LENGTH_SHORT)
            return null
        }
        if (!token.matches(TOKEN_REGEX)) {
            showToast(R.string.setup_bad_token, Toast.LENGTH_SHORT)
            return null
        }
        if (!isChatIdValid(chatId)) {
            showToast(R.string.setup_bad_chat, Toast.LENGTH_SHORT)
            return null
        }
        return CredentialInput(token, chatId, storedToken, storedChatId)
    }

    private suspend fun showToast(resId: Int, duration: Int) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (isFinishing || isDestroyed) return@withContext
            Toast.makeText(this@SetupActivity, getString(resId), duration).show()
        }
    }

    private sealed class VerifyOutcome {
        data class Success(val effectiveChatId: String) : VerifyOutcome()
        data class HttpError(val code: Int, val fromGetMe: Boolean) : VerifyOutcome()
        data class Error(val detail: String) : VerifyOutcome()
        object Stale : VerifyOutcome()
    }

    private suspend fun verifyCredentials(token: String, chatId: String, isStale: () -> Boolean): VerifyOutcome {
        return try {
            val meResp = TelegramClient.api.getMe("https://api.telegram.org/bot" + token + "/getMe")
            if (isStale()) return VerifyOutcome.Stale
            if (!meResp.isSuccessful || meResp.body()?.ok != true) {
                return VerifyOutcome.HttpError(meResp.code(), true)
            }
            val enc = try { java.net.URLEncoder.encode(chatId, "UTF-8") } catch (_: Exception) { chatId }
            val chatResp = TelegramClient.api.getChat("https://api.telegram.org/bot" + token + "/getChat?chat_id=" + enc)
            if (isStale()) return VerifyOutcome.Stale
            if (!chatResp.isSuccessful || chatResp.body()?.ok != true) {
                return VerifyOutcome.HttpError(chatResp.code(), false)
            }
            var effectiveChat = chatId
            if (effectiveChat.startsWith("@")) {
                val resolved = try { chatResp.body()?.result?.asJsonObject?.get("id")?.asLong?.toString().orEmpty() } catch (_: Exception) { "" }
                if (resolved.isNotEmpty() && resolved != "0") effectiveChat = resolved
            }
            VerifyOutcome.Success(effectiveChat)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VerifyOutcome.Error(redactToken(e.message))
        }
    }

    private suspend fun resolveChatId(token: String, chatId: String): String {
        if (!chatId.startsWith("@")) return chatId
        return try {
            val enc = try { java.net.URLEncoder.encode(chatId, "UTF-8") } catch (_: Exception) { chatId }
            val resp = TelegramClient.api.getChat("https://api.telegram.org/bot$token/getChat?chat_id=$enc")
            val id = try { resp.body()?.result?.asJsonObject?.get("id")?.asLong } catch (_: Exception) { null }
            if (resp.isSuccessful && resp.body()?.ok == true && id != null && id != 0L) id.toString() else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun redactToken(value: String?): String {
        val raw = value ?: return ""
        if (raw.isEmpty()) return ""
        val secrets = mutableSetOf<String>()
        try {
            prefs.botToken.takeIf { it.isNotEmpty() }?.let { secrets.add(it) }
        } catch (_: Exception) {
        }
        try {
            prefs.chatId.takeIf { it.isNotEmpty() }?.let { secrets.add(it) }
        } catch (_: Exception) {
        }
        try {
            prefs.accessPin.takeIf { it.isNotEmpty() }?.let { secrets.add(it) }
        } catch (_: Exception) {
        }
        if (::botTokenInput.isInitialized) {
            val typed = botTokenInput.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty() && typed != STORED_MASK) secrets.add(typed)
        }
        if (::chatIdInput.isInitialized) {
            val typedChat = chatIdInput.text?.toString()?.trim().orEmpty()
            if (typedChat.isNotEmpty() && typedChat != STORED_MASK) secrets.add(typedChat)
        }
        if (::accessPinInput.isInitialized) {
            val typedPin = accessPinInput.text?.toString()?.trim().orEmpty()
            if (typedPin.isNotEmpty() && typedPin != STORED_MASK) secrets.add(typedPin)
        }
        return com.redeye.parentalmonitor.utils.Redact.token(raw, *secrets.toTypedArray())
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
            requestBackgroundLocation()
            return
        }
        Toast.makeText(this, getString(R.string.setup_bg_granted), Toast.LENGTH_SHORT).show()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.setup_bg_request), Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }
        try {
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } catch (_: Exception) {
            openAppSettings()
        }
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_setup)

        pendingRestoreOwnerId = savedInstanceState?.getLong(STATE_RESTORE_OWNER, 0L) ?: 0L
        pendingRestorePinExpected = savedInstanceState?.getBoolean(STATE_RESTORE_PIN, false) ?: false
        pendingBackupBlob = savedInstanceState?.getString(STATE_BACKUP_BLOB)
        if (savedInstanceState == null) {
            pendingRestoreAccessPin = ""
        }

        try {
            supportActionBar?.hide()
        } catch (_: Exception) {
        }

        botTokenInput = findViewById(R.id.setupBotTokenInput)
        chatIdInput = findViewById(R.id.setupChatIdInput)
        accessPinInput = findViewById(R.id.setupAccessPinInput)
        syncIntervalInput = findViewById(R.id.setupSyncIntervalInput)
        cameraIntervalInput = findViewById(R.id.setupCameraIntervalInput)
        statusText = findViewById(R.id.setupStatusText)
        toggleButton = findViewById(R.id.setupToggleButton)
        permissionButton = findViewById(R.id.setupPermissionButton)
        notifButton = findViewById(R.id.setupNotifButton)
        shotButton = findViewById(R.id.setupShotButton)
        a11yButton = findViewById(R.id.setupA11yButton)

        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            try {
                PreferencesManager.refreshInstance(this@SetupActivity)
            } catch (_: Exception) {
            }
            try {
                PreferencesManager.getInstance(this@SetupActivity)
            } catch (_: Exception) {
            }
            val tokenMasked = try { prefs.botToken.isNotEmpty() } catch (_: Exception) { false }
            val chatMasked = try { prefs.chatId.isNotEmpty() } catch (_: Exception) { false }
            val accessPinMasked = try { prefs.accessPin.isNotEmpty() } catch (_: Exception) { false }
            val syncVal = try { prefs.syncInterval.toString() } catch (_: Exception) { com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL.toString() }
            val camVal = try { prefs.cameraInterval.toString() } catch (_: Exception) { "1" }
            val configuredNow = try { prefs.isConfigured() } catch (_: Exception) { false }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loadedConfigured = configuredNow
                loadedSyncInterval = syncVal.toIntOrNull() ?: com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL
                loadedCameraInterval = camVal.toIntOrNull() ?: 1
                try {
                    if (botTokenInput.text.toString().isEmpty() && tokenMasked) botTokenInput.setText(STORED_MASK)
                    if (chatIdInput.text.toString().isEmpty() && chatMasked) chatIdInput.setText(STORED_MASK)
                    if (accessPinInput.text.toString().isEmpty() && accessPinMasked) accessPinInput.setText(STORED_MASK)
                    if (syncIntervalInput.text.toString().isEmpty()) syncIntervalInput.setText(syncVal)
                    if (cameraIntervalInput.text.toString().isEmpty()) cameraIntervalInput.setText(camVal)
                } catch (_: Exception) {
                }
                fieldsLoaded = true
                try {
                    updateStatus()
                } catch (_: Exception) {
                }
            }
        }

        val saveButton = findViewById<MaterialButton>(R.id.setupSaveButton)
        saveButton.setOnClickListener { saveSettings() }
        saveButton.setOnLongClickListener {
            askClearCredentials()
            true
        }
        findViewById<MaterialButton>(R.id.setupTestButton).setOnClickListener { testConnection() }
        findViewById<MaterialButton>(R.id.setupBackupButton).setOnClickListener { backupSettings() }
        findViewById<MaterialButton>(R.id.setupRestoreButton).setOnClickListener {
            try {
                restoreLauncher.launch(arrayOf("application/octet-stream", "*/*"))
            } catch (_: Exception) {
            }
        }
        findViewById<MaterialButton>(R.id.setupHealthButton).setOnClickListener { showHealthDialog() }
        findViewById<MaterialButton>(R.id.setupPairLinkButton).setOnClickListener { copyPairingLink() }
        findViewById<MaterialButton>(R.id.setupRestoreCodeButton).setOnClickListener { showRestoreCodeDialog() }
        permissionButton.setOnClickListener {
            requestLocationPermissions()
        }
        findViewById<MaterialButton>(R.id.setupAdminButton).setOnClickListener { activateDeviceAdmin() }
        toggleButton.setOnClickListener { toggleMonitoring() }
        findViewById<MaterialButton>(R.id.setupBatteryButton).setOnClickListener { requestBatteryExemption() }
        findViewById<MaterialButton>(R.id.setupAntiKillButton).setOnClickListener { showAntiKillWizard() }
        findViewById<MaterialButton>(R.id.setupSelfCheckButton).setOnClickListener { startSelfCheck() }
        findViewById<MaterialButton>(R.id.setupUsageButton).setOnClickListener { com.redeye.parentalmonitor.utils.UsageAccess.openSettings(this) }
        findViewById<MaterialButton>(R.id.setupSendStatusButton).setOnClickListener { sendStatusNow() }
        notifButton.setOnClickListener { toggleNotifForwarding() }
        shotButton.setOnClickListener { requestScreenCapture() }
        a11yButton.setOnClickListener { openAccessibilitySettings() }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val meta = getSharedPreferences("setup_meta", MODE_PRIVATE)
                if (!meta.getBoolean("notif_asked", false)) {
                    try { meta.edit().putBoolean("notif_asked", true).apply() } catch (_: Exception) { }
                    try { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        reviveMonitoringIfNeeded()
        updateStatus()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_RESTORE_OWNER, pendingRestoreOwnerId)
        outState.putBoolean(STATE_RESTORE_PIN, pendingRestorePinExpected)
        outState.putString(STATE_BACKUP_BLOB, pendingBackupBlob)
    }

    private fun reviveMonitoringIfNeeded() {
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val shouldRevive = try {
                prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && hasAllPermissions() && hasBackgroundLocation()
            } catch (_: Exception) {
                false
            }
            if (!shouldRevive) return@launch
            try {
                val intent = Intent(this@SetupActivity, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_START_MONITORING
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        startForegroundService(intent)
                    } catch (e: Exception) {
                        try { com.redeye.parentalmonitor.utils.MessageScheduler.scheduleBootRestart(this@SetupActivity) } catch (_: Exception) { }
                        throw e
                    }
                } else {
                    startService(intent)
                }
            } catch (_: Exception) {
                android.util.Log.w("SetupActivity", "Revive monitoring failed")
            }
        }
    }

    private fun askClearCredentials() {
        val storedPin = try { prefs.accessPin } catch (_: Exception) { "" }
        if (storedPin.isEmpty()) {
            confirmClearCredentials()
            return
        }
        askPin(getString(R.string.setup_clear_title), false) { pin ->
            if (pin == null) return@askPin
            if (pin != storedPin) {
                Toast.makeText(this, getString(R.string.setup_bad_access_pin), Toast.LENGTH_LONG).show()
                return@askPin
            }
            confirmClearCredentials()
        }
    }

    private fun confirmClearCredentials() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_clear_title))
            .setMessage(getString(R.string.setup_clear_text))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> clearCredentials() }
            .show()
    }

    private fun clearCredentials() {
        try { saveSeq.incrementAndGet() } catch (_: Exception) { }
        try { testSeq.incrementAndGet() } catch (_: Exception) { }
        try { saveJob?.cancel() } catch (_: Exception) { }
        try { testJob?.cancel() } catch (_: Exception) { }
        try { stopService(Intent(this, MonitoringService::class.java)) } catch (_: Exception) { }
        try { MessageScheduler.cancelMessageSend(this) } catch (_: Exception) { }
        try { MessageScheduler.cancelWatchdog(this) } catch (_: Exception) { }
        try { MessageScheduler.cancelBootRestart(this) } catch (_: Exception) { }
        try { com.redeye.parentalmonitor.utils.Liveness.cancel(this) } catch (_: Exception) { }
        try {
            val admin = ComponentName(this, AdminReceiver::class.java)
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(admin)) dpm.removeActiveAdmin(admin)
        } catch (_: Exception) {
        }
        try {
            botTokenInput.setText("")
            chatIdInput.setText("")
            accessPinInput.setText("")
            syncIntervalInput.setText(com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL.toString())
            cameraIntervalInput.setText("1")
            pendingRestoreOwnerId = 0L
            pendingRestoreAccessPin = ""
            pendingRestorePinExpected = false
        } catch (_: Exception) {
        }
        persistScope.launch(ioExceptionHandler) {
            val oldOwner = try { prefs.ownerUserId } catch (_: Exception) { 0L }
            val oldToken = try { prefs.botToken } catch (_: Exception) { "" }
            try { prefs.botToken = "" } catch (_: Exception) { }
            try { prefs.chatId = "" } catch (_: Exception) { }
            try { prefs.ownerUserId = 0L } catch (_: Exception) { }
            try { prefs.setLastUpdateIdSync(0L) } catch (_: Exception) { }
            if (oldOwner != 0L && oldToken.isNotEmpty()) {
                try {
                    com.redeye.parentalmonitor.network.TelegramClient.api.deleteMyCommands(
                        "https://api.telegram.org/bot$oldToken/deleteMyCommands",
                        com.redeye.parentalmonitor.network.DeleteCommandsRequest(
                            com.redeye.parentalmonitor.network.BotCommandScope("chat", oldOwner)
                        )
                    )
                } catch (_: Exception) {
                }
            }
            if (oldToken.isNotEmpty()) {
                try {
                    com.redeye.parentalmonitor.network.TelegramClient.api.deleteMyCommands(
                        "https://api.telegram.org/bot$oldToken/deleteMyCommands",
                        com.redeye.parentalmonitor.network.DeleteCommandsRequest(null)
                    )
                } catch (_: Exception) {
                }
            }
            try { prefs.setCredentialError("", 0L) } catch (_: Exception) { }
            try { prefs.pendingSmsNumber = "" } catch (_: Exception) { }
            try { prefs.pendingSmsText = "" } catch (_: Exception) { }
            try { prefs.pendingSmsAt = 0L } catch (_: Exception) { }
            try { prefs.pendingSmsOwner = "" } catch (_: Exception) { }
            try { prefs.pendingPromptCmd = "" } catch (_: Exception) { }
            try { prefs.pendingPromptAt = 0L } catch (_: Exception) { }
            try { prefs.clearRingStateSync() } catch (_: Exception) { }
            try { prefs.lastSmsSendAt = 0L } catch (_: Exception) { }
            try { prefs.commandsTokenHash = "" } catch (_: Exception) { }
            try { prefs.ownerPairCode = "" } catch (_: Exception) { }
            try { prefs.accessPin = "" } catch (_: Exception) { }
            try { prefs.syncInterval = com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL } catch (_: Exception) { }
            try { prefs.cameraInterval = 1 } catch (_: Exception) { }
            try { prefs.patrolInterval = 30 } catch (_: Exception) { }
            try {
                cacheDir?.listFiles { f ->
                    f.isFile && (f.name.startsWith("camera_") || f.name.startsWith("video_") || f.name.startsWith("audio_") || f.name.startsWith("screenshot_"))
                }?.forEach {
                    try { it.delete() } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            }
            try { prefs.setSmsCursorSync(0L) } catch (_: Exception) { }
            try { prefs.setCallCursorSync(0L, 0L) } catch (_: Exception) { }
            try { prefs.lastSyncTime = 0L } catch (_: Exception) { }
            try { prefs.lastPhotoTime = 0L } catch (_: Exception) { }
            try { prefs.initialSyncDone = false } catch (_: Exception) { }
            try { com.redeye.parentalmonitor.utils.ScreenCapture.revoke() } catch (_: Exception) { }
            try { prefs.pendingMsgDrops = 0 } catch (_: Exception) { }
            try { prefs.pendingNotifDrops = 0 } catch (_: Exception) { }
            try { prefs.monitoringPaused = false } catch (_: Exception) { }
            try { prefs.photoPausedUntil = 0L } catch (_: Exception) { }
            try { prefs.patrolEnabled = false } catch (_: Exception) { }
            try { prefs.setMonitoringActive(false) } catch (_: Exception) { }
            try { prefs.setLastAliveMarker(0L, 0L) } catch (_: Exception) { }
            val queueCleared = try { com.redeye.parentalmonitor.data.MessageQueue.getInstance(this@SetupActivity).clearQueue() } catch (_: Exception) { false }
            try { com.redeye.parentalmonitor.service.NotificationForwarderService.clearHistory() } catch (_: Exception) { }
            try { synchronized(prefs.prefsRmwLock) { prefs.trail24 = "" } } catch (_: Exception) { }
            try { prefs.homeLatE6 = Long.MIN_VALUE } catch (_: Exception) { }
            try { prefs.homeLonE6 = Long.MIN_VALUE } catch (_: Exception) { }
            try { prefs.homeRadiusM = 0 } catch (_: Exception) { }
            try { prefs.homeState = 0 } catch (_: Exception) { }
            try { prefs.schoolLatE6 = Long.MIN_VALUE } catch (_: Exception) { }
            try { prefs.schoolLonE6 = Long.MIN_VALUE } catch (_: Exception) { }
            try { prefs.schoolRadiusM = 0 } catch (_: Exception) { }
            try { prefs.schoolState = 0 } catch (_: Exception) { }
            try { prefs.schoolAlertDate = "" } catch (_: Exception) { }
            try { prefs.clearAuditLog() } catch (_: Exception) { }
            try { prefs.watchKeywords = "" } catch (_: Exception) { }
            try { prefs.watchNumbers = "" } catch (_: Exception) { }
            var storageReset = false
            val wasVolatile = try { !prefs.isStorageEncrypted } catch (_: Exception) { false }
            if (wasVolatile) {
                val outcome = try {
                    PreferencesManager.resetEncryptedStorage(this@SetupActivity)
                } catch (_: Exception) {
                    PreferencesManager.StorageRecovery.FAILED
                }
                storageReset = outcome == PreferencesManager.StorageRecovery.RESET
                if (storageReset) {
                    try { PreferencesManager.getInstance(this@SetupActivity) } catch (_: Exception) { }
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loadedConfigured = false
                loadedSyncInterval = com.redeye.parentalmonitor.BuildConfig.SYNC_INTERVAL
                loadedCameraInterval = 1
                val messageRes = when {
                    storageReset -> R.string.setup_storage_recovered
                    !queueCleared -> R.string.setup_clear_queue_failed
                    wasVolatile -> R.string.setup_storage_reset_failed
                    else -> R.string.setup_credentials_cleared
                }
                Toast.makeText(this@SetupActivity, getString(messageRes), Toast.LENGTH_SHORT).show()
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
        val rawAccessPin = accessPinInput.text.toString().trim()
        val intervalRaw = syncIntervalInput.text.toString().trim()
        val cameraRaw = cameraIntervalInput.text.toString().trim()
        val intervalParsed = intervalRaw.toIntOrNull()
        val cameraParsed = cameraRaw.toIntOrNull()
        if ((intervalRaw.isNotEmpty() && (intervalParsed == null || intervalParsed !in 1..1440)) ||
            (cameraRaw.isNotEmpty() && (cameraParsed == null || cameraParsed !in 0..60))) {
            Toast.makeText(this, getString(R.string.setup_bad_interval), Toast.LENGTH_LONG).show()
            return
        }
        val interval = intervalParsed ?: loadedSyncInterval
        val cameraInterval = cameraParsed ?: loadedCameraInterval
        val accessPinCheck = if (rawAccessPin == STORED_MASK) "" else rawAccessPin
        if (accessPinCheck.isNotEmpty() && !AccessPin.isValid(accessPinCheck)) {
            Toast.makeText(this, getString(R.string.setup_bad_access_pin), Toast.LENGTH_LONG).show()
            return
        }
        if (rawAccessPin == STORED_MASK && pendingRestorePinExpected && pendingRestoreAccessPin.isEmpty()) {
            Toast.makeText(this, getString(R.string.setup_restore_pin_lost), Toast.LENGTH_LONG).show()
            return
        }
        val hasStored = loadedConfigured
        if (!fieldsLoaded) {
            Toast.makeText(this, getString(R.string.setup_loading), Toast.LENGTH_SHORT).show()
            return
        }
        if ((rawToken.isEmpty() || rawChat.isEmpty()) && hasStored) {
            Toast.makeText(this, getString(R.string.setup_clear_hint), Toast.LENGTH_LONG).show()
            return
        }
        val mySave = saveSeq.incrementAndGet()
        try { testSeq.incrementAndGet() } catch (_: Exception) { }
        try { saveJob?.cancel() } catch (_: Exception) { }
        try { testJob?.cancel() } catch (_: Exception) { }
        saveJob = persistScope.launch(ioExceptionHandler) {
            val input = resolveCredentialInput(rawToken, rawChat) ?: return@launch
            val currentToken = input.storedToken
            val storedChatId = input.storedChatId
            val token = input.token
            var chatId = input.chatId
            val storedAccessPin = try { prefs.accessPin } catch (_: Exception) { "" }
            val accessPin = if (rawAccessPin == STORED_MASK) pendingRestoreAccessPin.ifEmpty { storedAccessPin } else rawAccessPin
            if (chatId.startsWith("@")) {
                val resolved = resolveChatId(token, chatId)
                if (resolved.isEmpty()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_bad_chat), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } else {
                    chatId = resolved
                }
            }
            if (mySave != saveSeq.get()) return@launch
            var verified = true
            var verifyDetail = ""
            when (val outcome = verifyCredentials(token, chatId) { mySave != saveSeq.get() }) {
                is VerifyOutcome.Stale -> return@launch
                is VerifyOutcome.HttpError -> {
                    markCredentialErrorIfStored(outcome.code, token, chatId, currentToken, storedChatId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_save_failed, outcome.code), Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                is VerifyOutcome.Error -> {
                    verified = false
                    verifyDetail = outcome.detail
                }
                is VerifyOutcome.Success -> Unit
            }
            if (mySave != saveSeq.get()) return@launch
            if (!verified) {
                val storedToken = try { prefs.botToken } catch (_: Exception) { "" }
                val storedChat = try { prefs.chatId } catch (_: Exception) { "" }
                if (storedToken.isNotEmpty() && token == storedToken && chatId == storedChat) {
                    val offSaved: Boolean? = configMutex.withLock {
                        if (mySave != saveSeq.get()) return@withLock null
                        try {
                            prefs.saveCredentials(token, chatId, interval, cameraInterval, if (pendingRestoreOwnerId > 0L) pendingRestoreOwnerId else null)
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (offSaved == true) {
                        try { prefs.accessPin = accessPin } catch (_: Exception) { }
                        loadedConfigured = true
                        loadedSyncInterval = interval
                        loadedCameraInterval = cameraInterval
                        clearPendingRestore()
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (mySave != saveSeq.get()) return@withContext
                        Toast.makeText(this@SetupActivity, getString(if (offSaved == true) R.string.setup_saved_offline else R.string.setup_save_failed, verifyDetail.ifEmpty { getString(R.string.setup_no_network) }), Toast.LENGTH_LONG).show()
                        try { updateStatus() } catch (_: Exception) { }
                    }
                    return@launch
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_save_failed, verifyDetail.ifEmpty { getString(R.string.setup_no_network) }), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val persistOutcome: Boolean? = configMutex.withLock {
                if (mySave != saveSeq.get()) return@withLock null
                try {
                    prefs.saveCredentials(token, chatId, interval, cameraInterval, if (pendingRestoreOwnerId > 0L) pendingRestoreOwnerId else null)
                } catch (_: Exception) {
                    false
                }
            }
            if (persistOutcome == null) return@launch
            val persisted = persistOutcome
            if (persisted) {
                try { prefs.accessPin = accessPin } catch (_: Exception) { }
                loadedConfigured = true
                loadedSyncInterval = interval
                loadedCameraInterval = cameraInterval
                clearPendingRestore()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (mySave != saveSeq.get()) return@withContext
                if (!persisted) {
                    val storageOk = try { prefs.isStorageEncrypted } catch (_: Exception) { true }
                    val msg = if (storageOk) getString(R.string.setup_save_failed_keystore) else getString(R.string.setup_save_no_storage)
                    Toast.makeText(this@SetupActivity, msg, Toast.LENGTH_LONG).show()
                    return@withContext
                }
                try {
                    botTokenInput.setText(STORED_MASK)
                    chatIdInput.setText(STORED_MASK)
                    accessPinInput.setText(if (accessPin.isNotEmpty()) STORED_MASK else "")
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

    private fun clearPendingRestore() {
        pendingRestoreOwnerId = 0L
        pendingRestoreAccessPin = ""
        pendingRestorePinExpected = false
    }

    private fun showHealthDialog() {
        val body = statusText.text?.toString().orEmpty().ifBlank { getString(R.string.setup_loading) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_health_title))
            .setMessage(body + "\n\n" + getString(R.string.setup_health_footer))
            .setPositiveButton(getString(R.string.grant_permissions)) { _, _ ->
                try { permissionButton.performClick() } catch (_: Exception) { }
            }
            .setNeutralButton(getString(R.string.setup_antikill)) { _, _ ->
                try { showAntiKillWizard() } catch (_: Exception) { }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, value: String) {
        try {
            val manager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        } catch (_: Exception) {
        }
    }

    private fun copyPairingLink() {
        val rawToken = botTokenInput.text?.toString()?.trim().orEmpty()
        val rawChat = chatIdInput.text?.toString()?.trim().orEmpty()
        val rawAccessPin = accessPinInput.text?.toString()?.trim().orEmpty()
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            if (!fieldsLoaded) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_loading), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val token = if (rawToken == STORED_MASK) {
                try { prefs.botToken } catch (_: Exception) { "" }
            } else rawToken
            val chat = if (rawChat == STORED_MASK) {
                try { prefs.chatId } catch (_: Exception) { "" }
            } else rawChat
            val accessPin = if (rawAccessPin == STORED_MASK) {
                try { prefs.accessPin } catch (_: Exception) { "" }
            } else rawAccessPin
            if (accessPin.isNotEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_pair_link_pin_active), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val chatNumber = chat.toLongOrNull()
            if (!token.matches(TOKEN_REGEX) || chatNumber == null || chatNumber <= 0L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_pair_link_fail), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val username = try {
                val resp = TelegramClient.api.getMe("https://api.telegram.org/bot$token/getMe")
                if (!resp.isSuccessful || resp.body()?.ok != true) ""
                else resp.body()?.result?.asJsonObject?.get("username")?.asString.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val code = try { prefs.ensureOwnerPairCode() } catch (_: Exception) { "" }
            if (username.isEmpty() || code.isEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_pair_link_fail), Toast.LENGTH_LONG).show()
                }
            } else {
                val link = "https://t.me/$username?start=${chatNumber}_$code"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    copyToClipboard("pairing", link)
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_pair_link_copied), Toast.LENGTH_SHORT).show()
                    androidx.appcompat.app.AlertDialog.Builder(this@SetupActivity)
                        .setTitle(getString(R.string.setup_pair_link_title))
                        .setMessage(getString(R.string.setup_pair_link_body, link))
                        .setPositiveButton(getString(R.string.setup_pair_link_share)) { _, _ ->
                            try {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, link)
                                }
                                startActivity(Intent.createChooser(send, getString(R.string.setup_pair_link_share)))
                            } catch (_: Exception) {
                            }
                        }
                        .setNegativeButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun showRestoreCodeDialog() {
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val secret = try { prefs.ensureRestoreCodeSecret() } catch (_: Exception) { "" }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (secret.isEmpty()) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_restore_code_fail), Toast.LENGTH_LONG).show()
                } else {
                    showRestoreCodeDialogReady(secret)
                }
            }
        }
    }

    private fun showRestoreCodeDialogReady(secret: String) {
        val now = System.currentTimeMillis()
        val code = com.redeye.parentalmonitor.utils.RestoreCode.current(secret, now)
        val until = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(com.redeye.parentalmonitor.utils.RestoreCode.windowEnd(now)))
        val padH = (32 * resources.displayMetrics.density).toInt()
        val padV = (9 * resources.displayMetrics.density).toInt()
        val info = TextView(this).apply {
            text = getString(R.string.setup_restore_code_fmt, code, until) + "\n\n" + getString(R.string.setup_restore_code_need_setup)
            setPadding(padH, padV, padH, padV)
        }
        val toggle = android.widget.Switch(this).apply {
            isChecked = try { prefs.restoreStrict } catch (_: Exception) { false }
            text = getString(R.string.setup_restore_switch)
            setPadding(padH, padV, padH, padV)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(info)
            addView(toggle)
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            try { prefs.restoreStrict = checked } catch (_: Exception) { }
            Toast.makeText(
                this@SetupActivity,
                getString(if (checked) R.string.setup_restore_switch_on else R.string.setup_restore_switch_off),
                Toast.LENGTH_LONG
            ).show()
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_restore_code_title))
            .setView(box)
            .setPositiveButton(getString(R.string.setup_restore_copy)) { _, _ -> copyToClipboard("restore-code", code) }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun askPin(title: String, confirm: Boolean, done: (String?) -> Unit) {
        val minLen = if (confirm) MIN_BACKUP_PIN else MIN_RESTORE_PIN
        val first = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(if (confirm) R.string.backup_pin_hint else R.string.restore_pin_hint)
        }
        val second = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.backup_pin_hint2)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(first)
            if (confirm) addView(second)
        }
        var selesaiPin = false
        fun selesai(pin: String?) {
            if (selesaiPin) return
            selesaiPin = true
            done(pin)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel) { _, _ -> selesai(null) }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = first.text.toString()
                if (pin.length < minLen) {
                    Toast.makeText(this, getString(if (confirm) R.string.backup_pin_short else R.string.restore_pin_short), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (confirm && pin != second.text.toString()) {
                    Toast.makeText(this, getString(R.string.backup_pin_mismatch), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                selesai(pin)
            }
        }
        dialog.setOnCancelListener { selesai(null) }
        dialog.show()
    }

    private fun readBackupText(uri: android.net.Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (sb.length + n > MAX_BACKUP_BYTES) return null
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun backupSettings() {
        val uiToken = botTokenInput.text.toString().trim()
        val uiChat = chatIdInput.text.toString().trim()
        val uiInterval = syncIntervalInput.text.toString().trim().toIntOrNull()
        val uiCam = cameraIntervalInput.text.toString().trim().toIntOrNull()
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val storedToken = try { prefs.botToken } catch (_: Exception) { "" }
            val storedChat = try { prefs.chatId } catch (_: Exception) { "" }
            val token = resolveStored(uiToken, storedToken).takeIf { it.matches(TOKEN_REGEX) } ?: storedToken
            val resolvedChat = resolveStored(uiChat, storedChat)
            val chatId = if (isChatIdValid(resolvedChat)) resolvedChat else storedChat
            val interval = if (uiInterval != null && uiInterval in 1..1440) uiInterval else try { prefs.syncInterval } catch (_: Exception) { 5 }
            val camInterval = if (uiCam != null && uiCam in 0..60) uiCam else try { prefs.cameraInterval } catch (_: Exception) { 1 }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (token.isEmpty() || chatId.isEmpty()) {
                    Toast.makeText(this@SetupActivity, getString(R.string.backup_empty), Toast.LENGTH_LONG).show()
                    return@withContext
                }
                askPin(getString(R.string.backup_pin_title), true) { pin ->
                    if (pin == null) return@askPin
                    lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
                        val blob = try {
                            com.redeye.parentalmonitor.utils.SettingsBackup.export(
                                token, chatId, interval, camInterval, try { prefs.ownerUserId } catch (_: Exception) { 0L }, try { prefs.accessPin } catch (_: Exception) { "" }, pin
                            )
                        } catch (_: Exception) {
                            null
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (blob == null) {
                                Toast.makeText(this@SetupActivity, getString(R.string.backup_fail), Toast.LENGTH_LONG).show()
                                return@withContext
                            }
                            pendingBackupBlob = blob
                            try {
                                backupLauncher.launch("redeye-backup.redeyebak")
                            } catch (_: Exception) {
                                pendingBackupBlob = null
                            }
                        }
                    }
                }
            }
        }
    }

    private fun testConnection() {
        if (!fieldsLoaded) {
            Toast.makeText(this, getString(R.string.setup_loading), Toast.LENGTH_SHORT).show()
            return
        }
        val rawToken = botTokenInput.text.toString().trim()
        val rawChat = chatIdInput.text.toString().trim()
        val rawAccessPin = accessPinInput.text.toString().trim()
        val accessPinCheck = if (rawAccessPin == STORED_MASK) "" else rawAccessPin
        if (accessPinCheck.isNotEmpty() && !AccessPin.isValid(accessPinCheck)) {
            Toast.makeText(this, getString(R.string.setup_bad_access_pin), Toast.LENGTH_LONG).show()
            return
        }
        if (rawAccessPin == STORED_MASK && pendingRestorePinExpected && pendingRestoreAccessPin.isEmpty()) {
            Toast.makeText(this, getString(R.string.setup_restore_pin_lost), Toast.LENGTH_LONG).show()
            return
        }
        val intervalParsed = syncIntervalInput.text.toString().trim().toIntOrNull()
        val cameraParsed = cameraIntervalInput.text.toString().trim().toIntOrNull()
        val syncBlank = syncIntervalInput.text.toString().isBlank()
        val cameraBlank = cameraIntervalInput.text.toString().isBlank()
        if ((!syncBlank && (intervalParsed == null || intervalParsed !in 1..1440)) ||
            (!cameraBlank && (cameraParsed == null || cameraParsed !in 0..60))) {
            Toast.makeText(this, getString(R.string.setup_bad_interval), Toast.LENGTH_LONG).show()
            return
        }
        val syncInterval = intervalParsed ?: loadedSyncInterval
        val cameraInterval = cameraParsed ?: loadedCameraInterval
        Toast.makeText(this, getString(R.string.setup_testing), Toast.LENGTH_SHORT).show()
        val myTest = testSeq.incrementAndGet()
        try { testJob?.cancel() } catch (_: Exception) { }
        testJob = lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val input = resolveCredentialInput(rawToken, rawChat) ?: return@launch
            val storedToken = input.storedToken
            val storedChatId = input.storedChatId
            val token = input.token
            val chatId = input.chatId
            val storedAccessPin = try { prefs.accessPin } catch (_: Exception) { "" }
            val accessPin = if (rawAccessPin == STORED_MASK) pendingRestoreAccessPin.ifEmpty { storedAccessPin } else rawAccessPin
            when (val outcome = verifyCredentials(token, chatId) { myTest != testSeq.get() }) {
                is VerifyOutcome.Stale -> return@launch
                is VerifyOutcome.HttpError -> {
                    markCredentialErrorIfStored(outcome.code, token, chatId, storedToken, storedChatId)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, outcome.code), Toast.LENGTH_LONG).show()
                    }
                    if (outcome.fromGetMe) return@launch
                }
                is VerifyOutcome.Error -> {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, outcome.detail), Toast.LENGTH_LONG).show()
                    }
                }
                is VerifyOutcome.Success -> {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        if (myTest != testSeq.get()) return@withContext
                        confirmSaveAfterTest(token, outcome.effectiveChatId, myTest, syncInterval, cameraInterval, accessPin)
                    }
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                updateStatus()
            }
        }
    }

    private fun confirmSaveAfterTest(token: String, chatId: String, myTest: Int, syncInterval: Int, cameraInterval: Int, accessPin: String) {
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.save_settings))
                .setMessage(getString(R.string.setup_test_success))
                .setPositiveButton(getString(R.string.save_settings)) { _, _ ->
                    lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
                        val persisted = persistTestSettings(token, chatId, myTest, syncInterval, cameraInterval, accessPin)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (myTest != testSeq.get()) return@withContext
                            if (!persisted) {
                                val storageOk = try { prefs.isStorageEncrypted } catch (_: Exception) { true }
                                val msg = if (storageOk) getString(R.string.setup_save_failed_keystore) else getString(R.string.setup_save_no_storage)
                                Toast.makeText(this@SetupActivity, msg, Toast.LENGTH_LONG).show()
                                return@withContext
                            }
                            botTokenInput.setText(STORED_MASK)
                            chatIdInput.setText(STORED_MASK)
                            accessPinInput.setText(if (accessPin.isNotEmpty()) STORED_MASK else "")
                            Toast.makeText(this@SetupActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                            reviveMonitoringIfNeeded()
                            updateStatus()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    Toast.makeText(this, getString(R.string.setup_test_success), Toast.LENGTH_LONG).show()
                }
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.setup_test_success), Toast.LENGTH_LONG).show()
        }
    }

    private fun markCredentialErrorIfStored(code: Int, token: String, chatId: String, storedToken: String, storedChatId: String) {
        if (code != 401 && code != 403) return
        if (token != storedToken || chatId != storedChatId) return
        try {
            prefs.setCredentialErrorIfCredentialsMatch(token, chatId, code.toString(), System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    private suspend fun persistTestSettings(token: String, chatId: String, myTest: Int, syncInterval: Int, cameraInterval: Int, accessPin: String): Boolean {
        val outcome: Boolean? = configMutex.withLock {
            if (myTest != testSeq.get()) return@withLock null
            val saved = try { prefs.saveCredentials(token, chatId, syncInterval, cameraInterval, if (pendingRestoreOwnerId > 0L) pendingRestoreOwnerId else null) } catch (_: Exception) { false }
            if (saved) {
                try { prefs.accessPin = accessPin } catch (_: Exception) { }
                clearPendingRestore()
            }
            saved
        }
        return outcome ?: false
    }

    private fun hasAllPermissions(): Boolean {
        return com.redeye.parentalmonitor.utils.AppPermissions.hasAll(this)
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
            requestBackgroundLocation()
            return
        }
        try {
            if (!com.redeye.parentalmonitor.utils.AppPermissions.hasNotificationPermission(this)) {
                try { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.setup_consent_title))
            .setMessage(getString(R.string.setup_consent_text))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> startMonitoringConfirmed() }
            .show()
    }

    private fun stopMonitoringConfirmed() {
        try {
            if (!prefs.isMonitoringEnabled) {
                Toast.makeText(this, getString(R.string.monitoring_inactive), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Exception) {
        }
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            try { prefs.setMonitoringActive(false) } catch (_: Exception) { }
            try { MessageScheduler.cancelMessageSend(this@SetupActivity) } catch (_: Exception) { }
            try { MessageScheduler.cancelWatchdog(this@SetupActivity) } catch (_: Exception) { }
            try { MessageScheduler.cancelBootRestart(this@SetupActivity) } catch (_: Exception) { }
            try { com.redeye.parentalmonitor.utils.Liveness.cancel(this@SetupActivity) } catch (_: Exception) { }
            try { prefs.setLastAliveMarker(0L, 0L) } catch (_: Exception) { }
            try { stopService(Intent(this@SetupActivity, MonitoringService::class.java)) } catch (_: Exception) { }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                Toast.makeText(this@SetupActivity, getString(R.string.monitoring_inactive), Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }
    }

    private fun startMonitoringConfirmed() {
        try {
            if (prefs.isMonitoringEnabled) {
                Toast.makeText(this, getString(R.string.monitoring_active), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Exception) {
        }
        synchronized(startGuard) {
            if (startingMonitoring) return
            startingMonitoring = true
        }
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            try {
                try { MessageScheduler.scheduleWatchdog(this@SetupActivity) } catch (_: Exception) { }
                try { prefs.setMonitoringActive(true) } catch (_: Exception) { }
                try { com.redeye.parentalmonitor.utils.Liveness.schedule(this@SetupActivity) } catch (_: Exception) { }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    val intent = Intent(this@SetupActivity, MonitoringService::class.java).apply {
                        action = MonitoringService.ACTION_START_MONITORING
                    }
                    try {
                        ServiceStart.startForeground(this@SetupActivity, intent)
                        Toast.makeText(this@SetupActivity, getString(R.string.monitoring_active), Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {
                        Toast.makeText(this@SetupActivity, getString(R.string.msg_service_failed), Toast.LENGTH_LONG).show()
                        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
                            try { prefs.setMonitoringActive(false) } catch (_: Exception) { }
                            try { MessageScheduler.cancelWatchdog(this@SetupActivity) } catch (_: Exception) { }
                            try { com.redeye.parentalmonitor.utils.Liveness.cancel(this@SetupActivity) } catch (_: Exception) { }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (isFinishing || isDestroyed) return@withContext
                                try { updateStatus() } catch (_: Exception) { }
                            }
                        }
                    }
                    updateStatus()
                    try {
                        if (!isBatteryExempt() || !com.redeye.parentalmonitor.utils.AutoStart.canDrawOverlays(this@SetupActivity)) {
                            showAntiKillWizard()
                        }
                    } catch (_: Exception) {
                    }
                }
            } finally {
                synchronized(startGuard) { startingMonitoring = false }
            }
        }
    }

    private fun isBatteryExempt(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        return try {
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun toggleNotifForwarding() {
        val nowToggle = android.os.SystemClock.elapsedRealtime()
        if (nowToggle - lastNotifToggleAt < 2_000L) return
        lastNotifToggleAt = nowToggle
        if (!isNotificationAccessGranted()) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
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

    private fun startSelfCheck() {
        if (!prefs.isMonitoringEnabled || !MonitoringService.isRunning) {
            Toast.makeText(this, getString(R.string.selfcheck_off), Toast.LENGTH_LONG).show()
            return
        }
        try {
            val intent = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_LIVENESS_TEST
            }
            ServiceStart.startForeground(this, intent)
            Toast.makeText(this, getString(R.string.selfcheck_started), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAntiKillWizard() {
        val brand = com.redeye.parentalmonitor.utils.AutoStart.manufacturerLabel()
        val batteryOk = isBatteryExempt()
        val overlayOk = com.redeye.parentalmonitor.utils.AutoStart.canDrawOverlays(this)
        val message = buildString {
            append(getString(R.string.antikill_text, brand))
            append("\n\n")
            appendLine(getString(if (batteryOk) R.string.antikill_battery_ok else R.string.antikill_battery_off))
            append(getString(if (overlayOk) R.string.antikill_overlay_ok else R.string.antikill_overlay_off))
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.antikill_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.antikill_autostart)) { _, _ ->
                try {
                    if (!com.redeye.parentalmonitor.utils.AutoStart.open(this)) {
                        Toast.makeText(this, getString(R.string.antikill_unknown), Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) {
                }
            }
            .setNeutralButton(getString(R.string.antikill_overlay)) { _, _ ->
                try {
                    if (!com.redeye.parentalmonitor.utils.AutoStart.openOverlaySettings(this)) {
                        Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendStatusNow() {
        if (!prefs.isConfigured()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            NetworkUtils.sweepAuthBlock(prefs)
            if (prefs.credentialError.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.setup_status_failed, prefs.credentialError), Toast.LENGTH_LONG).show()
                return
            }
        } catch (_: Exception) {
        }
        lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            var pending = ""
            try {
                val token = prefs.botToken
                val chatId = prefs.chatId
                val lastSync = prefs.lastSyncTime
                val text = buildString {
                    appendLine(getString(R.string.setup_status_live_title))
                    appendLine(getString(R.string.setup_status_live_monitoring, getString(if (prefs.isMonitoringEnabled) R.string.setup_status_on else R.string.setup_status_off)))
                    appendLine(getString(R.string.setup_status_live_sync, if (lastSync > 0) com.redeye.parentalmonitor.utils.TimeFmt.full(lastSync) else getString(R.string.never)))
                    appendLine(getString(R.string.setup_status_live_photo, if (prefs.lastPhotoTime > 0) com.redeye.parentalmonitor.utils.TimeFmt.full(prefs.lastPhotoTime) else getString(R.string.never)))
                    appendLine(getString(R.string.setup_status_battery_fmt, getString(if (isBatteryExempt()) R.string.setup_status_battery_free else R.string.setup_status_battery_limited)))
                }
                val url = "https://api.telegram.org/bot$token/sendMessage"
                pending = text
                val resp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = text))
                val ok = resp.isSuccessful && resp.body()?.ok == true
                var failCode = resp.code()
                if (ok) {
                    prefs.setCredentialErrorIfCredentialsMatch(token, chatId, "", 0L)
                } else if (resp.code() == 429) {
                    val retryAfter = try {
                        NetworkUtils.parseRetryAfter(resp.errorBody()?.string())
                    } catch (_: Exception) {
                        5L
                    }
                    try {
                        MessageQueue.getInstance(applicationContext).addMessage(text, true)
                        MessageScheduler.scheduleMessageSendNext(applicationContext, retryAfter * 1000L)
                    } catch (_: Exception) {
                    }
                } else if (resp.code() == 401 || resp.code() == 403) {
                    markCredentialError(resp.code(), token, chatId)
                    queueStatusMessage(text)
                } else if (resp.code() == 400) {
                    val goneBody = try { resp.errorBody()?.string() } catch (_: Exception) { null }
                    val migratedDirect = NetworkUtils.extractMigratedChatId(goneBody)
                    val storedChatNow = try { prefs.chatId } catch (_: Exception) { chatId }
                    if (migratedDirect != null && migratedDirect != chatId && storedChatNow == chatId) {
                        applyMigratedChatId(migratedDirect)
                        try {
                            val retryResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = migratedDirect, text = text))
                            if (retryResp.isSuccessful && retryResp.body()?.ok == true) {
                                prefs.setCredentialErrorIfCredentialsMatch(token, migratedDirect, "", 0L)
                                toastStatusSent()
                                return@launch
                            }
                            failCode = retryResp.code()
                            if (retryResp.code() == 401 || retryResp.code() == 403) {
                                markCredentialError(retryResp.code(), token, migratedDirect)
                            } else if (retryResp.code() == 400) {
                                val retryBody = try { retryResp.errorBody()?.string() } catch (_: Exception) { null }
                                if (NetworkUtils.isChatMissing(retryBody) && NetworkUtils.extractMigratedChatId(retryBody) == null) {
                                    markCredentialError(retryResp.code(), token, migratedDirect)
                                }
                            }
                        } catch (_: Exception) {
                        }
                        queueStatusMessage(text)
                        val toastRetry = getString(R.string.setup_status_failed, failCode)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            Toast.makeText(this@SetupActivity, toastRetry, Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    if (NetworkUtils.isChatMissing(goneBody)) {
                        markCredentialError(resp.code(), token, chatId)
                    } else {
                        val plain = Html.unescape(text)
                        if (plain != text) {
                            try {
                                val plainResp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = plain, parseMode = null))
                                if (plainResp.isSuccessful && plainResp.body()?.ok == true) {
                                    toastStatusSent()
                                    return@launch
                                }
                                failCode = plainResp.code()
                                if (plainResp.code() == 401 || plainResp.code() == 403) {
                                    markCredentialError(plainResp.code(), token, chatId)
                                } else if (plainResp.code() == 400) {
                                    val plainBody = try {
                                        plainResp.errorBody()?.string()
                                    } catch (_: Exception) {
                                        null
                                    }
                                    val migratedPlain = NetworkUtils.extractMigratedChatId(plainBody)
                                    val storedChatPlain = try { prefs.chatId } catch (_: Exception) { chatId }
                                    if (migratedPlain != null && migratedPlain != chatId && storedChatPlain == chatId) {
                                        applyMigratedChatId(migratedPlain)
                                        try {
                                            val retryPlain = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = migratedPlain, text = plain, parseMode = null))
                                            if (retryPlain.isSuccessful && retryPlain.body()?.ok == true) {
                                                toastStatusSent()
                                                return@launch
                                            }
                                            failCode = retryPlain.code()
                                            if (retryPlain.code() == 400) {
                                                val retryPlainBody = try { retryPlain.errorBody()?.string() } catch (_: Exception) { null }
                                                if (NetworkUtils.isChatMissing(retryPlainBody) && NetworkUtils.extractMigratedChatId(retryPlainBody) == null) {
                                                    markCredentialError(retryPlain.code(), token, migratedPlain)
                                                }
                                            }
                                        } catch (_: Exception) {
                                        }
                                    } else if (NetworkUtils.isChatMissing(plainBody)) {
                                        markCredentialError(plainResp.code(), token, chatId)
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    queueStatusMessage(text)
                } else {
                    queueStatusMessage(text)
                }
                val toastRes = if (ok) getString(R.string.setup_status_sent) else getString(R.string.setup_status_failed, failCode)
                val toastLen = if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@SetupActivity, toastRes, toastLen).show()
                    if (ok) reviveMonitoringIfNeeded()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                try {
                    if (pending.isNotEmpty()) {
                        MessageQueue.getInstance(applicationContext).addMessage(pending, true)
                        MessageScheduler.scheduleMessageSend(applicationContext)
                    }
                } catch (_: Exception) {
                }
                val failure = getString(R.string.setup_status_failed, redactToken(e.message))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    Toast.makeText(this@SetupActivity, failure, Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun markCredentialError(code: Int, token: String, chatId: String) {
        try {
            prefs.setCredentialErrorIfCredentialsMatch(token, chatId, code.toString(), System.currentTimeMillis())
        } catch (_: Exception) {
        }
    }

    private fun queueStatusMessage(text: String) {
        try {
            MessageQueue.getInstance(applicationContext).addMessage(text, true)
            MessageScheduler.scheduleMessageSend(applicationContext)
        } catch (_: Exception) {
        }
    }

    private suspend fun toastStatusSent() {
        val toastOk = getString(R.string.setup_status_sent)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            Toast.makeText(this@SetupActivity, toastOk, Toast.LENGTH_SHORT).show()
            reviveMonitoringIfNeeded()
        }
    }

    private suspend fun applyMigratedChatId(newChatId: String) {
        try {
            prefs.chatId = newChatId
            prefs.setCredentialError("", 0L)
        } catch (_: Exception) {
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                chatIdInput.setText(STORED_MASK)
            }
        } catch (_: Exception) {
        }
        queueStatusMessage("\u267B\uFE0F Grup ditingkatkan jadi supergrup \u2014 Chat ID diperbarui otomatis.")
    }

    private fun requestScreenCapture() {
        try {
            if (!shotRequestBusy.compareAndSet(false, true)) return
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            try { shotRequestBusy.set(false) } catch (_: Exception) { }
            Toast.makeText(this, getString(R.string.setup_shot_unsupported), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_a11y_restricted_title))
                .setMessage(getString(R.string.setup_a11y_restricted_body))
                .setPositiveButton(getString(R.string.setup_a11y_app_info)) { _, _ -> openAppSettings() }
                .setNeutralButton(getString(R.string.setup_a11y_open_settings)) { _, _ -> launchAccessibilitySettings() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launchAccessibilitySettings()
    }

    private fun launchAccessibilitySettings() {
        try {
            try {
                prefs.a11ySettingsGraceUntil = System.currentTimeMillis() + 120_000L
            } catch (_: Exception) {
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            queueStatusMessage(getString(R.string.setup_a11y_how))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.setup_a11y_how), Toast.LENGTH_LONG).show()
        }
    }

    private fun activateDeviceAdmin() {
        val admin = ComponentName(this, AdminReceiver::class.java)
        try {
            if ((getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin)) {
                Toast.makeText(this, getString(R.string.setup_admin_on), Toast.LENGTH_SHORT).show()
                return
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_description))
            }
            try {
                prefs.a11yAdminGraceUntil = System.currentTimeMillis() + 120_000L
            } catch (_: Exception) {
            }
            deviceAdminLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_action_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val myStatus = statusSeq.incrementAndGet()
        try { statusJob?.cancel() } catch (_: Exception) { }
        statusJob = lifecycleScope.launch(ioExceptionHandler + kotlinx.coroutines.Dispatchers.IO) {
            val configured = try { prefs.isConfigured() } catch (_: Exception) { false }
            val perms = hasAllPermissions()
            val enabled = try { prefs.isMonitoringEnabled } catch (_: Exception) { false }
            val running = enabled && MonitoringService.isRunning
            val paused = try { prefs.monitoringPaused } catch (_: Exception) { false }
            val bg = hasBackgroundLocation()
            val fg = hasForegroundLocation()
            val exempt = isBatteryExempt()
            val encrypted = try { prefs.isStorageEncrypted } catch (_: Exception) { false }
            val credErr = try { prefs.credentialError } catch (_: Exception) { "" }
            val ownerKnown = try { prefs.ownerUserId != 0L } catch (_: Exception) { false }
            val accessPinActive = try { prefs.accessPin.isNotEmpty() } catch (_: Exception) { false }
            val pairCode = try {
                if (!configured || ownerKnown || accessPinActive) "" else prefs.currentOwnerPairCode()
            } catch (_: Exception) {
                ""
            }
            val ownerLine = if (ownerKnown) "\n" + getString(R.string.setup_status_owner_known)
            else if (accessPinActive) "\n" + getString(R.string.setup_status_owner_unknown_pin)
            else if (pairCode.isNotEmpty()) "\n" + getString(R.string.setup_status_owner_unknown_code, pairCode)
            else "\n" + getString(R.string.setup_status_owner_unknown)
            val notifOn = try { prefs.notifForwardEnabled } catch (_: Exception) { true }
            val shotOn = try { com.redeye.parentalmonitor.utils.ScreenCapture.granted() } catch (_: Exception) { false }
            val a11yOn = com.redeye.parentalmonitor.service.ShotAccessibilityService.isEnabled(this@SetupActivity)
            val postOn = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true else try { ContextCompat.checkSelfPermission(this@SetupActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED } catch (_: Exception) { false }
            val listener = isNotificationAccessGranted()
            val adminOn = try { (getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(ComponentName(this@SetupActivity, AdminReceiver::class.java)) } catch (_: Exception) { false }
            val lastAlive = try { prefs.lastAliveAt } catch (_: Exception) { 0L }
            val lastAliveElapsed = try { prefs.lastAliveElapsed } catch (_: Exception) { 0L }
            val kills = try { prefs.livenessKills } catch (_: Exception) { 0 }
            val heartHours = try { prefs.heartbeatHours } catch (_: Exception) { 0 }
            val nowWallAgo = System.currentTimeMillis()
            val nowElapsedAgo = android.os.SystemClock.elapsedRealtime()
            val aliveAgoMin = if (lastAliveElapsed > 0L && com.redeye.parentalmonitor.utils.BootClock.sameBoot(lastAlive, lastAliveElapsed, nowWallAgo, nowElapsedAgo)) ((nowElapsedAgo - lastAliveElapsed) / 60_000L).coerceAtLeast(0L)
            else if (lastAlive > 0L) ((nowWallAgo - lastAlive) / 60_000L).coerceAtLeast(0L) else -1L
            val testSec = try { prefs.liveTestLastSec } catch (_: Exception) { -1 }
            val testPending = try { prefs.liveTestPending } catch (_: Exception) { false }
            val testLine = if (testPending) getString(R.string.setup_status_selfcheck_running)
            else if (testSec >= 0) getString(R.string.setup_status_selfcheck_sec, testSec)
            else getString(R.string.setup_status_selfcheck_none)
            val livenessLine = "\n" + getString(
                R.string.setup_status_liveness_fmt,
                getString(if (running) R.string.setup_status_alive else R.string.setup_status_dead),
                if (aliveAgoMin >= 0L) getString(R.string.setup_status_alive_ago, aliveAgoMin) else "",
                if (kills > 0) getString(R.string.setup_status_recovered, kills) else "",
                com.redeye.parentalmonitor.utils.Liveness.intervalTextLocalized(this@SetupActivity),
                testLine,
                if (heartHours > 0) getString(R.string.setup_status_heartbeat_hours, heartHours) else getString(R.string.setup_status_heartbeat_off)
            )
            val authLine = if (credErr.isNotEmpty()) "\n" + getString(R.string.setup_status_auth_failed, credErr) else ""
            val usageOn = try { com.redeye.parentalmonitor.utils.UsageAccess.granted(this@SetupActivity) } catch (_: Exception) { false }
            val usageLine = "\n" + getString(R.string.setup_status_usage_fmt, getString(if (usageOn) R.string.setup_status_ready else R.string.setup_status_usage_off))
            val storageReason = try { PreferencesManager.storageFailureReason } catch (_: Exception) { "" }
            val storageLabel = when {
                encrypted -> getString(R.string.setup_status_storage_enc)
                storageReason.isNotEmpty() -> getString(R.string.setup_status_storage_plain_reason, storageReason)
                else -> getString(R.string.setup_status_storage_plain)
            }
            val body = getString(
                R.string.setup_status_fmt,
                if (configured) "OK" else "-",
                if (perms) "OK" else "-",
                if (running && paused) getString(R.string.monitoring_paused) else if (running) getString(R.string.monitoring_active) else getString(R.string.monitoring_inactive)
            ) + "\n" + getString(R.string.setup_status_battery_fmt, getString(if (exempt) R.string.setup_status_battery_free else R.string.setup_status_battery_limited)) +
                "\n" + getString(R.string.setup_status_storage_fmt, storageLabel) +
                authLine + ownerLine + livenessLine + usageLine +
                "\n" + getString(R.string.setup_status_notif_fmt, getString(if (listener && notifOn) R.string.setup_status_forwarded else R.string.setup_status_off)) +
                "\n" + getString(R.string.setup_status_shot_fmt, getString(if (shotOn) R.string.setup_status_allowed else R.string.setup_status_off)) +
                "\n" + getString(R.string.setup_status_a11y_fmt, getString(if (a11yOn) R.string.setup_status_on else R.string.setup_status_off)) +
                "\n" + getString(R.string.setup_status_admin_fmt, getString(if (adminOn) R.string.setup_status_on else R.string.setup_status_off)) +
                "\n" + getString(R.string.setup_status_post_fmt, getString(if (postOn) R.string.setup_status_allowed else R.string.setup_status_blocked)) +
                "\n" + getString(R.string.setup_status_patrol_fmt, try { if (prefs.patrolEnabled) getString(R.string.setup_status_patrol_on, prefs.patrolInterval) else getString(R.string.setup_status_off) } catch (_: Exception) { "-" }) +
                "\n" + getString(
                    R.string.setup_location_fmt,
                    if (fg) "OK" else "-",
                    if (bg) "OK" else "-"
                )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (myStatus != statusSeq.get()) return@withContext
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
                shotButton.text = if (shotOn) getString(R.string.setup_shot_ok) else getString(R.string.setup_shot)
                a11yButton.text = if (a11yOn) getString(R.string.setup_a11y_on) else getString(R.string.setup_a11y)
            }
        }
    }
}
