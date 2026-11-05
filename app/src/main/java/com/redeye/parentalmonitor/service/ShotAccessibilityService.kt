package com.redeye.parentalmonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.redeye.parentalmonitor.utils.AccessibilityProbe

class ShotAccessibilityService : AccessibilityService() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> A11yMonitor.screenOff(this@ShotAccessibilityService)
                Intent.ACTION_SCREEN_ON -> A11yMonitor.screenOn(this@ShotAccessibilityService)
                Intent.ACTION_USER_PRESENT -> A11yMonitor.screenOn(this@ShotAccessibilityService)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        released = false
        instance = this
        A11yMonitor.attach(this)
        A11yMonitor.noteEnabled(this)
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        A11yMonitor.onEvent(this, event)
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (released) return
        released = true
        if (instance === this) instance = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        A11yMonitor.flush(this)
        A11yMonitor.unbind(this)
    }

    @Volatile
    private var released = false

    companion object {
        @Volatile
        private var instance: ShotAccessibilityService? = null

        fun ready(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

        // Ter-bind sungguhan (issue #1505): instans hidup
        // berarti sistem benar-benar mengikat layanan —
        // berbeda dari sekadar tercantum di setelan.
        fun isBound(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean =
            AccessibilityProbe.isEnabled(context, ShotAccessibilityService::class.java)

        fun capture(onResult: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                onResult(null)
                return
            }
            ShotA11yCapture.run(service, onResult)
        }
    }
}

object ShotA11yCapture {

    private val main = Handler(Looper.getMainLooper())
    private val callbackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "redeye-shot-a11y").apply { isDaemon = true }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun run(service: AccessibilityService, onResult: (Bitmap?) -> Unit) {
        try {
            main.post {
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        callbackExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                onResult(toBitmap(result))
                            }

                            override fun onFailure(errorCode: Int) {
                                onResult(null)
                            }
                        }
                    )
                } catch (_: Exception) {
                    onResult(null)
                }
            }
        } catch (_: Exception) {
            onResult(null)
        }
    }

    // Lebar maks sama dengan jalur MediaProjection (issue
    // #1015): dulu jalur ini memproses resolusi penuh —
    // salinan bitmap ±10 MB dan JPEG 2–4x lebih besar pada
    // layar 1080px, untuk pratinjau yang sama.
    private const val MAX_WIDTH = 720

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        return try {
            val buffer = result.hardwareBuffer
            try {
                val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                try {
                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    if (copy == null || copy.width <= MAX_WIDTH) {
                        copy
                    } else {
                        val scale = MAX_WIDTH.toFloat() / copy.width
                        val scaled = Bitmap.createScaledBitmap(copy, MAX_WIDTH, (copy.height * scale).toInt().coerceAtLeast(1), true)
                        if (scaled !== copy) {
                            try { copy.recycle() } catch (_: Exception) { }
                            scaled
                        } else {
                            copy
                        }
                    }
                } finally {
                    try { wrapped?.recycle() } catch (_: Exception) { }
                }
            } finally {
                try { buffer.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            null
        }
    }
}
