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

    @Volatile
    private var cameraDevice: CameraDevice? = null
    @Volatile
    private var captureSession: CameraCaptureSession? = null
    @Volatile
    private var imageReader: ImageReader? = null
    @Volatile
    private var meteringTexture: android.graphics.SurfaceTexture? = null
    @Volatile
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
    @Volatile
    private var watchdogHandler: Handler? = null
    private val threadLock = Any()

    // Token milik posting-an SENDIRI (issue #1335): semua
    // Runnable yang dipasang kelas ini ke handler latar/
    // watchdog wajib lewat postDelayedOwn agar pembersihan
    // antrean saat reset membuang milik sendiri saja.
    // removeCallbacksAndMessages(null) ikut membuang callback
    // framework Camera2 yang tertunda (onOpened dkk) — kamera
    // yang callback-nya tak pernah terkirim bisa tertinggal
    // terbuka di sisi framework dan membuka berikutnya gagal.
    private val ownPostsToken = Any()

    private fun postDelayedOwn(handler: Handler?, r: Runnable, delayMs: Long): Boolean {
        if (handler == null) return false
        return try {
            handler.postAtTime(r, ownPostsToken, android.os.SystemClock.uptimeMillis() + delayMs)
        } catch (_: Exception) {
            false
        }
    }

    private fun currentBackgroundHandler(): Handler? {
        synchronized(threadLock) { return backgroundHandler }
    }

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
        try { currentBackgroundHandler()?.removeCallbacks(r) } catch (_: Exception) { }
        try { watchdogHandler?.removeCallbacks(r) } catch (_: Exception) { }
        try { mainHandler()?.removeCallbacks(r) } catch (_: Exception) { }
    }

    private fun mainHandler(): Handler? {
        watchdogHandler?.let { return it }
        synchronized(threadLock) {
            if (watchdogHandler == null) {
                try {
                    watchdogHandler = Handler(android.os.Looper.getMainLooper())
                } catch (_: Exception) {
                }
            }
            return watchdogHandler
        }
    }

    private val captureLock = Any()
    @Volatile
    private var activeRecorder: android.media.MediaRecorder? = null
    @Volatile
    private var activeVideoFile: java.io.File? = null

    init {
        Thread {
            try {
                com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context).listFiles { f ->
                    // .mig ikut disapu (issue #1508): sisa
                    // salinan migrasi MediaDirs yang prosesnya
                    // mati di tengah penyalinan — dulu tak ada
                    // pembersih yang mencocokkan akhiran itu.
                    f.isFile && (f.name.endsWith(".tmp") || f.name.endsWith(".mig")) &&
                        (f.name.startsWith("camera_") || f.name.startsWith("screenshot_") ||
                            f.name.startsWith("video_") || f.name.startsWith("audio_"))
                }?.forEach {
                    try { it.delete() } catch (_: Exception) { }
                }
                com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context).listFiles { f -> f.isFile && f.name.startsWith("video_") && f.name.endsWith(".mp4") && f.length() == 0L }?.forEach {
                    try { it.delete() } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            }
        }.start()
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
                currentBackgroundHandler()?.removeCallbacksAndMessages(ownPostsToken)
            } catch (_: Exception) {
            }
            try {
                watchdogHandler?.removeCallbacksAndMessages(ownPostsToken)
            } catch (_: Exception) {
            }
            try {
                activeRecorder?.stop()
            } catch (_: Exception) {
            }
            val staleSurface = try {
                activeRecorder?.surface
            } catch (_: Exception) {
                null
            }
            try {
                activeRecorder?.release()
            } catch (_: Exception) {
            }
            try {
                staleSurface?.release()
            } catch (_: Exception) {
            }
            activeRecorder = null
            try {
                val staleVideo = activeVideoFile
                activeVideoFile = null
                try { java.io.File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "rekam_video_aktif.txt").delete() } catch (_: Exception) { }
                if (staleVideo != null && staleVideo.exists()) staleVideo.delete()
            } catch (_: Exception) {
            }
            cleanup()
        }
    }

    fun startBackgroundThread() {
        synchronized(threadLock) {
            startBackgroundThreadLocked()
        }
    }

    private fun startBackgroundThreadLocked() {
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
        synchronized(threadLock) {
            stopBackgroundThreadLocked()
        }
    }

    private fun stopBackgroundThreadLocked() {
        val thread = backgroundThread
        val handler = backgroundHandler
        try { handler?.removeCallbacksAndMessages(ownPostsToken) } catch (_: Exception) { }
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
                val current = sessionSeq.get() == mySeq
                if (current) capturing.set(false)
                removeEverywhere(timeoutRunnable)
                if (current) {
                    onPhotoTaken(file)
                    if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.i(TAG, "Photo saved")
                } else {
                    // Sesi sudah digantikan (issue #992):
                    // hasil sesi lama tak boleh diserahkan
                    // sebagai hasil sesi yang meminta kini —
                    // berkas dibuang seperti jalur ganda.
                    try {
                        file.delete()
                    } catch (_: Exception) {
                    }
                }
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
            val flowHandler = currentBackgroundHandler()
            if (flowHandler == null) {
                finishWithError(Exception("Kamera tak tersedia (penangan latar belum siap)"))
                return
            }

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = getCameraId(cameraManager, lensFacing)

            if (cameraId == null) {
                if (sessionSeq.get() == mySeq) {
                    synchronized(captureLock) {
                        try {
                            imageReader?.close()
                        } catch (_: Exception) {
                        }
                        imageReader = null
                    }
                    stopBackgroundThread()
                }
                finishWithError(Exception("Kamera yang dipilih tak ditemukan"))
                return
            }

            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Using camera ID: $cameraId")
            stillArmed.set(false)
            if (sessionSeq.get() != mySeq) {
                finishWithError(Exception("Sesi kamera digantikan"))
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
            try {
                dummyTexture.setOnFrameAvailableListener({
                    try {
                        dummyTexture.updateTexImage()
                    } catch (_: Exception) {
                    }
                }, flowHandler)
            } catch (_: Exception) {
            }
            // Alokasi berpasangan dijaga (issue #1268):
            // releaseOwned() baru ada sesudah ImageReader lahir,
            // jadi kegagalan di antara Surface dan ImageReader
            // membocorkan tekstur/surface — kini kegagalan
            // alokasi melepas yang sudah terbuat lalu melempar
            // ke penanganan error penangkapan seperti biasa.
            var permukaanSementara: android.view.Surface? = null
            val ownedSurface: android.view.Surface
            val ownedReader: ImageReader
            try {
                permukaanSementara = android.view.Surface(dummyTexture)
                ownedSurface = permukaanSementara
                ownedReader = ImageReader.newInstance(photoSize.first, photoSize.second, ImageFormat.JPEG, 2)
            } catch (e: Exception) {
                try { permukaanSementara?.release() } catch (_: Exception) { }
                try { dummyTexture.release() } catch (_: Exception) { }
                throw e
            }
            fun releaseOwned() {
                try { ownedReader.close() } catch (_: Exception) { }
                try { ownedSurface.release() } catch (_: Exception) { }
                try { dummyTexture.release() } catch (_: Exception) { }
            }
            var superseded = false
            synchronized(captureLock) {
                if (sessionSeq.get() != mySeq) {
                    releaseOwned()
                    superseded = true
                } else {
                    meteringTexture = dummyTexture
                    meteringSurface = ownedSurface
                    imageReader = ownedReader
                }
            }
            if (superseded) {
                finishWithError(Exception("Sesi kamera digantikan"))
                return
            }
            ownedReader.setOnImageAvailableListener({ reader ->
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
            }, flowHandler)

            val timeout = Runnable {
                Log.e(TAG, "Capture timed out after ${timeoutMs}ms")
                onTrace("trace: TIMEOUT waiting for camera")
                finishWithError(Exception("Pemotretan timeout: kamera terbuka tapi tak ada gambar"))
            }
            timeoutRunnable = timeout
            val watchdogScheduled = try {
                val bg = flowHandler
                if (bg != null) {
                    postDelayedOwn(bg, timeout, timeoutMs)
                } else {
                    postDelayedOwn(mainHandler(), timeout, timeoutMs)
                }
            } catch (_: Exception) {
                false
            }
            if (!watchdogScheduled) {
                finishWithError(Exception("Kamera tak tersedia (pemantau timeout belum siap)"))
                return
            }

            // Open camera
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        var superseded = false
                        synchronized(captureLock) {
                            if (done.get() || sessionSeq.get() != mySeq) {
                                try { camera.close() } catch (_: Exception) { }
                                releaseOwned()
                                superseded = true
                            } else {
                                cameraDevice = camera
                            }
                        }
                        if (superseded) {
                            finishWithError(Exception("Sesi kamera digantikan"))
                            return
                        }
                        if (com.redeye.parentalmonitor.BuildConfig.DEBUG) Log.d(TAG, "Camera opened successfully")
                        onTrace("trace: camera opened")
                        createCaptureSession(camera, jpegOrientation, mySeq, ::finishWithError, { onTrace(it) })
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
            }, flowHandler)

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
        val recording = java.util.concurrent.atomic.AtomicBoolean(false)
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
                        if (recording.get()) {
                            try { recorder?.stop() } catch (_: Exception) { }
                        }
                    } catch (_: Exception) {
                    }
                    recording.set(false)
                    releaseRecorder()
                    releaseSurface()
                    try {
                        val stale = pendingOut
                        pendingOut = null
                        activeVideoFile = null
                        activeRecorder = null
                        // Galat sesi ini sudah berkabar lewat
                        // jalurnya sendiri — penanda jangan
                        // ikut memicu laporan 'terputus' palsu
                        // di start berikutnya (issue #1509).
                        try { java.io.File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "rekam_video_aktif.txt").delete() } catch (_: Exception) { }
                        if (stale != null && stale.exists()) stale.delete()
                    } catch (_: Exception) {
                    }
                    try {
                        cleanup()
                    } finally {
                        capturing.set(false)
                    }
                }
                try {
                    releaseRecorder()
                } catch (_: Exception) {
                }
                try {
                    releaseSurface()
                } catch (_: Exception) {
                }
                onError(e)
            }
        }

        fun finishWithVideo(file: File) {
            if (done.compareAndSet(false, true)) {
                if (sessionSeq.get() == mySeq) capturing.set(false)
                removeEverywhere(timeoutRunnable)
                removeEverywhere(stopRunnable)
                // Ambil identitas SEBELUM release: releaseRecorder() menull
                // `recorder` lebih dulu sehingga perbandingan sesudahnya tak
                // pernah cocok; activeVideoFile juga menunjuk tmpFile,
                // sedangkan `file` adalah hasil rename-nya (issue #674).
                val finishedRecorder = recorder
                val finishedFile = pendingOut
                releaseRecorder()
                releaseSurface()
                pendingOut = null
                if (activeVideoFile === finishedFile || activeVideoFile === file) activeVideoFile = null
                try { java.io.File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "rekam_video_aktif.txt").delete() } catch (_: Exception) { }
                if (activeRecorder === finishedRecorder) activeRecorder = null
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
            val flowHandler = currentBackgroundHandler()
            if (flowHandler == null) {
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
                postDelayedOwn(flowHandler, timeout, timeoutMs)
            } catch (_: Exception) {
                false
            }
            if (!watchdogScheduled) {
                finishWithError(Exception("Kamera tak tersedia (pemantau timeout belum siap)"))
                return
            }
            val videoSize = chooseVideoSize(cameraManager, cameraId)
            val outFile = File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "video_" + TimeFmt.fileStamp(System.currentTimeMillis()) + "_" + java.util.UUID.randomUUID() + ".mp4")
            val tmpFile = File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), outFile.name + ".tmp")
            pendingOut = tmpFile
            val rec = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                android.media.MediaRecorder()
            }
            recorder = rec
            rec.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(android.media.MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(android.media.MediaRecorder.VideoEncoder.H264)
            rec.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            // Trek audio hemat seperti perekam suara
            // (issue #1577): 32 kbps mono 22,05 kHz.
            try { rec.setAudioEncodingBitRate(32_000) } catch (_: Exception) { }
            try { rec.setAudioSamplingRate(22_050) } catch (_: Exception) { }
            try { rec.setAudioChannels(1) } catch (_: Exception) { }
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
            var superseded = false
            synchronized(captureLock) {
                if (sessionSeq.get() != mySeq) {
                    try { rec.release() } catch (_: Exception) {
                    }
                    try { tmpFile.delete() } catch (_: Exception) {
                    }
                    if (pendingOut === tmpFile) pendingOut = null
                    superseded = true
                } else {
                    // Terbitkan penanda global HANYA sesudah generasi sesi
                    // terkonfirmasi di bawah kunci: sebelumnya penanda
                    // dipasang sebelum prepare() sehingga forceReset()
                    // serentak bisa melepas recorder yang sedang disiapkan
                    // (issue #676).
                    activeVideoFile = tmpFile
                    activeRecorder = rec
                    // Penanda rekam video (issue #1509):
                    // padanan penanda audio #1461 — kematian
                    // proses di tengah rekam video dulu
                    // hilang senyap (.tmp dibuang init tanpa
                    // kabar); kini proses berikutnya membaca
                    // penanda ini dan melaporkannya.
                    try {
                        java.io.File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "rekam_video_aktif.txt").writeText(System.currentTimeMillis().toString())
                    } catch (_: Exception) {
                    }
                }
            }
            if (superseded) {
                finishWithError(Exception("Sesi kamera digantikan"))
                return
            }
            val recordSurface = videoSurface ?: run { finishWithError(Exception("Permukaan rekaman tak tersedia")); return }
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        var superseded = false
                        synchronized(captureLock) {
                            if (done.get() || sessionSeq.get() != mySeq) {
                                try { camera.close() } catch (_: Exception) { }
                                superseded = true
                            } else {
                                cameraDevice = camera
                            }
                        }
                        if (superseded) {
                            finishWithError(Exception("Sesi kamera digantikan"))
                            return
                        }
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(recordSurface)
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        camera.createCaptureSession(
                            listOf(recordSurface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    var superseded = false
                                    synchronized(captureLock) {
                                        if (sessionSeq.get() != mySeq) {
                                            try { session.close() } catch (_: Exception) { }
                                            superseded = true
                                        } else {
                                            captureSession = session
                                        }
                                    }
                                    if (superseded) {
                                        finishWithError(Exception("Sesi kamera digantikan"))
                                        return
                                    }
                                    try {
                                        session.setRepeatingRequest(builder.build(), null, flowHandler)
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
                                    recording.set(true)
                                    onTrace("trace: recording started")
                                    val stop = Runnable {
                                        try {
                                            var stopOk = true
                                            try { rec.stop() } catch (_: Exception) { stopOk = false }
                                            recording.set(false)
                                            val ownsDevice = synchronized(captureLock) {
                                                if (activeRecorder === rec) {
                                                    try { captureSession?.close() } catch (_: Exception) { }
                                                    captureSession = null
                                                    try { cameraDevice?.close() } catch (_: Exception) { }
                                                    cameraDevice = null
                                                    true
                                                } else {
                                                    false
                                                }
                                            }
                                            releaseRecorder()
                                            releaseSurface()
                                            if (ownsDevice) stopBackgroundThread()
                                            val hasData = tmpFile.exists() && tmpFile.length() > 0L
                                            val moved = hasData && try { tmpFile.renameTo(outFile) } catch (_: Exception) { false }
                                            // stop() gagal = muxer tak
                                            // memfinalisasi berkas (moov
                                            // tak tertulis, tak bisa
                                            // diputar): buang + kabar
                                            // gagal seperti jalur audio
                                            // (issue #1379) — jangan
                                            // teruskan sebagai sukses.
                                            if (!moved || !stopOk) {
                                                try { tmpFile.delete() } catch (_: Exception) { }
                                                if (moved) try { outFile.delete() } catch (_: Exception) { }
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
                                        if (postDelayedOwn(flowHandler, stop, durationMs)) true
                                        else postDelayedOwn(mainHandler(), stop, durationMs)
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
                            flowHandler
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
            }, flowHandler)
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
                val chosen = if (within.isNotEmpty()) {
                    within.maxByOrNull { it.width.toLong() * it.height }
                } else {
                    sizes.minByOrNull { it.width.toLong() * it.height }
                }
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
        val flowHandler = currentBackgroundHandler()
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
                    }, flowHandler)
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
                postDelayedOwn(flowHandler, pending, 3_000L) || postDelayedOwn(mainHandler(), pending, 3_000L)
            } catch (_: Exception) { false }
            if (!meterScheduled) { fireStill(); return }
            val meteringBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val previewSurface = meteringSurface ?: run { onError(Exception("Kamera ditutup")); return }
            meteringBuilder.addTarget(previewSurface)
            meteringBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // Jumlah frame pratinjau minimum sebelum metering
            // dinilai mapan (issue #1446): keadaan AE null
            // (perangkat tak mengisi CONTROL_AE_STATE) dan
            // FLASH_REQUIRED dulu diterima pada frame PERTAMA,
            // sehingga pemanasan paparan terlewati total dan
            // foto kurang terang bisa lolos. Jaring timeout
            // 3 detik di atas tetap batas kerasnya.
            val bingkaiMetering = java.util.concurrent.atomic.AtomicInteger(0)
            session.setRepeatingRequest(
                meteringBuilder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (bingkaiMetering.incrementAndGet() < 3) return
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
                flowHandler
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
            // Rumus resmi Android (issue #948): tanda putaran
            // perangkat berlawanan untuk lensa belakang, searah
            // untuk lensa depan. Rumus lama terbalik — potret
            // kebetulan benar (putaran 0), tetapi hasil lanskap
            // terbalik 180 derajat.
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                (sensor + degrees) % 360
            } else {
                (sensor - degrees + 360) % 360
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
        mySeq: Int,
        onError: (Exception) -> Unit,
        onTrace: (String) -> Unit = {}
    ) {
        val flowHandler = currentBackgroundHandler()
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
                        var superseded = false
                        synchronized(captureLock) {
                            if (sessionSeq.get() != mySeq) {
                                try { session.close() } catch (_: Exception) { }
                                superseded = true
                            } else {
                                captureSession = session
                            }
                        }
                        if (superseded) {
                            onError(Exception("Sesi kamera digantikan"))
                            return
                        }
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
                flowHandler
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
                // Plafon keras (issue #1589): utamakan
                // ukuran TERBESAR yang masih di bawah target
                // — pilihan 'terdekat' lama bisa mendarat di
                // 1080p+ pada kamera yang tak menawarkan
                // ukuran <= target (JPEG lebih besar, encode
                // lebih lama, unggah lebih mahal). Hanya bila
                // tak ada satu pun di bawah target, kembali ke
                // terdekat di atasnya.
                val chosen = pool.filter { it.width.toLong() * it.height <= targetArea }.maxByOrNull { it.width * it.height }
                    ?: pool.minByOrNull { kotlin.math.abs(it.width * it.height - targetArea) }
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
        val file = File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), "camera_${timestamp}_${java.util.UUID.randomUUID()}.jpg")
        val tmp = File(com.redeye.parentalmonitor.utils.MediaDirs.queueDir(context), file.name + ".tmp")
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
        synchronized(captureLock) {
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
        }
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
