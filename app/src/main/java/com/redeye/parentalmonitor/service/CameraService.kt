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

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.i(TAG, "Background thread started")
    }

    fun stopBackgroundThread() {
        val thread = backgroundThread
        backgroundThread = null
        backgroundHandler = null
        thread?.quitSafely()
        try {
            if (thread != null && Thread.currentThread() !== thread) {
                thread.join()
            }
            Log.i(TAG, "Background thread stopped")
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
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null

        fun finishWithError(e: Exception) {
            if (done.compareAndSet(false, true)) {
                try {
                    timeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
                } catch (_: Exception) {
                }
                cleanup()
                onError(e)
            }
        }

        fun finishWithPhoto(file: File) {
            if (done.compareAndSet(false, true)) {
                try {
                    timeoutRunnable?.let { backgroundHandler?.removeCallbacks(it) }
                } catch (_: Exception) {
                }
                onPhotoTaken(file)
                Log.i(TAG, "✓ Photo saved: ${file.absolutePath}")
            } else {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

        try {
            Log.i(TAG, "Starting photo capture...")
            onTrace("trace: starting capture")
            startBackgroundThread()

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager, lensFacing)

            if (cameraId == null) {
                stopBackgroundThread()
                finishWithError(Exception("Selected camera not found"))
                return
            }

            Log.d(TAG, "Using camera ID: $cameraId")

            timeoutRunnable = Runnable {
                Log.e(TAG, "Capture timed out after ${timeoutMs}ms")
                onTrace("trace: TIMEOUT waiting for camera")
                finishWithError(Exception("Capture timed out: camera opened but no image arrived"))
            }
            backgroundHandler?.postDelayed(timeoutRunnable!!, timeoutMs)

            // Setup ImageReader
            val photoSize = choosePhotoSize(cameraManager, cameraId)
            imageReader = ImageReader.newInstance(photoSize.first, photoSize.second, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    onTrace("trace: image arrived")
                    val image = reader.acquireLatestImage()
                    image?.let {
                        val file = saveImage(it)
                        it.close()

                        captureSession?.close()
                        cameraDevice?.close()
                        imageReader?.close()
                        stopBackgroundThread()

                        finishWithPhoto(file)
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
                    Log.d(TAG, "Camera opened successfully")
                    onTrace("trace: camera opened")
                    createCaptureSession(camera, ::finishWithError, { onTrace(it) })
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    finishWithError(Exception("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    finishWithError(Exception("Camera error: $error"))
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            finishWithError(e)
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

    private fun createCaptureSession(
        camera: CameraDevice,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit = {}
    ) {
        try {
            val surface = imageReader!!.surface
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface)

            // Auto settings
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 85.toByte())

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        Log.d(TAG, "Capture session configured")
                        onTrace("trace: session configured")
                        try {
                            session.capture(captureBuilder.build(), null, backgroundHandler)
                            Log.d(TAG, "Capture request sent")
                            onTrace("trace: capture request sent")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error capturing", e)
                            onError(e)
                        }
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
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) {
                IMAGE_WIDTH to IMAGE_HEIGHT
            } else {
                sizes.firstOrNull { it.width == IMAGE_WIDTH && it.height == IMAGE_HEIGHT }
                    ?.let { it.width to it.height }
                    ?: (sizes[0].width to sizes[0].height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error choosing photo size", e)
            IMAGE_WIDTH to IMAGE_HEIGHT
        }
    }

    private fun saveImage(image: Image): File {
        val buffer: ByteBuffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val timestamp = TimeFmt.fileStamp(System.currentTimeMillis())
        val file = File(context.cacheDir, "camera_$timestamp.jpg")
        
        FileOutputStream(file).use { output ->
            output.write(bytes)
        }
        
        return file
    }

    private fun cleanup() {
        try {
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

