package com.redeye.parentalmonitor.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.ScreenCapture
import com.redeye.parentalmonitor.utils.ServiceStart

class ShotConsentActivity : Activity() {

    private val safetyHandler = Handler(Looper.getMainLooper())
    private val safetyFinish = Runnable { finishQuietly() }
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
        finishQuietly()
    }

    override fun onDestroy() {
        try { safetyHandler.removeCallbacks(safetyFinish) } catch (_: Exception) {
        }
        super.onDestroy()
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
