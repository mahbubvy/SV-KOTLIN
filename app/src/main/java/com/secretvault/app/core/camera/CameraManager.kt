package com.secretvault.app.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.secretvault.app.core.worker.MediaSaveQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(
    private val context: Context,
    private val saveQueue: MediaSaveQueue
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var recordingStartTime: Long = 0L
    private var cameraReady = false
    private var cmfCameraView: CmfHighFpsCameraView? = null
    private var cmfHighFpsActive = false
    private var cmfRecordingStartTime = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationTick = object : Runnable {
        override fun run() {
            if (!cmfHighFpsActive || !_isRecording.value) return
            _recordingDurationSeconds.value = ((SystemClock.elapsedRealtime() - cmfRecordingStartTime) / 1000L).toInt()
            durationHandler.postDelayed(this, 250L)
        }
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cmfHighFpsView: CmfHighFpsCameraView,
        cameraMode: CameraMode,
        lensFacing: LensFacing,
        flashMode: FlashMode,
        videoMode: VideoMode,
        recordAudio: Boolean,
        onVideoConfigured: (List<VideoMode>, VideoMode) -> Unit,
        onCameraReady: () -> Unit,
        onCameraError: (String) -> Unit
    ) {
        if (activeRecording != null || _isRecording.value) return
        cameraReady = false
        cmfHighFpsActive = false
        cmfCameraView = cmfHighFpsView
        cmfHighFpsView.deactivate()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            try {
                cameraProvider?.unbindAll()

                if (cameraMode == CameraMode.PHOTO) {
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(flashMode.imageCaptureMode)
                        .build()
                    videoCapture = null

                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        lensFacing.selector,
                        preview,
                        imageCapture
                    )
                } else {
                    imageCapture = null
                    val cameraInfo = lensFacing.selector.filter(cameraProvider!!.availableCameraInfos).first()
                    val supportedQualities = Recorder.getVideoCapabilities(cameraInfo)
                        .getSupportedQualities(DynamicRange.SDR)
                    val streamMap = Camera2CameraInfo.from(cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val supportedRanges = cameraInfo.supportedFrameRateRanges.map { it.lower..it.upper }
                    val supportedModes = VideoMode.entries.filter { mode ->
                        if (mode == VideoMode.FHD_60 && CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK) return@filter false
                        if (mode.quality !in supportedQualities) return@filter false
                        val size = QualitySelector.getResolution(cameraInfo, mode.quality) ?: return@filter false
                        val minFrameDurationNs = try {
                            streamMap?.getOutputMinFrameDuration(MediaCodec::class.java, size) ?: 0L
                        } catch (e: IllegalArgumentException) {
                            0L
                        }
                        supportsVideoFrameRate(mode.fps, minFrameDurationNs, supportedRanges)
                    }.toMutableList().apply {
                        if (CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK) add(VideoMode.FHD_60)
                    }
                    val selectedMode = videoMode.takeIf { it in supportedModes }
                        ?: supportedModes.firstOrNull()
                        ?: throw IllegalStateException("No supported video mode")
                    onVideoConfigured(supportedModes, selectedMode)

                    if (selectedMode == VideoMode.FHD_60 && CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK) {
                        camera = null
                        cmfHighFpsActive = true
                        cmfHighFpsView.start(
                            includeAudio = recordAudio,
                            onReady = {
                                cameraReady = true
                                onCameraReady()
                            },
                            onError = { error ->
                                cameraReady = false
                                onCameraError(error)
                            }
                        )
                        return@addListener
                    }

                    val size = QualitySelector.getResolution(cameraInfo, selectedMode.quality)

                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(selectedMode.quality))
                        .setExecutor(cameraExecutor)
                        .build()

                    videoCapture = VideoCapture.Builder(recorder)
                        .setTargetFrameRate(Range(selectedMode.fps, selectedMode.fps))
                        .build()
                    Log.i("VaultCamera", "Video target: $size, ${selectedMode.fps} fps; supported: $supportedModes")

                    camera = cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        lensFacing.selector,
                        preview,
                        videoCapture
                    )
                }
                cameraReady = true
                onCameraReady()
            } catch (e: Exception) {
                videoCapture = null
                imageCapture = null
                onCameraError(e.message ?: "Camera could not start")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setFlashMode(flashMode: FlashMode) {
        imageCapture?.flashMode = flashMode.imageCaptureMode
        if (cmfHighFpsActive) cmfCameraView?.setTorch(flashMode == FlashMode.ON)
    }

    fun focusOnPoint(
        x: Float,
        y: Float,
        previewView: PreviewView,
        cmfHighFpsView: CmfHighFpsCameraView
    ) {
        if (cmfHighFpsActive) {
            cmfHighFpsView.focusAt(x, y)
            return
        }
        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(
            previewView.width.toFloat(),
            previewView.height.toFloat()
        )
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun capturePhoto(
        autoFaceBlur: Boolean,
        onShutter: () -> Unit,
        onSaved: () -> Unit
    ) {
        if (!cameraReady) return
        val capture = imageCapture ?: return
        onShutter()

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val buffer: ByteBuffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    saveQueue.enqueuePhoto(bytes, rotationDegrees, autoFaceBlur) {
                        onSaved()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        recordAudio: Boolean,
        onRecordingStarted: () -> Unit
    ): Boolean {
        if (!cameraReady || activeRecording != null) return false
        if (cmfHighFpsActive) {
            val started = cmfCameraView?.startRecording() ?: false
            if (started) {
                cmfRecordingStartTime = SystemClock.elapsedRealtime()
                _recordingDurationSeconds.value = 0
                _isRecording.value = true
                durationHandler.post(durationTick)
                onRecordingStarted()
            }
            return started
        }
        val capture = videoCapture ?: return false
        val tempFile = File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(tempFile).build()

        recordingStartTime = System.currentTimeMillis()
        val pendingRecording = capture.output.prepareRecording(context, outputOptions)
        if (recordAudio) {
            try {
                pendingRecording.withAudioEnabled()
            } catch (e: Exception) {
                // Audio permission missing
            }
        }

        activeRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _isRecording.value = true
                    onRecordingStarted()
                }
                is VideoRecordEvent.Status -> {
                    val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                    _recordingDurationSeconds.value = (durationMs / 1000L).toInt()
                }
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
                    _isRecording.value = false
                    _recordingDurationSeconds.value = 0
                    if (!event.hasError()) {
                        val totalDurationMs = System.currentTimeMillis() - recordingStartTime
                        saveQueue.enqueueVideo(tempFile, totalDurationMs)
                    } else {
                        if (tempFile.exists()) tempFile.delete()
                    }
                }
            }
        }
        return true
    }

    fun stopVideoRecording() {
        if (cmfHighFpsActive && _isRecording.value) {
            durationHandler.removeCallbacks(durationTick)
            cmfCameraView?.stopRecording { file, durationMs ->
                _isRecording.value = false
                _recordingDurationSeconds.value = 0
                if (file != null) saveQueue.enqueueVideo(file, durationMs)
            }
            return
        }
        activeRecording?.stop()
    }

    fun release() {
        durationHandler.removeCallbacks(durationTick)
        cmfCameraView?.release()
        _isRecording.value = false
        _recordingDurationSeconds.value = 0
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }
}
