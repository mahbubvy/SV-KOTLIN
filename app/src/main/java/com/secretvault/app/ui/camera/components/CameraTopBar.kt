package com.secretvault.app.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.core.camera.FlashMode
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurface

@Composable
fun CameraTopBar(
    cameraMode: CameraMode,
    flashMode: FlashMode,
    autoFaceBlur: Boolean,
    recordAudio: Boolean,
    videoMode: VideoMode,
    supportedVideoModes: List<VideoMode>,
    isRecording: Boolean,
    onVideoModeSelect: (VideoMode) -> Unit,
    onFlashToggle: () -> Unit,
    onFaceBlurToggle: () -> Unit,
    onAudioToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.4f))
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Camera",
                tint = TextPrimary
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cameraMode == CameraMode.VIDEO) {
                var menuExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(
                        onClick = { menuExpanded = true },
                        enabled = !isRecording && supportedVideoModes.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = TextPrimary,
                            disabledContentColor = TextMuted
                        ),
                        modifier = Modifier
                            .widthIn(max = 128.dp)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .semantics { contentDescription = "Video quality: ${videoMode.label}" }
                    ) {
                        Text("${if (videoMode == VideoMode.UHD_30) "4K" else "1080p"} · ${videoMode.fps}", fontSize = 14.sp)
                    }
                    DropdownMenu(
                        expanded = menuExpanded && !isRecording,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = VaultSurface
                    ) {
                        VideoMode.entries.forEach { mode ->
                            val available = mode in supportedVideoModes
                            DropdownMenuItem(
                                text = { Text(if (available) mode.label else "${mode.label} (unavailable)") },
                                leadingIcon = {
                                    if (mode == videoMode) Icon(Icons.Default.Check, contentDescription = null)
                                },
                                enabled = available,
                                colors = MenuDefaults.itemColors(
                                    textColor = TextPrimary,
                                    leadingIconColor = TextPrimary,
                                    disabledTextColor = TextMuted,
                                    disabledLeadingIconColor = TextMuted
                                ),
                                onClick = {
                                    menuExpanded = false
                                    onVideoModeSelect(mode)
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { selected = mode == videoMode }
                            )
                        }
                    }
                }
            }
            // Flash Mode Toggle
            IconButton(
                onClick = onFlashToggle,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                val icon = when (flashMode) {
                    FlashMode.AUTO -> Icons.Default.FlashAuto
                    FlashMode.ON -> Icons.Default.FlashOn
                    FlashMode.OFF -> Icons.Default.FlashOff
                }
                Icon(
                    imageVector = icon,
                    contentDescription = "Flash Mode",
                    tint = if (flashMode != FlashMode.OFF) VaultAccent else TextPrimary
                )
            }

            if (cameraMode == CameraMode.PHOTO) {
                // Face Blur Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (autoFaceBlur) VaultAccent.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.4f))
                        .clickable(onClick = onFaceBlurToggle)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Face Blur",
                            tint = if (autoFaceBlur) VaultAccent else TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (autoFaceBlur) "BLUR ON" else "BLUR OFF",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (autoFaceBlur) VaultAccent else TextMuted
                        )
                    }
                }
            } else {
                // Video Mode: Mic Toggle
                IconButton(
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = if (recordAudio) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Microphone Toggle",
                        tint = if (recordAudio) VaultAccent else Color.Red
                    )
                }
            }
        }
    }
}
