package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.os.Build
import android.graphics.Matrix
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.image.decodeEncryptedImage
import com.secretvault.app.core.image.drawPaintStrokes
import com.secretvault.app.core.image.frameSize
import com.secretvault.app.core.image.photoMatrix
import com.secretvault.app.core.image.renderPhoto
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.core.processing.Adjustments
import com.secretvault.app.core.processing.BrushStroke
import com.secretvault.app.core.processing.CropRect
import com.secretvault.app.core.processing.PhotoEdit
import com.secretvault.app.core.processing.PhotoFilter
import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The preview works on a copy this big (long side); the save uses the full photo. */
private const val PREVIEW_SIDE = 1600
/** Smaller still while a slider or handle is moving, so the preview keeps up. */
private const val QUICK_SIDE = 900
/** Bigger photos are saved scaled down to this long side, to stay within memory. */
private const val SAVE_SIDE = 6000
/** The GPU preview's colour table: smaller than the save's, so it's quick to rebuild as a slider moves. */
private const val GPU_TABLE = 17

private enum class PhotoTab(val label: String) { ADJUST("Adjust"), CROP("Crop"), FILTERS("Filters"), DRAW("Draw") }

/**
 * Edits a vault photo: adjustments and grain, crop and straighten, filters and drawing, then saves a new copy beside
 * it. The photo is decrypted in memory only.
 */
