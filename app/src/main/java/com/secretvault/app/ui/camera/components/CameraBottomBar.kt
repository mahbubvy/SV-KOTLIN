package com.secretvault.app.ui.camera.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import com.secretvault.app.core.camera.FlashMode
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.secretvault.app.core.image.EncryptedMediaUri
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.camera.CameraLensOption
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultError

@Composable
fun CameraBottomBar(
    cameraMode: CameraMode,
    isRecording: Boolean,
    flashMode: FlashMode,
    recordAudio: Boolean,
    onFlashToggle: () -> Unit,
    onAudioToggle: () -> Unit,
    recordingDurationSeconds: Int,
    isRecordingPaused: Boolean = false,
    onPauseToggle: () -> Unit = {},
    latestMediaItem: MediaItem? = null,
    rearLensOptions: List<CameraLensOption>,
    selectedRearLensId: String?,
    onRearLensSelect: (String?) -> Unit,
    onModeSelect: (CameraMode) -> Unit,
    onShutterClick: () -> Unit,
    onFlipCamera: () -> Unit,
    onGalleryClick: () -> Unit,
    onStreamToggle: () -> Unit,
    streamEnabled: Boolean,
    isStreaming: Boolean = false,
    cameraControlsEnabled: Boolean = true,
    flashEnabled: Boolean = true,
    photoAvailable: Boolean = true,
    audioEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Recording Time Counter
        AnimatedVisibility(
            visible = isRecording,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val minutes = recordingDurationSeconds / 60
            val seconds = recordingDurationSeconds % 60
            val formattedTime = String.format("%02d:%02d", minutes, seconds)
            val blink by rememberInfiniteTransition(label = "pausedBlink").animateFloat(
                initialValue = 1f, targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pausedDot"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(VaultError.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(if (isRecordingPaused) blink else 1f)
                        .clip(CircleShape)
                        .background(Color.White)
                )
                Text(
                    text = if (isRecordingPaused) "Paused $formattedTime" else formattedTime,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Mode Switcher (Photo / Video)
        if (!isRecording && !isStreaming) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModePill(
                    title = "PHOTO",
                    isSelected = cameraMode == CameraMode.PHOTO,
                    onClick = { onModeSelect(CameraMode.PHOTO) }
                )
                ModePill(
                    title = "VIDEO",
                    isSelected = cameraMode == CameraMode.VIDEO,
                    onClick = { onModeSelect(CameraMode.VIDEO) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            if (cameraMode == CameraMode.VIDEO || isStreaming) {
                IconButton(
                    onClick = onAudioToggle,
                    enabled = !isRecording && audioEnabled,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = if (recordAudio) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = if (recordAudio) "Microphone on" else "Microphone off",
                        tint = if (recordAudio) VaultAccent else TextPrimary
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isRecording && rearLensOptions.size > 1) {
                rearLensOptions.forEach { lens ->
                    val isSelected = lens.id == selectedRearLensId
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) VaultAccent.copy(alpha = 0.24f) else Color.Black.copy(alpha = 0.55f))
                            .selectable(
                                selected = isSelected,
                                enabled = cameraControlsEnabled,
                                role = Role.RadioButton,
                                onClick = { onRearLensSelect(lens.id) }
                            )
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${lens.label} camera lens"
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lens.label,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) VaultAccent else TextPrimary
                        )
                    }
                }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onStreamToggle, enabled = streamEnabled,
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.8f))) {
                    Icon(if (isStreaming) Icons.Default.StopCircle else Icons.Default.Cast,
                        contentDescription = if (isStreaming) "Stop stream" else "Stream",
                        tint = if (!streamEnabled) TextMuted else if (isStreaming) VaultAccent else TextPrimary)
                }
                IconButton(
                    onClick = onFlashToggle,
                    enabled = flashEnabled,
                    modifier = Modifier.size(48.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = when (flashMode) {
                            FlashMode.AUTO -> Icons.Default.FlashAuto
                            FlashMode.ON -> Icons.Default.FlashOn
                            FlashMode.OFF -> Icons.Default.FlashOff
                        },
                        contentDescription = "Flash ${flashMode.name.lowercase()}",
                        tint = if (flashMode != FlashMode.OFF) VaultAccent else TextPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Main Shutter Action Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gallery Shortcut / Recent Media Preview
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .border(1.5.dp, VaultAccent.copy(alpha = 0.6f), CircleShape)
                    .clickable(enabled = !isRecording, onClick = onGalleryClick),
                contentAlignment = Alignment.Center
            ) {
                val thumb = latestMediaItem?.thumbnailPath ?: latestMediaItem?.encryptedPath
                if (thumb != null) {
                    AsyncImage(
                        model = EncryptedMediaUri(thumb),
                        contentDescription = "Recent Media",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = "Vault Gallery",
                        tint = TextPrimary
                    )
                }
            }

            // Shutter Button
            ShutterButton(
                cameraMode = cameraMode,
                isRecording = isRecording,
                enabled = cameraControlsEnabled && ((cameraMode == CameraMode.PHOTO && photoAvailable) || !isStreaming),
                onClick = onShutterClick
            )

            // Pause/Resume takes the flip slot while recording, when flipping is disabled anyway
            if (isRecording && cameraMode == CameraMode.VIDEO) IconButton(
                onClick = onPauseToggle,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = if (isRecordingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isRecordingPaused) "Resume recording" else "Pause recording",
                    tint = TextPrimary
                )
            } else IconButton(
                onClick = onFlipCamera,
                enabled = !isRecording && cameraControlsEnabled,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Flip Camera",
                    tint = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun ModePill(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        color = if (isSelected) VaultAccent else TextMuted,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun ShutterButton(
    cameraMode: CameraMode,
    isRecording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .border(4.dp, if (enabled) Color.White else TextMuted, CircleShape)
            .padding(6.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val innerShape = if (cameraMode == CameraMode.VIDEO && isRecording) {
            RoundedCornerShape(8.dp)
        } else {
            CircleShape
        }

        val innerColor = if (cameraMode == CameraMode.VIDEO) {
            VaultError
        } else {
            Color.White
        }

        val innerSize = if (cameraMode == CameraMode.VIDEO && isRecording) {
            32.dp
        } else {
            60.dp
        }

        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(innerShape)
                .background(innerColor)
        )
    }
}
