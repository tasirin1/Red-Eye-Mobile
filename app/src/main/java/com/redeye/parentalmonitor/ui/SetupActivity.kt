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

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

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
        private val TOKEN_REGEX = Regex("^[0-9]+:[A-Za-z0-9_-]{20,}$")
        private val CHAT_ID_REGEX = Regex("^-?[0-9]+$")
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
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        try { PreferencesManager.refreshInstance(this) } catch (_: Exception) { }
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

        botTokenInput.setText(if (prefs.botToken.isEmpty()) "" else STORED_MASK)
        chatIdInput.setText(if (prefs.chatId.isEmpty()) "" else STORED_MASK)
        syncIntervalInput.setText(prefs.syncInterval.toString())
        cameraIntervalInput.setText(prefs.cameraInterval.toString())

        findViewById<MaterialButton>(R.id.setupSaveButton).setOnClickListener { saveSettings() }
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
            if (prefs.isMonitoringEnabled && prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring) {
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

    private fun saveSettings(): Boolean {
        val token = resolveStored(botTokenInput.text.toString().trim(), prefs.botToken)
        val chatId = resolveStored(chatIdInput.text.toString().trim(), prefs.chatId)
        val interval = syncIntervalInput.text.toString().toIntOrNull() ?: prefs.syncInterval

        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return false
        }
        if (!token.matches(TOKEN_REGEX)) {
            Toast.makeText(this, getString(R.string.setup_bad_token), Toast.LENGTH_SHORT).show()
            return false
        }
        if (!chatId.matches(CHAT_ID_REGEX)) {
            Toast.makeText(this, getString(R.string.setup_bad_chat), Toast.LENGTH_SHORT).show()
            return false
        }

        val cameraInterval = cameraIntervalInput.text.toString().toIntOrNull() ?: prefs.cameraInterval

        if (token != prefs.botToken || chatId != prefs.chatId) {
            prefs.commandsTokenHash = ""
            prefs.credentialError = ""
            prefs.credentialErrorAt = 0L
        }
        prefs.botToken = token
        prefs.chatId = chatId
        prefs.syncInterval = interval.coerceIn(1, 1440)
        prefs.cameraInterval = cameraInterval.coerceIn(0, 60)

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        reviveMonitoringIfNeeded()
        updateStatus()
        return true
    }

    private fun testConnection() {
        val token = resolveStored(botTokenInput.text.toString().trim(), prefs.botToken)
        val chatId = resolveStored(chatIdInput.text.toString().trim(), prefs.chatId)
        if (token.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return
        }
        if (!token.matches(TOKEN_REGEX) || !chatId.matches(CHAT_ID_REGEX)) {
            Toast.makeText(this, getString(R.string.setup_bad_token), Toast.LENGTH_SHORT).show()
            return
        }
        val interval = syncIntervalInput.text.toString().toIntOrNull() ?: prefs.syncInterval
        val cameraInterval = cameraIntervalInput.text.toString().toIntOrNull() ?: prefs.cameraInterval
        val probeText = getString(R.string.setup_test_ok)
        Toast.makeText(this, getString(R.string.setup_testing), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = "https://api.telegram.org/bot$token/sendMessage"
                val resp = TelegramClient.api.sendMessage(url, TelegramMessage(chatId = chatId, text = probeText))
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    persistTestSettings(token, chatId, interval, cameraInterval)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@SetupActivity, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_success), Toast.LENGTH_LONG).show()
                        reviveMonitoringIfNeeded()
                    }
                } else {
                    if (resp.code() == 400 || resp.code() == 401 || resp.code() == 403) {
                        prefs.credentialError = resp.code().toString()
                        prefs.credentialErrorAt = android.os.SystemClock.elapsedRealtime()
                    }
                    val code = resp.code()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, code), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                val detail = redactToken(e.message)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@SetupActivity, getString(R.string.setup_test_fail, detail), Toast.LENGTH_LONG).show()
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                updateStatus()
            }
        }
    }

    private fun persistTestSettings(token: String, chatId: String, interval: Int, cameraInterval: Int) {
        if (token != prefs.botToken || chatId != prefs.chatId) {
            prefs.commandsTokenHash = ""
        }
        prefs.botToken = token
        prefs.chatId = chatId
        prefs.syncInterval = interval.coerceIn(1, 1440)
        prefs.cameraInterval = cameraInterval.coerceIn(0, 60)
        prefs.credentialError = ""
        prefs.credentialErrorAt = 0L
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleMonitoring() {
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
            requestLocationPermissions()
            return
        }
        if (prefs.isMonitoringEnabled) {
            val intent = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_STOP_MONITORING
            }
            try {
                startService(intent)
                prefs.isMonitoringEnabled = false
                prefs.userDisabledMonitoring = true
                Toast.makeText(this, getString(R.string.monitoring_inactive), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.msg_service_failed), Toast.LENGTH_LONG).show()
            }
        } else {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.setup_consent_title))
                .setMessage(getString(R.string.setup_consent_text))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> startMonitoringConfirmed() }
                .show()
            return
        }
        updateStatus()
    }

    private fun startMonitoringConfirmed() {
        val intent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            prefs.isMonitoringEnabled = true
            prefs.userDisabledMonitoring = false
            prefs.userConsentedMonitoring = true
            Toast.makeText(this, getString(R.string.monitoring_active), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_service_failed), Toast.LENGTH_LONG).show()
        }
        updateStatus()
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
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        prefs.notifForwardEnabled = !prefs.notifForwardEnabled
        Toast.makeText(
            this,
            getString(if (prefs.notifForwardEnabled) R.string.setup_notif_on else R.string.setup_notif_off),
            Toast.LENGTH_SHORT
        ).show()
        updateStatus()
    }

    private fun requestBatteryExemption() {
        if (isBatteryExempt()) {
            Toast.makeText(this, getString(R.string.setup_battery_on), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendStatusNow() {
        if (!prefs.isConfigured()) {
            Toast.makeText(this, getString(R.string.setup_fill_all), Toast.LENGTH_SHORT).show()
            return
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
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val snap = try { prefs.snapshot() } catch (_: Exception) { emptyMap<String, Any?>() }
            fun s(key: String): String = snap[key] as? String ?: ""
            fun b(key: String, def: Boolean): Boolean = snap[key] as? Boolean ?: def
            val configured = s(PreferencesManager.KEY_BOT_TOKEN).isNotEmpty() && s(PreferencesManager.KEY_CHAT_ID).isNotEmpty()
            val perms = hasAllPermissions()
            val running = b(PreferencesManager.KEY_MONITORING_ENABLED, false)
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
                if (running) getString(R.string.monitoring_active) else getString(R.string.monitoring_inactive)
            ) + "\nBattery: " + (if (exempt) "unrestricted" else "restricted") + "\nStorage: " + (if (encrypted) "encrypted" else "volatile (keystore unavailable)") + authLine + "\nNotifications: " + (if (listener && notifOn) "forwarding" else "off") + "\n" + getString(
            R.string.setup_location_fmt,
            if (fg) "OK" else "-",
            if (bg) "OK" else "-"
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            statusText.text = body
            toggleButton.text = if (running) getString(R.string.disable_monitoring) else getString(R.string.enable_monitoring)
            toggleButton.isEnabled = configured && perms
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
