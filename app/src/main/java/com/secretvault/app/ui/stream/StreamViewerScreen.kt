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
    val canTakePhoto = state.live && !state.photoBusy && !state.cameraBusy && !state.stopping &&
        (state.cameraState.options.isEmpty() || state.cameraState.selectedId != null)
    val latestSession by rememberUpdatedState(session)
    var prompt by remember { mutableStateOf<StreamEndpoint?>(null) }
    var selected by remember { mutableStateOf<StreamEndpoint?>(null) }
    var pendingPin by remember { mutableStateOf<CharArray?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var cameraMenu by remember { mutableStateOf(false) }
    fun disconnect() {
        cameraMenu = false
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
                Text(selected?.name.orEmpty(), color = TextPrimary)
                Text(state.message, color = TextSecondary)
                state.photoMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                state.cameraMessage?.let { Text(it, color = TextPrimary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (state.cameraState.options.size > 1) Box {
                    OutlinedButton(onClick = { cameraMenu = true },
                        enabled = state.live && !state.photoBusy && !state.cameraBusy && state.cameraState.selectedId != null && !state.stopping,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                        Icon(Icons.Default.Cameraswitch, "Choose camera")
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.cameraBusy) "Changing camera…" else
                            state.cameraState.options.firstOrNull { it.id == state.cameraState.selectedId }?.label ?: "Preparing camera…")
                    }
                    DropdownMenu(expanded = cameraMenu && state.live && !state.cameraBusy && !state.photoBusy && state.cameraState.selectedId != null,
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.photoAvailable) IconButton(onClick = { session.takePhoto() },
                        enabled = canTakePhoto,
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(if (canTakePhoto) VaultAccent else VaultSurface)) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take photo",
                            tint = if (canTakePhoto) VaultDarkBg else TextMuted)
                    }
                    OutlinedButton(onClick = ::disconnect, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) {
                        Text(if (state.busy) "Disconnect" else "Back to cameras")
                    }
                }
            }
        }
    }
    if (prompt != null) StreamPinDialog(setup = false, onConfirm = { pin ->
        pendingPin = pin; selected = prompt; prompt = null
    }, onDismiss = { prompt = null })
}
