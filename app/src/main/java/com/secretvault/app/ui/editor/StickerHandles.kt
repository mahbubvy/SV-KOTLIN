package com.secretvault.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.secretvault.app.core.processing.Placement
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val Ink = Color(0xFF080808)
private val HandleEdge = Color(0xFFD9D9D9)
private val Shade = Color.Black.copy(alpha = 0.18f)

private enum class Grip { CORNER, SIDE, EDGE, ROTATE }

/**
 * The selected sticker's box, over the whole frame it sits in: corner and side handles resize it, the button under it
 * turns it (drag, or tap for a quarter turn), and the bar above duplicates or deletes it. [onChange] gets the change
 * to make to the placement shown now, so keyframes work as with the two-finger gestures. [free] (blur regions) adds
 * top and bottom handles and lets width and height change separately. [inside] is the picture the placement is a
 * fraction of, in this box's pixels; the whole box when null.
 */
@Composable
internal fun StickerHandles(
    placement: Placement,
    aspect: Float, // the image's height / width
    canDuplicate: Boolean,
    onStart: () -> Unit,
    onChange: ((Placement) -> Placement) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    free: Boolean = false,
    inside: Rect? = null
) {
    val p by rememberUpdatedState(placement)
    val shape by rememberUpdatedState(aspect)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onChange)
    val picture by rememberUpdatedState(inside)
    val density = LocalDensity.current
    val rotateGap = with(density) { 34.dp.toPx() } // bottom edge to the rotate button's centre
    val reach = with(density) { 24.dp.toPx() }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(free) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val fw = size.width.toFloat()
            val fh = size.height.toFloat()
            val first = p
            val area = picture ?: Rect(0f, 0f, fw, fh)
            val center = area.topLeft + Offset(first.centerX * area.width, first.centerY * area.height)
            val halfW = first.widthFraction * area.width / 2
            val halfH = halfW * shape * first.stretch
            val a = Math.toRadians(first.angle.toDouble())
            // From the sticker's own axes (x right, y down, around its centre) to the frame, and back.
            fun toFrame(x: Float, y: Float) = center + Offset((x * cos(a) - y * sin(a)).toFloat(), (x * sin(a) + y * cos(a)).toFloat())
            fun alongW(o: Offset) = abs(o.x * cos(a) + o.y * sin(a)).toFloat()
            fun alongH(o: Offset) = abs(o.y * cos(a) - o.x * sin(a)).toFloat()
            fun near(x: Float, y: Float) = (toFrame(x, y) - down.position).getDistance() <= reach
            val grip = when {
                near(0f, halfH + rotateGap) -> Grip.ROTATE
                listOf(-1f, 1f).any { sx -> listOf(-1f, 1f).any { sy -> near(sx * halfW, sy * halfH) } } -> Grip.CORNER
                near(-halfW, 0f) || near(halfW, 0f) -> Grip.SIDE
                free && (near(0f, -halfH) || near(0f, halfH)) -> Grip.EDGE
                else -> return@awaitEachGesture // not ours: the preview's own gestures handle it
            }
            down.consume()
            start()
            val from = down.position - center
            var moved = false
            while (true) {
                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                c.consume()
                val to = c.position - center
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (!moved) continue
                // Distances along the box's own width and height, so a turned box still resizes the right way.
                val sx = alongW(to) / alongW(from).coerceAtLeast(1f)
                val sy = alongH(to) / alongH(from).coerceAtLeast(1f)
                when (grip) {
                    Grip.CORNER -> if (free) {
                        change { it.copy(widthFraction = first.widthFraction * sx, stretch = first.stretch * sy / sx) }
                    } else {
                        val scale = to.getDistance() / from.getDistance().coerceAtLeast(1f)
                        change { it.copy(widthFraction = first.widthFraction * scale) }
                    }
                    // A free box keeps its height while its width changes.
                    Grip.SIDE -> change { it.copy(widthFraction = first.widthFraction * sx, stretch = if (free) first.stretch / sx else it.stretch) }
                    Grip.EDGE -> change { it.copy(stretch = first.stretch * sy) }
                    Grip.ROTATE -> {
                        val turned = Math.toDegrees((atan2(to.y, to.x) - atan2(from.y, from.x)).toDouble()).toFloat()
                        change { it.copy(rotation = first.rotation + turned) }
                    }
                }
            }
            if (!moved && grip == Grip.ROTATE) change { it.copy(rotation = snapAngle90(first.rotation + 90f)) }
        }
    }) {
        val fw = constraints.maxWidth.toFloat()
        val fh = constraints.maxHeight.toFloat()
        val area = inside ?: Rect(0f, 0f, fw, fh)
        val center = area.topLeft + Offset(p.centerX * area.width, p.centerY * area.height)
        val w = p.widthFraction * area.width
        val h = w * shape * p.stretch
        val a = Math.toRadians(p.angle.toDouble())

        Canvas(Modifier.fillMaxSize()) {
            rotate(p.angle, center) { drawBox(center, w, h, free) }
        }

        // Rotate button, turned with the box.
        val rotateAt = center + Offset((-(h / 2 + rotateGap) * sin(a)).toFloat(), ((h / 2 + rotateGap) * cos(a)).toFloat())
        val button = with(density) { 30.dp.toPx() }
        Box(Modifier.offset { IntOffset((rotateAt.x - button / 2).roundToInt(), (rotateAt.y - button / 2).roundToInt()) }
            .size(30.dp).graphicsLayer { rotationZ = p.angle }.shadow(2.dp, CircleShape).background(Color.White, CircleShape)
            .border(0.5.dp, Color(0xFFE4E4E4), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate", tint = Ink, modifier = Modifier.size(20.dp))
        }

        // Duplicate and delete, upright above the box (or below it when there's no room above).
        val barW = with(density) { 88.dp.toPx() }
        val barH = with(density) { 40.dp.toPx() }
        val gap = with(density) { 14.dp.toPx() }
        val halfTall = (abs(w * sin(a)) + abs(h * cos(a))).toFloat() / 2
        val above = center.y - halfTall - gap - barH
        val barY = if (above >= 0f) above else (center.y + halfTall + rotateGap + gap).coerceAtMost(fh - barH)
        val barX = (center.x - barW / 2).coerceIn(0f, (fw - barW).coerceAtLeast(0f))
        Row(Modifier.offset { IntOffset(barX.roundToInt(), barY.roundToInt()) }.size(88.dp, 40.dp)
            .shadow(3.dp, RoundedCornerShape(20.dp)).background(Color.White, RoundedCornerShape(20.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDuplicate, enabled = canDuplicate, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.LibraryAdd, "Duplicate", tint = if (canDuplicate) Ink else Ink.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Delete, "Delete", tint = Ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/** A tap on the rotate button lands on the next quarter turn. */
private fun snapAngle90(degrees: Float) = (degrees / 90f).roundToInt() * 90f

// The frame and its handles, drawn upright around [center]; the caller turns the canvas. [free] adds top and bottom.
private fun DrawScope.drawBox(center: Offset, w: Float, h: Float, free: Boolean) {
    val topLeft = center - Offset(w / 2, h / 2)
    drawRoundRect(Shade, topLeft, Size(w, h), CornerRadius(2.dp.toPx()), style = Stroke(4.dp.toPx()))
    drawRoundRect(Color.White, topLeft, Size(w, h), CornerRadius(2.dp.toPx()), style = Stroke(2.dp.toPx()))
    val r = 8.dp.toPx()
    for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
        val at = center + Offset(sx * w / 2, sy * h / 2)
        drawCircle(Shade, r + 1.5.dp.toPx(), at)
        drawCircle(Color.White, r, at)
        drawCircle(HandleEdge, r, at, style = Stroke(1.dp.toPx()))
    }
    fun pill(middle: Offset, size: Size) {
        val at = middle - Offset(size.width / 2, size.height / 2)
        val radius = CornerRadius(minOf(size.width, size.height) / 2)
        val grow = 1.5.dp.toPx()
        drawRoundRect(Shade, at - Offset(grow, grow), Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(radius.x + grow))
        drawRoundRect(Color.White, at, size, radius)
        drawRoundRect(HandleEdge, at, size, radius, style = Stroke(1.dp.toPx()))
    }
    val upright = Size(8.dp.toPx(), 24.dp.toPx())
    for (s in listOf(-1f, 1f)) {
        pill(center + Offset(s * w / 2, 0f), upright)
        if (free) pill(center + Offset(0f, s * h / 2), Size(upright.height, upright.width))
    }
}
