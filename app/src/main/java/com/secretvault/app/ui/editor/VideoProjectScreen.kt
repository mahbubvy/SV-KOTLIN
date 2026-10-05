package com.secretvault.app.ui.editor

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.secretvault.app.R
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.player.DecryptingMediaDataSource
import com.secretvault.app.core.player.EncryptedMediaDataSource
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.worker.VideoEditState
import com.secretvault.app.ui.gallery.components.StableEncryptedThumbnail
import com.secretvault.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val MutedColor = Color(0xFF4F8DF7)

/** Multi-clip editor opened from the home screen. See SPEC-video-editor-plus.md. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoProjectScreen(app: SecretVaultApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editState by app.videoEditManager.state.collectAsState()
    var project by remember { mutableStateOf(VideoProject()) }
    var selected by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(true) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val latestProject by rememberUpdatedState(project)
    val clip = project.clips.getOrNull(selected)
    val saving = editState is VideoEditState.Running

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EncryptedMediaDataSource.Factory(app.cryptoEngine)))
            .build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    fun seekTo(outputMs: Long) {
        latestProject.clipAt(outputMs)?.let { (index, offset) -> player.seekTo(index, offset) }
        positionMs = outputMs
    }

    // The preview is a playlist of the clips; rebuild it whenever they change, keeping the playhead.
    LaunchedEffect(project.clips) {
        val keep = positionMs.coerceAtMost(project.durationMs)
        player.setMediaItems(project.clips.map { c ->
            androidx.media3.common.MediaItem.Builder()
                .setMediaId(c.id)
                .setUri(Uri.fromFile(File(c.media.encryptedPath)))
                .setClippingConfiguration(androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build())
                .build()
        })
        player.prepare()
        seekTo(keep)
        selected = selected.coerceIn(0, (project.clips.size - 1).coerceAtLeast(0))
    }
    LaunchedEffect(player) {
        while (true) {
            val current = latestProject
            val index = player.currentMediaItemIndex
            if (index in current.clips.indices) {
                positionMs = current.outputStartOf(index) + player.currentPosition
                player.volume = if (current.clips[index].muted) 0f else 1f
            }
            delay(100L)
        }
    }

    // Results belong to this visit only.
    LaunchedEffect(Unit) { app.videoEditManager.clearResult() }
    LaunchedEffect(editState) {
        when (editState) {
            is VideoEditState.Running -> player.pause()
            is VideoEditState.Done -> {
                Toast.makeText(context, "Saved as a new video", Toast.LENGTH_SHORT).show()
                app.videoEditManager.clearResult()
                onBack()
            }
            else -> Unit
        }
    }

    fun leave() { if (project.clips.isEmpty()) onBack() else confirmDiscard = true }
    BackHandler { leave() }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
            Text("Video editor", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val canSave = project.clips.isNotEmpty() && !saving
            TextButton(onClick = { player.pause(); app.videoEditManager.start(project) }, enabled = canSave) {
                Text("Save", color = if (canSave) VaultAccent else TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().clickable(enabled = project.clips.isNotEmpty()) {
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_ENDED) seekTo(0L)
                player.play()
            }
        }, contentAlignment = Alignment.Center) {
            AndroidView(factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.streaming_player_view, null) as PlayerView).apply {
                    this.player = player; useController = false
                }
            }, modifier = Modifier.fillMaxSize())
            if (project.clips.isEmpty()) Button(onClick = { showPicker = true },
                colors = ButtonDefaults.buttonColors(containerColor = VaultAccent, contentColor = VaultDarkBg)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add videos from vault")
            } else if (!isPlaying) Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, enabled = project.clips.isNotEmpty()) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = TextPrimary)
                }
                Text("${formatTime(positionMs)} / ${formatTime(project.durationMs)}", color = TextSecondary, fontSize = 13.sp)
            }
            ClipTrack(project, selected, positionMs) { index, outputMs ->
                player.pause(); selected = index; seekTo(outputMs)
            }
            if (clip != null) {
                RangeSlider(
                    value = clip.startMs.toFloat()..clip.endMs.toFloat(),
                    onValueChange = { range ->
                        val movedStart = range.start.toLong() != clip.startMs
                        project = project.trim(selected, range.start.toLong(), range.endInclusive.toLong())
                        val trimmed = project.clips[selected]
                        player.pause()
                        positionMs = project.outputStartOf(selected) + if (movedStart) 0L else trimmed.durationMs - 1
                    },
                    valueRange = 0f..clip.sourceDurationMs.toFloat(),
                    enabled = !saving,
                    colors = SliderDefaults.colors(thumbColor = VaultAccent, activeTrackColor = VaultAccent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f))
                )
                Text("Clip ${selected + 1} of ${project.clips.size}: ${formatTime(clip.startMs)} – ${formatTime(clip.endMs)} of " +
                    "${clip.media.originalName}${if (clip.muted) " · muted" else ""}", color = TextMuted, fontSize = 12.sp, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val ready = !saving
                Tool(Icons.Default.Add, "Add", ready) { showPicker = true }
                Tool(Icons.Default.ContentCut, "Split", ready && clip != null) {
                    val before = project.clips.size
                    project = project.split(positionMs)
                    if (project.clips.size == before) Toast.makeText(context, "Move the playhead away from the clip's edge to split", Toast.LENGTH_SHORT).show()
                }
                Tool(if (clip?.muted == true) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    if (clip?.muted == true) "Unmute" else "Mute", ready && clip != null) { project = project.toggleMute(selected) }
                Tool(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", ready && selected > 0) {
                    project = project.move(selected, -1); selected--
                }
                Tool(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", ready && selected < project.clips.lastIndex) {
                    project = project.move(selected, 1); selected++
                }
                Tool(Icons.Default.Delete, "Delete", ready && clip != null) { project = project.delete(selected) }
            }
        }
    }

    if (showPicker) VaultVideoPicker(app, onDismiss = { showPicker = false }) { picked ->
        showPicker = false
        scope.launch {
            val durations = withContext(Dispatchers.IO) { picked.map { it to sourceDuration(app, it) } }
            val before = project.clips.size
            project = durations.fold(project) { acc, (media, duration) -> acc.add(media, duration) }
            if (project.clips.size > before) selected = before
            if (project.clips.size - before < picked.size) Toast.makeText(context, "Videos shorter than half a second were skipped", Toast.LENGTH_SHORT).show()
        }
    }

    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, containerColor = VaultSurface,
        title = { Text("Discard changes?", color = TextPrimary) },
        text = { Text("Your edit hasn't been saved. The original videos are not affected.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard", color = VaultError) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = VaultAccent) } })

    when (val state = editState) {
        is VideoEditState.Running -> SavingDialog(state, onCancel = { app.videoEditManager.cancel() })
        is VideoEditState.Failed -> AlertDialog(onDismissRequest = { app.videoEditManager.clearResult() },
            title = { Text("Couldn't save", color = TextPrimary) }, text = { Text(state.message, color = TextSecondary) },
            confirmButton = { TextButton(onClick = { app.videoEditManager.clearResult() }) { Text("OK", color = VaultAccent) } },
            containerColor = VaultSurface)
        else -> Unit
    }
}

/** Fit-to-width strip of clip blocks with the playhead. Phase 3 replaces it with a scrolling timeline. */
@Composable
private fun ClipTrack(project: VideoProject, selected: Int, positionMs: Long, onTap: (index: Int, outputMs: Long) -> Unit) {
    val total = project.durationMs
    val latestTap by rememberUpdatedState(onTap)
    val latestProject by rememberUpdatedState(project)
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(VaultSurface)
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                val current = latestProject
                if (current.durationMs <= 0) return@detectTapGestures
                val ms = (offset.x / size.width * current.durationMs).toLong()
                current.clipAt(ms)?.let { (index, _) -> latestTap(index, ms) }
            }
        }) {
        Row(Modifier.fillMaxSize()) {
            project.clips.forEachIndexed { index, c ->
                Box(Modifier.weight(c.durationMs.toFloat()).fillMaxHeight().padding(1.dp).clip(RoundedCornerShape(6.dp))
                    .border(if (index == selected) 2.dp else 0.dp, if (index == selected) VaultAccent else Color.Transparent, RoundedCornerShape(6.dp))) {
                    c.media.thumbnailPath?.let { StableEncryptedThumbnail(c.media.id, it, c.media.originalName, Modifier.fillMaxSize()) }
                    if (c.muted) Icon(Icons.AutoMirrored.Filled.VolumeOff, "Muted", tint = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).background(MutedColor, CircleShape).padding(2.dp).size(14.dp))
                }
            }
        }
        if (total > 0) Canvas(Modifier.fillMaxSize()) {
            val x = positionMs.toFloat() / total * size.width
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) TextPrimary else TextMuted
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Icon(icon, null, tint = tint)
        Text(label, color = tint, fontSize = 11.sp)
    }
}