@Composable
fun PhotoEditorScreen(app: SecretVaultApp, item: MediaItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A draft means the vault locked mid-edit: carry on where it stopped.
    var edit by remember { mutableStateOf(app.videoEditManager.photoDraft?.takeIf { it.first == item.id }?.second ?: PhotoEdit()) }
    var leaving by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(PhotoTab.ADJUST) }
    // A slider or handle is moving: the preview renders smaller to keep up.
    var moving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // The crop shape picked (an index into CROP_SHAPES); a fixed shape keeps the crop box that shape while dragging.
    var cropShape by remember { mutableIntStateOf(0) }
    // Brush settings, kept while switching tabs.
    var brush by remember { mutableStateOf(BrushSettings()) }
    // The Size or Softness slider is moving: the brush circle shows.
    var sizing by remember { mutableStateOf(false) }

    // A smaller copy for the preview; null while loading, and a failed decode shows an error.
    var failed by remember { mutableStateOf(false) }
    val source by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { decodeEncryptedImage(app.cryptoEngine, File(item.encryptedPath), PREVIEW_SIDE) }.getOrNull() }
        if (value == null) failed = true
    }
    // On Android 13+ colour, filters and grain are a GPU shader over the preview and painted strokes are drawn on top,
    // so those change instantly; the CPU only redraws the shape (turn, crop, straighten) and blur strokes.
    val photoLook = remember { if (Build.VERSION.SDK_INT >= 33) PhotoLook() else null }
    val gpu = photoLook != null

    // The edited preview, redrawn whenever what it shows changes; a newer edit stops an older render part-way.
    var preview by remember { mutableStateOf<Rendered?>(null) }
    LaunchedEffect(source) {
        val src = source ?: return@LaunchedEffect
        snapshotFlow {
            val shape = if (gpu) edit.copy(filter = null, filterStrength = 1f, adjustments = Adjustments(), grain = 0,
                strokes = edit.strokes.filter { it.blur }) else edit
            Triple(shape, tab == PhotoTab.CROP, moving)
        }.collectLatest { (e, wholeFrame, quick) ->
            withContext(Dispatchers.Default) {
                renderPhoto(src, e, cropped = !wholeFrame, maxSide = if (quick) QUICK_SIDE else PREVIEW_SIDE, colour = !gpu) { isActive }
            }?.let { preview = Rendered(it, e, wholeFrame) }
        }
    }

    // Kept up to date so a lock (which drops this screen without warning) loses nothing; cleared only on purpose.
    SideEffect { if (!leaving) app.videoEditManager.photoDraft = if (edit == PhotoEdit()) null else item.id to edit }
    fun close() { leaving = true; app.videoEditManager.photoDraft = null; onBack() }

    fun leave() { if (edit == PhotoEdit()) close() else confirmDiscard = true }
    BackHandler(enabled = !saving) { leave() }

    fun save() {
        saving = true
        scope.launch {
            // Finishes even if the screen goes away mid-save, so a copy is never half made.
            val saved = withContext(NonCancellable + Dispatchers.Default) {
                runCatching {
                    val full = decodeEncryptedImage(app.cryptoEngine, File(item.encryptedPath), SAVE_SIDE) ?: return@runCatching null
                    val edited = renderPhoto(full, edit).also { full.recycle() } ?: return@runCatching null
                    val names = app.mediaRepository.getMedia().first().mapTo(HashSet()) { it.filename }
                    app.mediaSaveQueue.savePhoto(edited, item.albumId, VideoEditPlan.editedFileName(item.filename, names))
                        // Saved: nothing left to come back to, even if a lock dropped this screen meanwhile.
                        .also { app.videoEditManager.photoDraft = null }
                }.getOrNull()
            }
            saving = false
            if (saved != null) {
                Toast.makeText(context, "Saved as a new photo", Toast.LENGTH_SHORT).show()
                close()
            } else Toast.makeText(context, "Couldn't save the edited photo", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::leave, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
            Text("Edit photo", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val canSave = source != null && !saving && edit != PhotoEdit()
            TextButton(onClick = ::save, enabled = canSave) {
                Text("Save", color = if (canSave) VaultAccent else TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val shown = preview
            val src = source
            val cropRatio = src?.let { s -> frameSize(s.width, s.height, edit).let { (w, h) -> shapeRatio(cropShape, w, h) } }
            when {
                failed -> Text("This photo couldn't be opened.", color = TextSecondary)
                shown == null || src == null -> CircularProgressIndicator(color = VaultAccent)
                else -> {
                    // Where the picture sits in this box, and where the photo's pixels land in it, for what's drawn over it.
                    val boxW = constraints.maxWidth.toFloat()
                    val boxH = constraints.maxHeight.toFloat()
                    val fit = min(boxW / shown.bitmap.width, boxH / shown.bitmap.height)
                    val picture = Rect(Offset((boxW - shown.bitmap.width * fit) / 2, (boxH - shown.bitmap.height * fit) / 2),
                        Size(shown.bitmap.width * fit, shown.bitmap.height * fit))
                    val toScreen = remember(shown, picture, src) { previewToScreen(shown, picture, src.width, src.height) }

                    // Colour and grain on the GPU. A grain is as big on screen as it'll be in the saved photo.
                    val savedLong = max(max(item.width, item.height).takeIf { it > 0 } ?: max(src.width, src.height), 1).coerceAtMost(SAVE_SIDE)
                    val grainCell = 2.5f * toScreen.mapRadius(1f) * max(src.width, src.height) / savedLong
                    val colourKey = Triple(edit.filter to edit.filterStrength, edit.adjustments, edit.grain)
                    val look by produceState<android.graphics.RenderEffect?>(null, colourKey, (grainCell * 10).roundToInt()) {
                        value = photoLook?.let { gpuLook ->
                            withContext(Dispatchers.Default) {
                                gpuLook.effect(if (edit.changesColour) edit.colourTable(GPU_TABLE) else null, GPU_TABLE, edit.grain, grainCell)
                            }
                        }
                    }
                    Image(shown.bitmap.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { renderEffect = look?.asComposeRenderEffect() },
                        contentScale = ContentScale.Fit)
                    // Painted strokes over the GPU preview, untouched by its colour (the CPU draws them otherwise).
                    if (gpu && edit.strokes.any { !it.blur }) Canvas(Modifier.fillMaxSize()) {
                        drawIntoCanvas { drawPaintStrokes(it.nativeCanvas, toScreen, src.width, src.height, edit.strokes) }
                    }

                    if (tab == PhotoTab.CROP && shown.wholeFrame && !saving) {
                        val (fw, fh) = frameSize(src.width, src.height, edit)
                        CropOverlay(edit.crop, picture, cropRatio, fw, fh, onMoving = { moving = it }) { edit = edit.copy(crop = it) }
                    }
                    if (tab == PhotoTab.DRAW && !shown.wholeFrame && !saving) {
                        DrawOverlay(toScreen, src.width, src.height, brush) { edit = edit.copy(strokes = edit.strokes + it) }
                        BrushSizeCircle(brush, sizing, picture.center, brush.width * min(src.width, src.height) * toScreen.mapRadius(1f))
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = source != null && !saving
            when (tab) {
                PhotoTab.ADJUST -> PhotoAdjustPanel(edit, ready, onMoving = { if (!gpu) moving = it }) { edit = it }
                PhotoTab.CROP -> source?.let { src ->
                    CropPanel(edit, src.width, src.height, cropShape, ready, onMoving = { moving = it },
                        onShape = { shape -> cropShape = shape }) { edit = it }
                }
                PhotoTab.FILTERS -> source?.let { src -> FiltersPanel(edit, src, ready, onMoving = { if (!gpu) moving = it }) { edit = it } }
                PhotoTab.DRAW -> DrawPanel(brush, edit, ready, onSizing = { sizing = it }, onBrush = { brush = it }) { edit = it }
                else -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhotoTab.entries.forEach { t ->
                    val icon = when (t) {
                        PhotoTab.ADJUST -> Icons.Default.Tune
                        PhotoTab.CROP -> Icons.Default.Crop
                        PhotoTab.FILTERS -> Icons.Default.FilterVintage
                        PhotoTab.DRAW -> Icons.Default.Brush
                    }
                    TabTool(icon, t.label, selected = tab == t, enabled = ready) { tab = t }
                }
            }
        }
    }

    if (saving) AlertDialog(onDismissRequest = {}, containerColor = VaultSurface,
        title = { Text("Saving", color = TextPrimary) },
        text = { LinearProgressIndicator(color = VaultAccent, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {})

    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, containerColor = VaultSurface,
        title = { Text("Discard changes?", color = TextPrimary) },
        text = { Text("Your edit hasn't been saved. The original photo is not affected.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; close() }) { Text("Discard", color = VaultError) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = VaultAccent) } })
}

/** A bottom tab: like a [Tool], in the accent colour while selected. */
@Composable
private fun TabTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when { !enabled -> TextMuted; selected -> VaultAccent; else -> TextSecondary }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** The video editor's adjustments plus Grain: chips pick one, the slider sets it. */
@Composable
private fun PhotoAdjustPanel(edit: PhotoEdit, enabled: Boolean, onMoving: (Boolean) -> Unit, onChange: (PhotoEdit) -> Unit) {
    // null is Grain, which isn't a colour adjustment.
    var current by remember { mutableStateOf<Adjustment?>(Adjustment.BRIGHTNESS) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (Adjustment.entries + listOf(null)).forEach { a ->
            val value = if (a == null) edit.grain else edit.adjustments[a]
            FilterChip(selected = current == a, onClick = { current = a },
                label = { Text((a?.label ?: "Grain") + if (value != 0) " •" else "") },
                colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary, selectedLabelColor = VaultDarkBg,
                    selectedContainerColor = VaultAccent))
        }
    }
    val a = current
    val value = if (a == null) edit.grain else edit.adjustments[a]
    LabeledSlider(a?.label ?: "Grain", value.toString(), value.toFloat(), if (a == null) 0f..100f else -100f..100f, enabled,
        onDone = { onMoving(false) }) { v ->
        onMoving(true)
        val n = kotlin.math.round(v).toInt()
        onChange(if (a == null) edit.copy(grain = n) else edit.copy(adjustments = edit.adjustments.with(a, n)))
    }
    Row {
        PanelButton("Reset", enabled && value != 0) { onChange(if (a == null) edit.copy(grain = 0) else edit.copy(adjustments = edit.adjustments.with(a, 0))) }
        PanelButton("Reset all", enabled && (!edit.adjustments.isNone || edit.grain != 0)) {
            onChange(edit.copy(adjustments = com.secretvault.app.core.processing.Adjustments(), grain = 0))
        }
    }
}

/** A preview and the edit it shows; [wholeFrame] when drawn uncropped, for the crop tool. */
private class Rendered(val bitmap: Bitmap, val edit: PhotoEdit, val wholeFrame: Boolean)

/** Crop shapes: a name and width / height, null for free; Original is the photo's own shape (as turned). */
private val CROP_SHAPES = listOf("Free" to null, "Original" to 0f, "1:1" to 1f, "4:5" to 4f / 5, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)

private fun shapeRatio(shape: Int, frameW: Int, frameH: Int): Float? =
    CROP_SHAPES[shape].second?.let { if (it == 0f) frameW.toFloat() / frameH else it }

/**
 * The crop box over the whole straightened [picture] (in this box's pixels): the outside dimmed, thirds while
 * dragging. Drag a corner to resize (keeping [ratio] when set) or the inside to move.
 */
@Composable
private fun CropOverlay(crop: CropRect, picture: Rect, ratio: Float?, frameW: Int, frameH: Int, onMoving: (Boolean) -> Unit,
                        onChange: (CropRect) -> Unit) {
    val current by rememberUpdatedState(crop)
    val area by rememberUpdatedState(picture)
    val shape by rememberUpdatedState(ratio)
    val change by rememberUpdatedState(onChange)
    val moving by rememberUpdatedState(onMoving)
    var dragging by remember { mutableStateOf(false) }
    val reach = with(LocalDensity.current) { 32.dp.toPx() }
    Canvas(Modifier.fillMaxSize().pointerInput(frameW, frameH) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val start = current
            val a = area
            fun fraction(o: Offset) = Offset((o.x - a.left) / a.width, (o.y - a.top) / a.height)
            fun corner(right: Boolean, bottom: Boolean) =
                Offset(a.left + (if (right) start.right else start.left) * a.width, a.top + (if (bottom) start.bottom else start.top) * a.height)
            val grip = listOf(false to false, true to false, false to true, true to true)
                .filter { (r, b) -> (corner(r, b) - down.position).getDistance() <= reach }
                .minByOrNull { (r, b) -> (corner(r, b) - down.position).getDistance() }
            val from = fraction(down.position)
            if (grip == null && (from.x !in start.left..start.right || from.y !in start.top..start.bottom)) return@awaitEachGesture
            down.consume()
            dragging = true
            moving(true)
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                c.consume()
                val to = fraction(c.position)
                change(grip?.let { (r, b) -> start.dragCorner(r, b, to.x, to.y, shape, frameW, frameH) } ?: start.moved(to.x - from.x, to.y - from.y))
            }
            dragging = false
            moving(false)
        }
    }) {
        val box = Rect(area.left + crop.left * area.width, area.top + crop.top * area.height,
            area.left + crop.right * area.width, area.top + crop.bottom * area.height)
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset(area.left, area.top), Size(area.width, box.top - area.top))
        drawRect(dim, Offset(area.left, box.bottom), Size(area.width, area.bottom - box.bottom))
        drawRect(dim, Offset(area.left, box.top), Size(box.left - area.left, box.height))
        drawRect(dim, Offset(box.right, box.top), Size(area.right - box.right, box.height))
        if (dragging) for (i in 1..2) {
            val x = box.left + box.width * i / 3
            val y = box.top + box.height * i / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, box.top), Offset(x, box.bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(box.left, y), Offset(box.right, y), 1.dp.toPx())
        }
        drawRect(Color.White, box.topLeft, box.size, style = Stroke(1.5.dp.toPx()))
        // An L at each corner, to show where to grab.
        val l = 18.dp.toPx()
        val t = 4.dp.toPx()
        for ((cx, sx) in listOf(box.left to 1f, box.right to -1f)) for ((cy, sy) in listOf(box.top to 1f, box.bottom to -1f)) {
            drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * l, cy), t)
            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * l), t)
        }
    }
}

