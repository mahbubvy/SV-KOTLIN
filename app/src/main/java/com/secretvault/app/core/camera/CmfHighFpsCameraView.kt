package com.secretvault.app.core.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat
import java.io.File
import java.util.concurrent.Executor

class CmfHighFpsCameraView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    companion object {
        const val CAMERA_ID = "0"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 60
        private const val VIDEO_BIT_RATE = 16_000_000
        private const val SESSION_OPERATION_MODE = 0xF008
        fun isCmfPhone1(): Boolean = isCmfPhone1(Build.MANUFACTURER, Build.MODEL, Build.DEVICE)
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var active = false
    private var sessionReady = false
    private var recording = false
    @Volatile private var stoppingRecording = false
    private var recordingStartedAt = 0L
    private var includeAudio = false
    private var orientationHint = 90
    private var onReady: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var focusReset: Runnable? = null
    private var torchEnabled = false
    private var zoomRatio = 1f
    val currentZoom: Float get() = zoomRatio
    val maximumZoom: Float get() = (cameraManager.getCameraCharacteristics(CAMERA_ID)
        .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).coerceIn(1f, 100f)
    private var streamRenderer: com.secretvault.app.core.stream.StreamPreviewRenderer? = null
    private var renderedPreview: com.secretvault.app.core.stream.StreamPreviewRenderer.NativeInput? = null
    private var cameraGeneration = 0

    init {
        surfaceTextureListener = this
    }

    fun start(includeAudio: Boolean, orientationHint: Int, onReady: () -> Unit, onError: (String) -> Unit,
              streamRenderer: com.secretvault.app.core.stream.StreamPreviewRenderer? = null) {
        stopCamera()
        zoomRatio = 1f
        this.streamRenderer = streamRenderer
        this.includeAudio = includeAudio
        this.orientationHint = orientationHint
        this.onReady = onReady
        this.onError = onError
        active = true
        startBackgroundThread()
        if (isAvailable) openCamera()
    }

    fun setZoomRatio(ratio: Float, onApplied: (Boolean) -> Unit) {
        if (!sessionReady || !ratio.isFinite() || ratio !in 1f..maximumZoom) { onApplied(false); return }
        val generation = cameraGeneration
        backgroundHandler?.post {
            if (generation != cameraGeneration || !sessionReady) { post { onApplied(false) }; return@post }
            zoomRatio = ratio
            applyRepeatingRequest(onApplied)
        } ?: onApplied(false)
    }

