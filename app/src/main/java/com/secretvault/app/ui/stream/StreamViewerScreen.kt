package com.secretvault.app.ui.stream

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.secretvault.app.core.stream.*
import com.secretvault.app.ui.theme.*
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.camera.VideoMode
import kotlinx.coroutines.flow.first

@Composable
fun StreamViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SecretVaultApp
    val lifecycle = LocalLifecycleOwner.current
    val discovery = remember { StreamDiscovery(context.applicationContext) }
    val devices by discovery.state.collectAsState()
    val bluetooth = remember { StreamBleDiscovery(context.applicationContext) }
    val nearby by bluetooth.state.collectAsState()
    var bluetoothEnabled by remember { mutableStateOf(StreamBleDiscovery.allowed(context, false)) }
    val cameras = (devices.cameras + nearby.cameras).distinctBy { it.sessionId }.take(16)
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        app.sessionManager.setExternalPickerInProgress(false)
        bluetoothEnabled = grants.values.all { it }
        if (bluetoothEnabled && lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) bluetooth.search()
        else android.widget.Toast.makeText(context, "Wi-Fi discovery still works without Bluetooth permission", android.widget.Toast.LENGTH_SHORT).show()
    }
    var session by remember { mutableStateOf(StreamSession(context.applicationContext)) }
    val state by session.state.collectAsState()
    val canChooseCamera = state.live && !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy &&
        !state.recordingState.recording && !state.recordingState.saving && !state.stopping && state.cameraState.selectedId != null
    val canTakePhoto = state.live && !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy &&
        !state.recordingState.recording && !state.recordingState.saving && !state.stopping &&
        (state.cameraState.options.isEmpty() || state.cameraState.selectedId != null)
    val latestSession by rememberUpdatedState(session)
    var prompt by remember { mutableStateOf<StreamEndpoint?>(null) }
    var selected by remember { mutableStateOf<StreamEndpoint?>(null) }
    var pendingPin by remember { mutableStateOf<CharArray?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val confirmations = setOf("Photo saved on camera device", "Camera changed", "Recording started",
        "Video saved on camera device", "Camera setting applied")
    listOf(state.photoMessage, state.cameraMessage, state.recordingMessage, state.settingsMessage).forEach { message ->
        LaunchedEffect(session, message) {
            if (message in confirmations) {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(requireNotNull(message), duration = SnackbarDuration.Short)
            }
        }
    }
    fun disconnect() {
        snackbar.currentSnackbarData?.dismiss()
        session.close(); pendingPin?.fill('\u0000'); pendingPin = null; selected = null; discovery.search()
        if (bluetoothEnabled) bluetooth.search()
    }
    BackHandler { session.close(); onBack() }
    LaunchedEffect(Unit) { discovery.search(); if (bluetoothEnabled) bluetooth.search() }
    LaunchedEffect(selected, surface) {
        val camera = selected ?: return@LaunchedEffect
        val output = surface ?: return@LaunchedEffect
        val pin = if (camera.requiresPin) pendingPin ?: return@LaunchedEffect else null
        pendingPin = null
        discovery.stopSearching(); bluetooth.stopSearching(); session.close()
        var handedOff = false
        try {
            session.state.first { !it.busy }
            session = StreamSession(context.applicationContext).also {
                if (camera.requiresPin) it.view(camera, requireNotNull(pin), output) else it.view(camera, output)
            }
            handedOff = true
        } finally { if (!handedOff) pin?.fill('\u0000') }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            latestSession.close(); discovery.close(); bluetooth.close(); pendingPin?.fill('\u0000'); pendingPin = null; prompt = null
        } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer); latestSession.close(); discovery.close(); bluetooth.close()
            pendingPin?.fill('\u0000'); surface?.release()
        }
    }
    Column(Modifier.fillMaxSize().background(VaultDarkBg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = { session.close(); onBack() }, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Text(selected?.name ?: "View stream", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 56.dp))
        }
        if (selected == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(devices.message, color = TextSecondary, fontSize = 16.sp)
                cameras.forEach { camera ->
                    OutlinedButton(onClick = {
                        if (camera.requiresPin) prompt = camera else { pendingPin = null; selected = camera }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(camera.name)
                            if (!camera.requiresPin) Text("No PIN required", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }
                TextButton(onClick = { discovery.search(); if (bluetoothEnabled) bluetooth.search() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh", color = VaultAccent) }
                if (bluetoothEnabled) Text(nearby.message, color = TextSecondary)
                else TextButton(onClick = {
                    if (StreamBleDiscovery.allowed(context, false)) { bluetoothEnabled = true; bluetooth.search() }
                    else {
                        app.sessionManager.setExternalPickerInProgress(true)
                        bluetoothPermission.launch(StreamBleDiscovery.permissions(false))
                    }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use Bluetooth too", color = VaultAccent) }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.Black)
                .semantics { if (state.live) contentDescription = "Live camera" }) {
                AndroidView(factory = { androidContext ->
                    TextureView(androidContext).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface?.release()
                                texture.setDefaultBufferSize(1280, 720); surface = Surface(texture)
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                                fitPreview(this@apply, latestSession.state.value.config, false)
                            }
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                latestSession.close(); surface?.release(); surface = null; return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                }, update = { view -> fitPreview(view, state.config, false) }, modifier = Modifier.fillMaxSize())
                if (state.live) Row(Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(VaultDarkBg.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.settingsState.mode?.let { VideoMode.entries[it].label }.orEmpty(), color = TextPrimary, fontSize = 12.sp)
                    Icon(if (state.recordingState.audio) Icons.Default.Mic else Icons.Default.MicOff,
                        if (state.recordingState.audio) "Microphone on" else "Microphone off", tint = TextPrimary, modifier = Modifier.size(16.dp))
                }
                val notices = listOfNotNull(state.photoMessage, state.cameraMessage, state.recordingMessage, state.settingsMessage)
                    .filter { it !in confirmations && it !in setOf("Taking photo…", "Changing camera…", "Applying camera setting…", "Starting recording…", "Saving encrypted video…") }
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.live) Text(state.message, color = TextPrimary,
                        modifier = Modifier.background(VaultDarkBg, RoundedCornerShape(8.dp)).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    notices.forEach { message -> Text(message, color = TextPrimary,
                        modifier = Modifier.background(VaultDarkBg, RoundedCornerShape(8.dp)).padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 8.dp, end = 8.dp))
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.recordingState.recording) Text("Recording %02d:%02d".format(state.recordingState.seconds / 60, state.recordingState.seconds % 60),
                        color = TextPrimary, modifier = Modifier.background(VaultDarkBg, RoundedCornerShape(8.dp)).padding(8.dp))
                    if (state.photoAvailable) IconButton(onClick = { session.takePhoto() }, enabled = canTakePhoto,
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(if (canTakePhoto) VaultAccent else VaultSurface)) {
                        Icon(Icons.Default.CameraAlt, "Take photo", tint = if (canTakePhoto) VaultDarkBg else TextMuted)
                    }
                    if (state.live) StreamViewerControls(state = state, settingsAvailable = selected?.settingsControls == true,
                        canChooseCamera = canChooseCamera, onSetting = { kind, value -> session.setSetting(kind, value) },
                        onCamera = { session.selectCamera(it) })
                }
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val recording = state.recordingState
                if (recording.available) Button(onClick = { session.setRecording(!recording.recording) },
                    enabled = state.live && !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy && !recording.saving &&
                        !state.stopping && state.cameraState.selectedId != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording.recording) VaultError else VaultAccent, contentColor = VaultDarkBg,
                        disabledContainerColor = VaultSurfaceVariant, disabledContentColor = TextMuted)) {
                    Icon(if (recording.recording) Icons.Default.Stop else Icons.Default.Videocam, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.recordingBusy || recording.saving) { if (state.recordingRequestedStart == true) "Starting recording…" else "Saving video…" }
                        else if (recording.recording) "Stop recording" else "Record video", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = LocalContentColor.current)
                } else if (selected?.recordingControls == true && state.live) Text("Recording unavailable", color = TextSecondary)
                OutlinedButton(onClick = ::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), border = BorderStroke(1.dp, TextMuted),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                    Text(if (state.busy) "Disconnect" else "Back to cameras", fontSize = 16.sp)
                }
            }
        }
    }
    if (prompt != null) StreamPinDialog(setup = false, onConfirm = { pin ->
        pendingPin = pin; selected = prompt; prompt = null
    }, onDismiss = { prompt = null })
}
