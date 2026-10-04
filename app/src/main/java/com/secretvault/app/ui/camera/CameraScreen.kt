package com.secretvault.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.camera.CameraManager
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.core.camera.CmfHighFpsCameraView
import com.secretvault.app.core.camera.LensFacing
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.core.camera.FlashMode
import com.secretvault.app.core.stream.StreamDiscovery
import com.secretvault.app.core.stream.StreamBleDiscovery
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.core.stream.StreamPreviewRenderer
import com.secretvault.app.core.stream.StreamSession
import com.secretvault.app.core.stream.StreamCameraState
import com.secretvault.app.core.stream.StreamCameraOption
import com.secretvault.app.core.stream.StreamInteractionState
import kotlinx.coroutines.channels.Channel
import com.secretvault.app.ui.stream.StreamPinDialog
import com.secretvault.app.ui.camera.components.CameraBottomBar
import com.secretvault.app.ui.camera.components.CameraPreviewView
import com.secretvault.app.ui.camera.components.CameraTopBar
import com.secretvault.app.ui.camera.components.FocusIndicator
import com.secretvault.app.ui.camera.components.CameraZoomSlider
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CameraScreen(
    app: SecretVaultApp,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    onNavigateToGallery: () -> Unit,
    onNavigateToViewer: (mediaId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: false
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    val cameraManager = remember {
        CameraManager(context, app.mediaSaveQueue)
    }
    val pins = remember { StreamPinManager(context.applicationContext) }
    val discovery = remember { StreamDiscovery(context.applicationContext) }
    val bluetooth = remember { StreamBleDiscovery(context.applicationContext) }
    val bluetoothState by bluetooth.state.collectAsState()
    val bluetoothEnabled = remember {
        context.getSharedPreferences("sv_stream_config", android.content.Context.MODE_PRIVATE)
            .getBoolean("bluetooth_discovery", true) && StreamBleDiscovery.allowed(context, true)
    }
    val discoveryState by discovery.state.collectAsState()
    var stream by remember { mutableStateOf(StreamSession(context.applicationContext)) }
    val streamState by stream.state.collectAsState()
    val streamRecording by cameraManager.streamRecordingState.collectAsState()
    val recordingPaused by cameraManager.isRecordingPaused.collectAsState()
    val streamSettings by cameraManager.streamSettings.collectAsState()
    val interaction by cameraManager.interaction.collectAsState()
    var streamVideoMode by remember { mutableStateOf<VideoMode?>(null) }
    var localFlashBusy by remember { mutableStateOf(false) }
    var renderer by remember { mutableStateOf<StreamPreviewRenderer?>(null) }
    var streamStarted by remember { mutableStateOf(false) }
    var streamStopping by remember { mutableStateOf(false) }
    var previewAspect by remember { mutableStateOf(0f) }
    var foreground by remember { mutableStateOf(true) }
    var showStreamPin by remember { mutableStateOf(false) }
    var startAfterPin by remember { mutableStateOf(false) }
    var savingPin by remember { mutableStateOf(false) }
    var streamError by remember { mutableStateOf<String?>(null) }

    fun stopStream() {
        streamStopping = true
        if (renderer != null) cameraManager.stopVideoRecording()
        val oldRenderer = renderer
        val oldStream = stream
        discovery.stopAdvertising()
        bluetooth.stopAdvertising()
        oldStream.close()
        scope.launch {
            oldStream.state.first { !it.busy }
            cameraManager.awaitStreamRecordingFinalized()
            if (renderer === oldRenderer) { renderer = null; streamStarted = false; streamStopping = false }
            oldRenderer?.close()
        }
    }
    fun startStream() {
        if (renderer != null || streamStopping || !foreground || uiState.isRecording) return
        streamError = null
        if (pins.isPinRequired() && !pins.isConfigured()) { startAfterPin = true; showStreamPin = true; return }
        stream = StreamSession(context.applicationContext)
        streamVideoMode = null
        streamStarted = false
        try {
            renderer = StreamPreviewRenderer { scope.launch {
                streamError = "Camera stream failed. Stop and try again."
                stopStream()
            } }
        } catch (_: Exception) {
            stream.close()
            streamError = "Camera stream could not start. Try again."
        }
    }
    LaunchedEffect(streamState.endpoint, streamState.live, bluetoothEnabled) {
        val endpoint = streamState.endpoint
        if (endpoint != null && !streamState.live) discovery.advertise(endpoint) else discovery.stopAdvertising()
        if (endpoint != null && !streamState.live && bluetoothEnabled) bluetooth.advertise(endpoint) else bluetooth.stopAdvertising()
    }
    LaunchedEffect(streamState.busy, streamStarted) {
        if (streamStarted && !stream.state.value.busy && renderer != null) {
            streamError = streamState.message
            stopStream()
        }
    }
    LaunchedEffect(renderer, previewAspect) {
        if (previewAspect > 0f) renderer?.updateViewport(previewAspect)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false; startAfterPin = false; showStreamPin = false
                discovery.stopAdvertising(); bluetooth.close(); stopStream(); cameraManager.pausePreview()
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hardware/Gesture back handler: always return to Vault Home
    BackHandler {
        onNavigateToGallery()
    }

    val latestMediaList by app.mediaRepository.getMedia(albumId = null, sortOrder = com.secretvault.app.core.model.SortOrder.NEWEST_FIRST).collectAsState(initial = emptyList())
    val latestMediaItem = latestMediaList.firstOrNull()

    val isSavingVideo by app.mediaSaveQueue.isSavingVideo.collectAsState()
    val videoSaveProgress by app.mediaSaveQueue.videoSaveProgress.collectAsState()
    val savingQueueCount by app.mediaSaveQueue.savingQueueCount.collectAsState()

    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    var cmfHighFpsViewInstance by remember { mutableStateOf<CmfHighFpsCameraView?>(null) }
    val shutterFlashAlpha = remember { Animatable(0f) }
    fun flashShutter() {
        scope.launch {
            shutterFlashAlpha.snapTo(0.8f)
            shutterFlashAlpha.animateTo(0f, tween(150))
        }
    }
    var cameraGeneration by remember { mutableStateOf(0) }
    var cameraIsReady by remember { mutableStateOf(false) }
    var appliedCameraId by remember { mutableStateOf<String?>(null) }
    val remoteCameraState = remember { MutableStateFlow(StreamCameraState()) }
    val remoteInteractions = remember { MutableStateFlow(StreamInteractionState()) }
    val localZoomTargets = remember { Channel<Pair<Int, Float>>(Channel.CONFLATED) }
    LaunchedEffect(interaction, appliedCameraId, previewAspect) {
        remoteInteractions.value = interaction.copy(cameraId = appliedCameraId, previewAspect = previewAspect.takeIf { it > 0f } ?: 1f)
    }
    LaunchedEffect(cameraGeneration) {
        for ((generation, ratio) in localZoomTargets) {
            if (generation == cameraGeneration && cameraIsReady && foreground) {
                kotlinx.coroutines.withTimeoutOrNull(2500) { cameraManager.applyContinuousZoom(ratio) }
            }
        }
    }
    LaunchedEffect(uiState.availableFacings, uiState.rearLensOptions, appliedCameraId) {
        val choices = buildList {
            if (LensFacing.BACK in uiState.availableFacings) uiState.rearLensOptions.take(7).forEach {
                add(StreamCameraOption("back/${it.id ?: "default"}", "Rear ${it.label}"))
            }
            if (LensFacing.FRONT in uiState.availableFacings) add(StreamCameraOption("front", "Front"))
        }
        remoteCameraState.value = StreamCameraState(choices, appliedCameraId?.takeIf { id -> choices.any { it.id == id } })
    }
    val selectedRearOption = uiState.rearLensOptions.firstOrNull { it.id == uiState.selectedRearLensId }
    val selectedPhysicalId = selectedRearOption?.physicalCameraId
    val useCmfHighFps = uiState.lensFacing == LensFacing.BACK &&
        (if (renderer == null) uiState.cameraMode == CameraMode.VIDEO && uiState.videoMode == VideoMode.FHD_60
         else (streamVideoMode?.ordinal ?: streamSettings.mode) == VideoMode.FHD_60.ordinal) &&
        uiState.selectedRearLensId == null &&
        CmfHighFpsCameraView.isCmfPhone1()

    // Start / Restart Camera when permission is granted, camera mode changes, lens facing changes, or preview ready
    LaunchedEffect(hasCameraPermission, foreground, streamStopping, renderer, streamVideoMode, uiState.cameraMode, uiState.lensFacing, selectedPhysicalId, uiState.videoMode, uiState.videoOrientation, uiState.recordAudio, previewViewInstance, cmfHighFpsViewInstance) {
        if (hasCameraPermission && foreground && !streamStopping) {
            cameraIsReady = false
            appliedCameraId = null
            val pv = previewViewInstance ?: return@LaunchedEffect
            val cmfView = cmfHighFpsViewInstance ?: return@LaunchedEffect
            cameraManager.startCamera(
                lifecycleOwner = lifecycleOwner,
                previewView = pv,
                cmfHighFpsView = cmfView,
                cameraMode = uiState.cameraMode,
                lensFacing = uiState.lensFacing,
                flashMode = uiState.flashMode,
                videoMode = uiState.videoMode,
                videoOrientation = uiState.videoOrientation,
                recordAudio = uiState.recordAudio,
                selectedRearLensId = selectedPhysicalId,
                onRearLensOptions = viewModel::setRearLensOptions,
                onAvailableFacings = viewModel::setAvailableFacings,
                streamVideoMode = streamVideoMode,
                onVideoConfigured = { modes, selected -> viewModel.setSupportedVideoModes(modes, selected) },
                onCameraReady = {
                    cameraGeneration++
                    cameraIsReady = true
                    val gl = renderer
                    if (gl != null && !streamStarted) {
                        val activePreview = if (useCmfHighFps) cmfView else pv
                        streamStarted = true
                        val owner = stream
                        stream.sendShared(gl, if (activePreview.width < activePreview.height) 90 else 0,
                            activePreview.width.toFloat() / activePreview.height.coerceAtLeast(1), pins, capturePhoto = {
                                if (!app.sessionManager.isUnlocked.value || !foreground || !cameraIsReady || stream !== owner || renderer == null || uiState.isRecording || localFlashBusy || !cameraManager.streamSettings.value.photoAvailable) false
                                else suspendCancellableCoroutine { result ->
                                    val completed = AtomicBoolean(false)
                                    fun finish(saved: Boolean) {
                                        if (completed.compareAndSet(false, true) && result.isActive) result.resume(saved)
                                    }
                                    cameraManager.capturePhoto(autoFaceBlur = uiState.autoFaceBlur, onShutter = ::flashShutter,
                                        onError = { finish(false) }, onSaved = {
                                            ContextCompat.getMainExecutor(context).execute {
                                                Toast.makeText(context, "Remote photo saved", Toast.LENGTH_SHORT).show()
                                            }
                                            finish(true)
                                        })
                                }
                            }, cameraState = remoteCameraState, selectCamera = { target ->
                                if (!app.sessionManager.isUnlocked.value || !foreground || !cameraIsReady || stream !== owner ||
                                    renderer == null || appliedCameraId == null || localFlashBusy || viewModel.uiState.value.isRecording ||
                                    cameraManager.streamRecordingState.value.saving || remoteCameraState.value.options.none { it.id == target }) false
                                else if (appliedCameraId == target) true
                                else {
                                    appliedCameraId = null
                                    if (!viewModel.selectStreamCamera(target)) false
                                    else {
                                        androidx.compose.runtime.snapshotFlow { appliedCameraId }.first { it == target }
                                        app.sessionManager.isUnlocked.value && foreground && cameraIsReady && stream === owner
                                    }
                                }
                            }, recordingState = cameraManager.streamRecordingState, setRecording = { start ->
                                if (!app.sessionManager.isUnlocked.value || !foreground || !cameraIsReady || stream !== owner ||
                                    renderer == null || appliedCameraId == null || localFlashBusy) false
                                else cameraManager.setStreamRecording(start, cameraManager.streamRecordingState.value.audio)
                            }, settingsState = cameraManager.streamSettings, setSetting = { kind, value ->
                                if (!app.sessionManager.isUnlocked.value || !foreground || !cameraIsReady || stream !== owner ||
                                    renderer == null || appliedCameraId == null || localFlashBusy) false
                                else if (kind == 2) {
                                    val paused = value == 1
                                    cameraManager.setRecordingPaused(paused) &&
                                        kotlinx.coroutines.withTimeoutOrNull(3000) { cameraManager.isRecordingPaused.first { it == paused } } != null
                                } else if (kind == 1) {
                                    val mode = FlashMode.entries[value]
                                    cameraManager.setStreamFlash(mode).also { if (it) viewModel.setFlashMode(mode) }
                                } else if (cameraManager.streamRecordingState.value.recording || cameraManager.streamRecordingState.value.saving) false
                                else {
                                    val generation = cameraGeneration
                                    streamVideoMode = VideoMode.entries[value]
                                    androidx.compose.runtime.snapshotFlow { cameraGeneration > generation && cameraIsReady && appliedCameraId != null }.first { it }
                                    app.sessionManager.isUnlocked.value && foreground && stream === owner && cameraManager.streamSettings.value.mode == value
                                }
                            }, interactionState = remoteInteractions, applyInteraction = { request ->
                                if (!app.sessionManager.isUnlocked.value || !foreground || !cameraIsReady || stream !== owner ||
                                    renderer == null || appliedCameraId != request.cameraId || localFlashBusy) false
                                else when (request.kind) {
                                    0 -> kotlinx.coroutines.withTimeoutOrNull(2500) { cameraManager.applyContinuousZoom(request.x) } == true
                                    1 -> {
                                        val active = if (cmfView.visibility == android.view.View.VISIBLE) cmfView else pv
                                        viewModel.setFocusPoint(Offset(request.x * active.width, request.y * active.height))
                                        cameraManager.focusPreviewPoint(request.x, request.y, pv, cmfView)
                                    }
                                    2 -> {
                                        val enabled = request.x == 1f
                                        val current = cameraManager.streamRecordingState.value
                                        if (current.recording || current.saving || !cameraManager.interaction.value.microphoneAvailable) false
                                        else if (viewModel.uiState.value.recordAudio == enabled) current.audio == enabled
                                        else {
                                            val generation = cameraGeneration
                                            if (!viewModel.setRecordAudio(enabled)) false
                                            else {
                                                androidx.compose.runtime.snapshotFlow { cameraGeneration > generation && cameraIsReady && appliedCameraId == request.cameraId }.first { it }
                                                app.sessionManager.isUnlocked.value && foreground && stream === owner &&
                                                    cameraManager.streamRecordingState.value.audio == enabled
                                            }
                                        }
                                    }
                                    else -> false
                                }
                            })
                    } else if (gl != null) stream.refreshCameraFrame()
                },
                onCameraError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    if (renderer != null) { streamError = error; stopStream() }
                },
                streamRenderer = renderer
            )
        }
    }

    LaunchedEffect(cameraGeneration, uiState.selectedRearLensId, uiState.lensFacing) {
        if (!cameraIsReady) return@LaunchedEffect
        appliedCameraId = null
        val ratio = if (uiState.lensFacing == LensFacing.BACK) selectedRearOption?.zoomRatio ?: 1f else 1f
        val targetId = if (uiState.lensFacing == LensFacing.FRONT) "front" else "back/${uiState.selectedRearLensId ?: "default"}"
        cameraManager.setZoomRatio(ratio, onApplied = {
            appliedCameraId = targetId
        }) { error ->
            viewModel.selectRearLens(null)
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    // Sync Flash Mode changes
    LaunchedEffect(uiState.flashMode) {
        if (hasCameraPermission) {
            cameraManager.setFlashMode(uiState.flashMode)
        }
    }

    // Sync Video Recording durations
    LaunchedEffect(Unit) {
        cameraManager.isRecording.collect { isRec ->
            viewModel.setRecordingState(isRec, cameraManager.recordingDurationSeconds.value)
        }
    }

    LaunchedEffect(Unit) {
        cameraManager.recordingDurationSeconds.collect { dur ->
            if (cameraManager.isRecording.value) {
                viewModel.setRecordingState(true, dur)
            }
        }
    }

    DisposableEffect(cameraManager) {
        onDispose {
            cameraManager.release()
        }
    }
    DisposableEffect(stream, renderer) {
        val currentStream = stream
        val currentRenderer = renderer
        onDispose { discovery.stopAdvertising(); currentStream.close(); currentRenderer?.close() }
    }
    DisposableEffect(discovery) { onDispose { discovery.close() } }
    DisposableEffect(bluetooth) { onDispose { bluetooth.close() } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (it.height > 0) previewAspect = it.width.toFloat() / it.height }
            .background(Color.Black)
    ) {
        if (!hasCameraPermission) {
            // Permission Request View
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = VaultAccent,
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Camera Permission Required",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "SecretVault requires camera access to capture private encrypted photos and videos directly into your vault.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.RECORD_AUDIO
                            )
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Grant Permission",
                        color = VaultDarkBg,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.weight(1.5f))
            }
        } else {
            // Camera Preview View
            CameraPreviewView(
                useCmfHighFps = useCmfHighFps,
                interactions = interaction.copy(cameraId = appliedCameraId),
                interactionEnabled = cameraIsReady && appliedCameraId != null && !streamStopping && !localFlashBusy && !streamState.cameraBusy &&
                    !streamState.photoBusy && !streamState.settingsBusy && !streamState.recordingBusy && !streamState.interactionBusy && !streamRecording.saving,
                onZoom = { localZoomTargets.trySend(cameraGeneration to it) },
                onPreviewViewsCreated = { preview, cmf ->
                    previewViewInstance = preview
                    cmfHighFpsViewInstance = cmf
                },
                onTapToFocus = { x, y, pv, cmf ->
                    viewModel.setFocusPoint(Offset(x, y))
                    cameraManager.focusOnPoint(x, y, pv, cmf)
                }
            )

            // White Shutter Flash Effect
            if (shutterFlashAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White.copy(alpha = shutterFlashAlpha.value))
                )
            }

            // Focus Circle
            FocusIndicator(focusPoint = uiState.focusPoint)
            CameraZoomSlider(interaction.copy(cameraId = appliedCameraId),
                enabled = cameraIsReady && appliedCameraId != null && !streamStopping && !localFlashBusy && !streamState.cameraBusy && !streamState.photoBusy &&
                    !streamState.settingsBusy && !streamState.recordingBusy && !streamState.interactionBusy && !streamRecording.saving,
                onZoom = { localZoomTargets.trySend(cameraGeneration to it) },
                modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-80).dp).padding(end = 16.dp))

            // Top Bar
            CameraTopBar(
                cameraMode = uiState.cameraMode,
                autoFaceBlur = uiState.autoFaceBlur,
                videoMode = uiState.videoMode,
                videoOrientation = uiState.videoOrientation,
                supportedVideoModes = uiState.supportedVideoModes,
                isRecording = uiState.isRecording || renderer != null,
                onVideoModeSelect = viewModel::setVideoMode,
                onVideoOrientationSelect = viewModel::setVideoOrientation,
                onFaceBlurToggle = { viewModel.toggleFaceBlur() },
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )

            if (renderer != null || streamError != null) Column(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f)).padding(8.dp)
            ) {
                val status = when {
                    renderer != null -> if (!streamStarted) "Preparing camera…" else if (streamState.live) "Viewer connected" else streamState.message
                    streamError != null -> streamError
                    else -> null
                }
                status?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) }
                streamState.photoMessage?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                streamState.cameraMessage?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                streamState.recordingMessage?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                streamState.settingsMessage?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }
                if (renderer != null) {
                    Text(if (streamRecording.recording) "${if (streamRecording.paused) "Paused" else "Recording"} %02d:%02d".format(streamRecording.seconds / 60, streamRecording.seconds % 60)
                        else if (streamRecording.saving) "Saving encrypted video…"
                        else if (streamRecording.available) "Remote video: ${streamSettings.mode?.let { VideoMode.entries[it].label }} · Microphone ${if (streamRecording.audio) "on" else "off"}"
                        else "Remote video unavailable for this camera",
                        color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    if (streamRecording.available && !streamSettings.photoAvailable) Text("Select 30 FPS to take photos", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                    if (streamRecording.recording) androidx.compose.material3.TextButton(onClick = { cameraManager.stopVideoRecording() },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop recording", color = VaultAccent) }
                }
                if (renderer != null && discoveryState.message.startsWith("Could not")) {
                    Text(discoveryState.message, color = TextPrimary, modifier = Modifier.padding(8.dp))
                }
                if (renderer != null && bluetoothEnabled && bluetoothState.message.isNotEmpty() && !streamState.live) {
                    Text(bluetoothState.message, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }

            // Bottom Bar
            CameraBottomBar(
                flashMode = uiState.flashMode,
                recordAudio = if (renderer != null) streamRecording.audio else uiState.recordAudio,
                onFlashToggle = {
                    if (renderer == null) viewModel.toggleFlashMode()
                    else scope.launch {
                        localFlashBusy = true
                        try {
                            val next = when (uiState.flashMode) { FlashMode.AUTO -> FlashMode.ON; FlashMode.ON -> FlashMode.OFF; FlashMode.OFF -> FlashMode.AUTO }
                            if (kotlinx.coroutines.withTimeoutOrNull(10_000) { cameraManager.setStreamFlash(next) } == true) viewModel.setFlashMode(next)
                        } finally { localFlashBusy = false }
                    }
                },
                flashEnabled = !localFlashBusy && (renderer == null || (streamSettings.flashAvailable && !streamState.photoBusy && !streamState.cameraBusy && !streamState.recordingBusy && !streamState.settingsBusy && !streamRecording.saving)),
                photoAvailable = renderer == null || streamSettings.photoAvailable,
                audioEnabled = cameraIsReady && interaction.microphoneAvailable && !streamRecording.recording && !streamRecording.saving &&
                    !streamState.photoBusy && !streamState.cameraBusy && !streamState.recordingBusy && !streamState.settingsBusy && !streamState.interactionBusy,
                onAudioToggle = viewModel::toggleRecordAudio,
                cameraMode = uiState.cameraMode,
                isRecording = uiState.isRecording,
                isStreaming = renderer != null,
                onStreamToggle = { if (renderer == null) startStream() else stopStream() },
                streamEnabled = !uiState.isRecording && !streamStopping && !streamState.stopping && (renderer != null || cameraIsReady),
                recordingDurationSeconds = uiState.recordingDurationSeconds,
                isRecordingPaused = recordingPaused,
                onPauseToggle = { cameraManager.setRecordingPaused(!recordingPaused) },
                onModeSelect = { viewModel.setCameraMode(it) },
                onShutterClick = {
                    if (uiState.cameraMode == CameraMode.PHOTO) {
                        cameraManager.capturePhoto(
                            autoFaceBlur = uiState.autoFaceBlur,
                            onShutter = ::flashShutter,
                            onSaved = {
                                ContextCompat.getMainExecutor(context).execute {
                                    Toast.makeText(context, "Encrypted photo saved", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        if (uiState.isRecording) {
                            cameraManager.stopVideoRecording()
                        } else {
                            val started = cameraManager.startVideoRecording(
                                recordAudio = uiState.recordAudio,
                                onRecordingStarted = { }
                            )
                            if (started) viewModel.setRecordingState(true)
                        }
                    }
                },
                onFlipCamera = { if (!streamState.photoBusy && !streamState.cameraBusy && !streamState.recordingBusy && !streamState.settingsBusy &&
                    !streamRecording.recording && !streamRecording.saving) viewModel.toggleLensFacing() },
                latestMediaItem = latestMediaItem,
                rearLensOptions = if (uiState.lensFacing == LensFacing.BACK) uiState.rearLensOptions else emptyList(),
                selectedRearLensId = uiState.selectedRearLensId,
                onRearLensSelect = { if (!streamState.photoBusy && !streamState.cameraBusy && !streamState.recordingBusy && !streamState.settingsBusy &&
                    !streamRecording.recording && !streamRecording.saving) viewModel.selectRearLens(it) },
                cameraControlsEnabled = !localFlashBusy && !streamState.photoBusy && !streamState.cameraBusy && !streamState.recordingBusy && !streamState.settingsBusy &&
                    !streamRecording.recording && !streamRecording.saving,
                onGalleryClick = {
                    val item = latestMediaItem
                    if (item != null) {
                        onNavigateToViewer(item.id)
                    } else {
                        onNavigateToGallery()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // Non-blocking Background Saving Indicator Pill
            AnimatedVisibility(
                visible = isSavingVideo,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (renderer != null) 176.dp else 128.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(VaultSurface.copy(alpha = 0.9f))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    CircularProgressIndicator(
                        color = VaultAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp)
                    )
                    val queueText = if (savingQueueCount > 1) " ($savingQueueCount in queue)" else ""
                    val pct = (videoSaveProgress * 100).toInt()
                    Text(
                        text = "Encrypting video $pct%$queueText",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    if (showStreamPin) StreamPinDialog(setup = true, saving = savingPin, error = streamError,
        onDismiss = { showStreamPin = false; startAfterPin = false }, onConfirm = { pin ->
            savingPin = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { try { pins.setPin(pin) } finally { pin.fill('\u0000') } }
                    showStreamPin = false
                    if (startAfterPin && foreground) startStream()
                } catch (_: Exception) { streamError = "Could not save streaming PIN. Try again." }
                finally { pin.fill('\u0000'); savingPin = false; startAfterPin = false }
            }
        })
}
