package com.secretvault.app.ui.stream

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.PersistableBundle
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.secretvault.app.core.stream.*
import com.secretvault.app.ui.theme.*

@Composable
fun CameraStreamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var session by remember { mutableStateOf(StreamSession(context.applicationContext)) }
    val state by session.state.collectAsState()
    val latestSession by rememberUpdatedState(session)
    var sending by remember { mutableStateOf(true) }
    val latestSending by rememberUpdatedState(sending)
    var details by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var startedRotation by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf<String?>(null) }
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }

    fun clearCopied() {
        val ownText = copied ?: return
        if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == ownText) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        copied = null
    }
    fun start() {
        val surface = previewSurface ?: return
        val invitation = if (sending) null else try { StreamInvitation.parse(details) } catch (error: IllegalArgumentException) {
            validationError = error.message; return
        }
        validationError = null
        session.close()
        val next = StreamSession(context.applicationContext)
        session = next
        if (sending) {
            startedRotation = textureView?.display?.rotation ?: Surface.ROTATION_0
            next.send(surface, startedRotation * 90)
        } else {
            details = ""
            next.view(requireNotNull(invitation), surface)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else validationError = "Camera permission is required to send a live view"
    }
    BackHandler { session.close(); onBack() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { latestSession.close(); clearCopied(); details = "" }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); latestSession.close(); clearCopied() }
    }
    LaunchedEffect(state.invitation) { if (state.invitation == null) clearCopied() }

    BoxWithConstraints(Modifier.fillMaxSize().background(VaultDarkBg).statusBarsPadding().navigationBarsPadding().imePadding()) {
    val previewHeight = (maxHeight * if (state.busy) 0.65f else 0.45f).coerceAtLeast(160.dp)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { session.close(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Text("Camera stream test", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        if (!state.busy) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val roleColors = FilterChipDefaults.filterChipColors(labelColor = TextPrimary,
                    selectedLabelColor = VaultDarkBg, selectedContainerColor = VaultAccent)
                FilterChip(selected = sending, onClick = { sending = true; validationError = null },
                    label = { Text("Send camera") }, colors = roleColors, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(selected = !sending, onClick = { sending = false; validationError = null },
                    label = { Text("View camera") }, colors = roleColors, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (!sending) OutlinedTextField(
                value = details, onValueChange = { if (it.length <= 256) { details = it; validationError = null } },
                label = { Text("Connection details") }, placeholder = { Text("Paste from the camera phone") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                maxLines = 3, modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedLabelColor = VaultAccent, unfocusedLabelColor = TextSecondary, cursorColor = VaultAccent,
                    focusedBorderColor = VaultAccent, unfocusedBorderColor = TextSecondary)
            )
        }
        Box(Modifier.height(previewHeight).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(factory = {
                TextureView(it).also { view ->
                    textureView = view
                    view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                            texture.setDefaultBufferSize(1280, 720)
                            previewSurface = Surface(texture)
                        }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                            if (latestSending && latestSession.state.value.busy && view.display.rotation != startedRotation) latestSession.close()
                            fitPreview(view, latestSession.state.value.config, latestSending)
                        }
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                            latestSession.close(); previewSurface?.release(); previewSurface = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                    }
                }
            }, update = { view ->
                fitPreview(view, state.config, sending)
            }, modifier = Modifier.fillMaxSize())
            if (!state.busy || (!sending && !state.live)) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (state.busy) state.message else if (sending) "Start this camera, then connect from another SV device" else "Connect to see the live camera",
                        color = TextSecondary, fontSize = 16.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(validationError ?: state.message, color = if (validationError == null) TextPrimary else VaultError,
                fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            state.config?.let { config ->
                val fps = if (sending) state.encodedFps else state.renderedFps
                Text("${config.width} × ${config.height} · $fps FPS", color = TextSecondary, fontSize = 14.sp)
            }
            if (state.invitation != null) TextButton(onClick = {
                val value = state.invitation ?: return@TextButton
                val clip = ClipData.newPlainText("SV camera connection", value)
                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                clipboard.setPrimaryClip(clip); copied = value
                Toast.makeText(context, "Connection details copied", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy connection details", color = VaultAccent) }
            Button(onClick = {
                if (state.busy) session.close()
                else if (sending && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.CAMERA)
                else start()
            }, enabled = !state.stopping && (state.busy || (previewSurface != null && (sending || details.isNotBlank()))),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VaultAccent, contentColor = VaultDarkBg)) {
                Text(if (state.stopping) "Stopping…" else if (state.busy) { if (sending) "Stop camera" else "Disconnect" } else { if (sending) "Start camera" else "Connect" })
            }
        }
    }
    }
}

private fun fitPreview(view: TextureView, config: StreamConfig?, cameraPreview: Boolean) {
    if (config == null || view.width == 0 || view.height == 0) return
    view.setTransform(streamPreviewTransform(view.width, view.height, config, cameraPreview, view.display.rotation * 90))
}

internal fun streamPreviewTransform(viewWidth: Int, viewHeight: Int, config: StreamConfig,
                                    cameraPreview: Boolean, displayDegrees: Int): Matrix {
    val rotated = config.rotation % 180 != 0
    val contentWidth = if (rotated) config.height else config.width
    val contentHeight = if (rotated) config.width else config.height
    val scale = minOf(viewWidth.toFloat() / contentWidth, viewHeight.toFloat() / contentHeight)
    // Camera2 TextureView already applies sensor rotation; decoder output does not.
    val sensorRotated = cameraPreview && (config.rotation + displayDegrees) % 180 != 0
    val inputWidth = if (sensorRotated) config.height else config.width
    val inputHeight = if (sensorRotated) config.width else config.height
    return Matrix().apply {
        setScale(inputWidth.toFloat() / viewWidth, inputHeight.toFloat() / viewHeight)
        postTranslate(-inputWidth / 2f, -inputHeight / 2f)
        postRotate(if (cameraPreview) -displayDegrees.toFloat() else config.rotation.toFloat())
        postScale(scale, scale)
        postTranslate(viewWidth / 2f, viewHeight / 2f)
    }
}
