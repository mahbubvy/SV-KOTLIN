package com.secretvault.app.ui.editor

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.secretvault.app.core.image.StickerImage
import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.core.processing.Adjustments
import com.secretvault.app.core.processing.BlurStyle
import com.secretvault.app.core.processing.Clip
import com.secretvault.app.core.processing.blurUniforms
import com.secretvault.app.core.processing.Framing
import com.secretvault.app.core.processing.FoundFace
import com.secretvault.app.core.processing.scanFaces
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.StickerSource
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.processing.snapAngle
import com.secretvault.app.core.worker.VideoEditState
import com.secretvault.app.ui.gallery.components.StableEncryptedThumbnail
import com.secretvault.app.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    var panel by remember { mutableStateOf<Panel?>(null) }
    // Face scan: progress while it runs, then the faces found on that clip (by id) to choose from.
    var scanProgress by remember { mutableStateOf<Float?>(null) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var found by remember { mutableStateOf<Pair<String, List<FoundFace>>?>(null) }
    val latestProject by rememberUpdatedState(project)
    val clip = project.clips.getOrNull(selected)
    // The selected sticker or blur region (stickerId covers both), in output time; blurLook is set for a blur region.
    val sticker = project.layers.firstOrNull { it.id == stickerId }
    val blurLook = sticker?.source as? StickerSource.Blur
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
    // Keyed on what the player plays, so muting or framing a clip (handled on top of the player) does not reload it.
    LaunchedEffect(project.clips.map { listOf(it.id, it.startMs, it.endMs) }) {
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

    // Sticker pictures, once per emoji, photo or pack sticker; null when a photo can't be decoded.
    // ponytail: two stickers of the same GIF share one picture, so they animate in step in the preview (not the export).
    val stickerImages = remember { mutableStateMapOf<String, StickerImage?>() }
    LaunchedEffect(project.stickers.mapTo(HashSet()) { it.source.key }) {
        latestProject.stickers.map { it.source }.distinctBy { it.key }.filter { it.key !in stickerImages }.forEach { source ->
            stickerImages[source.key] = withContext(Dispatchers.IO) { StickerImage.load(app, app.cryptoEngine, source) }
        }
    }

    // The preview frame takes the first clip's shape, like the export, so stickers land where they'll be saved.
    val frameSize by produceState<Pair<Int, Int>?>(null, project.clips.firstOrNull()?.media?.id) {
        value = latestProject.clips.firstOrNull()?.let { withContext(Dispatchers.IO) { app.videoEditManager.uprightSize(it.media) } }
    }

    // Each source's upright size, to know where its picture sits in the frame (blur regions are drawn on it).
    val clipSizes = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    LaunchedEffect(project.clips.mapTo(HashSet()) { it.media.id }) {
        latestProject.clips.distinctBy { it.media.id }.filter { it.media.id !in clipSizes }.forEach { c ->
            clipSizes[c.media.id] = withContext(Dispatchers.IO) { app.videoEditManager.uprightSize(c.media) }
        }
    }
    val blurPreview = remember { if (Build.VERSION.SDK_INT >= 33) BlurPreview() else null }

    // The clip on screen's colour, drawn over the player as a shader built from the same table the export uses.
    // Android 13+ only (RuntimeShader); older phones still get it in the saved video.
    val onScreenLook = project.clipAt(positionMs)?.let { (index, _) -> project.clips[index].adjustments } ?: Adjustments()
    val look by produceState<android.graphics.RenderEffect?>(null, onScreenLook) {
        value = if (onScreenLook.isNone || Build.VERSION.SDK_INT < 33) null else withContext(Dispatchers.Default) { lookEffect(onScreenLook) }
    }

    fun openPanel(which: Panel) {
        panel = which
        player.pause()
        if (project.clipAt(positionMs)?.first != selected) seekTo(project.outputStartOf(selected))
    }

    /** Zooms the selected clip until it covers the whole frame, cropping what spills over. */
    fun fillFrame() {
        val index = selected
        val c = project.clips.getOrNull(index) ?: return
        val (frameW, frameH) = frameSize ?: return
        scope.launch {
            val (clipW, clipH) = withContext(Dispatchers.IO) { app.videoEditManager.uprightSize(c.media) }
            // Fitted (zoom 1) the clip touches two sides of the frame; a quarter turn then swaps its sides.
            val fit = min(frameW.toFloat() / clipW, frameH.toFloat() / clipH)
            val turned = (c.framing.angle / 90f).roundToInt() % 2 != 0
            val w = (if (turned) clipH else clipW) * fit
            val h = (if (turned) clipW else clipH) * fit
            project = project.frame(index) { it.copy(zoom = max(frameW / w, frameH / h), offsetX = 0f, offsetY = 0f) }
        }
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
            val frameWPx = with(LocalDensity.current) { frameW.toPx() }
            val frameHPx = with(LocalDensity.current) { frameH.toPx() }
            val shown = project.shownAt(positionMs, clipSizes, frameWPx, frameHPx)
            Box(Modifier.size(frameW, frameH).clipToBounds()
                // Tap: pick the sticker or blur region under the finger, or let go of the selected one, or play/pause.
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
                        } ?: project.shownAt(positionMs, clipSizes, size.width.toFloat(), size.height.toFloat())?.let { on ->
                            val point = toPicture(tap, size.width.toFloat(), size.height.toFloat(), on)
                            project.layers.lastOrNull { b ->
                                (positionMs in b.startMs until b.endMs || b.id == stickerId) && project.regionClipOf(b.id) == on.index && b.covers(point, on, positionMs)
                            }
                        }
                        when {
                            hit != null -> stickerId = hit.id
                            stickerId != null -> stickerId = null
                            else -> togglePlay()
                        }
                    }
                }
                // Selected sticker or blur region: drag moves, pinch resizes, twist turns it (with keyframes, at the
                // playhead's moment). Otherwise the same gestures frame the clip on screen, which becomes the selected clip.
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        var dx = pan.x / size.width
                        var dy = pan.y / size.height
                        player.pause()
                        val id = stickerId
                        val blurClip = id?.let(project::regionClipOf) ?: -1
                        if (blurClip >= 0) {
                            // A blur region moves on its clip's picture: undo the clip's turn and zoom.
                            val on = project.shownAt(positionMs, clipSizes, size.width.toFloat(), size.height.toFloat())
                            if (on == null || on.index != blurClip) return@detectTransformGestures
                            val d = (pan / on.clip.framing.zoom).turned(-on.clip.framing.angle)
                            dx = d.x / on.picture.width
                            dy = d.y / on.picture.height
                        }
                        if (id != null) project = project.updateLayer(id) { s ->
                            s.placeAt(positionMs) {
                                it.copy(centerX = it.centerX + dx, centerY = it.centerY + dy, widthFraction = it.widthFraction * zoom,
                                    rotation = it.rotation + rotation)
                            }
                        } else project.clipAt(positionMs)?.let { (index, _) ->
                            selected = index
                            project = project.frame(index) {
                                it.copy(rotation = it.rotation + rotation, zoom = it.zoom * zoom, offsetX = it.offsetX + dx, offsetY = it.offsetY + dy)
                            }
                        }
                    }
                }) {
                val framing = shown?.clip?.framing ?: Framing()
                // Same turn, zoom and move as the export's FramingTransformation.
                fun GraphicsLayerScope.frameLike() {
                    rotationZ = framing.angle
                    scaleX = framing.zoom
                    scaleY = framing.zoom
                    translationX = framing.offsetX * size.width
                    translationY = framing.offsetY * size.height
                }
                // Blur regions (on the clip's own picture) then colour, as in the export. Android 13+, like the colour.
                val blur = if (shown != null) blurPreview?.effect(blurUniforms(shown.clip.regions, shown.sourceMs,
                    shown.picture.left, shown.picture.top, shown.picture.width, shown.picture.height)) else null
                val colour = look
                val effect = if (Build.VERSION.SDK_INT >= 33 && colour != null && blur != null) android.graphics.RenderEffect.createChainEffect(colour, blur)
                    else colour ?: blur
                AndroidView(factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.streaming_player_view, null) as PlayerView).apply {
                        this.player = player; useController = false
                    }
                }, modifier = Modifier.fillMaxSize().graphicsLayer {
                    frameLike()
                    renderEffect = effect?.asComposeRenderEffect()
                })
                project.stickers.forEach { s ->
                    val img = stickerImages[s.source.key] ?: return@forEach
                    if (positionMs !in s.startMs until s.endMs && s.id != stickerId) return@forEach
                    val p = s.placementAt(positionMs)
                    val w = frameW * p.widthFraction
                    val h = w * img.height / img.width
                    Image(img.frameAt(positionMs - s.startMs).asImageBitmap(), null, Modifier.offset(frameW * p.centerX - w / 2, frameH * p.centerY - h / 2).size(w, h)
                        .graphicsLayer { rotationZ = p.angle })
                }
                fun duplicate(id: String) {
                    val before = project.layers.mapTo(HashSet()) { it.id }
                    project = project.duplicateLayer(id)
                    project.layers.firstOrNull { it.id !in before }?.let { stickerId = it.id }
                }
                @Composable fun handles(selection: Sticker, aspect: Float, free: Boolean, inside: Rect? = null) =
                    StickerHandles(selection.placementAt(positionMs), aspect, canDuplicate = project.canDuplicate(selection.id),
                        onStart = { player.pause() },
                        onChange = { change -> project = project.updateLayer(selection.id) { it.placeAt(positionMs, change) } },
                        onDuplicate = { duplicate(selection.id) },
                        onDelete = { project = project.deleteLayer(selection.id); stickerId = null },
                        free = free, inside = inside)
                val img = sticker?.let { stickerImages[it.source.key] }
                if (sticker != null && img != null && !saving) handles(sticker, img.height.toFloat() / img.width, free = false)
                // A blur region's box sits on its clip's picture, so it turns and zooms with the clip.
                if (sticker != null && blurLook != null && shown != null && project.regionClipOf(sticker.id) == shown.index && !saving) {
                    Box(Modifier.fillMaxSize().graphicsLayer { frameLike() }) { handles(sticker, 1f, free = true, inside = shown.picture) }
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
                onSelectSticker = { stickerId = it },
                // With a panel open, show the clip it now edits.
                onSelect = { selected = it; stickerId = null; if (panel != null) seekTo(project.outputStartOf(it)) },
                onStickerTime = { startMs, endMs, atEnd ->
                    val id = stickerId ?: return@Timeline
                    project = project.updateLayer(id) { it.copy(startMs = startMs, endMs = endMs) }
                    // Show the frame where it appears (or, dragging its end, disappears).
                    project.layers.firstOrNull { it.id == id }?.let { seekTo(if (atEnd) it.endMs - 1 else it.startMs) }
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
                fun setLook(look: StickerSource.Blur) { project = project.updateLayer(sticker.id) { it.copy(source = look) } }
                if (blurLook != null) LabeledSlider("Strength", "${(blurLook.strength * 100).roundToInt()}%", blurLook.strength, 0f..1f, !saving) {
                    setLook(blurLook.copy(strength = it))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (blurLook == null) Tool(Icons.Default.EmojiEmotions, "Sticker", !saving && project.stickers.size < VideoProject.MAX_STICKERS) {
                        showStickerPicker = true
                    } else {
                        val pixelate = blurLook.style == BlurStyle.PIXELATE
                        Tool(if (pixelate) Icons.Default.GridOn else Icons.Default.BlurOn, if (pixelate) "Pixelate" else "Blur", !saving) {
                            setLook(blurLook.copy(style = if (pixelate) BlurStyle.BLUR else BlurStyle.PIXELATE))
                        }
                        Tool(if (blurLook.oval) Icons.Outlined.Circle else Icons.Outlined.CropSquare, if (blurLook.oval) "Oval" else "Rectangle", !saving) {
                            setLook(blurLook.copy(oval = !blurLook.oval))
                        }
                    }
                    Tool(Icons.Default.Diamond, if (onKey) "Remove key" else "Add key",
                        !saving && positionMs in sticker.startMs..sticker.endMs) {
                        project = project.updateLayer(sticker.id) { it.toggleKeyAt(positionMs) }
                    }
                    Tool(Icons.Default.Delete, "Delete", !saving) { project = project.deleteLayer(sticker.id); stickerId = null }
                    Tool(Icons.Default.Check, "Done", true) { stickerId = null }
                }
            } else if (clip != null && panel == Panel.SIZE) {
                SizePanel(clip.framing, enabled = !saving, onChange = { f -> project = project.frame(selected) { f } },
                    onFill = ::fillFrame, onDone = { panel = null })
            } else if (clip != null && panel == Panel.ADJUST) {
                AdjustPanel(clip.adjustments, enabled = !saving, onChange = { a -> project = project.adjust(selected) { a } },
                    onApplyToAll = {
                        project = project.adjustAllLike(selected)
                        Toast.makeText(context, "Applied to all clips", Toast.LENGTH_SHORT).show()
                    },
                    onDone = { panel = null })
            } else if (clip != null) {
                Text("Clip ${selected + 1} of ${project.clips.size}: ${formatTime(clip.startMs)} – ${formatTime(clip.endMs)} of " +
                    "${clip.media.originalName}${if (clip.muted) " · muted" else ""}", color = TextMuted, fontSize = 12.sp, maxLines = 1)
            }
            if (sticker == null && panel == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Tool(Icons.Default.ZoomIn, "Size", ready && clip != null) { openPanel(Panel.SIZE) }
                Tool(Icons.Default.Tune, "Adjust", ready && clip != null) { openPanel(Panel.ADJUST) }
                Tool(Icons.Default.EmojiEmotions, "Sticker", ready && clip != null && project.stickers.size < VideoProject.MAX_STICKERS) {
                    showStickerPicker = true
                }
                // On the clip at the playhead, from there to the clip's end.
                val onScreen = project.clipAt(positionMs)?.first
                Tool(Icons.Default.BlurOn, "Blur", ready && onScreen != null && project.clips[onScreen].regions.size < VideoProject.MAX_REGIONS) {
                    player.pause()
                    project = project.addBlur(positionMs)
                    stickerId = onScreen?.let { project.clips[it].regions.lastOrNull()?.id }
                }
                // Scans the clip at the playhead (its trimmed part) and offers a blur region per face found.
                Tool(Icons.Default.Face, "Faces", ready && onScreen != null && scanProgress == null) {
                    val target = project.clips[onScreen ?: return@Tool]
                    player.pause()
                    scanProgress = 0f
                    scanJob = scope.launch {
                        try {
                            val result = scanFaces(app.cryptoEngine, target) { p -> scope.launch { scanProgress = p } }
                            when {
                                result.faces.isNotEmpty() -> found = target.id to result.faces
                                result.alreadyCovered > 0 -> Toast.makeText(context, "Every face found already has a region", Toast.LENGTH_SHORT).show()
                                else -> Toast.makeText(context, "No faces found in this clip", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, "Couldn't scan this clip for faces", Toast.LENGTH_SHORT).show()
                        } finally {
                            scanProgress = null
                        }
                    }
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

    scanProgress?.let { progress ->
        AlertDialog(onDismissRequest = {}, containerColor = VaultSurface,
            title = { Text("Finding faces", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { progress }, color = VaultAccent, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).roundToInt()}%", color = TextSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { scanJob?.cancel() }) { Text("Cancel", color = VaultAccent) } })
    }

    found?.let { (clipId, faces) ->
        FoundFacesDialog(faces, onDismiss = { found = null }) { chosen ->
            found = null
            val index = project.clips.indexOfFirst { it.id == clipId }
            val room = VideoProject.MAX_REGIONS - (project.clips.getOrNull(index)?.regions?.size ?: return@FoundFacesDialog)
            project = project.addRegions(index, chosen.map { it.blur })
            if (chosen.size > room) Toast.makeText(context, "Only ${VideoProject.MAX_REGIONS} blur regions fit on one clip", Toast.LENGTH_SHORT).show()
        }
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
    stickerImages: Map<String, StickerImage?>, enabled: Boolean,
    onSelect: (Int) -> Unit, onSelectSticker: (String?) -> Unit, onTouch: (Boolean) -> Unit, onScrub: (Long) -> Unit,
    onTrim: (start: Boolean, startMs: Long, endMs: Long) -> Unit,
    onStickerTime: (startMs: Long, endMs: Long, atEnd: Boolean) -> Unit
) {
    var dpPerSecond by remember { mutableFloatStateOf(60f) }
    val pxPerMs = with(LocalDensity.current) { dpPerSecond.dp.toPx() } / 1000f
    val mutePainter = rememberVectorPainter(Icons.AutoMirrored.Filled.VolumeOff)
    val layers = remember(project) { project.layers }
    // Where each blur region's clip plays in the output: its bar is only shown, and stacked, there.
    val windows = remember(project) {
        project.clips.flatMapIndexed { i, c -> val s = project.outputStartOf(i); c.regions.map { it.id to (s to s + c.durationMs) } }.toMap()
    }
    fun shownPart(s: Sticker) = windows[s.id]?.let { (a, b) -> s.copy(startMs = max(s.startMs, a), endMs = min(s.endMs, b)) } ?: s
    val lanes = remember(layers) { stickerLanes(layers.map(::shownPart)) }
    val blurPainter = rememberVectorPainter(Icons.Default.BlurOn)
    val laneCount = (lanes.values.maxOrNull() ?: -1) + 1
    val state by rememberUpdatedState(TimelineState(project, selected, stickerId, positionMs, pxPerMs, layers.map(::shownPart), lanes,
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
                val sticker = begin.layers.firstOrNull { it.id == begin.stickerId }
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
                    else begin.onSelectSticker(begin.layers.firstOrNull { begin.lanes[it.id] == lane && atMs in it.startMs until it.endMs }?.id)
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

        layers.forEach { s ->
            val top = videoRow + (LANE_GAP + LANE * (lanes[s.id] ?: return@forEach)).toPx()
            val height = LANE.toPx() - 4.dp.toPx()
            val shown = shownPart(s)
            val l = screenX(shown.startMs)
            val r = screenX(shown.endMs)
            if (r <= l || r < 0f || l > size.width) return@forEach
            val blur = s.source is StickerSource.Blur
            val color = if (blur) BlurColor else StickerColor
            drawRoundRect(color, Offset(l, top), Size(r - l, height), CornerRadius(4.dp.toPx()))
            // A small copy of the sticker (or a blur mark) at the visible start of its bar.
            val h = height - 4.dp.toPx()
            val x = max(l, 0f) + (if (s.id == stickerId) handle else 0f) + 4.dp.toPx()
            if (blur) {
                if (x + h < r) translate(x, top + 2.dp.toPx()) { with(blurPainter) { draw(Size(h, h), colorFilter = ColorFilter.tint(Color.White)) } }
            } else stickerImages[s.source.key]?.poster?.asImageBitmap()?.let { img ->
                val w = h * img.width / img.height
                if (x + w < r) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                    IntOffset(x.roundToInt(), (top + 2.dp.toPx()).roundToInt()), IntSize(w.roundToInt(), h.roundToInt()))
            }
            if (s.id == stickerId) handles(l, r, top, height, Color.White, color)
            // A diamond per keyframe, on the bar's centre line.
            val d = 8.dp.toPx()
            s.keys.forEach { k ->
                val kx = screenX(s.startMs + k.atMs)
                if (kx < l || kx > r) return@forEach
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
private val BlurColor = Color(0xFF4A7BD0)

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
    val layers: List<Sticker>, val lanes: Map<String, Int>,
    val onSelect: (Int) -> Unit, val onSelectSticker: (String?) -> Unit, val onTouch: (Boolean) -> Unit,
    val onScrub: (Long) -> Unit, val onTrim: (Boolean, Long, Long) -> Unit, val onStickerTime: (Long, Long, Boolean) -> Unit
)

@Composable
private fun Tool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) TextPrimary else TextMuted
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 60.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = 12.sp, maxLines = 1)
    }
}

private enum class Panel { SIZE, ADJUST }

/** The selected clip's zoom and turn as controls: the same thing two fingers do on the video. */
@Composable
private fun SizePanel(framing: Framing, enabled: Boolean, onChange: (Framing) -> Unit, onFill: () -> Unit, onDone: () -> Unit) {
    Column {
        LabeledSlider("Zoom", "${(framing.zoom * 100).roundToInt()}%", framing.zoom, 0.25f..5f, enabled) { onChange(framing.copy(zoom = it)) }
        // A twist can wind the stored angle past a full turn; the slider shows it within one.
        val turn = ((framing.rotation + 180f) % 360f + 360f) % 360f - 180f
        LabeledSlider("Rotate", "${framing.angle.roundToInt()}°", turn, -180f..180f, enabled) { onChange(framing.copy(rotation = it)) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            PanelButton("Fit", enabled) { onChange(framing.copy(zoom = 1f, offsetX = 0f, offsetY = 0f)) }
            PanelButton("Fill", enabled, onFill)
            PanelButton("Rotate 90°", enabled) {
                val next = snapAngle(framing.angle) + 90f
                onChange(framing.copy(rotation = if (next > 180f) next - 360f else next))
            }
            PanelButton("Reset", enabled) { onChange(Framing()) }
            Spacer(Modifier.weight(1f))
            PanelButton("Done", true, onDone)
        }
    }
}

/** Colour adjustments for the selected clip: pick one with a chip, set it with the slider. A dot marks the ones in use. */
@Composable
private fun AdjustPanel(adjustments: Adjustments, enabled: Boolean, onChange: (Adjustments) -> Unit, onApplyToAll: () -> Unit, onDone: () -> Unit) {
    var current by remember { mutableStateOf(Adjustment.BRIGHTNESS) }
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Adjustment.entries.forEach { a ->
                FilterChip(selected = a == current, onClick = { current = a },
                    label = { Text(if (adjustments[a] != 0) "${a.label} •" else a.label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary, selectedContainerColor = VaultAccent,
                        selectedLabelColor = VaultDarkBg))
            }
        }
        val value = adjustments[current]
        LabeledSlider(current.label, if (value > 0) "+$value" else "$value", value.toFloat(), -100f..100f, enabled) {
            onChange(adjustments.with(current, it.roundToInt()))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            PanelButton("Reset", enabled && value != 0) { onChange(adjustments.with(current, 0)) }
            PanelButton("Reset all", enabled && !adjustments.isNone) { onChange(Adjustments()) }
            PanelButton("Apply to all clips", enabled, onApplyToAll)
            Spacer(Modifier.weight(1f))
            PanelButton("Done", true, onDone)
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Slider(current, onChange, Modifier.weight(1f), enabled, range,
            colors = SliderDefaults.colors(thumbColor = VaultAccent, activeTrackColor = VaultAccent, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        Text(value, color = TextPrimary, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun PanelButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text(label, color = if (enabled) VaultAccent else TextMuted, fontSize = 13.sp)
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

/** Emoji, a bundled sticker or a vault photo for a new sticker; PNGs and WebPs keep their transparency. */
@Composable
private fun StickerPicker(app: SecretVaultApp, onDismiss: () -> Unit, onPick: (StickerSource) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val media by remember { app.mediaRepository.getMedia() }.collectAsState(initial = null)
    val pack = remember { app.assets.list("stickers").orEmpty().sorted().map { "stickers/$it" } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(VaultDarkBg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = TextPrimary) }
                Text("Add sticker", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            TabRow(selectedTabIndex = tab, containerColor = VaultDarkBg, contentColor = VaultAccent) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Emoji") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Stickers") })
                Tab(tab == 2, onClick = { tab = 2 }, text = { Text("Vault photo") })
            }
            val photos = media?.filter { it.mediaType == MediaType.PHOTO }
            when {
                tab == 0 -> LazyVerticalGrid(GridCells.Adaptive(56.dp), contentPadding = PaddingValues(8.dp)) {
                    items(EMOJI) { emoji ->
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onPick(StickerSource.Emoji(emoji)) },
                            contentAlignment = Alignment.Center) { Text(emoji, fontSize = 32.sp) }
                    }
                }
                // Every image in assets/stickers, in file-name order.
                tab == 1 -> LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pack) { asset ->
                        AsyncImage("file:///android_asset/$asset", null,
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onPick(StickerSource.Pack(asset)) }.padding(4.dp))
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

/** The clip at the playhead as the preview shows it: its source time, and where its upright picture sits in the frame. */
private class Shown(val index: Int, val clip: Clip, val sourceMs: Long, val picture: Rect)

/** In a [frameW]×[frameH] pixel frame, before the clip's framing; null until the clip's size is known. */
private fun VideoProject.shownAt(outputMs: Long, sizes: Map<String, Pair<Int, Int>>, frameW: Float, frameH: Float): Shown? {
    val (index, offset) = clipAt(outputMs) ?: return null
    val clip = clips[index]
    val (w, h) = sizes[clip.media.id] ?: return null
    val scale = min(frameW / w, frameH / h)
    return Shown(index, clip, clip.startMs + offset,
        Rect(Offset((frameW - w * scale) / 2, (frameH - h * scale) / 2), Size(w * scale, h * scale)))
}

private fun Offset.turned(degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    return Offset((x * cos(a) - y * sin(a)).toFloat(), (x * sin(a) + y * cos(a)).toFloat())
}

/** From a point on the frame to fractions of [shown]'s picture, undoing the clip's framing (turn and zoom about the centre, then move). */
private fun toPicture(point: Offset, frameW: Float, frameH: Float, shown: Shown): Offset {
    val f = shown.clip.framing
    val centre = Offset(frameW / 2, frameH / 2)
    val v = centre + ((point - centre - Offset(f.offsetX * frameW, f.offsetY * frameH)) / f.zoom).turned(-f.angle)
    return Offset((v.x - shown.picture.left) / shown.picture.width, (v.y - shown.picture.top) / shown.picture.height)
}

/** Whether this blur region (in output time) covers [point], in fractions of [shown]'s picture, at [outputMs]. */
private fun Sticker.covers(point: Offset, shown: Shown, outputMs: Long): Boolean {
    val look = source as? StickerSource.Blur ?: return false
    val p = placementAt(outputMs)
    val d = Offset((point.x - p.centerX) * shown.picture.width, (point.y - p.centerY) * shown.picture.height).turned(-p.angle)
    val halfW = p.widthFraction * shown.picture.width / 2
    val halfH = halfW * p.stretch
    return if (look.oval) (d.x / halfW).let { it * it } + (d.y / halfH).let { it * it } <= 1f else abs(d.x) <= halfW && abs(d.y) <= halfH
}

/** The faces a scan found, all ticked: untick any that should stay visible, then blur the rest. */
@Composable
private fun FoundFacesDialog(faces: List<FoundFace>, onDismiss: () -> Unit, onBlur: (List<FoundFace>) -> Unit) {
    var keep by remember(faces) { mutableStateOf(emptySet<FoundFace>()) }
    val chosen = faces.filter { it !in keep }
    AlertDialog(onDismissRequest = onDismiss, containerColor = VaultSurface,
        title = { Text(if (faces.size == 1) "1 face found" else "${faces.size} faces found", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Untick any face that should stay visible.", color = TextSecondary, fontSize = 13.sp)
                LazyVerticalGrid(GridCells.Adaptive(88.dp), Modifier.heightIn(max = 320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(faces) { face ->
                        val ticked = face !in keep
                        Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(VaultDarkBg)
                            .clickable { keep = if (ticked) keep + face else keep - face }) {
                            face.preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            Checkbox(ticked, onCheckedChange = null, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                colors = CheckboxDefaults.colors(checkedColor = VaultAccent, uncheckedColor = Color.White))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onBlur(chosen) }, enabled = chosen.isNotEmpty()) {
                Text(if (chosen.size == 1) "Blur 1 face" else "Blur ${chosen.size} faces", color = if (chosen.isNotEmpty()) VaultAccent else TextMuted)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } })
}
