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
        A11yMonitor.refreshEventTypes(this)
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

        // Identitas instans hidup (issue #1678): jepretan
        // yang sudah terposkan wajib memastikan layanan
        // yang sama masih terikat saat gilirannya jalan.
        fun isCurrent(service: ShotAccessibilityService): Boolean = instance === service

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
                    if (!ShotAccessibilityService.isCurrent(service)) {
                        onResult(null)
                        return@post
                    }
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        callbackExecutor,
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val bmp = toBitmap(result)
                                if (!ShotAccessibilityService.isCurrent(service)) {
                                    try { bmp?.recycle() } catch (_: Exception) { }
                                    onResult(null)
                                    return
                                }
                                onResult(bmp)
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
                    if (wrapped != null && wrapped.width > MAX_WIDTH) {
                        // Skala langsung dari bitmap perangkat
                        // keras (issue #1576): jalur lama
                        // menyalin resolusi PENUH ke bitmap
                        // lunak dulu (lonjakan sesaat s.d.
                        // ±25 MB di layar 1080px) baru
                        // memperkecil. Hasil skala yang masih
                        // berkonfigurasi keras disalin pada
                        // ukuran kecilnya; gagal skala -> jalur
                        // salin penuh lama di bawah.
                        val scale = MAX_WIDTH.toFloat() / wrapped.width
                        val targetH = (wrapped.height * scale).toInt().coerceAtLeast(1)
                        val scaled = try { Bitmap.createScaledBitmap(wrapped, MAX_WIDTH, targetH, true) } catch (_: Exception) { null }
                        if (scaled != null) {
                            @Suppress("DEPRECATION")
                            if (scaled.config == Bitmap.Config.HARDWARE) {
                                val lunak = try { scaled.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
                                if (lunak != null) {
                                    try { scaled.recycle() } catch (_: Exception) { }
                                    return lunak
                                }
                            } else {
                                return scaled
                            }
                        }
                    }
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