    fun focusAt(viewX: Float, viewY: Float) {
        val handler = backgroundHandler ?: return
        handler.post {
            try {
                val camera = cameraDevice ?: return@post
                val session = captureSession ?: return@post
                val preview = previewSurface ?: return@post
                val recorder = recorderSurface ?: return@post
                val characteristics = cameraManager.getCameraCharacteristics(CAMERA_ID)
                val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@post
                val canFocus = (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0
                val canMeter = (characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0
                if (!canFocus && !canMeter) return@post
                val viewAspect = width.toFloat() / height.coerceAtLeast(1)
                val bufferAspect = VIDEO_HEIGHT.toFloat() / VIDEO_WIDTH
                val cropScaleX = if (viewAspect < bufferAspect) bufferAspect / viewAspect else 1f
                val x = (((viewX - width / 2f) / cropScaleX) + width / 2f) / width
                val displayX = x.coerceIn(0f, 1f)
                val displayY = (viewY / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                val centerX = activeArray.left + (displayY * activeArray.width()).toInt()
                val centerY = activeArray.top + ((1f - displayX) * activeArray.height()).toInt()
                val half = (minOf(activeArray.width(), activeArray.height()) * 0.06f).toInt()
                val rect = Rect(
                    (centerX - half).coerceIn(activeArray.left, activeArray.right - 1),
                    (centerY - half).coerceIn(activeArray.top, activeArray.bottom - 1),
                    (centerX + half).coerceIn(activeArray.left + 1, activeArray.right),
                    (centerY + half).coerceIn(activeArray.top + 1, activeArray.bottom)
                )
                val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX)
                val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(preview)
                    addTarget(recorder)
                    configureRequest(this)
                    if (canFocus) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    }
                    if (canMeter) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                session.capture(builder.build(), null, handler)
                focusReset?.let(handler::removeCallbacks)
                focusReset = Runnable { if (sessionReady) applyRepeatingRequest() }.also { handler.postDelayed(it, 5_000) }
            } catch (error: Exception) {
                reportError("Tap focus failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun startRecording(): Boolean {
        if (!sessionReady || recording) return false
        return try {
            mediaRecorder?.start() ?: return false
            recording = true
            recordingStartedAt = android.os.SystemClock.elapsedRealtime()
            true
        } catch (error: Exception) {
            reportError("CMF 60 FPS recording could not start: ${error.message ?: error.javaClass.simpleName}")
            false
        }
    }

    fun stopRecording(onFinished: (File?, Long) -> Unit) {
        val handler = backgroundHandler ?: return
        if (!recording) return
        recording = false
        stoppingRecording = true
        val duration = android.os.SystemClock.elapsedRealtime() - recordingStartedAt
        handler.post {
            val candidate = outputFile
            val file = try {
                captureSession?.stopRepeating()
                captureSession?.abortCaptures()
                mediaRecorder?.stop()
                candidate?.takeIf { it.exists() && it.length() > 0L }
            } catch (_: Exception) {
                null
            }
            if (file == null) candidate?.delete()
            outputFile = null
            try { mediaRecorder?.reset(); mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            recorderSurface?.release()
            recorderSurface = null
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null
            sessionReady = false
            if (active && cameraDevice != null) createCmfSession()
            stoppingRecording = false
            post { onFinished(file, duration) }
        }
    }

    fun setTorch(enabled: Boolean, onApplied: ((Boolean) -> Unit)? = null) {
        torchEnabled = enabled
        if (sessionReady) applyRepeatingRequest(onApplied) else onApplied?.invoke(false)
    }

    fun release() {
        if (recording) {
            recording = false
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            outputFile?.delete()
        }
        active = false
        stopCamera()
        stopBackgroundThread()
    }

    fun deactivate() {
        active = false
        stopCamera()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread?.isAlive == true) return
        backgroundThread = HandlerThread("CmfHighFpsCamera").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        val thread = backgroundThread ?: return
        thread.quitSafely()
        try { thread.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!active || cameraDevice != null) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reportError("Camera permission is required for CMF 60 FPS recording.")
            return
        }
        try {
            val generation = cameraGeneration
            cameraManager.openCamera(CAMERA_ID, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!active || generation != cameraGeneration) { camera.close(); return }
                    cameraDevice = camera; createCmfSession()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (generation != cameraGeneration) return
                    cameraDevice = null; reportError("CMF rear camera disconnected.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (generation != cameraGeneration) return
                    cameraDevice = null; reportError("CMF rear camera error $error.")
                }
            }, backgroundHandler)
        } catch (error: Exception) {
            reportError("Could not open CMF rear camera: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    @SuppressLint("WrongConstant")
    private fun createCmfSession() {
        val camera = cameraDevice ?: return
        val texture = surfaceTexture ?: return
        val handler = backgroundHandler ?: return
        try {
            val renderer = streamRenderer
            texture.setDefaultBufferSize(if (renderer == null) VIDEO_WIDTH else VIDEO_HEIGHT,
                if (renderer == null) VIDEO_HEIGHT else VIDEO_WIDTH)
            if (renderedPreview != null) renderedPreview?.close() else previewSurface?.release()
            renderedPreview = null
            val displaySurface = Surface(texture)
            if (renderer != null) {
                renderedPreview = renderer.nativePreview(displaySurface, android.util.Size(VIDEO_WIDTH, VIDEO_HEIGHT),
                    android.util.Size(VIDEO_HEIGHT, VIDEO_WIDTH))
                previewSurface = requireNotNull(renderedPreview).surface
                displaySurface.release()
            } else previewSurface = displaySurface
            prepareRecorder()
            val preview = requireNotNull(previewSurface)
            val recorder = requireNotNull(recorderSurface)
            val executor = Executor { handler.post(it) }
            val configuration = SessionConfiguration(
                SESSION_OPERATION_MODE,
                listOf(OutputConfiguration(preview), OutputConfiguration(recorder)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!active || cameraDevice !== camera) { session.close(); return }
                        captureSession = session
                        sessionReady = true
                        applyRepeatingRequest()
                        post { onReady?.invoke() }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        reportError("CMF 60 FPS camera session was rejected.")
                    }
                }
            )
            val parameters = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            configureRequest(parameters)
            configuration.setSessionParameters(parameters.build())
            camera.createCaptureSession(configuration)
        } catch (error: Exception) {
            reportError("Could not configure CMF 1080p/60: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun prepareRecorder() {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        outputFile = File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.mp4")
        val audioAllowed = includeAudio && ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (audioAllowed) recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mediaRecorder = recorder
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(requireNotNull(outputFile).absolutePath)
        recorder.setVideoEncodingBitRate(VIDEO_BIT_RATE)
        recorder.setVideoFrameRate(VIDEO_FPS)
        recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (audioAllowed) {
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(48_000)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        recorder.setOrientationHint(orientationHint)
        recorder.prepare()
        recorderSurface = recorder.surface
    }

    private fun configureRequest(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(60, 60))
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        builder.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        cameraManager.getCameraCharacteristics(CAMERA_ID).get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            val crop = zoomCrop(it.left, it.top, it.right, it.bottom, zoomRatio)
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(crop[0], crop[1], crop[2], crop[3]))
        }
        try { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) } catch (_: IllegalArgumentException) {}
        try {
            builder.set(CaptureRequest.Key("com.mediatek.streamingfeature.hfpsMode", Int::class.javaObjectType), 1)
        } catch (error: Exception) {
            throw CameraAccessException(CameraAccessException.CAMERA_ERROR, "CMF high-FPS mode unavailable: ${error.message}")
        }
    }

