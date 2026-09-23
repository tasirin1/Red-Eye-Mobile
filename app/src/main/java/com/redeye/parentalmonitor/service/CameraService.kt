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
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
            Log.i(TAG, "Background thread stopped")
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun capturePhoto(onPhotoTaken: (File) -> Unit, onError: (Exception) -> Unit) {
        try {
            Log.i(TAG, "Starting photo capture...")
            startBackgroundThread()

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getFrontCameraId(cameraManager)

            if (cameraId == null) {
                stopBackgroundThread()
                onError(Exception("Front camera not found"))
                return
            }

            Log.d(TAG, "Using camera ID: $cameraId")

            // Setup ImageReader
            imageReader = ImageReader.newInstance(IMAGE_WIDTH, IMAGE_HEIGHT, ImageFormat.JPEG, 1)
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    image?.let {
                        val file = saveImage(it)
                        it.close()
                        
                        // Cleanup
                        captureSession?.close()
                        cameraDevice?.close()
                        imageReader?.close()
                        stopBackgroundThread()
                        
                        onPhotoTaken(file)
                        Log.i(TAG, "✓ Photo saved: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image", e)
                    cleanup()
                    onError(e)
                }
            }, backgroundHandler)

            // Open camera
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    Log.d(TAG, "Camera opened successfully")
                    createCaptureSession(camera, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    cleanup()
                    onError(Exception("Camera disconnected"))
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    cleanup()
                    onError(Exception("Camera error: $error"))
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            cleanup()
            onError(e)
        }
    }

    private fun getFrontCameraId(cameraManager: CameraManager): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding front camera", e)
            null
        }
    }

    private fun createCaptureSession(camera: CameraDevice, onError: (Exception) -> Unit) {
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
                        try {
                            session.capture(captureBuilder.build(), null, backgroundHandler)
                            Log.d(TAG, "Capture request sent")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error capturing", e)
                            cleanup()
                            onError(e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        cleanup()
                        onError(Exception("Session configuration failed"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            cleanup()
            onError(e)
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

