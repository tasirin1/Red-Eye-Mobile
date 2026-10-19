package com.redeye.parentalmonitor.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.redeye.parentalmonitor.R
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.ScreenCapture

class ShotConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CONSENT)
        } catch (_: Exception) {
            finishQuietly()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    private fun continueScreenshot() {
        try {
            val intent = Intent(this, MonitoringService::class.java)
                .setAction(MonitoringService.ACTION_AFTER_SHOT_GRANT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
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
    }
}