    private fun applyRepeatingRequest(onApplied: ((Boolean) -> Unit)? = null) {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val preview = previewSurface ?: return
        val recorder = recorderSurface ?: return
        val handler = backgroundHandler ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(recorder)
                configureRequest(this)
            }
            val expected = if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            val expectedCrop = request.get(CaptureRequest.SCALER_CROP_REGION)
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            session.setRepeatingRequest(request.build(), if (onApplied == null) null else object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    val crop = result.get(android.hardware.camera2.CaptureResult.SCALER_CROP_REGION)
                    val zoomApplied = expectedCrop == null || crop != null && kotlin.math.abs(crop.width() - expectedCrop.width()) <= 16 &&
                        kotlin.math.abs(crop.height() - expectedCrop.height()) <= 16
                    if (result.get(android.hardware.camera2.CaptureResult.FLASH_MODE) == expected && zoomApplied && completed.compareAndSet(false, true)) post { onApplied(true) }
                }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    if (completed.compareAndSet(false, true)) post { onApplied(false) }
                }
            }, handler)
        } catch (error: Exception) {
            post { onApplied?.invoke(false) }
            reportError("CMF 60 FPS request failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun reportError(message: String) {
        post { onError?.invoke(message) }
    }

    private fun stopCamera() {
        cameraGeneration++
        sessionReady = false
        focusReset?.let { backgroundHandler?.removeCallbacks(it) }
        focusReset = null
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { mediaRecorder?.reset(); mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        recorderSurface?.release()
        recorderSurface = null
        if (renderedPreview != null) renderedPreview?.close() else previewSurface?.release()
        renderedPreview = null
        previewSurface = null
        if (!recording) outputFile?.delete()
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val viewAspect = viewWidth.toFloat() / viewHeight
        val bufferAspect = VIDEO_HEIGHT.toFloat() / VIDEO_WIDTH
        val scaleX: Float
        val scaleY: Float
        if (viewAspect < bufferAspect) { scaleX = bufferAspect / viewAspect; scaleY = 1f }
        else { scaleX = 1f; scaleY = viewAspect / bufferAspect }
        setTransform(Matrix().apply { setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f) })
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { configureTransform(width, height); if (active) openCamera() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = configureTransform(width, height)
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (stoppingRecording) {
            active = false
            backgroundHandler?.post { stopCamera() }
        } else stopCamera()
        return true
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
