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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.exoplayer.SeekParameters
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

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
    var touching by remember { mutableStateOf(false) }
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
    // Every frame, so the timeline scrolls smoothly; a finger on the timeline owns the playhead instead.
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            val current = latestProject
            val index = player.currentMediaItemIndex
            if (index in current.clips.indices) {
                if (!touching) positionMs = current.outputStartOf(index) + player.currentPosition
                player.volume = if (current.clips[index].muted) 0f else 1f
            }
        }
    }

    // Frames for the timeline, once per source video.
    val frames = remember { mutableStateMapOf<String, List<ImageBitmap>>() }
    LaunchedEffect(project.clips.mapTo(HashSet()) { it.media.id }) {
        latestProject.clips.distinctBy { it.media.id }.filter { it.media.id !in frames }.forEach { c ->
            val count = (c.sourceDurationMs / 2_000).toInt().coerceIn(4, 30)
            frames[c.media.id] = loadTimelineFrames(app, c.media, c.sourceDurationMs, count).map { it.asImageBitmap() }
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
            Timeline(project, selected, positionMs, frames, enabled = !saving,
                onSelect = { selected = it },
                onTouch = { down ->
                    touching = down
                    if (down) { player.pause(); player.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
                    else { player.setSeekParameters(SeekParameters.EXACT); seekTo(positionMs) }
                },
                onScrub = ::seekTo,
                onTrim = { start, startMs, endMs ->
                    project = project.trim(selected, startMs, endMs)
                    // Park the dragged edge under the playhead so the preview shows the cut frame.
                    positionMs = project.outputStartOf(selected) + if (start) 0L else project.clips[selected].durationMs - 1
                })
            if (clip != null) {
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

/**
 * Scrolling timeline with the playhead fixed in the centre: swipe to scrub, pinch to zoom, tap to select a clip,
 * drag the selected clip's edges to trim. [onTouch] brackets every swipe, pinch or edge drag.
 */
@Composable
private fun Timeline(
    project: VideoProject, selected: Int, positionMs: Long, frames: Map<String, List<ImageBitmap>>, enabled: Boolean,
    onSelect: (Int) -> Unit, onTouch: (Boolean) -> Unit, onScrub: (Long) -> Unit,
    onTrim: (start: Boolean, startMs: Long, endMs: Long) -> Unit
) {
    var dpPerSecond by remember { mutableFloatStateOf(60f) }
    val pxPerMs = with(LocalDensity.current) { dpPerSecond.dp.toPx() } / 1000f
    val mutePainter = rememberVectorPainter(Icons.AutoMirrored.Filled.VolumeOff)
    val state by rememberUpdatedState(TimelineState(project, selected, positionMs, pxPerMs, onSelect, onTouch, onScrub, onTrim))

    Canvas(Modifier.fillMaxWidth().height(64.dp).clipToBounds().pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            val begin = state
            if (begin.project.clips.isEmpty()) return@awaitEachGesture
            val centre = size.width / 2f
            fun screenX(outputMs: Long) = centre + (outputMs - begin.positionMs) * begin.pxPerMs
            // Which edge of the selected clip is under the finger, if any: -1 start, 1 end, 0 neither.
            val edge = begin.project.clips.getOrNull(begin.selected)?.let { c ->
                val left = screenX(begin.project.outputStartOf(begin.selected))
                val right = left + c.durationMs * begin.pxPerMs
                val reach = 24.dp.toPx()
                val toLeft = abs(down.position.x - left)
                val toRight = abs(down.position.x - right)
                when {
                    toLeft <= reach && toLeft <= toRight -> -1
                    toRight <= reach -> 1
                    else -> 0
                }
            } ?: 0
            var dragged = 0f
            var moving = false
            var zooming = false
            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) zooming = true
                val pan = event.calculatePan().x
                val zoom = event.calculateZoom()
                if (!moving && (zooming || abs(dragged + pan) > viewConfiguration.touchSlop)) { moving = true; begin.onTouch(true) }
                dragged += pan
                if (moving) {
                    event.changes.forEach { it.consume() }
                    val now = state
                    when {
                        zooming -> dpPerSecond = (dpPerSecond * zoom).coerceIn(10f, 400f)
                        edge != 0 -> {
                            val c = begin.project.clips[begin.selected]
                            val byMs = (dragged / begin.pxPerMs).toLong()
                            if (edge < 0) now.onTrim(true, c.startMs + byMs, c.endMs) else now.onTrim(false, c.startMs, c.endMs + byMs)
                        }
                        else -> now.onScrub((begin.positionMs - (dragged / begin.pxPerMs).toLong()).coerceIn(0L, begin.project.durationMs))
                    }
                }
            } while (event.changes.any { it.pressed })
            if (moving) state.onTouch(false)
            else begin.project.clipAt(begin.positionMs + ((down.position.x - centre) / begin.pxPerMs).toLong())
                ?.let { (index, _) -> begin.onSelect(index) }
        }
    }) {
        val centre = size.width / 2f
        val tile = size.height
        val gap = 1.dp.toPx()
        var outputStart = 0L
        project.clips.forEachIndexed { index, c ->
            val left = centre + (outputStart - positionMs) * pxPerMs
            val right = left + c.durationMs * pxPerMs
            outputStart += c.durationMs
            if (right < 0f || left > size.width) return@forEachIndexed
            val l = left + gap
            val r = right - gap
            clipRect(l, 0f, r, size.height) {
                drawRect(VaultSurface, Offset(l, 0f), Size(r - l, size.height))
                val pics = frames[c.media.id].orEmpty()
                // Square tiles, each showing the frame nearest the source time at its middle.
                var x = if (left < 0f) left + floor(-left / tile) * tile else left
                while (pics.isNotEmpty() && x < min(r, size.width)) {
                    val sourceMs = c.startMs + ((x + tile / 2 - left) / pxPerMs).toLong()
                    val pic = pics[(sourceMs * pics.size / c.sourceDurationMs).toInt().coerceIn(0, pics.lastIndex)]
                    val side = min(pic.width, pic.height)
                    drawImage(pic, IntOffset((pic.width - side) / 2, (pic.height - side) / 2), IntSize(side, side),
                        IntOffset(x.roundToInt(), 0), IntSize(tile.roundToInt(), tile.roundToInt()))
                    x += tile
                }
            }
            if (c.muted) {
                val badge = 18.dp.toPx()
                val bx = min(r, size.width) - badge - 4.dp.toPx()
                if (bx > l) {
                    drawCircle(MutedColor, badge / 2, Offset(bx + badge / 2, 4.dp.toPx() + badge / 2))
                    translate(bx + 3.dp.toPx(), 4.dp.toPx() + 3.dp.toPx()) {
                        with(mutePainter) { draw(Size(badge - 6.dp.toPx(), badge - 6.dp.toPx()), colorFilter = ColorFilter.tint(Color.White)) }
                    }
                }
            }
            if (index == selected) {
                val handle = 10.dp.toPx()
                drawRect(VaultAccent, Offset(l, gap), Size(r - l, size.height - 2 * gap), style = Stroke(2.dp.toPx()))
                for (hx in listOf(l, r - handle)) {
                    drawRect(VaultAccent, Offset(hx, 0f), Size(handle, size.height))
                    drawLine(Color.White, Offset(hx + handle / 2, size.height * 0.3f), Offset(hx + handle / 2, size.height * 0.7f), 2.dp.toPx())
                }
            }
        }
        drawLine(Color.White, Offset(centre, 0f), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
    }
}

/** Everything the timeline's gesture handler reads, captured together so one gesture sees a consistent snapshot. */
private class TimelineState(
    val project: VideoProject, val selected: Int, val positionMs: Long, val pxPerMs: Float,
    val onSelect: (Int) -> Unit, val onTouch: (Boolean) -> Unit, val onScrub: (Long) -> Unit,
    val onTrim: (Boolean, Long, Long) -> Unit
)

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
