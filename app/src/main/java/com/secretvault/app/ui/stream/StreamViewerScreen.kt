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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
    val latestSession by rememberUpdatedState(session)
    var prompt by remember { mutableStateOf<StreamEndpoint?>(null) }
    var selected by remember { mutableStateOf<StreamEndpoint?>(null) }
    var pendingPin by remember { mutableStateOf<CharArray?>(null) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    fun disconnect() {
        session.close(); pendingPin?.fill('\u0000'); pendingPin = null; selected = null; discovery.search()
        if (bluetoothEnabled) bluetooth.search()
    }
    BackHandler { session.close(); onBack() }
    LaunchedEffect(Unit) { discovery.search(); if (bluetoothEnabled) bluetooth.search() }
    LaunchedEffect(selected, surface) {
        val camera = selected ?: return@LaunchedEffect
        val output = surface ?: return@LaunchedEffect
        val pin = pendingPin ?: return@LaunchedEffect
        pendingPin = null
        discovery.stopSearching(); bluetooth.stopSearching(); session.close()
        var handedOff = false
        try {
            session.state.first { !it.busy }
            session = StreamSession(context.applicationContext).also { it.view(camera, pin, output) }
            handedOff = true
        } finally { if (!handedOff) pin.fill('\u0000') }
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
                    OutlinedButton(onClick = { prompt = camera }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text(camera.name) }
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
                                latestSession.state.value.config?.let { setTransform(streamPreviewTransform(width, height, it, false, 0)) }
                            }
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                latestSession.close(); surface?.release(); surface = null; return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                }, update = { view -> if (view.width > 0 && view.height > 0) state.config?.let {
                    view.setTransform(streamPreviewTransform(view.width, view.height, it, false, 0))
                } }, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected?.name.orEmpty(), color = TextPrimary)
                Text(state.message, color = TextSecondary)
                Button(onClick = ::disconnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VaultAccent, contentColor = VaultDarkBg)) {
                    Text(if (state.busy) "Disconnect" else "Back to cameras")
                }
            }
        }
    }
    if (prompt != null) StreamPinDialog(setup = false, onConfirm = { pin ->
        pendingPin = pin; selected = prompt; prompt = null
    }, onDismiss = { prompt = null })
}
