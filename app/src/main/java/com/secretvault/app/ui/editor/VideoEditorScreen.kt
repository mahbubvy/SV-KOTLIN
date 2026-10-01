package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.secretvault.app.R
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.player.DecryptingMediaDataSource
import com.secretvault.app.core.player.EncryptedMediaDataSource
import com.secretvault.app.core.processing.VideoEditMode
import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.core.worker.VideoEditState
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultError
import com.secretvault.app.ui.theme.VaultSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val TIMELINE_FRAMES = 8

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditorScreen(
    app: SecretVaultApp,
    item: MediaItem,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val editState by app.videoEditManager.state.collectAsState()

    val player = remember(item.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EncryptedMediaDataSource.Factory(app.cryptoEngine)))
            .build()
            .apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(File(item.encryptedPath))))
                prepare()
            }
    }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(VideoEditMode.TRIM) }
    var range by remember { mutableStateOf(0f..0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0L && player.duration > 0) {
                    durationMs = player.duration
                    range = 0f..durationMs.toFloat()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            delay(100L)
        }
    }

    // Earlier results belong to a previous visit to the editor.
    LaunchedEffect(Unit) { app.videoEditManager.clearResult() }

    LaunchedEffect(editState) {
        when (val state = editState) {
            is VideoEditState.Running -> player.pause()
            is VideoEditState.Done -> {
                Toast.makeText(context, "Saved as a new video", Toast.LENGTH_SHORT).show()
                app.videoEditManager.clearResult()
                onBack()
            }
            else -> Unit
        }
    }

    val frames by produceState(initialValue = emptyList<Bitmap>(), item.id, durationMs) {
        if (durationMs > 0) value = loadTimelineFrames(app, item, durationMs)
    }

    val startMs = range.start.toLong()
    val endMs = range.endInclusive.toLong()
    val segments = if (durationMs > 0) VideoEditPlan.keptSegments(mode, durationMs, startMs, endMs) else emptyList()
    val resultMs = segments.sumOf { it.durationMs }
    val canSave = segments.isNotEmpty() && resultMs < durationMs

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("Edit video", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { app.videoEditManager.start(item, segments) },
                enabled = canSave && editState !is VideoEditState.Running
            ) {
                Text("Save", color = if (canSave) VaultAccent else TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { if (player.isPlaying) player.pause() else player.play() },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.streaming_player_view, null) as PlayerView).apply {
                        this.player = player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (durationMs == 0L) {
                CircularProgressIndicator(color = VaultAccent, modifier = Modifier.size(48.dp))
            } else if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(VideoEditMode.TRIM to "Trim", VideoEditMode.REMOVE_SECTION to "Remove section")
                    .forEachIndexed { index, (option, label) ->
                        SegmentedButton(
                            selected = mode == option,
                            onClick = {
                                if (mode != option) {
                                    mode = option
                                    // Full range means "keep everything" for trim but "remove everything" for
                                    // remove-section, so start each mode from a sensible selection.
                                    val d = durationMs.toFloat()
                                    range = if (option == VideoEditMode.TRIM) 0f..d else d / 3f..d * 2f / 3f
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = VaultAccent.copy(alpha = 0.25f),
                                activeContentColor = TextPrimary,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = TextSecondary
                            )
                        ) { Text(label) }
                    }
            }

            Spacer(Modifier.height(16.dp))

            TimelineStrip(
                frames = frames,
                durationMs = durationMs,
                positionMs = positionMs,
                removedRanges = when (mode) {
                    VideoEditMode.TRIM -> listOf(0L to startMs, endMs to durationMs)
                    VideoEditMode.REMOVE_SECTION -> listOf(startMs to endMs)
                }
            )

            val trackColor = if (mode == VideoEditMode.TRIM) VaultAccent else VaultError
            RangeSlider(
                value = range,
                onValueChange = { newRange ->
                    // Show the frame under whichever handle is moving.
                    val moved = if (newRange.start != range.start) newRange.start else newRange.endInclusive
                    range = newRange
                    player.pause()
                    player.seekTo(moved.toLong())
                },
                valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                enabled = durationMs > 0 && editState !is VideoEditState.Running,
                colors = SliderDefaults.colors(
                    thumbColor = trackColor,
                    activeTrackColor = trackColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Start ${formatTime(startMs)}", color = TextSecondary, fontSize = 12.sp)
                Text("End ${formatTime(endMs)}", color = TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    durationMs == 0L -> "Loading video…"
                    segments.isEmpty() -> "The new video must be at least 1 second long."
                    !canSave -> if (mode == VideoEditMode.TRIM) "Drag the handles to choose the part to keep." else "Drag the handles to choose the part to remove."
                    else -> "New video: ${formatTime(resultMs)} (removes ${formatTime(durationMs - resultMs)}). The original is kept."
                },
                color = if (durationMs > 0 && segments.isEmpty()) VaultError else TextMuted,
                fontSize = 13.sp
            )
        }
    }

    when (val state = editState) {
        is VideoEditState.Running -> SavingDialog(state, onCancel = { app.videoEditManager.cancel() })
        is VideoEditState.Failed -> AlertDialog(
            onDismissRequest = { app.videoEditManager.clearResult() },
            title = { Text("Couldn't save", color = TextPrimary) },
            text = { Text(state.message, color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { app.videoEditManager.clearResult() }) { Text("OK", color = VaultAccent) }
            },
            containerColor = VaultSurface
        )
        else -> Unit
    }
}

@Composable
private fun TimelineStrip(
    frames: List<Bitmap>,
    durationMs: Long,
    positionMs: Long,
    removedRanges: List<Pair<Long, Long>>
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(VaultSurface)
    ) {
        Row(Modifier.fillMaxSize()) {
            frames.forEach { frame ->
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.weight(1f).fillMaxSize()
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            if (durationMs <= 0) return@Canvas
            val pxPerMs = size.width / durationMs
            removedRanges.forEach { (from, to) ->
                if (to > from) {
                    drawRect(
                        color = Color.Black.copy(alpha = 0.7f),
                        topLeft = Offset(from * pxPerMs, 0f),
                        size = Size((to - from) * pxPerMs, size.height)
                    )
                }
            }
            val x = positionMs * pxPerMs
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun SavingDialog(state: VideoEditState.Running, onCancel: () -> Unit) {
    val cutting = state.stage == VideoEditState.Stage.CUTTING
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (cutting) "Cutting video…" else "Encrypting…", color = TextPrimary) },
        text = {
            Column {
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(color = VaultAccent, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, color = VaultAccent, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}%", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (cutting) TextButton(onClick = onCancel) { Text("Cancel", color = TextMuted) }
        },
        containerColor = VaultSurface
    )
}

/** Small frames spread across the video, decoded straight from the encrypted file. */
private suspend fun loadTimelineFrames(app: SecretVaultApp, item: MediaItem, durationMs: Long): List<Bitmap> =
    withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            DecryptingMediaDataSource(app.cryptoEngine, File(item.encryptedPath)).use { source ->
                retriever.setDataSource(source)
                (0 until TIMELINE_FRAMES).mapNotNull { i ->
                    val atUs = durationMs * 1000L * (2 * i + 1) / (2 * TIMELINE_FRAMES)
                    retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                        val height = 96
                        val width = (height * frame.width / frame.height.coerceAtLeast(1)).coerceAtLeast(1)
                        Bitmap.createScaledBitmap(frame, width, height, true).also { if (it !== frame) frame.recycle() }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

private fun formatTime(ms: Long): String {
    val tenths = (ms / 100) % 10
    val seconds = (ms / 1000) % 60
    val minutes = ms / 60_000
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}