/** Crop shapes, straighten, quarter turns and flips. */
@Composable
private fun CropPanel(edit: PhotoEdit, srcW: Int, srcH: Int, shape: Int, enabled: Boolean, onMoving: (Boolean) -> Unit,
                      onShape: (Int) -> Unit, onChange: (PhotoEdit) -> Unit) {
    val (fw, fh) = frameSize(srcW, srcH, edit)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CROP_SHAPES.forEachIndexed { i, (name, _) ->
            FilterChip(selected = shape == i, enabled = enabled, onClick = {
                onShape(i)
                shapeRatio(i, fw, fh)?.let { onChange(edit.copy(crop = CropRect.centred(it, fw, fh))) }
            }, label = { Text(name) }, colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary,
                selectedLabelColor = VaultDarkBg, selectedContainerColor = VaultAccent))
        }
    }
    LabeledSlider("Straighten", "${edit.straighten.roundToInt()}°", edit.straighten, -30f..30f, enabled, onDone = { onMoving(false) }) {
        onMoving(true)
        onChange(edit.copy(straighten = (it * 2).roundToInt() / 2f))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // A turn swaps the frame's sides, so the box turns too (4:5 becomes 5:4) and the shape goes back to Free.
        Tool(Icons.AutoMirrored.Filled.RotateRight, "Rotate 90°", enabled) { onShape(0); onChange(edit.turned()) }
        Tool(Icons.Default.Flip, "Flip H", enabled) { onChange(edit.flippedH()) }
        Tool(Icons.Default.Flip, "Flip V", enabled, iconRotation = 90f) { onChange(edit.flippedV()) }
        Tool(Icons.Default.RestartAlt, "Reset", enabled) {
            onShape(0)
            onChange(edit.copy(quarterTurns = 0, flipH = false, flipV = false, straighten = 0f, crop = CropRect.FULL))
        }
    }
}

