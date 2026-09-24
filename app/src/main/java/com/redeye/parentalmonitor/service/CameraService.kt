package com.redeye.parentalmonitor.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import com.redeye.parentalmonitor.utils.TimeFmt

class CameraService(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    companion object {
        private const val TAG = "CameraService"
        private const val IMAGE_WIDTH = 1280
        private const val IMAGE_HEIGHT = 720
    }

    private val capturing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val stillArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val photoSizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private var watchdogHandler: Handler? = null

    private fun mainHandler(): Handler? {
        if (watchdogHandler == null) {
            try {
                watchdogHandler = Handler(android.os.Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        return watchdogHandler
    }

    fun forceReset() {
        capturing.set(false)
        try {
            backgroundHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        try {
            watchdogHandler?.removeCallbacksAndMessages(null)
        } catch (_: Exception) {
        }
        cleanup()
    }

    fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true && backgroundHandler != null) return
        try {
            backgroundThread?.quitSafely()
        } catch (_: Exception) {
        }
        val thread = HandlerThread("CameraBackground")
        try {
            thread.start()
        } catch (_: Exception) {
            return
        }
        val looper = try {
            thread.looper
        } catch (_: Exception) {
            try { thread.quitSafely() } catch (_: Exception) { }
            return
        }
        backgroundThread = thread
        backgroundHandler = Handler(looper)
        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Background thread started")
    }

    fun stopBackgroundThread() {
        val thread = backgroundThread
        backgroundThread = null
        backgroundHandler = null
        thread?.quitSafely()
        try {
            if (thread != null && Thread.currentThread() !== thread) {
                try { thread.join(2_000) } catch (_: InterruptedException) { }
            }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Background thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun capturePhoto(
        onPhotoTaken: (File) -> Unit,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit = {},
        timeoutMs: Long = 45_000L,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
    ) {
        if (!capturing.compareAndSet(false, true)) {
            onError(Exception("Camera is busy"))
            return
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null

        fun finishWithError(e: Exception) {
            if (done.compareAndSet(false, true)) {
                capturing.set(false)
                try {
                    timeoutRunnable?.let { r ->
                        try {
                            backgroundHandler?.removeCallbacks(r)
                        } catch (_: Exception) {
                        }
                        try {
                            watchdogHandler?.removeCallbacks(r)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                cleanup()
                onError(e)
            }
        }

        fun finishWithPhoto(file: File) {
            if (done.compareAndSet(false, true)) {
                capturing.set(false)
                try {
                    timeoutRunnable?.let { r ->
                        try {
                            backgroundHandler?.removeCallbacks(r)
                        } catch (_: Exception) {
                        }
                        try {
                            watchdogHandler?.removeCallbacks(r)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }
                onPhotoTaken(file)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Photo saved")
            } else {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Starting photo capture")
            onTrace("trace: starting capture")
            startBackgroundThread()
            if (backgroundHandler == null) {
                finishWithError(Exception("Camera unavailable (background handler not ready)"))
                return
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager, lensFacing)

            if (cameraId == null) {
                try {
                    imageReader?.close()
                } catch (_: Exception) {
                }
                imageReader = null
                stopBackgroundThread()
                finishWithError(Exception("Selected camera not found"))
                return
            }

            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Using camera ID: $cameraId")
            stillArmed.set(false)

            val timeout = Runnable {
                Log.e(TAG, "Capture timed out after ${timeoutMs}ms")
                onTrace("trace: TIMEOUT waiting for camera")
                finishWithError(Exception("Capture timed out: camera opened but no image arrived"))
            }
            timeoutRunnable = timeout
            (mainHandler() ?: backgroundHandler)?.postDelayed(timeout, timeoutMs)

            // Setup ImageReader
            val photoSize = choosePhotoSize(cameraManager, cameraId)
            val jpegOrientation = getJpegOrientation(cameraManager, cameraId, lensFacing)
            imageReader = ImageReader.newInstance(photoSize.first, photoSize.second, ImageFormat.JPEG, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    onTrace("trace: image arrived")
                    val image = reader.acquireLatestImage()
                    image?.let {
                        if (!stillArmed.get()) {
                            try {
                                it.close()
                            } catch (_: Exception) {
                            }
                            onTrace("trace: warmup frame dropped")
                        } else {
                            val file = saveImage(it)
                            it.close()

                            captureSession?.close()
                            captureSession = null
                            cameraDevice?.close()
                            cameraDevice = null
                            imageReader?.close()
                            imageReader = null
                            stopBackgroundThread()

                            finishWithPhoto(file)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    finishWithError(e)
                }
            }, backgroundHandler)

            // Open camera
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Camera opened successfully")
                    onTrace("trace: camera opened")
                    createCaptureSession(camera, jpegOrientation, ::finishWithError, { onTrace(it) })
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    finishWithError(Exception("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                        finishWithError(Exception("Camera disabled by policy (CAMERA_DISABLED)"))
                    } else {
                        finishWithError(Exception("Camera error: $error"))
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            finishWithError(e)
        }
    }

    private fun runMeteredCapture(
        camera: CameraDevice,
        session: CameraCaptureSession,
        captureBuilder: CaptureRequest.Builder,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit
    ) {
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        var fallback: Runnable? = null

        fun fireStill() {
            if (settled.compareAndSet(false, true)) {
                stillArmed.set(true)
                try {
                    fallback?.let { backgroundHandler?.removeCallbacks(it) }
                } catch (_: Exception) {
                }
                try {
                    try {
                        session.stopRepeating()
                    } catch (_: Exception) {
                    }
                    captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                    session.capture(captureBuilder.build(), null, backgroundHandler)
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Capture request sent")
                    onTrace("trace: capture request sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Error capturing", e)
                    onError(e)
                }
            }
        }

        try {
            fallback = Runnable {
                Log.w(TAG, "Metering timeout, capturing anyway")
                onTrace("trace: metering timeout")
                fireStill()
            }
            val pending = fallback ?: return
            backgroundHandler?.postDelayed(pending, 5_000L)
            val meteringBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val meterSurface = imageReader?.surface ?: run { onError(Exception("Camera closed")); return }
            meteringBuilder.addTarget(meterSurface)
            meteringBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            session.setRepeatingRequest(
                meteringBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            aeState == CaptureResult.CONTROL_AE_STATE_LOCKED
                        ) {
                            onTrace("trace: metering settled")
                            fireStill()
                        }
                    }
                },
                backgroundHandler
            )
            onTrace("trace: metering started")
        } catch (e: Exception) {
            Log.e(TAG, "Error metering", e)
            fireStill()
        }
    }

    @Suppress("DEPRECATION")
    private fun getJpegOrientation(cameraManager: CameraManager, cameraId: String, lensFacing: Int): Int {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val rotation = try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                windowManager.defaultDisplay.rotation
            } catch (_: Exception) {
                android.view.Surface.ROTATION_0
            }
            val degrees = when (rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensor - degrees + 360) % 360
            } else {
                (sensor + degrees) % 360
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun getCameraId(cameraManager: CameraManager, lensFacing: Int): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == lensFacing
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding camera", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun createCaptureSession(
        camera: CameraDevice,
        jpegOrientation: Int,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit = {}
    ) {
        try {
            val surface = imageReader?.surface ?: run { onError(Exception("Camera closed")); return }
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Capture session configured")
                        onTrace("trace: session configured")
                        runMeteredCapture(camera, session, captureBuilder, onError, onTrace)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        onError(Exception("Session configuration failed"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            onError(e)
        }
    }

    private fun choosePhotoSize(cameraManager: CameraManager, cameraId: String): Pair<Int, Int> {
        photoSizeCache[cameraId]?.let { return it }
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) {
                IMAGE_WIDTH to IMAGE_HEIGHT
            } else {
                val chosen = sizes.minByOrNull {
                    kotlin.math.abs(it.width - IMAGE_WIDTH) + kotlin.math.abs(it.height - IMAGE_HEIGHT)
                }
                if (chosen != null) {
                    (chosen.width to chosen.height).also { photoSizeCache[cameraId] = it }
                } else {
                    IMAGE_WIDTH to IMAGE_HEIGHT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error choosing photo size", e)
            IMAGE_WIDTH to IMAGE_HEIGHT
        }
    }

    private fun saveImage(image: Image): File {
        val buffer: ByteBuffer = image.planes[0].buffer
        val timestamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val file = File(context.cacheDir, "camera_${timestamp}_${java.util.UUID.randomUUID()}.jpg")
        java.io.BufferedOutputStream(FileOutputStream(file), 8192).use { output ->
            val chunk = ByteArray(8192)
            while (buffer.hasRemaining()) {
                val n = kotlin.math.min(chunk.size, buffer.remaining())
                buffer.get(chunk, 0, n)
                output.write(chunk, 0, n)
            }
            output.flush()
        }
        return file
    }

    private fun cleanup() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