/** Grid of vault videos; tap to pick (numbers show the order they'll be added in). */
@Composable
private fun VaultVideoPicker(app: SecretVaultApp, onDismiss: () -> Unit, onAdd: (List<MediaItem>) -> Unit) {
    val videos by remember { app.mediaRepository.getMedia() }.collectAsState(initial = null)
    var picked by remember { mutableStateOf(emptyList<MediaItem>()) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(VaultDarkBg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = TextPrimary) }
                Text("Add videos", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onAdd(picked) }, enabled = picked.isNotEmpty()) {
                    Text(if (picked.isEmpty()) "Add" else "Add (${picked.size})", color = if (picked.isEmpty()) TextMuted else VaultAccent)
                }
            }
            val list = videos?.filter { it.mediaType == MediaType.VIDEO }
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VaultAccent) }
                list.isEmpty() -> Text("No videos in the vault yet.", color = TextSecondary, modifier = Modifier.padding(16.dp))
                else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(list, key = { it.id }) { video ->
                        val order = picked.indexOfFirst { it.id == video.id }
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(VaultSurface)
                            .border(if (order >= 0) 2.5.dp else 0.dp, if (order >= 0) VaultAccent else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { picked = if (order >= 0) picked.filter { it.id != video.id } else picked + video }) {
                            video.thumbnailPath?.let { StableEncryptedThumbnail(video.id, it, video.originalName, Modifier.fillMaxSize()) }
                            Text(formatTime(video.durationMs).substringBeforeLast('.'), color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp))
                            if (order >= 0) Text("${order + 1}", color = VaultDarkBg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(VaultAccent, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

/** The stored duration, or read from the file when an older import didn't record one. */
private fun sourceDuration(app: SecretVaultApp, media: MediaItem): Long {
    if (media.durationMs > 0) return media.durationMs
    val retriever = MediaMetadataRetriever()
    return try {
        DecryptingMediaDataSource(app.cryptoEngine, File(media.encryptedPath)).use { source ->
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (e: Exception) { 0L } finally { retriever.release() }
}