/** A thumbnail per filter (and none) of the photo as it's cropped now; the picked one gets a strength slider. */
@Composable
private fun FiltersPanel(edit: PhotoEdit, source: Bitmap, enabled: Boolean, onMoving: (Boolean) -> Unit, onChange: (PhotoEdit) -> Unit) {
    val geometry = edit.copy(filter = null, filterStrength = 1f, adjustments = Adjustments(), grain = 0, strokes = emptyList())
    val thumbs by produceState<List<Bitmap>?>(null, geometry) {
        value = withContext(Dispatchers.Default) {
            val base = renderPhoto(source, geometry, maxSide = 200) ?: return@withContext null
            listOf(base) + PhotoFilter.entries.mapNotNull { renderPhoto(base, PhotoEdit(filter = it)) }
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(null) + PhotoFilter.entries).forEachIndexed { i, f ->
            val picked = edit.filter == f
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) {
                    // A newly picked filter starts at full strength.
                    if (!picked) onChange(edit.copy(filter = f, filterStrength = 1f))
                }) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(VaultSurface)
                    .border(if (picked) 2.dp else 0.dp, if (picked) VaultAccent else Color.Transparent, RoundedCornerShape(8.dp))) {
                    thumbs?.getOrNull(i)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Text(f?.label ?: "None", color = if (picked) VaultAccent else TextSecondary, fontSize = 12.sp)
            }
        }
    }
    if (edit.filter != null) LabeledSlider("Strength", "${(edit.filterStrength * 100).roundToInt()}%", edit.filterStrength, 0f..1f, enabled,
        onDone = { onMoving(false) }) {
        onMoving(true)
        onChange(edit.copy(filterStrength = it))
    }
}

