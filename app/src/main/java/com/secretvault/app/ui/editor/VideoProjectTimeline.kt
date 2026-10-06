package com.secretvault.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import com.secretvault.app.core.image.key
import com.secretvault.app.core.image.StickerImage
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.ui.theme.*
import kotlin.math.*

/**
 * Scrolling timeline with the playhead fixed in the centre: swipe to scrub, pinch to zoom, tap to select a clip or a
 * sticker, drag the selected one's edges to trim, drag a selected sticker's bar to move it. Stickers sit in lanes
 * under the clips. [onTouch] brackets every swipe, pinch or drag.
 */
@Composable
internal fun Timeline(
    project: VideoProject, selected: Int, stickerId: String?, positionMs: Long, frames: Map<String, List<ImageBitmap>>,
    stickerImages: Map<String, StickerImage?>, enabled: Boolean, onAdd: () -> Unit, onMute: (Int) -> Unit,
    onSelect: (Int) -> Unit, onSelectSticker: (String?) -> Unit, onTouch: (Boolean) -> Unit, onScrub: (Long) -> Unit,
    onTrim: (start: Boolean, startMs: Long, endMs: Long) -> Unit,
    onStickerTime: (startMs: Long, endMs: Long, atEnd: Boolean) -> Unit
) {
    var dpPerSecond by remember { mutableFloatStateOf(60f) }
    val pxPerMs = with(LocalDensity.current) { dpPerSecond.dp.toPx() } / 1000f
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val lanes = remember(project.stickers) { stickerLanes(project.stickers) }
    val laneCount = (lanes.values.maxOrNull() ?: -1) + 1
    var focused by remember { mutableStateOf(false) }
    val state by rememberUpdatedState(TimelineState(project, selected, stickerId, positionMs, pxPerMs, lanes,
        onSelect, onSelectSticker, onTouch, onScrub, onTrim, onStickerTime))

    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val viewportWidth = maxWidth
        val centrePx = with(density) { maxWidth.toPx() / 2f }
        val gutter = 64.dp
        val rulerHeight = maxOf(32.dp, with(density) { 16.sp.toDp() } + 8.dp)
        Column {
            Canvas(Modifier.fillMaxWidth().height(rulerHeight)) {
                val desired = 72f * density.fontScale.coerceAtLeast(1f) / dpPerSecond
                val unit = 10f.pow(floor(log10(desired)))
                val step = listOf(1f, 2f, 5f, 10f).first { it * unit >= desired } * unit * 1000f
                val first = floor((positionMs - centrePx / pxPerMs) / step).toLong()
                val last = ceil((positionMs + centrePx / pxPerMs) / step).toLong()
                clipRect(left = gutter.toPx()) {
                    for (tick in first..last) {
                        val time = tick * step
                        if (time < 0 || time > project.durationMs) continue
                        val x = centrePx + (time - positionMs) * pxPerMs
                        val seconds = (time / 1000f).roundToInt()
                        val label = if (step < 1000f) "%02d:%04.1f".format(java.util.Locale.ROOT, (time / 60_000).toInt(), time / 1000f % 60)
                            else "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
                        val measured = textMeasurer.measure(label, TextStyle(color = TextSecondary, fontSize = 12.sp))
                        drawText(measured, topLeft = Offset(x - measured.size.width / 2, 2.dp.toPx()))
                        drawLine(TextSecondary, Offset(x, size.height - 6.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                        val half = x + step * pxPerMs / 2
                        drawLine(TextMuted, Offset(half, size.height - 3.dp.toPx()), Offset(half, size.height), 1.dp.toPx())
                    }
                }
                drawLine(TextPrimary, Offset(centrePx, size.height - 8.dp.toPx()), Offset(centrePx, size.height), 2.dp.toPx())
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 168.dp).verticalScroll(rememberScrollState())) {
                Canvas(Modifier.fillMaxWidth().height(VIDEO_ROW + if (laneCount > 0) LANE_GAP + LANE * laneCount else 0.dp).clipToBounds()
                    .border(if (focused) 2.dp else 0.dp, if (focused) VaultAccent else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown || event.key !in listOf(Key.DirectionLeft, Key.DirectionRight)) false
                        else {
                            onTouch(true)
                            onScrub((positionMs + if (event.key == Key.DirectionLeft) -1000L else 1000L).coerceIn(0L, project.durationMs))
                            onTouch(false)
                            true
                        }
                    }.focusable(enabled)
                    .semantics {
                        contentDescription = "Video timeline"
                        progressBarRangeInfo = ProgressBarRangeInfo(positionMs.toFloat(), 0f..project.durationMs.coerceAtLeast(1).toFloat())
                        if (enabled) {
                            setProgress { value -> onTouch(true); onScrub(value.toLong().coerceIn(0L, project.durationMs)); onTouch(false); true }
                            customActions = listOf(
                                CustomAccessibilityAction("Zoom timeline in") { dpPerSecond = (dpPerSecond * 2).coerceAtMost(400f); true },
                                CustomAccessibilityAction("Zoom timeline out") { dpPerSecond = (dpPerSecond / 2).coerceAtLeast(10f); true },
                                CustomAccessibilityAction("Mute or unmute selected clip") { onMute(selected); true }
                            ) + project.clips.indices.map { index ->
                                CustomAccessibilityAction("Select clip ${index + 1}") { onSelect(index); true }
                            } + project.stickers.mapIndexed { index, sticker ->
                                CustomAccessibilityAction("Select sticker ${index + 1}") { onSelectSticker(sticker.id); true }
                            }
                        } else disabled()
                    }
                    .pointerInput(enabled) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (down.position.x < gutter.toPx()) return@awaitEachGesture
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
                            var vertical = 0f
                            var scrollingLanes = false
                            try { do {
                                val event = awaitPointerEvent()
                                if (event.changes.size > 1) zooming = true
                                val panOffset = event.calculatePan()
                                val pan = panOffset.x
                                vertical += panOffset.y
                                if (!moving && !zooming && abs(vertical) > viewConfiguration.touchSlop && abs(vertical) > abs(dragged + pan)) {
                                    scrollingLanes = true
                                    break
                                }
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
                            if (!moving && !scrollingLanes) {
                                val atMs = begin.positionMs + ((x - centre) / begin.pxPerMs).toLong()
                                if (lane < 0) begin.project.clipAt(atMs)?.let { (index, _) -> begin.onSelect(index) }
                                else begin.onSelectSticker(begin.project.stickers.firstOrNull { begin.lanes[it.id] == lane && atMs in it.startMs until it.endMs }?.id)
                            }
                            } finally { if (moving) state.onTouch(false) }
                        }
                    }) {
                    val centre = size.width / 2f
                    val videoRow = VIDEO_ROW.toPx()
                    val tile = videoRow
                    val gap = 1.dp.toPx()
                    val handle = 10.dp.toPx()
                    fun screenX(outputMs: Long) = centre + (outputMs - positionMs) * pxPerMs
                    fun handles(l: Float, r: Float, top: Float, height: Float, color: Color, grip: Color) {
                        drawRect(VaultDarkBg, Offset(l, top + gap), Size(r - l, height - 2 * gap), style = Stroke(4.dp.toPx()))
                        drawRect(color, Offset(l, top + gap), Size(r - l, height - 2 * gap), style = Stroke(2.dp.toPx()))
                        for (hx in listOf(l, r - handle)) {
                            drawRect(color, Offset(hx, top), Size(handle, height))
                            drawLine(grip, Offset(hx + handle / 2, top + height * 0.3f), Offset(hx + handle / 2, top + height * 0.7f), 2.dp.toPx())
                        }
                    }

                    clipRect(left = gutter.toPx()) {
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
                            if (index == selected && stickerId == null) handles(l, r, 0f, videoRow, VaultAccent, VaultDarkBg)
                        }

                        project.stickers.forEach { s ->
                            val top = videoRow + (LANE_GAP + LANE * (lanes[s.id] ?: return@forEach)).toPx()
                            val height = LANE.toPx() - 4.dp.toPx()
                            val l = screenX(s.startMs)
                            val r = screenX(s.endMs)
                            if (r < 0f || l > size.width) return@forEach
                            drawRoundRect(VaultSurfaceVariant, Offset(l, top), Size(r - l, height), CornerRadius(4.dp.toPx()))
                            drawRoundRect(TextSecondary, Offset(l, top), Size(r - l, height), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
                    stickerImages[s.source.key]?.poster?.asImageBitmap()?.let { img ->
                                // A small copy of the sticker at the visible start of its bar.
                                val h = height - 4.dp.toPx()
                                val w = h * img.width / img.height
                                val x = l + handle + 4.dp.toPx()
                                if (x + w < r) drawImage(img, IntOffset.Zero, IntSize(img.width, img.height),
                                    IntOffset(x.roundToInt(), (top + 2.dp.toPx()).roundToInt()), IntSize(w.roundToInt(), h.roundToInt()))
                            }
                            if (s.id == stickerId) handles(l, r, top, height, VaultAccent, VaultDarkBg)
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
                    }
                    drawLine(Color.White, Offset(centre, 0f), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
                }
                project.clips.forEachIndexed { index, clip ->
                    val x = with(density) { (centrePx + (project.outputStartOf(index) - positionMs) * pxPerMs).toDp() } + 12.dp
                    if (index == selected && x >= gutter && x + 48.dp <= viewportWidth) {
                        IconButton(onClick = { onMute(index) }, enabled = enabled,
                            modifier = Modifier.offset(x = x, y = 8.dp).size(48.dp).background(Color.Black.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)) {
                            Icon(if (clip.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                "${if (clip.muted) "Unmute" else "Mute"} clip ${index + 1}", tint = TextPrimary, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
        Column(Modifier.width(gutter).height(rulerHeight + minOf(168.dp, VIDEO_ROW + if (laneCount > 0) LANE_GAP + LANE * laneCount else 0.dp))
            .background(VaultDarkBg).padding(top = rulerHeight), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onAdd, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Add, "Add videos", tint = if (enabled) TextPrimary else TextMuted)
            }
            Text("Add", color = TextSecondary, fontSize = 12.sp)
        }
    }
}

private enum class Drag { SCRUB, CLIP_START, CLIP_END, STICKER_START, STICKER_END, STICKER_MOVE }

private val VIDEO_ROW = 64.dp
private val LANE = 48.dp
private val LANE_GAP = 8.dp

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
