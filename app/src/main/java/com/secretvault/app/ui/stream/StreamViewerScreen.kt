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
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.secretvault.app.core.camera.FlashMode
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
    var cameraMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var flashMenu by remember { mutableStateOf(false) }
    fun disconnect() {
        cameraMenu = false
        qualityMenu = false
        flashMenu = false
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
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.close(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
            Text("View stream", color = TextPrimary, fontSize = 20.sp)
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
            Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black)) {
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
            }
            Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp / 2).dp)
                .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected?.name.orEmpty(), color = TextPrimary)
                        Text(state.message, color = TextSecondary)
                    }
                    if (state.photoAvailable) IconButton(onClick = { session.takePhoto() },
                        enabled = canTakePhoto,
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(if (canTakePhoto) VaultAccent else VaultSurface)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take photo",
                            tint = if (canTakePhoto) VaultDarkBg else TextMuted)
                    }
                }
                state.photoMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                state.cameraMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                state.recordingMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                state.settingsMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (selected?.settingsControls == true && state.live) {
                    val settings = state.settingsState
                    val canSetFlash = state.live && !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy &&
                        !state.recordingState.saving && !state.stopping && state.cameraState.selectedId != null && settings.flashAvailable
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { qualityMenu = true }, enabled = canChooseCamera && settings.mode != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Video quality", fontSize = 12.sp)
                                    Text(settings.mode?.let { VideoMode.entries[it].label } ?: "Preparing…", fontSize = 14.sp)
                                }
                            }
                            DropdownMenu(expanded = qualityMenu && canChooseCamera, onDismissRequest = { qualityMenu = false }, containerColor = VaultSurface) {
                                settings.modes.forEach { mode ->
                                    DropdownMenuItem(text = { Text(VideoMode.entries[mode].label, color = TextPrimary) },
                                        onClick = { qualityMenu = false; session.setSetting(0, mode) },
                                        trailingIcon = { if (mode == settings.mode) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                                        modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            val flash = FlashMode.entries[settings.flashMode]
                            OutlinedButton(onClick = { flashMenu = true }, enabled = canSetFlash,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                                Icon(when (flash) { FlashMode.AUTO -> Icons.Default.FlashAuto; FlashMode.ON -> Icons.Default.FlashOn; FlashMode.OFF -> Icons.Default.FlashOff }, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (settings.flashAvailable) "Flash ${flash.name.lowercase()}" else "No flash", fontSize = 14.sp)
                            }
                            DropdownMenu(expanded = flashMenu && canSetFlash, onDismissRequest = { flashMenu = false }, containerColor = VaultSurface) {
                                listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON).forEach { mode ->
                                    DropdownMenuItem(text = { Column {
                                        Text("Flash ${mode.name.lowercase()}", color = TextPrimary)
                                        if (mode != FlashMode.OFF) Text(if (mode == FlashMode.AUTO) "Photos only" else "Continuous light", color = TextSecondary, fontSize = 12.sp)
                                    } },
                                        onClick = { flashMenu = false; session.setSetting(1, mode.ordinal) },
                                        trailingIcon = { if (mode == flash) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                                        modifier = Modifier.heightIn(min = 48.dp))
                                }
                            }
                        }
                    }
                    if (settings.mode != null && !settings.photoAvailable) Text("Select 30 FPS to take photos", color = TextSecondary)
                }
                if (state.recordingState.available) {
                    val recording = state.recordingState
                    Text(if (recording.recording) "Recording %02d:%02d".format(recording.seconds / 60, recording.seconds % 60)
                        else if (recording.saving) "Saving encrypted video…"
                        else "${state.settingsState.mode?.let { VideoMode.entries[it].label } ?: "1080p · 30 FPS"} · Microphone ${if (recording.audio) "on" else "off"}", color = TextPrimary)
                    OutlinedButton(onClick = { session.setRecording(!recording.recording) },
                        enabled = state.live && !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy && !recording.saving &&
                            !state.stopping && state.cameraState.selectedId != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (recording.recording) VaultError else TextPrimary)) {
                        Icon(if (recording.recording) Icons.Default.Stop else Icons.Default.Videocam, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.recordingBusy) { if (state.recordingRequestedStart == true) "Starting recording…" else "Saving video…" }
                            else if (recording.recording) "Stop recording" else "Record video")
                    }
                } else if (selected?.recordingControls == true && state.live) Text("Video recording unavailable for this camera", color = TextSecondary)
                if (state.cameraState.options.size > 1) Box {
                    OutlinedButton(onClick = { cameraMenu = true },
                        enabled = canChooseCamera,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                        Icon(Icons.Default.Cameraswitch, "Choose camera")
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.cameraBusy) "Changing camera…" else
                            state.cameraState.options.firstOrNull { it.id == state.cameraState.selectedId }?.label ?: "Preparing camera…")
                    }
                    DropdownMenu(expanded = cameraMenu && canChooseCamera,
                        onDismissRequest = { cameraMenu = false },
                        containerColor = VaultSurface) {
                        state.cameraState.options.forEach { option ->
                            DropdownMenuItem(text = { Text(option.label, color = TextPrimary) },
                                onClick = { cameraMenu = false; session.selectCamera(option.id) },
                                trailingIcon = { if (option.id == state.cameraState.selectedId) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                                modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            OutlinedButton(onClick = ::disconnect, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                Text(if (state.busy) "Disconnect" else "Back to cameras")
            }
        }
    }
    if (prompt != null) StreamPinDialog(setup = false, onConfirm = { pin ->
        pendingPin = pin; selected = prompt; prompt = null
    }, onDismiss = { prompt = null })
}