private enum class BrushMode(val label: String) { PAINT("Brush"), BLUR("Blur"), MOSAIC("Mosaic") }

/** The brush as set in the Draw panel: [size] 1..100, [softness] and [strength] (blur or mosaic) 0..1. */
private data class BrushSettings(
    val mode: BrushMode = BrushMode.PAINT,
    val size: Float = 20f,
    val softness: Float = 0f,
    val strength: Float = 0.5f,
    val color: Int = 0xFFFFFFFF.toInt()
) {
    /** Stroke width as a fraction of the photo's short side. */
    val width: Float get() = 0.004f + size / 100f * 0.08f
    val hides: Boolean get() = mode != BrushMode.PAINT

    fun stroke(points: List<Pair<Float, Float>>) =
        BrushStroke(points, width, softness, color, blur = hides, mosaic = mode == BrushMode.MOSAIC, strength = strength)
}

private val BRUSH_COLOURS = listOf(0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFF06292)
    .map { it.toInt() }

/** From the original photo's ([srcW]×[srcH]) pixels to the screen: as [shown] was drawn, then fitted into [picture]. */
private fun previewToScreen(shown: Rendered, picture: Rect, srcW: Int, srcH: Int): Matrix {
    val (fw, _) = frameSize(srcW, srcH, shown.edit)
    val cropW = if (shown.wholeFrame) 1f else shown.edit.crop.width
    return photoMatrix(srcW, srcH, shown.edit, cropped = !shown.wholeFrame, scale = shown.bitmap.width / (cropW * fw)).apply {
        postScale(picture.width / shown.bitmap.width, picture.height / shown.bitmap.height)
        postTranslate(picture.left, picture.top)
    }
}

/**
 * Draws on the cropped preview: a finger's path shows straight away and becomes a stroke when lifted, stored in
 * fractions of the original photo ([srcW]×[srcH]) so later crops and turns keep it in place.
 */
