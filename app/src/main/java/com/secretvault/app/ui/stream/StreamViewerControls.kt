package com.secretvault.app.ui.stream

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.camera.FlashMode
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.core.stream.StreamState
import com.secretvault.app.ui.theme.*

@Composable
internal fun StreamViewerControls(
    state: StreamState,
    settingsAvailable: Boolean,
    canChooseCamera: Boolean,
    onSetting: (Int, Int) -> Unit,
    onCamera: (String) -> Unit,
) {
    var qualityMenu by remember { mutableStateOf(false) }
    var flashMenu by remember { mutableStateOf(false) }
    var cameraMenu by remember { mutableStateOf(false) }
    val settings = state.settingsState
    val mode = settings.mode?.let { VideoMode.entries[it] }
    val flash = FlashMode.entries[settings.flashMode]
    val canSetFlash = !state.photoBusy && !state.cameraBusy && !state.recordingBusy && !state.settingsBusy && !state.interactionBusy &&
        !state.recordingState.saving && !state.stopping && state.cameraState.selectedId != null && settings.flashAvailable
    val quality: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            ViewerMenuButton("Video quality", Icons.Default.HighQuality, mode?.resolutionLabel ?: "Quality",
                mode?.let { "${it.fps} FPS" }, canChooseCamera && mode != null, { qualityMenu = true })
            DropdownMenu(qualityMenu && canChooseCamera, { qualityMenu = false }, containerColor = VaultSurface) {
                settings.modes.forEach { value ->
                    DropdownMenuItem(text = { Text(VideoMode.entries[value].label, color = TextPrimary) },
                        onClick = { qualityMenu = false; onSetting(0, value) },
                        trailingIcon = { if (value == settings.mode) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
                if (!settings.photoAvailable) Text("Photos require 30 FPS", color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
    val flashControl: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            ViewerMenuButton(if (settings.flashAvailable) "Flash ${flash.name.lowercase()}" else "No flash",
                when (flash) { FlashMode.AUTO -> Icons.Default.FlashAuto; FlashMode.ON -> Icons.Default.FlashOn; FlashMode.OFF -> Icons.Default.FlashOff },
                if (settings.flashAvailable) flash.name.lowercase().replaceFirstChar { it.uppercase() } else "No flash",
                null, canSetFlash, { flashMenu = true })
            DropdownMenu(flashMenu && canSetFlash, { flashMenu = false }, containerColor = VaultSurface) {
                listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON).forEach { value ->
                    DropdownMenuItem(text = { Column {
                        Text("Flash ${value.name.lowercase()}", color = TextPrimary)
                        if (value != FlashMode.OFF) Text(if (value == FlashMode.AUTO) "Photos only" else "Continuous light", color = TextSecondary, fontSize = 12.sp)
                    } }, onClick = { flashMenu = false; onSetting(1, value.ordinal) },
                        trailingIcon = { if (value == flash) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
    val camera: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            ViewerMenuButton("Choose camera", Icons.Default.Cameraswitch,
                state.cameraState.options.firstOrNull { it.id == state.cameraState.selectedId }?.label ?: "Camera",
                null, canChooseCamera && state.cameraState.options.size > 1, { cameraMenu = true })
            DropdownMenu(cameraMenu && canChooseCamera, { cameraMenu = false }, containerColor = VaultSurface) {
                state.cameraState.options.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label, color = TextPrimary) },
                        onClick = { cameraMenu = false; onCamera(option.id) },
                        trailingIcon = { if (option.id == state.cameraState.selectedId) Icon(Icons.Default.Check, "Selected", tint = TextPrimary) },
                        modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (settingsAvailable) { quality(Modifier.fillMaxWidth()); flashControl(Modifier.fillMaxWidth()) }
                camera(Modifier.fillMaxWidth())
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settingsAvailable) { quality(Modifier.weight(1f)); flashControl(Modifier.weight(1f)) }
            camera(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ViewerMenuButton(
    description: String,
    icon: ImageVector,
    label: String,
    detail: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = description },
        border = BorderStroke(1.dp, if (enabled) TextMuted else TextMuted.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = VaultSurface, contentColor = TextPrimary,
            disabledContainerColor = VaultSurface, disabledContentColor = TextMuted)) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontSize = 14.sp)
            if (detail != null) Text(detail, fontSize = 12.sp, color = if (enabled) TextSecondary else TextMuted)
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
    }
}
