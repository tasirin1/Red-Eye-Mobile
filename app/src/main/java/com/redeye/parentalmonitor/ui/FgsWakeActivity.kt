package com.redeye.parentalmonitor.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.redeye.parentalmonitor.service.MonitoringService

// Jendela transparan tanpa tampilan, hidup ~2 dtk: Android 14+ hanya memasang
// tipe FGS "camera" saat aplikasi punya activity terlihat, jadi jendela ini
// dipakai agar tipe itu bisa dipasang sendiri setelah reboot.
class FgsWakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestTypeRefresh()
    }

    override fun onResume() {
        super.onResume()
        requestTypeRefresh()
        Handler(Looper.getMainLooper()).postDelayed({ finishQuietly() }, VISIBLE_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestTypeRefresh()
    }

    private fun requestTypeRefresh() {
        if (!MonitoringService.isRunning) {
            finishQuietly()
            return
        }
        try {
            val intent = Intent(this, MonitoringService::class.java).apply {
                action = MonitoringService.ACTION_REFRESH_FGS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
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
        try {
            finish()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val VISIBLE_MS = 2_500L
    }
}
