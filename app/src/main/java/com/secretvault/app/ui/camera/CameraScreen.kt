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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.camera.CameraManager
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.core.camera.CmfHighFpsCameraView
import com.secretvault.app.core.camera.LensFacing
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.ui.camera.components.CameraBottomBar
import com.secretvault.app.ui.camera.components.CameraPreviewView
import com.secretvault.app.ui.camera.components.CameraTopBar
import com.secretvault.app.ui.camera.components.FocusIndicator
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import kotlinx.coroutines.launch

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

    // Hardware/Gesture back handler: always return to Vault Home
    BackHandler {
        onNavigateToGallery()
    }

    val latestMediaList by app.mediaRepository.getMedia(albumId = null, sortOrder = com.secretvault.app.core.model.SortOrder.NEWEST_FIRST).collectAsState(initial = emptyList())
    val latestMediaItem = latestMediaList.firstOrNull()

    val isSavingVideo by app.mediaSaveQueue.isSavingVideo.collectAsState()
    val videoSaveProgress by app.mediaSaveQueue.videoSaveProgress.collectAsState()
    val savingQueueCount by app.mediaSaveQueue.savingQueueCount.collectAsState()
    var wasSavingVideo by remember { mutableStateOf(false) }

    LaunchedEffect(isSavingVideo) {
        if (wasSavingVideo && !isSavingVideo) {
            Toast.makeText(context, "Encrypted video saved", Toast.LENGTH_SHORT).show()
        }
        wasSavingVideo = isSavingVideo
    }

    var previewViewInstance by remember { mutableStateOf<PreviewView?>(null) }
    var cmfHighFpsViewInstance by remember { mutableStateOf<CmfHighFpsCameraView?>(null) }
    val shutterFlashAlpha = remember { Animatable(0f) }
    var cameraGeneration by remember { mutableStateOf(0) }
    val selectedRearOption = uiState.rearLensOptions.firstOrNull { it.id == uiState.selectedRearLensId }
    val selectedPhysicalId = selectedRearOption?.physicalCameraId
    val useCmfHighFps = uiState.cameraMode == CameraMode.VIDEO &&
        uiState.lensFacing == LensFacing.BACK &&
        uiState.videoMode == VideoMode.FHD_60 &&
        uiState.selectedRearLensId == null &&
        CmfHighFpsCameraView.isCmfPhone1()

    // Start / Restart Camera when permission is granted, camera mode changes, lens facing changes, or preview ready
    LaunchedEffect(hasCameraPermission, uiState.cameraMode, uiState.lensFacing, selectedPhysicalId, uiState.videoMode, uiState.videoOrientation, uiState.recordAudio, previewViewInstance, cmfHighFpsViewInstance) {
        if (hasCameraPermission) {
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
                onVideoConfigured = { modes, selected -> viewModel.setSupportedVideoModes(modes, selected) },
                onCameraReady = { cameraGeneration++ },
                onCameraError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    LaunchedEffect(cameraGeneration, uiState.selectedRearLensId, uiState.lensFacing) {
        val ratio = if (uiState.lensFacing == LensFacing.BACK) selectedRearOption?.zoomRatio ?: 1f else 1f
        cameraManager.setZoomRatio(ratio) { error ->
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

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.release()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
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

            // Top Bar
            CameraTopBar(
                cameraMode = uiState.cameraMode,
                autoFaceBlur = uiState.autoFaceBlur,
                videoMode = uiState.videoMode,
                videoOrientation = uiState.videoOrientation,
                supportedVideoModes = uiState.supportedVideoModes,
                isRecording = uiState.isRecording,
                onVideoModeSelect = viewModel::setVideoMode,
                onVideoOrientationSelect = viewModel::setVideoOrientation,
                onFaceBlurToggle = { viewModel.toggleFaceBlur() },
                onClose = onClose,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
            )

            // Bottom Bar
            CameraBottomBar(
                flashMode = uiState.flashMode,
                recordAudio = uiState.recordAudio,
                onFlashToggle = viewModel::toggleFlashMode,
                onAudioToggle = viewModel::toggleRecordAudio,
                cameraMode = uiState.cameraMode,
                isRecording = uiState.isRecording,
                recordingDurationSeconds = uiState.recordingDurationSeconds,
                onModeSelect = { viewModel.setCameraMode(it) },
                onShutterClick = {
                    if (uiState.cameraMode == CameraMode.PHOTO) {
                        cameraManager.capturePhoto(
                            autoFaceBlur = uiState.autoFaceBlur,
                            onShutter = {
                                scope.launch {
                                    shutterFlashAlpha.snapTo(0.8f)
                                    shutterFlashAlpha.animateTo(0f, tween(150))
                                }
                            },
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
                onFlipCamera = { viewModel.toggleLensFacing() },
                latestMediaItem = latestMediaItem,
                rearLensOptions = if (uiState.lensFacing == LensFacing.BACK) uiState.rearLensOptions else emptyList(),
                selectedRearLensId = uiState.selectedRearLensId,
                onRearLensSelect = viewModel::selectRearLens,
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
                    .padding(top = 64.dp)
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
}
