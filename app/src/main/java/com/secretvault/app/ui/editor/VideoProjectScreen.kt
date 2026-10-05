package com.secretvault.app.ui.editor

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEmotions
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.media3.exoplayer.SeekParameters
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import com.secretvault.app.core.image.key
import com.secretvault.app.core.image.stickerBitmap
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.StickerSource
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
import kotlin.math.max
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
    // A draft means the vault locked mid-edit: carry on where it stopped.
    val restored = remember { app.videoEditManager.draft }
    var project by remember { mutableStateOf(restored ?: VideoProject()) }
    var selected by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(if (restored != null) app.videoEditManager.draftPositionMs else 0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(restored == null) }
    var leaving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    var stickerId by remember { mutableStateOf<String?>(null) }
    var showStickerPicker by remember { mutableStateOf(false) }
    val latestProject by rememberUpdatedState(project)
    val clip = project.clips.getOrNull(selected)
    val sticker = project.stickers.firstOrNull { it.id == stickerId }
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

    // Sticker images, once per emoji or photo; null when a photo can't be decoded.
    val stickerImages = remember { mutableStateMapOf<String, ImageBitmap?>() }
    LaunchedEffect(project.stickers.mapTo(HashSet()) { it.source.key }) {
        latestProject.stickers.map { it.source }.distinctBy { it.key }.filter { it.key !in stickerImages }.forEach { source ->
            stickerImages[source.key] = withContext(Dispatchers.IO) { stickerBitmap(app.cryptoEngine, source) }?.asImageBitmap()
        }
    }

    // The preview frame takes the first clip's shape, like the export, so stickers land where they'll be saved.
    val frameSize by produceState<Pair<Int, Int>?>(null, project.clips.firstOrNull()?.media?.id) {
        value = latestProject.clips.firstOrNull()?.let { withContext(Dispatchers.IO) { app.videoEditManager.uprightSize(it.media) } }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) seekTo(0L)
            player.play()
        }
    }

    // Kept up to date so a lock (which drops this screen without warning) loses nothing; cleared only on purpose.
    SideEffect { if (!leaving) app.videoEditManager.keepDraft(project, positionMs) }
    fun close() { leaving = true; app.videoEditManager.clearDraft(); onBack() }

    // Hidden (screen off, home button, lock): pause rather than play on unseen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Results belong to this visit only, unless it's a restored edit whose save was running or failed meanwhile.
    LaunchedEffect(Unit) { if (restored == null) app.videoEditManager.clearResult() }
    LaunchedEffect(editState) {
        when (editState) {
            is VideoEditState.Running -> player.pause()
            is VideoEditState.Done -> {
                Toast.makeText(context, "Saved as a new video", Toast.LENGTH_SHORT).show()
                app.videoEditManager.clearResult()
                close()
            }
            else -> Unit
        }
    }

    fun leave() { if (project.clips.isEmpty()) close() else confirmDiscard = true }
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

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = frameSize?.let { (w, h) -> w.toFloat() / h }
            val frameW = if (ratio == null) maxWidth else minOf(maxWidth, maxHeight * ratio)
            val frameH = if (ratio == null) maxHeight else frameW / ratio
            Box(Modifier.size(frameW, frameH).clipToBounds()
                // Tap: pick the sticker under the finger, or let go of the selected one, or play/pause.
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        if (project.clips.isEmpty()) return@detectTapGestures
                        val hit = project.stickers.lastOrNull { s ->
                            val img = stickerImages[s.source.key] ?: return@lastOrNull false
                            val p = s.placementAt(positionMs)
                            val w = p.widthFraction * size.width
                            val h = w * img.height / img.width
                            (positionMs in s.startMs until s.endMs || s.id == stickerId) &&
                                abs(tap.x - p.centerX * size.width) <= w / 2 && abs(tap.y - p.centerY * size.height) <= h / 2
                        }
                        when {
                            hit != null -> stickerId = hit.id
                            stickerId != null -> stickerId = null
                            else -> togglePlay()
                        }
                    }
                }
                // Drag moves the selected sticker, pinch resizes it; with keyframes, at the playhead's moment.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val id = stickerId ?: return@detectTransformGestures
                        player.pause()
                        project = project.updateSticker(id) { s ->
                            s.placeAt(positionMs) {
                                it.copy(centerX = it.centerX + pan.x / size.width, centerY = it.centerY + pan.y / size.height,
                                    widthFraction = it.widthFraction * zoom)
                            }
                        }
                    }
                }) {
                AndroidView(factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.streaming_player_view, null) as PlayerView).apply {
                        this.player = player; useController = false
                    }
                }, modifier = Modifier.fillMaxSize())
                project.stickers.forEach { s ->
                    val img = stickerImages[s.source.key] ?: return@forEach
                    if (positionMs !in s.startMs until s.endMs && s.id != stickerId) return@forEach
                    val p = s.placementAt(positionMs)
                    val w = frameW * p.widthFraction
                    val h = w * img.height / img.width
                    Image(img, null, Modifier.offset(frameW * p.centerX - w / 2, frameH * p.centerY - h / 2).size(w, h)
                        .then(if (s.id == stickerId) Modifier.border(1.5.dp, Color.White) else Modifier))
                }
            }
            if (project.clips.isEmpty()) Button(onClick = { showPicker = true },
                colors = ButtonDefaults.buttonColors(containerColor = VaultAccent, contentColor = VaultDarkBg)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add videos from vault")
            } else if (!isPlaying && sticker == null) Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::togglePlay, enabled = project.clips.isNotEmpty()) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play", tint = TextPrimary)
                }
                Text("${formatTime(positionMs)} / ${formatTime(project.durationMs)}", color = TextSecondary, fontSize = 13.sp)
            }
            Timeline(project, selected, sticker?.id, positionMs, frames, stickerImages, enabled = !saving,
                onSelect = { selected = it; stickerId = null },
                onSelectSticker = { stickerId = it },
                onStickerTime = { startMs, endMs, atEnd ->
                    val id = stickerId ?: return@Timeline
                    project = project.updateSticker(id) { it.copy(startMs = startMs, endMs = endMs) }
                    // Show the frame where the sticker appears (or, dragging its end, disappears).
                    project.stickers.firstOrNull { it.id == id }?.let { seekTo(if (atEnd) it.endMs - 1 else it.startMs) }
                },
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
            if (sticker != null) {
                val onKey = sticker.keyAt(positionMs) != null
                Text(when {
                    sticker.keys.isEmpty() -> "Drag to place, pinch to resize. Do it at another time to make it move."
                    onKey -> "On a key: drag or pinch to change it."
                    else -> "${sticker.keys.size} keys. Drag or pinch here to add one."
                }, color = TextMuted, fontSize = 12.sp, maxLines = 1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tool(Icons.Default.EmojiEmotions, "Sticker", !saving && project.stickers.size < VideoProject.MAX_STICKERS) { showStickerPicker = true }
                    Tool(Icons.Default.Diamond, if (onKey) "Remove key" else "Add key",
                        !saving && positionMs in sticker.startMs..sticker.endMs) {
                        project = project.updateSticker(sticker.id) { it.toggleKeyAt(positionMs) }
                    }
                    Tool(Icons.Default.Delete, "Delete", !saving) { project = project.deleteSticker(sticker.id); stickerId = null }
                    Tool(Icons.Default.Check, "Done", true) { stickerId = null }
                }
            } else if (clip != null) {
                Text("Clip ${selected + 1} of ${project.clips.size}: ${formatTime(clip.startMs)} – ${formatTime(clip.endMs)} of " +
                    "${clip.media.originalName}${if (clip.muted) " · muted" else ""}", color = TextMuted, fontSize = 12.sp, maxLines = 1)
            }
            if (sticker == null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                Tool(Icons.Default.EmojiEmotions, "Sticker", ready && clip != null && project.stickers.size < VideoProject.MAX_STICKERS) {
                    showStickerPicker = true
                }
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

    if (showStickerPicker) StickerPicker(app, onDismiss = { showStickerPicker = false }) { source ->
        showStickerPicker = false
        val before = project.stickers.size
        project = project.addSticker(source, positionMs)
        if (project.stickers.size > before) { player.pause(); stickerId = project.stickers.last().id }
    }

    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, containerColor = VaultSurface,
        title = { Text("Discard changes?", color = TextPrimary) },
        text = { Text("Your edit hasn't been saved. The original videos are not affected.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; close() }) { Text("Discard", color = VaultError) } },
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
 * Scrolling timeline with the playhead fixed in the centre: swipe to scrub, pinch to zoom, tap to select a clip or a
 * sticker, drag the selected one's edges to trim, drag a selected sticker's bar to move it. Stickers sit in lanes
 * under the clips. [onTouch] brackets every swipe, pinch or drag.
 */
@Composable
private fun Timeline(
    project: VideoProject, selected: Int, stickerId: String?, positionMs: Long, frames: Map<String, List<ImageBitmap>>,
    stickerImages: Map<String, ImageBitmap?>, enabled: Boolean,
    onSelect: (Int) -> Unit, onSelectSticker: (String?) -> Unit, onTouch: (Boolean) -> Unit, onScrub: (Long) -> Unit,
    onTrim: (start: Boolean, startMs: Long, endMs: Long) -> Unit,
    onStickerTime: (startMs: Long, endMs: Long, atEnd: Boolean) -> Unit
) {
    var dpPerSecond by remember { mutableFloatStateOf(60f) }
    val pxPerMs = with(LocalDensity.current) { dpPerSecond.dp.toPx() } / 1000f
    val mutePainter = rememberVectorPainter(Icons.AutoMirrored.Filled.VolumeOff)
    val lanes = remember(project.stickers) { stickerLanes(project.stickers) }
    val laneCount = (lanes.values.maxOrNull() ?: -1) + 1
    val state by rememberUpdatedState(TimelineState(project, selected, stickerId, positionMs, pxPerMs, lanes,
        onSelect, onSelectSticker, onTouch, onScrub, onTrim, onStickerTime))

    Canvas(Modifier.fillMaxWidth().height(VIDEO_ROW + if (laneCount > 0) LANE_GAP + LANE * laneCount else 0.dp).clipToBounds()
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                val begin = state
                if (begin.project.clips.isEmpty()) return@awaitEachGesture
                val centre = size.width / 2f
                fun screenX(outputMs: Long) = centre + (outputMs - begin.positionMs) * begin.pxPerMs
                val x = down.position.x
                val lane = if (down.position.y < VIDEO_ROW.toPx()) -1 else ((down.position.y - (VIDEO_ROW + LANE_GAP).toPx()) / LANE.toPx()).toInt()
                val reach = 24.dp.toPx()
                // Nearest edge within reach: -1 the start, 1 the end, 0 neither.
                fun edgeAt(left: Float, right: Float) = when {
                    abs(x - left) <= reach && abs(x - left) <= abs(x - right) -> -1
                    abs(x - right) <= reach -> 1
                    else -> 0
                }
                val sticker = begin.project.stickers.firstOrNull { it.id == begin.stickerId }
                val clip = begin.project.clips.getOrNull(begin.selected)
                val drag = when {
                    sticker != null && lane >= 0 && lane == begin.lanes[sticker.id] -> {
                        val left = screenX(sticker.startMs)
                        val right = screenX(sticker.endMs)
                        when (edgeAt(left, right)) {
                            -1 -> Drag.STICKER_START
                            1 -> Drag.STICKER_END
                            else -> if (x in left..right) Drag.STICKER_MOVE else Drag.SCRUB
                        }
                    }
                    sticker == null && clip != null && lane < 0 -> {
                        val left = screenX(begin.project.outputStartOf(begin.selected))
                        when (edgeAt(left, left + clip.durationMs * begin.pxPerMs)) {
                            -1 -> Drag.CLIP_START
                            1 -> Drag.CLIP_END
                            else -> Drag.SCRUB
                        }
                    }
                    else -> Drag.SCRUB
                }
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
                        val byMs = (dragged / begin.pxPerMs).toLong()
                        if (zooming) dpPerSecond = (dpPerSecond * zoom).coerceIn(10f, 400f)
                        else when (drag) {
                            Drag.SCRUB -> now.onScrub((begin.positionMs - byMs).coerceIn(0L, begin.project.durationMs))
                            Drag.CLIP_START -> now.onTrim(true, clip!!.startMs + byMs, clip.endMs)
                            Drag.CLIP_END -> now.onTrim(false, clip!!.startMs, clip.endMs + byMs)
                            Drag.STICKER_START -> now.onStickerTime(sticker!!.startMs + byMs, sticker.endMs, false)
                            Drag.STICKER_END -> now.onStickerTime(sticker!!.startMs, sticker.endMs + byMs, true)
                            Drag.STICKER_MOVE -> {
                                val length = sticker!!.endMs - sticker.startMs
                                val start = (sticker.startMs + byMs).coerceIn(0L, (begin.project.durationMs - length).coerceAtLeast(0L))
                                now.onStickerTime(start, start + length, false)
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (moving) state.onTouch(false)
                else {
                    val atMs = begin.positionMs + ((x - centre) / begin.pxPerMs).toLong()
                    if (lane < 0) begin.project.clipAt(atMs)?.let { (index, _) -> begin.onSelect(index) }
                    else begin.onSelectSticker(begin.project.stickers.firstOrNull { begin.lanes[it.id] == lane && atMs in it.startMs until it.endMs }?.id)
                }
            }
        }) {
        val centre = size.width / 2f
        val videoRow = VIDEO_ROW.toPx()
        val tile = videoRow
        val gap = 1.dp.toPx()
        val handle = 10.dp.toPx()
        fun screenX(outputMs: Long) = centre + (outputMs - positionMs) * pxPerMs
        fun handles(l: Float, r: Float, top: Float, height: Float, color: Color, grip: Color) {
            drawRect(color, Offset(l, top + gap), Size(r - l, height - 2 * gap), style = Stroke(2.dp.toPx()))
            for (hx in listOf(l, r - handle)) {
                drawRect(color, Offset(hx, top), Size(handle, height))
                drawLine(grip, Offset(hx + handle / 2, top + height * 0.3f), Offset(hx + handle / 2, top + height * 0.7f), 2.dp.toPx())
            }
        }

        var outputStart = 0L
        project.clips.forEachIndexed { index, c ->
            val left = screenX(outputStart)
            val right = left + c.durationMs * pxPerMs
            outputStart += c.durationMs
            if (right < 0f || left > size.width) return@forEachIndexed
            val l = left + gap
            val r = right - gap
            clipRect(l, 0f, r, videoRow) {
                drawRect(VaultSurface, Offset(l, 0f), Size(r - l, videoRow))
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
            if (index == selected && stickerId == null) handles(l, r, 0f, videoRow, VaultAccent, Color.White)
        }

        project.stickers.forEach { s ->
            val top = videoRow + (LANE_GAP + LANE * (lanes[s.id] ?: return@forEach)).toPx()
            val height = LANE.toPx() - 4.dp.toPx()
            val l = screenX(s.startMs)
            val r = screenX(s.endMs)
            if (r < 0f || l > size.width) return@forEach
            drawRoundRect(StickerColor, Offset(l, top), Size(r - l, height), CornerRadius(4.dp.toPx()))
            stickerImages[s.source.key]?.let { img ->
                // A small copy of the sticker at the visible start of its bar.
                val h = height - 4.dp.toPx()
                val w = h * img.width / img.height
                val x = max(l, 0f) + (if (s.id == stickerId) handle else 0f) + 4.dp.toPx()
                if (x + w < r) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset(x.roundToInt(), (top + 2.dp.toPx()).roundToInt()), IntSize(w.roundToInt(), h.roundToInt()))
            }
            if (s.id == stickerId) handles(l, r, top, height, Color.White, StickerColor)
            // A diamond per keyframe, on the bar's centre line.
            val d = 8.dp.toPx()
            s.keys.forEach { k ->
                val kx = screenX(s.startMs + k.atMs)
                rotate(45f, Offset(kx, top + height / 2)) {
                    drawRect(VaultDarkBg, Offset(kx - d / 2, top + height / 2 - d / 2), Size(d, d))
                    drawRect(Color.White, Offset(kx - d / 2, top + height / 2 - d / 2), Size(d, d), style = Stroke(1.dp.toPx()))
                }
            }
        }

        drawLine(Color.White, Offset(centre, 0f), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
    }
}

private enum class Drag { SCRUB, CLIP_START, CLIP_END, STICKER_START, STICKER_END, STICKER_MOVE }

private val VIDEO_ROW = 64.dp
private val LANE = 26.dp
private val LANE_GAP = 6.dp
private val StickerColor = Color(0xFFE0A030)

/** Puts each sticker in the first lane that's free when it starts, so overlapping stickers stack. */
private fun stickerLanes(stickers: List<Sticker>): Map<String, Int> {
    val laneEnds = mutableListOf<Long>()
    return stickers.sortedBy { it.startMs }.associate { s ->
        var lane = laneEnds.indexOfFirst { it <= s.startMs }
        if (lane < 0) { laneEnds += 0L; lane = laneEnds.lastIndex }
        laneEnds[lane] = s.endMs
        s.id to lane
    }
}

/** Everything the timeline's gesture handler reads, captured together so one gesture sees a consistent snapshot. */
private class TimelineState(
    val project: VideoProject, val selected: Int, val stickerId: String?, val positionMs: Long, val pxPerMs: Float,
    val lanes: Map<String, Int>,
    val onSelect: (Int) -> Unit, val onSelectSticker: (String?) -> Unit, val onTouch: (Boolean) -> Unit,
    val onScrub: (Long) -> Unit, val onTrim: (Boolean, Long, Long) -> Unit, val onStickerTime: (Long, Long, Boolean) -> Unit
)

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) TextPrimary else TextMuted
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp)) {
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

private val EMOJI = listOf(
    "😀", "😂", "🤣", "😍", "🥰", "😘", "😎", "🤩", "🥳", "😜", "🤔", "😴", "😭", "😡", "🤯", "😱",
    "👍", "👎", "👏", "🙌", "🙏", "💪", "✌️", "👋", "❤️", "💔", "🔥", "✨", "⭐", "🎉", "🎂", "🎁",
    "💯", "✅", "❌", "⚠️", "📍", "🎵", "📸", "🎬", "🌞", "🌙", "🌈", "⚡", "❄️", "🌸", "🐶", "🐱"
)

/** Emoji or a vault photo for a new sticker; photo PNGs keep their transparency. */
@Composable
private fun StickerPicker(app: SecretVaultApp, onDismiss: () -> Unit, onPick: (StickerSource) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val media by remember { app.mediaRepository.getMedia() }.collectAsState(initial = null)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(VaultDarkBg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = TextPrimary) }
                Text("Add sticker", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            TabRow(selectedTabIndex = tab, containerColor = VaultDarkBg, contentColor = VaultAccent) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Emoji") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Vault photo") })
            }
            val photos = media?.filter { it.mediaType == MediaType.PHOTO }
            when {
                tab == 0 -> LazyVerticalGrid(GridCells.Adaptive(56.dp), contentPadding = PaddingValues(8.dp)) {
                    items(EMOJI) { emoji ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onPick(StickerSource.Emoji(emoji)) },
                            contentAlignment = Alignment.Center) { Text(emoji, fontSize = 32.sp) }
                    }
                }
                photos == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VaultAccent) }
                photos.isEmpty() -> Text("No photos in the vault yet.", color = TextSecondary, modifier = Modifier.padding(16.dp))
                else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(photos, key = { it.id }) { photo ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(VaultSurface)
                            .clickable { onPick(StickerSource.Photo(photo)) }) {
                            photo.thumbnailPath?.let { StableEncryptedThumbnail(photo.id, it, photo.originalName, Modifier.fillMaxSize()) }
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
