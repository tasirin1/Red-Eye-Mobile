package com.redeye.parentalmonitor.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.redeye.parentalmonitor.utils.AccessibilityProbe

class ShotAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: ShotAccessibilityService? = null

        fun ready(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && instance != null

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

    @RequiresApi(Build.VERSION_CODES.R)
    fun run(service: AccessibilityService, onResult: (Bitmap?) -> Unit) {
        try {
            main.post {
                try {
                    service.takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        service.mainExecutor,
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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toBitmap(result: AccessibilityService.ScreenshotResult): Bitmap? {
        return try {
            val buffer = result.hardwareBuffer
            try {
                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                try { buffer.close() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            null
        }
    }
}