@Composable
private fun DrawOverlay(toScreen: Matrix, srcW: Int, srcH: Int, brush: BrushSettings, onStroke: (BrushStroke) -> Unit) {
    val toPhoto = remember(toScreen) { Matrix().also { toScreen.invert(it) } }
    val settings by rememberUpdatedState(brush)
    val add by rememberUpdatedState(onStroke)
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Canvas(Modifier.fillMaxSize().pointerInput(toPhoto) {
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val points = mutableListOf(down.position)
            live = points.toList()
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                c.consume()
                if ((c.position - points.last()).getDistance() >= 3f) { points += c.position; live = points.toList() }
            }
            val xy = FloatArray(points.size * 2).also { a -> points.forEachIndexed { i, p -> a[i * 2] = p.x; a[i * 2 + 1] = p.y } }
            toPhoto.mapPoints(xy)
            add(settings.stroke(points.indices.map { xy[it * 2] / srcW to xy[it * 2 + 1] / srcH }))
            live = emptyList()
        }
    }) {
        if (live.isEmpty()) return@Canvas
        val b = settings
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(live[0].x, live[0].y)
            live.drop(1).forEach { lineTo(it.x, it.y) }
            if (live.size == 1) lineTo(live[0].x + 0.1f, live[0].y)
        }
        val width = b.width * min(srcW, srcH) * toScreen.mapRadius(1f)
        drawPath(path, if (b.hides) Color.White.copy(alpha = 0.4f) else Color(b.color),
            style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The brush at its real size ([diameter] screen pixels), softness and colour, in the middle of the photo while
 * [visible] (a Size or Softness slider is moving); it fades out after.
 */
@Composable
private fun BrushSizeCircle(brush: BrushSettings, visible: Boolean, centre: Offset, diameter: Float) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "brush size")
    if (alpha == 0f) return
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val radius = diameter / 2
            val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (brush.hides) android.graphics.Color.WHITE else brush.color
                this.alpha = ((if (brush.hides) 0.35f else 1f) * alpha * 255).roundToInt()
                if (brush.softness > 0f) maskFilter = BlurMaskFilter(brush.softness * radius, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.nativeCanvas.drawCircle(centre.x, centre.y, radius, fill)
        }
        // An outline, so a black or white brush shows on any photo.
        drawCircle(Color.White.copy(alpha = 0.8f * alpha), diameter / 2, centre, style = Stroke(1.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f * alpha), diameter / 2 + 1.dp.toPx(), centre, style = Stroke(1.dp.toPx()))
    }
}

/** Brush, blur or mosaic; size, softness, strength (blur and mosaic) and colour (brush); undo and clear. */
@Composable
private fun DrawPanel(brush: BrushSettings, edit: PhotoEdit, enabled: Boolean, onSizing: (Boolean) -> Unit, onBrush: (BrushSettings) -> Unit,
                      onChange: (PhotoEdit) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BrushMode.entries.forEach { mode ->
            FilterChip(selected = brush.mode == mode, enabled = enabled, onClick = { onBrush(brush.copy(mode = mode)) }, label = { Text(mode.label) },
                colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary, selectedLabelColor = VaultDarkBg, selectedContainerColor = VaultAccent))
        }
        Spacer(Modifier.weight(1f))
        PanelButton("Undo", enabled && edit.strokes.isNotEmpty()) { onChange(edit.copy(strokes = edit.strokes.dropLast(1))) }
        PanelButton("Clear", enabled && edit.strokes.isNotEmpty()) { onChange(edit.copy(strokes = emptyList())) }
    }
    LabeledSlider("Size", brush.size.roundToInt().toString(), brush.size, 1f..100f, enabled, onDone = { onSizing(false) }) {
        onSizing(true)
        onBrush(brush.copy(size = it))
    }
    LabeledSlider("Softness", "${(brush.softness * 100).roundToInt()}%", brush.softness, 0f..1f, enabled, onDone = { onSizing(false) }) {
        onSizing(true)
        onBrush(brush.copy(softness = it))
    }
    if (brush.hides) LabeledSlider("Strength", "${(brush.strength * 100).roundToInt()}%", brush.strength, 0f..1f, enabled) {
        onBrush(brush.copy(strength = it))
    } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BRUSH_COLOURS.forEach { c ->
            val picked = brush.color == c
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                .border(if (picked) 3.dp else 1.dp, if (picked) VaultAccent else Color.White.copy(alpha = 0.4f), CircleShape)
                .clickable(enabled = enabled) { onBrush(brush.copy(color = c)) })
        }
    }
}
