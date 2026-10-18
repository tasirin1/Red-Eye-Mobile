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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import com.redeye.parentalmonitor.utils.TimeFmt

class CameraService(private val context: Context) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var meteringTexture: android.graphics.SurfaceTexture? = null
    private var meteringSurface: android.view.Surface? = null
    @Volatile
    private var backgroundHandler: Handler? = null
    @Volatile
    private var backgroundThread: HandlerThread? = null

    companion object {
        private const val TAG = "CameraService"
        private const val IMAGE_WIDTH = 1280
        private const val IMAGE_HEIGHT = 720
    }

    private val capturing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val sessionSeq = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile
    var lastLensFacing = -1
    private val stillArmed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val photoSizeCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val characteristicsCache = java.util.concurrent.ConcurrentHashMap<String, CameraCharacteristics>()
    private var watchdogHandler: Handler? = null

    private fun characteristicsOf(cameraManager: CameraManager, cameraId: String): CameraCharacteristics? {
        characteristicsCache[cameraId]?.let { return it }
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristicsCache[cameraId] = characteristics
            characteristics
        } catch (_: Exception) {
            null
        }
    }

    private fun removeEverywhere(r: Runnable?) {
        if (r == null) return
        try { backgroundHandler?.removeCallbacks(r) } catch (_: Exception) { }
        try { watchdogHandler?.removeCallbacks(r) } catch (_: Exception) { }
        try { mainHandler()?.removeCallbacks(r) } catch (_: Exception) { }
    }

    private fun mainHandler(): Handler? {
        if (watchdogHandler == null) {
            try {
                watchdogHandler = Handler(android.os.Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        return watchdogHandler
    }

    private val captureLock = Any()
    @Volatile
    private var activeRecorder: android.media.MediaRecorder? = null
    @Volatile
    private var activeVideoFile: java.io.File? = null

    fun currentVideoFile(): java.io.File? = activeVideoFile

    init {
        try {
            context.cacheDir?.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach {
                try { it.delete() } catch (_: Exception) { }
            }
            context.cacheDir?.listFiles { f -> f.isFile && f.name.startsWith("video_") && f.name.endsWith(".mp4") && f.length() == 0L }?.forEach {
                try { it.delete() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
        }
    }

    fun forceReset() {
        synchronized(captureLock) {
            capturing.set(false)
            sessionSeq.incrementAndGet()
            try {
                photoSizeCache.clear()
            } catch (_: Exception) {
            }
            try {
                backgroundHandler?.removeCallbacksAndMessages(null)
            } catch (_: Exception) {
            }
            try {
                watchdogHandler?.removeCallbacksAndMessages(null)
            } catch (_: Exception) {
            }
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {
            }
            try {
                activeRecorder?.release()
            } catch (_: Exception) {
            }
            activeRecorder = null
            try {
                val staleVideo = activeVideoFile
                activeVideoFile = null
                if (staleVideo != null && staleVideo.exists()) staleVideo.delete()
            } catch (_: Exception) {
            }
            cleanup()
        }
    }

    fun startBackgroundThread() {
        val old = backgroundThread
        if (old?.isAlive == true && backgroundHandler != null) return
        try {
            old?.quitSafely()
        } catch (_: Exception) {
        }
        val onMain = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Exception) { false }
        try {
            if (!onMain && old != null && Thread.currentThread() !== old) old.join(500)
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
        val handler = backgroundHandler
        try { handler?.removeCallbacksAndMessages(null) } catch (_: Exception) { }
        try { thread?.quitSafely() } catch (_: Exception) { }
        backgroundThread = null
        backgroundHandler = null
        val onMain = try { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() } catch (_: Exception) { false }
        try {
            if (!onMain && thread != null && Thread.currentThread() !== thread) {
                try { thread.join(500) } catch (_: InterruptedException) { }
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
        timeoutMs: Long = 30_000L,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
    ) {
        var mySeq = 0
        synchronized(captureLock) {
            if (!capturing.compareAndSet(false, true)) {
                onError(Exception("Kamera sedang dipakai"))
                return
            }
            mySeq = sessionSeq.get()
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null

        fun finishWithError(e: Exception) {
            if (done.compareAndSet(false, true)) {
                if (sessionSeq.get() == mySeq) {
                    removeEverywhere(timeoutRunnable)
                    try {
                        cleanup()
                    } finally {
                        capturing.set(false)
                    }
                }
                onError(e)
            }
        }

        fun finishWithPhoto(file: File) {
            if (done.compareAndSet(false, true)) {
                if (sessionSeq.get() == mySeq) capturing.set(false)
                removeEverywhere(timeoutRunnable)
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
                finishWithError(Exception("Kamera tak tersedia (penangan latar belum siap)"))
                return
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager, lensFacing)

            if (cameraId == null) {
                if (sessionSeq.get() == mySeq) {
                    try {
                        imageReader?.close()
                    } catch (_: Exception) {
                    }
                    imageReader = null
                    stopBackgroundThread()
                }
                finishWithError(Exception("Kamera yang dipilih tak ditemukan"))
                return
            }

            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Using camera ID: $cameraId")
            stillArmed.set(false)

            val timeout = Runnable {
                Log.e(TAG, "Capture timed out after ${timeoutMs}ms")
                onTrace("trace: TIMEOUT waiting for camera")
                finishWithError(Exception("Pemotretan timeout: kamera terbuka tapi tak ada gambar"))
            }
            timeoutRunnable = timeout
            val watchdogScheduled = try {
                val bg = backgroundHandler
                if (bg != null) {
                    bg.postDelayed(timeout, timeoutMs)
                    true
                } else {
                    val mh = mainHandler()
                    if (mh != null) {
                        mh.postDelayed(timeout, timeoutMs)
                        true
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
            if (!watchdogScheduled) {
                finishWithError(Exception("Kamera tak tersedia (pemantau timeout belum siap)"))
                return
            }

            // Setup ImageReader (still capture only) plus a dummy surface for AE metering,
            // so warmup preview frames can never be mistaken for the still photo.
            val photoSize = choosePhotoSize(cameraManager, cameraId)
            val meterSize = choosePreviewSize(cameraManager, cameraId, photoSize)
            val jpegOrientation = getJpegOrientation(cameraManager, cameraId, lensFacing)
            val dummyTexture = android.graphics.SurfaceTexture(1)
            try {
                dummyTexture.setDefaultBufferSize(meterSize.first, meterSize.second)
            } catch (_: Exception) {
            }
            meteringTexture = dummyTexture
            meteringSurface = android.view.Surface(dummyTexture)
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
                            val file = try {
                                saveImage(it)
                            } finally {
                                try {
                                    it.close()
                                } catch (_: Exception) {
                                }
                            }

                            if (sessionSeq.get() == mySeq) {
                                teardownDevice()
                            }

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
                    try {
                        if (done.get() || sessionSeq.get() != mySeq) {
                            try { camera.close() } catch (_: Exception) { }
                            return
                        }
                        cameraDevice = camera
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Camera opened successfully")
                        onTrace("trace: camera opened")
                        createCaptureSession(camera, jpegOrientation, ::finishWithError, { onTrace(it) })
                    } catch (e: Exception) {
                        finishWithError(e)
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) { }
                    try {
                        Log.w(TAG, "Camera disconnected")
                        finishWithError(Exception("Kamera terputus"))
                    } catch (e: Exception) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) { }
                    try {
                        Log.e(TAG, "Camera error: $error")
                        if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                            finishWithError(Exception("Kamera dimatikan kebijakan perangkat (CAMERA_DISABLED)"))
                        } else {
                            finishWithError(Exception("Galat kamera: $error"))
                        }
                    } catch (e: Exception) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing photo", e)
            finishWithError(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun captureVideo(
        durationMs: Long,
        onVideoTaken: (File) -> Unit,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit = {},
        timeoutMs: Long = durationMs + 60_000L,
        lensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
    ) {
        var mySeq = 0
        synchronized(captureLock) {
            if (!capturing.compareAndSet(false, true)) {
                onError(Exception("Kamera sedang dipakai"))
                return
            }
            mySeq = sessionSeq.get()
        }
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeoutRunnable: Runnable? = null
        var stopRunnable: Runnable? = null
        var recorder: android.media.MediaRecorder? = null
        var videoSurface: android.view.Surface? = null
        var recording = false
        var pendingOut: java.io.File? = null

        fun releaseRecorder() {
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
        }

        fun releaseSurface() {
            try { videoSurface?.release() } catch (_: Exception) { }
            videoSurface = null
        }

        fun finishWithError(e: Exception) {
            if (done.compareAndSet(false, true)) {
                if (sessionSeq.get() == mySeq) {
                    removeEverywhere(timeoutRunnable)
                    removeEverywhere(stopRunnable)
                    try {
                        if (recording) {
                            try { recorder?.stop() } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    }
                    recording = false
                    releaseRecorder()
                    releaseSurface()
                    try {
                        val stale = pendingOut
                        pendingOut = null
                        activeVideoFile = null
                        activeRecorder = null
                        if (stale != null && stale.exists()) stale.delete()
                    } catch (_: Exception) {
                    }
                    try {
                        cleanup()
                    } finally {
                        capturing.set(false)
                    }
                }
                onError(e)
            }
        }

        fun finishWithVideo(file: File) {
            if (done.compareAndSet(false, true)) {
                if (sessionSeq.get() == mySeq) capturing.set(false)
                removeEverywhere(timeoutRunnable)
                removeEverywhere(stopRunnable)
                releaseRecorder()
                releaseSurface()
                pendingOut = null
                activeVideoFile = null
                activeRecorder = null
                onVideoTaken(file)
                if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Video saved")
            } else {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }

        try {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Starting video capture")
            onTrace("trace: starting video capture")
            startBackgroundThread()
            if (backgroundHandler == null) {
                finishWithError(Exception("Kamera tak tersedia (penangan latar belum siap)"))
                return
            }
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager, lensFacing)
            if (cameraId == null) {
                if (sessionSeq.get() == mySeq) stopBackgroundThread()
                finishWithError(Exception("Kamera yang dipilih tak ditemukan"))
                return
            }
            val timeout = Runnable {
                Log.e(TAG, "Video capture timed out after ${timeoutMs}ms")
                onTrace("trace: TIMEOUT waiting for video")
                finishWithError(Exception("Perekaman video timeout"))
            }
            timeoutRunnable = timeout
            val watchdogScheduled = try {
                backgroundHandler?.postDelayed(timeout, timeoutMs) ?: false
            } catch (_: Exception) {
                false
            }
            if (!watchdogScheduled) {
                finishWithError(Exception("Kamera tak tersedia (pemantau timeout belum siap)"))
                return
            }
            val videoSize = chooseVideoSize(cameraManager, cameraId)
            val outFile = File(context.cacheDir, "video_" + TimeFmt.fileStamp(System.currentTimeMillis()) + "_" + java.util.UUID.randomUUID() + ".mp4")
            val tmpFile = File(context.cacheDir, outFile.name + ".tmp")
            pendingOut = tmpFile
            activeVideoFile = tmpFile
            val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                android.media.MediaRecorder()
            }
            recorder = rec
            activeRecorder = rec
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            rec.setVideoSize(videoSize.first, videoSize.second)
            try {
                rec.setOrientationHint(getJpegOrientation(cameraManager, cameraId, lensFacing))
            } catch (_: Exception) {
            }
            try {
                val ranges = characteristicsOf(cameraManager, cameraId)?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                if (ranges?.any { it.lower <= 24 && 24 <= it.upper } == true) rec.setVideoFrameRate(24)
            } catch (_: Exception) {
            }
            rec.setVideoEncodingBitRate(if (videoSize.first * videoSize.second >= 1280 * 720) 2_000_000 else 1_000_000)
            try {
                if (durationMs > 0L) rec.setMaxDuration((durationMs + 30_000L).coerceAtMost(10 * 60_000L).toInt())
            } catch (_: Exception) {
            }
            rec.setOutputFile(tmpFile.absolutePath)
            rec.prepare()
            videoSurface = rec.surface
            val recordSurface = videoSurface ?: run { finishWithError(Exception("Permukaan rekaman tak tersedia")); return }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        if (done.get() || sessionSeq.get() != mySeq) {
                            try { camera.close() } catch (_: Exception) { }
                            return
                        }
                        cameraDevice = camera
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(recordSurface)
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        camera.createCaptureSession(
                            listOf(recordSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    captureSession = session
                                    try {
                                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                                    } catch (e: Exception) {
                                        finishWithError(Exception("Sesi rekaman gagal: " + e.message))
                                        return
                                    }
                                    try {
                                        rec.start()
                                    } catch (e: Exception) {
                                        finishWithError(Exception("Mulai merekam gagal: " + e.message))
                                        return
                                    }
                                    recording = true
                                    onTrace("trace: recording started")
                                    val stop = Runnable {
                                        try {
                                            var stopOk = true
                                            try { rec.stop() } catch (_: Exception) { stopOk = false }
                                            recording = false
                                            captureSession?.close()
                                            captureSession = null
                                            cameraDevice?.close()
                                            cameraDevice = null
                                            releaseRecorder()
                                            releaseSurface()
                                            stopBackgroundThread()
                                            val finalized = stopOk && tmpFile.exists() && tmpFile.length() > 0L &&
                                                try { tmpFile.renameTo(outFile) } catch (_: Exception) { false }
                                            if (!finalized) {
                                                try { tmpFile.delete() } catch (_: Exception) { }
                                                finishWithError(Exception(if (stopOk) "Video kosong" else "Gagal menghentikan video"))
                                            } else {
                                                finishWithVideo(outFile)
                                            }
                                        } catch (e: Exception) {
                                            finishWithError(e)
                                        }
                                    }
                                    stopRunnable = stop
                                    val stopScheduled = try {
                                        if (backgroundHandler?.postDelayed(stop, durationMs) == true) true
                                        else mainHandler()?.postDelayed(stop, durationMs) == true
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (!stopScheduled) {
                                        finishWithError(Exception("Kamera tak tersedia (gagal menjadwalkan penghentian)"))
                                        return
                                    }
                                }
                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    try { session.close() } catch (_: Exception) { }
                                    finishWithError(Exception("Konfigurasi sesi rekaman gagal"))
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        finishWithError(e)
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    try { camera.close() } catch (_: Exception) { }
                    try {
                        finishWithError(Exception("Kamera terputus"))
                    } catch (e: Exception) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    try { camera.close() } catch (_: Exception) { }
                    try {
                        if (error == CameraDevice.StateCallback.ERROR_CAMERA_DISABLED) {
                            finishWithError(Exception("Kamera dimatikan kebijakan perangkat (CAMERA_DISABLED)"))
                        } else {
                            finishWithError(Exception("Galat kamera: $error"))
                        }
                    } catch (e: Exception) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    } catch (t: Throwable) {
                        try {
                            finishWithError(Exception("Kegagalan callback kamera"))
                        } catch (_: Exception) {
                        }
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing video", e)
            finishWithError(e)
        }
    }

    private fun chooseVideoSize(cameraManager: CameraManager, cameraId: String): Pair<Int, Int> {
        return try {
            val characteristics = characteristicsOf(cameraManager, cameraId) ?: return 640 to 480
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)
            if (sizes.isNullOrEmpty()) {
                640 to 480
            } else {
                val capArea = 1280L * 720L
                val within = sizes.filter { it.width.toLong() * it.height <= capArea }
                val pool = if (within.isNotEmpty()) within else sizes.toList()
                val chosen = pool.maxByOrNull { it.width.toLong() * it.height }
                if (chosen != null) chosen.width to chosen.height else 640 to 480
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error choosing video size", e)
            640 to 480
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
                removeEverywhere(fallback)
                try {
                    try {
                        session.stopRepeating()
                    } catch (_: Exception) {
                    }
                    session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                            Log.e(TAG, "Still capture failed: ${failure.reason}")
                            onError(Exception("Pemotretan gagal (${failure.reason})"))
                        }
                    }, backgroundHandler)
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
            val meterScheduled = try {
                backgroundHandler?.postDelayed(pending, 3_000L) == true || mainHandler()?.postDelayed(pending, 3_000L) == true
            } catch (_: Exception) { false }
            if (!meterScheduled) { fireStill(); return }
            val meteringBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val previewSurface = meteringSurface ?: run { onError(Exception("Kamera ditutup")); return }
            meteringBuilder.addTarget(previewSurface)
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
            val characteristics = characteristicsOf(cameraManager, cameraId) ?: return 0
            val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            val rotation = try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                display?.rotation ?: android.view.Surface.ROTATION_0
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
        fun facingOf(id: String): Int? {
            return characteristicsOf(cameraManager, id)?.get(CameraCharacteristics.LENS_FACING)
        }
        return try {
            val ids = try { cameraManager.cameraIdList } catch (_: Exception) { emptyArray() }
            val exact = ids.firstOrNull { facingOf(it) == lensFacing }
            if (exact != null) {
                lastLensFacing = lensFacing
                return exact
            }
            val fallback = ids.firstOrNull { val f = facingOf(it); f == CameraCharacteristics.LENS_FACING_BACK || f == CameraCharacteristics.LENS_FACING_FRONT }
            if (fallback != null) {
                Log.w(TAG, "Requested lens missing, using fallback camera")
                lastLensFacing = facingOf(fallback) ?: -1
            }
            fallback
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
            val surface = imageReader?.surface ?: run { onError(Exception("Kamera ditutup")); return }
            val meterSurface = meteringSurface ?: run { onError(Exception("Kamera ditutup")); return }
            val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, 85.toByte())
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            camera.createCaptureSession(
                listOf(surface, meterSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Capture session configured")
                        onTrace("trace: session configured")
                        runMeteredCapture(camera, session, captureBuilder, onError, onTrace)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        try { session.close() } catch (_: Exception) { }
                        onError(Exception("Konfigurasi sesi gagal"))
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
            onError(e)
        }
    }

    private fun choosePreviewSize(cameraManager: CameraManager, cameraId: String, fallback: Pair<Int, Int>): Pair<Int, Int> {
        return try {
            val characteristics = characteristicsOf(cameraManager, cameraId) ?: return fallback
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            if (sizes.isNullOrEmpty()) fallback
            else {
                val fitting = sizes.filter { it.width <= 1280 && it.height <= 720 }
                val pool = if (fitting.isNotEmpty()) fitting else sizes.toList()
                val chosen = pool.minByOrNull { kotlin.math.abs(it.width - fallback.first) + kotlin.math.abs(it.height - fallback.second) }
                if (chosen != null) chosen.width to chosen.height else fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun choosePhotoSize(cameraManager: CameraManager, cameraId: String): Pair<Int, Int> {
        photoSizeCache[cameraId]?.let { return it }
        return try {
            val characteristics = characteristicsOf(cameraManager, cameraId) ?: return IMAGE_WIDTH to IMAGE_HEIGHT
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            if (sizes.isNullOrEmpty()) {
                IMAGE_WIDTH to IMAGE_HEIGHT
            } else {
                val targetArea = (IMAGE_WIDTH * IMAGE_HEIGHT).toDouble()
                val wide = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - 16.0 / 9.0) < 0.05 }
                val pool = if (wide.isNotEmpty()) wide else sizes.toList()
                val chosen = pool.minByOrNull { kotlin.math.abs(it.width * it.height - targetArea) }
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
        val tmp = File(context.cacheDir, file.name + ".tmp")
        try {
            java.io.BufferedOutputStream(FileOutputStream(tmp), 8192).use { output ->
                val chunk = ByteArray(8192)
                while (buffer.hasRemaining()) {
                    val n = kotlin.math.min(chunk.size, buffer.remaining())
                    buffer.get(chunk, 0, n)
                    output.write(chunk, 0, n)
                }
                output.flush()
            }
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            throw e
        }
        try {
            if (!tmp.renameTo(file)) {
                try { tmp.delete() } catch (_: Exception) { }
                throw java.io.IOException("gagal mengganti nama")
            }
        } catch (e: Exception) {
            try { tmp.delete() } catch (_: Exception) { }
            throw java.io.IOException("gagal menyimpan", e)
        }
        return file
    }

    private fun teardownDevice() {
        try { captureSession?.close() } catch (_: Exception) { }
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) { }
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        try { meteringSurface?.release() } catch (_: Exception) { }
        meteringSurface = null
        try { meteringTexture?.release() } catch (_: Exception) { }
        meteringTexture = null
        stopBackgroundThread()
    }

    private fun cleanup() {
        try {
            teardownDevice()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}
