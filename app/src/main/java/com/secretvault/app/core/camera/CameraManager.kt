package com.secretvault.app.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
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
import com.secretvault.app.core.stream.StreamRecordingState
import com.secretvault.app.core.stream.StreamSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

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
    private var zoomRequest = 0
    private var cameraSession = 0
    private var streaming = false
    private var cmfCameraView: CmfHighFpsCameraView? = null
    private var cmfHighFpsActive = false
    private var cmfRecordingStartTime = 0L
    private val durationHandler = Handler(Looper.getMainLooper())
    private val durationTick = object : Runnable {
        override fun run() {
            if (!cmfHighFpsActive || !_isRecording.value) return
            _recordingDurationSeconds.value = ((SystemClock.elapsedRealtime() - cmfRecordingStartTime) / 1000L).toInt()
            if (streaming) _streamRecordingState.value = _streamRecordingState.value.copy(seconds = _recordingDurationSeconds.value)
            durationHandler.postDelayed(this, 250L)
        }
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()
    private val _streamRecordingState = MutableStateFlow(StreamRecordingState())
    val streamRecordingState: StateFlow<StreamRecordingState> = _streamRecordingState.asStateFlow()
    private val _streamSettings = MutableStateFlow(StreamSettingsState())
    val streamSettings: StateFlow<StreamSettingsState> = _streamSettings.asStateFlow()
    private var streamVideoSaved: CompletableDeferred<Boolean>? = null
    private var streamVideoFinalized: CompletableDeferred<Unit>? = null
    private var nativeStopping = false

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cmfHighFpsView: CmfHighFpsCameraView,
        cameraMode: CameraMode,
        lensFacing: LensFacing,
        flashMode: FlashMode,
        videoMode: VideoMode,
        videoOrientation: VideoOrientation,
        recordAudio: Boolean,
        selectedRearLensId: String?,
        onRearLensOptions: (List<CameraLensOption>) -> Unit,
        onVideoConfigured: (List<VideoMode>, VideoMode) -> Unit,
        onCameraReady: () -> Unit,
        onCameraError: (String) -> Unit,
        streamRenderer: com.secretvault.app.core.stream.StreamPreviewRenderer? = null,
        onAvailableFacings: (List<LensFacing>) -> Unit = {},
        streamVideoMode: VideoMode? = null
    ) {
        if (activeRecording != null || _isRecording.value) return
        streaming = streamRenderer != null
        _streamRecordingState.value = StreamRecordingState(saving = _streamRecordingState.value.saving)
        _streamSettings.value = StreamSettingsState()
        val session = ++cameraSession
        cameraReady = false
        camera = null
        zoomRequest++
        cmfHighFpsActive = false
        cmfCameraView = cmfHighFpsView
        cmfHighFpsView.deactivate()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (session != cameraSession) return@addListener
            cameraProvider = cameraProviderFuture.get()

            val physicalCameraId = selectedRearLensId
                ?.takeIf { lensFacing == LensFacing.BACK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P }
            val previewBuilder = Preview.Builder()
            if (streamRenderer != null) {
                previewBuilder.setTargetFrameRate(Range(30, 30))
            }
            if (cameraMode == CameraMode.VIDEO) {
                previewBuilder.setTargetRotation(videoOrientation.targetRotation)
            }
            physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
            preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            try {
                cameraProvider?.unbindAll()
                val availableInfos = cameraProvider!!.availableCameraInfos
                onAvailableFacings(LensFacing.entries.filter { it.selector.filter(availableInfos).isNotEmpty() })
                val backCameraInfo = LensFacing.BACK.selector.filter(availableInfos).firstOrNull()
                val rearLensOptions = backCameraInfo?.let {
                    runCatching { discoverRearLensOptions(it) }
                        .getOrDefault(listOf(CameraLensOption(null, "1×")))
                }.orEmpty()
                onRearLensOptions(rearLensOptions)
                val selectedCameraSelector = createCameraSelector(lensFacing, selectedRearLensId)
                fun bind(capture: UseCase?, additional: UseCase? = null): Camera? {
                    val group = UseCaseGroup.Builder().addUseCase(requireNotNull(preview))
                    capture?.let(group::addUseCase)
                    additional?.let(group::addUseCase)
                    streamRenderer?.let { group.addEffect(it.effect) }
                    return cameraProvider?.bindToLifecycle(lifecycleOwner, selectedCameraSelector, group.build())
                }

                if (cameraMode == CameraMode.PHOTO || streaming) {
                    val imageCaptureBuilder = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setFlashMode(flashMode.imageCaptureMode)
                    physicalCameraId?.let { Camera2Interop.Extender(imageCaptureBuilder).setPhysicalCameraId(it) }
                    Camera2Interop.Extender(imageCaptureBuilder).setSessionCaptureCallback(
                        object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession,
                                request: android.hardware.camera2.CaptureRequest,
                                result: android.hardware.camera2.TotalCaptureResult) {
                                if (request.get(android.hardware.camera2.CaptureRequest.CONTROL_CAPTURE_INTENT) !=
                                    android.hardware.camera2.CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE) return
                                val focal = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                                val physical = if (Build.VERSION.SDK_INT >= 28)
                                    result.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                                val zoom = if (Build.VERSION.SDK_INT >= 30)
                                    result.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO) else null
                                Log.i("VaultCamera", "Still capture: focal=$focal physical=$physical zoom=$zoom")
                            }
                        })
                    imageCapture = imageCaptureBuilder.build()
                    videoCapture = null

                    if (streaming) {
                        val info = selectedCameraSelector.filter(availableInfos).first()
                        val modes = supportedVideoModes(info, physicalCameraId, lensFacing).sortedByDescending { it.ordinal }.toMutableList()
                        val order = listOfNotNull(streamVideoMode?.takeIf { it in modes }) + modes.filter { it != streamVideoMode }
                        var bound = false
                        for (mode in order) {
                            _streamSettings.value = StreamSettingsState(modes.map { it.ordinal }, mode.ordinal,
                                info.hasFlashUnit(), flashMode.ordinal, mode.fps == 30)
                            val audio = recordAudio && ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (mode == VideoMode.FHD_60 && CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK && physicalCameraId == null) {
                                imageCapture = null
                                cmfHighFpsActive = true
                                cmfHighFpsView.start(audio, videoOrientation.recorderOrientationHint, onReady = {
                                    _streamRecordingState.value = _streamRecordingState.value.copy(available = true, audio = audio)
                                    cameraReady = true; setFlashMode(flashMode); onCameraReady()
                                }, onError = onCameraError, streamRenderer = streamRenderer)
                                return@addListener
                            }
                            val streamPreviewBuilder = Preview.Builder().setTargetFrameRate(Range(mode.fps, mode.fps))
                                .setTargetRotation(videoOrientation.targetRotation)
                            val interop = Camera2Interop.Extender(streamPreviewBuilder)
                            interop.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.fps, mode.fps))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) physicalCameraId?.let { interop.setPhysicalCameraId(it) }
                            preview = streamPreviewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
                            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(mode.quality)).setExecutor(cameraExecutor).build()
                            val builder = VideoCapture.Builder(recorder).setTargetFrameRate(Range(mode.fps, mode.fps))
                                .setTargetRotation(videoOrientation.targetRotation)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
                            videoCapture = builder.build()
                            try {
                                camera = bind(if (mode.fps == 30) imageCapture else null, videoCapture)
                                if (mode.fps == 60) imageCapture = null
                                _streamRecordingState.value = _streamRecordingState.value.copy(available = true, audio = audio)
                                bound = true; break
                            } catch (_: IllegalArgumentException) { cameraProvider?.unbindAll(); modes.remove(mode) }
                        }
                        if (!bound) {
                            preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
                            videoCapture = null; camera = bind(imageCapture)
                            _streamSettings.value = StreamSettingsState(flashAvailable = info.hasFlashUnit(), flashMode = flashMode.ordinal, photoAvailable = true)
                        } else _streamSettings.value = _streamSettings.value.copy(modes = modes.map { it.ordinal })
                    } else camera = bind(imageCapture)
                } else {
                    imageCapture = null
                    val cameraInfo = selectedCameraSelector.filter(availableInfos).first()
                    val supportedModes = supportedVideoModes(cameraInfo, physicalCameraId, lensFacing)
                    val selectedMode = videoMode.takeIf { it in supportedModes }
                        ?: supportedModes.firstOrNull()
                        ?: throw IllegalStateException("No supported video mode")
                    onVideoConfigured(supportedModes, selectedMode)

                    if (selectedMode == VideoMode.FHD_60 && selectedRearLensId == null &&
                        CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK) {
                        camera = null
                        cmfHighFpsActive = true
                        cmfHighFpsView.start(
                            includeAudio = recordAudio && !streaming,
                            orientationHint = videoOrientation.recorderOrientationHint,
                            onReady = {
                                cameraReady = true
                                onCameraReady()
                            },
                            onError = { error ->
                                cameraReady = false
                                onCameraError(error)
                            },
                            streamRenderer = streamRenderer
                        )
                        return@addListener
                    }

                    val size = QualitySelector.getResolution(cameraInfo, selectedMode.quality)

                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(selectedMode.quality))
                        .setExecutor(cameraExecutor)
                        .build()

                    val videoCaptureBuilder = VideoCapture.Builder(recorder)
                        .setTargetFrameRate(Range(selectedMode.fps, selectedMode.fps))
                        .setTargetRotation(videoOrientation.targetRotation)
                    physicalCameraId?.let { Camera2Interop.Extender(videoCaptureBuilder).setPhysicalCameraId(it) }
                    videoCapture = videoCaptureBuilder.build()
                    Log.i("VaultCamera", "Video target: $size, ${selectedMode.fps} fps; supported: $supportedModes")

                    camera = bind(videoCapture)
                }
                if (lensFacing == LensFacing.BACK && selectedRearLensId == null) {
                    camera?.cameraInfo?.zoomState?.value?.let { state ->
                        val logicalOptions = logicalRearLensOptions(state.minZoomRatio)
                        if (logicalOptions.size > 1) onRearLensOptions(logicalOptions)
                    }
                }
                cameraReady = true
                if (streaming) setFlashMode(flashMode)
                onCameraReady()
            } catch (e: Exception) {
                videoCapture = null
                imageCapture = null
                onCameraError(e.message ?: "Camera could not start")
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }


    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun supportedVideoModes(cameraInfo: CameraInfo, physicalCameraId: String?, lensFacing: LensFacing): List<VideoMode> {
        val selectedCharacteristics = physicalCameraId
            ?.let { camera2Manager.getCameraCharacteristics(it) }
        val streamMap = selectedCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: Camera2CameraInfo.from(cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val supportedRanges = selectedCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.lower..it.upper }
            ?: cameraInfo.supportedFrameRateRanges.map { it.lower..it.upper }
        val physicalVideoSizes = selectedCharacteristics?.let {
            streamMap?.getOutputSizes(MediaCodec::class.java)?.toSet().orEmpty()
        }
        val supportedQualities = Recorder.getVideoCapabilities(cameraInfo)
            .getSupportedQualities(DynamicRange.SDR)
        val useCmfHighFps = physicalCameraId == null &&
            CmfHighFpsCameraView.isCmfPhone1() && lensFacing == LensFacing.BACK
        val supportedModes = VideoMode.entries.filter { mode ->
            if (mode == VideoMode.FHD_60 && useCmfHighFps) return@filter false
            if (mode.quality !in supportedQualities) return@filter false
            val size = QualitySelector.getResolution(cameraInfo, mode.quality) ?: return@filter false
            if (physicalVideoSizes != null && size !in physicalVideoSizes) return@filter false
            val minFrameDurationNs = try {
                streamMap?.getOutputMinFrameDuration(MediaCodec::class.java, size) ?: 0L
            } catch (e: IllegalArgumentException) {
                0L
            }
            supportsVideoFrameRate(mode.fps, minFrameDurationNs, supportedRanges)
        }.toMutableList().apply {
            if (useCmfHighFps) add(VideoMode.FHD_60)
        }
        return supportedModes
    }
    private val camera2Manager: AndroidCameraManager
        get() = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    private fun createCameraSelector(lensFacing: LensFacing, physicalCameraId: String?): CameraSelector {
        if (lensFacing == LensFacing.BACK && physicalCameraId != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            return CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .setPhysicalCameraId(physicalCameraId)
                .build()
        }
        return lensFacing.selector
    }

    private fun discoverRearLensOptions(cameraInfo: CameraInfo): List<CameraLensOption> {
        val defaultOption = CameraLensOption(null, "1×")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return listOf(defaultOption)

        val logicalOptions = logicalRearLensOptions(cameraInfo.zoomState.value?.minZoomRatio ?: 1f)
        if (logicalOptions.size > 1) return logicalOptions
        val camera2Info = Camera2CameraInfo.from(cameraInfo)
        val logicalCharacteristics = camera2Manager.getCameraCharacteristics(camera2Info.cameraId)
        val logicalFocal = logicalCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return listOf(defaultOption)
        val logicalSensorWidth = logicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
            ?: return listOf(defaultOption)
        val logicalFov = logicalSensorWidth / logicalFocal
        if (logicalFov <= 0f) return listOf(defaultOption)

        val options = mutableListOf(defaultOption)
        logicalCharacteristics.physicalCameraIds.forEach { id ->
            val characteristics = runCatching { camera2Manager.getCameraCharacteristics(id) }.getOrNull() ?: return@forEach
            if (characteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@forEach
            val sensorWidth = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: return@forEach
            val focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return@forEach
            if (focal <= 0f) return@forEach
            val zoomRatio = logicalFov / (sensorWidth / focal)
            if (abs(zoomRatio - 1f) < 0.05f) return@forEach
            val outputMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@forEach
            if (outputMap.getOutputSizes(ImageFormat.JPEG).isNullOrEmpty() ||
                outputMap.getOutputSizes(MediaCodec::class.java).isNullOrEmpty()
            ) return@forEach
            options += CameraLensOption(id, String.format(Locale.US, "%.1f×", zoomRatio))
        }
        return options
    }

    fun setZoomRatio(ratio: Float, onApplied: () -> Unit = {}, onError: (String) -> Unit) {
        if (cmfHighFpsActive) { if (ratio == 1f) onApplied() else onError("This camera does not support the selected zoom"); return }
        if (_isRecording.value || camera == null) return
        val boundCamera = camera ?: return
        val state = boundCamera.cameraInfo.zoomState.value ?: return
        if (!ratio.isFinite() || ratio < state.minZoomRatio || ratio > state.maxZoomRatio) {
            onError("This camera does not support the selected zoom")
            return
        }
        val request = ++zoomRequest
        cameraReady = false
        val operation = boundCamera.cameraControl.setZoomRatio(ratio)
        operation.addListener({
            if (camera !== boundCamera || request != zoomRequest) return@addListener
            cameraReady = true
            try {
                operation.get()
                Log.i("VaultCamera", "Zoom applied: $ratio; range=${state.minZoomRatio}..${state.maxZoomRatio}")
                onApplied()
            } catch (e: Exception) {
                onError("Could not apply camera zoom")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun setFlashMode(flashMode: FlashMode, onApplied: (Boolean) -> Unit = {}) {
        imageCapture?.flashMode = flashMode.imageCaptureMode
        val generation = cameraSession
        fun applied(success: Boolean) {
            if (generation != cameraSession) { onApplied(false); return }
            if (success && streaming) _streamSettings.value = _streamSettings.value.copy(flashMode = flashMode.ordinal)
            onApplied(success)
        }
        if (cmfHighFpsActive) cmfCameraView?.setTorch(flashMode == FlashMode.ON, ::applied)
        else if (streaming && camera?.cameraInfo?.hasFlashUnit() == true) {
            val operation = requireNotNull(camera).cameraControl.enableTorch(flashMode == FlashMode.ON)
            operation.addListener({ applied(runCatching { operation.get(); true }.getOrDefault(false)) }, ContextCompat.getMainExecutor(context))
        } else applied(!streaming || flashMode != FlashMode.ON)
    }

    suspend fun setStreamFlash(mode: FlashMode): Boolean {
        if (!streaming || !cameraReady || !_streamSettings.value.flashAvailable || _streamRecordingState.value.saving) return false
        val applied = CompletableDeferred<Boolean>()
        setFlashMode(mode) { applied.complete(it) }
        return applied.await()
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
        onError: () -> Unit = {},
        onSaved: () -> Unit
    ) {
        if (!cameraReady) { onError(); return }
        val capture = imageCapture ?: run { onError(); return }
        onShutter()

        try { capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotationDegrees = image.imageInfo.rotationDegrees
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        saveQueue.enqueuePhoto(bytes, rotationDegrees, autoFaceBlur, onError = onError) { onSaved() }
                    } catch (_: Exception) {
                        onError()
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    onError()
                }
            }
        ) } catch (_: Exception) { onError() }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        recordAudio: Boolean,
        onRecordingStarted: () -> Unit,
        onRecordingError: () -> Unit = {}
    ): Boolean {
        if (!cameraReady || activeRecording != null || _streamRecordingState.value.saving ||
            streaming && !_streamRecordingState.value.available) return false
        if (cmfHighFpsActive) {
            if (streaming) {
                streamVideoSaved = CompletableDeferred()
                streamVideoFinalized = CompletableDeferred()
            }
            val started = cmfCameraView?.startRecording() ?: false
            if (started) {
                cmfRecordingStartTime = SystemClock.elapsedRealtime()
                _recordingDurationSeconds.value = 0
                _isRecording.value = true
                if (streaming) _streamRecordingState.value = _streamRecordingState.value.copy(recording = true, seconds = 0)
                durationHandler.post(durationTick)
                onRecordingStarted()
            } else if (streaming) { streamVideoSaved?.complete(false); streamVideoFinalized?.complete(Unit) }
            return started
        }
        val capture = videoCapture ?: return false
        val streamVideo = streaming
        val saved = if (streamVideo) CompletableDeferred<Boolean>().also { streamVideoSaved = it } else null
        val finalized = if (streamVideo) CompletableDeferred<Unit>().also { streamVideoFinalized = it } else null
        val tempFile = File(context.cacheDir, "temp_rec_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(tempFile).build()

        fun failedStart(): Boolean {
            saved?.complete(false); finalized?.complete(Unit)
            tempFile.delete(); onRecordingError(); return false
        }

        recordingStartTime = System.currentTimeMillis()
        val pendingRecording = try { capture.output.prepareRecording(context, outputOptions) }
            catch (_: Exception) { return failedStart() }
        if (recordAudio) {
            try {
                pendingRecording.withAudioEnabled()
            } catch (e: Exception) {
                if (streamVideo) {
                    return failedStart()
                }
            }
        }

        activeRecording = try {
            pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _isRecording.value = true
                        if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(recording = true, seconds = 0)
                        onRecordingStarted()
                    }
                    is VideoRecordEvent.Status -> {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        _recordingDurationSeconds.value = (durationMs / 1000L).toInt()
                        if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(seconds = _recordingDurationSeconds.value)
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        _isRecording.value = false
                        _recordingDurationSeconds.value = 0
                        if (!event.hasError() || streamVideo && event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE &&
                            event.recordingStats.recordedDurationNanos > 0 && tempFile.length() > 0) {
                            val totalDurationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                            if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(recording = false, saving = true)
                            saveQueue.enqueueVideo(tempFile, totalDurationMs, onComplete = {
                                ContextCompat.getMainExecutor(context).execute {
                                    if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(saving = false, seconds = 0)
                                    saved?.complete(true)
                                }
                            }, onError = {
                                ContextCompat.getMainExecutor(context).execute {
                                    if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(saving = false, seconds = 0)
                                    saved?.complete(false)
                                }
                            })
                        } else {
                            if (tempFile.exists()) tempFile.delete()
                            if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(recording = false, saving = false, seconds = 0)
                            saved?.complete(false)
                            onRecordingError()
                        }
                        finalized?.complete(Unit)
                    }
                }
            }
        } catch (_: Exception) { return failedStart() }
        return true
    }

    suspend fun setStreamRecording(start: Boolean, recordAudio: Boolean): Boolean {
        if (!streaming || !cameraReady) return false
        if (start) {
            if (_isRecording.value || activeRecording != null || _streamRecordingState.value.saving) return false
            val started = CompletableDeferred<Boolean>()
            if (!startVideoRecording(recordAudio, { started.complete(true) }, { started.complete(false) })) return false
            try { return started.await() }
            finally { if (!started.isCompleted) stopVideoRecording() }
        }
        val saved = streamVideoSaved ?: return false
        if (!_isRecording.value && !_streamRecordingState.value.saving) return false
        stopVideoRecording()
        return saved.await()
    }

    suspend fun awaitStreamRecordingFinalized() { streamVideoFinalized?.await() }

    fun stopVideoRecording() {
        if (cmfHighFpsActive && _isRecording.value) {
            if (nativeStopping) return
            nativeStopping = true
            durationHandler.removeCallbacks(durationTick)
            val streamVideo = streaming
            val saved = streamVideoSaved
            val finalized = streamVideoFinalized
            cmfCameraView?.stopRecording { file, durationMs ->
                nativeStopping = false
                _isRecording.value = false
                _recordingDurationSeconds.value = 0
                if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(recording = false, saving = file != null)
                finalized?.complete(Unit)
                if (file != null) saveQueue.enqueueVideo(file, durationMs, onComplete = {
                    ContextCompat.getMainExecutor(context).execute {
                        if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(saving = false, seconds = 0)
                        saved?.complete(true)
                    }
                }, onError = {
                    ContextCompat.getMainExecutor(context).execute {
                        if (streamVideo) _streamRecordingState.value = _streamRecordingState.value.copy(saving = false, seconds = 0)
                        saved?.complete(false)
                    }
                }) else saved?.complete(false)
            }
            return
        }
        activeRecording?.stop()
    }

    fun pausePreview() {
        if (_isRecording.value || activeRecording != null) return
        cameraSession++
        zoomRequest++
        cameraReady = false
        camera = null
        cmfCameraView?.deactivate()
        cameraProvider?.unbindAll()
    }

    fun release() {
        cameraSession++
        zoomRequest++
        camera = null
        cameraReady = false
        durationHandler.removeCallbacks(durationTick)
        if (streaming && cmfHighFpsActive && _isRecording.value) {
            stopVideoRecording()
            streamVideoFinalized?.invokeOnCompletion { cmfCameraView?.release() }
        } else cmfCameraView?.release()
        _isRecording.value = false
        _recordingDurationSeconds.value = 0
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        val finalizing = streamVideoFinalized?.takeIf { !it.isCompleted }
        if (finalizing == null) cameraExecutor.shutdown()
        else finalizing.invokeOnCompletion { cameraExecutor.shutdown() }
    }
}
