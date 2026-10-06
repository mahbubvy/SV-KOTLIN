package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.secretvault.app.core.processing.BrushStroke
import com.secretvault.app.core.processing.PhotoEdit
import com.secretvault.app.core.processing.straightenZoom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TABLE = 33

/** The photo's size once turned: [srcW]×[srcH], sides swapped by an odd number of quarter turns. */
fun frameSize(srcW: Int, srcH: Int, edit: PhotoEdit): Pair<Int, Int> = if (edit.turnsSideways) srcH to srcW else srcW to srcH

/**
 * Where each pixel of the [srcW]×[srcH] photo lands in the edited picture: turned, flipped, straightened (zoomed in to
 * cover the frame) and, when [cropped], cropped; then scaled by [scale].
 */
fun photoMatrix(srcW: Int, srcH: Int, edit: PhotoEdit, cropped: Boolean, scale: Float): Matrix {
    val (w, h) = frameSize(srcW, srcH, edit)
    val zoom = straightenZoom(edit.straighten, w, h)
    return Matrix().apply {
        postTranslate(-srcW / 2f, -srcH / 2f)
        postRotate(90f * edit.quarterTurns)
        postScale(if (edit.flipH) -1f else 1f, if (edit.flipV) -1f else 1f)
        postRotate(edit.straighten)
        postScale(zoom, zoom)
        postTranslate(w / 2f, h / 2f)
        if (cropped) postTranslate(-edit.crop.left * w, -edit.crop.top * h)
        postScale(scale, scale)
    }
}

/**
 * [source] with [edit] applied, no bigger than [maxSide] on its long side. The same steps make the preview (from a
 * smaller copy) and the saved photo (full size), so they match. [cropped] false shows the whole straightened frame,
 * for the crop tool. [keepGoing] is asked every row, so a render that's no longer wanted can stop early (returns null).
 */
fun renderPhoto(source: Bitmap, edit: PhotoEdit, cropped: Boolean = true, maxSide: Int = Int.MAX_VALUE,
                keepGoing: () -> Boolean = { true }): Bitmap? {
    val (w, h) = frameSize(source.width, source.height, edit)
    val fullW = if (cropped) edit.crop.width * w else w.toFloat()
    val fullH = if (cropped) edit.crop.height * h else h.toFloat()
    val scale = min(1f, maxSide / max(fullW, fullH))
    val out = Bitmap.createBitmap((fullW * scale).roundToInt().coerceAtLeast(1), (fullH * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.BLACK)
    val matrix = photoMatrix(source.width, source.height, edit, cropped, scale)
    canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    if (!colourAndGrain(out, edit, max(fullW, fullH), keepGoing)) { out.recycle(); return null }
    drawStrokes(canvas, out, matrix, source.width, source.height, edit.strokes)
    return out
}

// The filter, adjustments and grain, a row at a time. Grain is placed on a grid of the full-size photo
// ([fullLongSide] pixels long), so the preview shows the same grain as the saved photo, only smaller.
private fun colourAndGrain(bitmap: Bitmap, edit: PhotoEdit, fullLongSide: Float, keepGoing: () -> Boolean): Boolean {
    val table = if (edit.changesColour) edit.colourTable(TABLE) else null
    val grain = edit.grain / 100f * 0.16f
    if (table == null && grain == 0f) return true
    val w = bitmap.width
    val toGrid = fullLongSide / max(w, bitmap.height) / GRAIN_CELL
    val row = IntArray(w)
    for (y in 0 until bitmap.height) {
        if (!keepGoing()) return false
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        val gy = floor(y * toGrid).toInt()
        for (x in 0 until w) {
            var c = row[x]
            if (table != null) c = lookUp(table, c)
            if (grain > 0f) c = grained(c, (noise(floor(x * toGrid).toInt(), gy) * grain * 255f).roundToInt())
            row[x] = c
        }
        bitmap.setPixels(row, 0, w, 0, y, w, 1)
    }
    return true
}

private const val GRAIN_CELL = 2.5f // full-size pixels per grain

// −1..1, the same for the same cell every time.
private fun noise(x: Int, y: Int): Float {
    var n = x * 374761393 + y * 668265263
    n = (n xor (n ushr 13)) * 1274126177
    return ((n xor (n ushr 16)) and 0xFFFF) / 32767.5f - 1f
}

private fun grained(c: Int, by: Int): Int {
    fun ch(v: Int) = (v + by).coerceIn(0, 255)
    return (c and 0xFF000000.toInt()) or (ch(c shr 16 and 255) shl 16) or (ch(c shr 8 and 255) shl 8) or ch(c and 255)
}

// Trilinear lookup in the colour table: the eight table entries around the colour, weighted by how close each is.
private fun lookUp(table: IntArray, c: Int): Int {
    val s = TABLE - 1
    val r = (c shr 16 and 255) * s / 255f
    val g = (c shr 8 and 255) * s / 255f
    val b = (c and 255) * s / 255f
    val r0 = r.toInt().coerceAtMost(s - 1); val g0 = g.toInt().coerceAtMost(s - 1); val b0 = b.toInt().coerceAtMost(s - 1)
    val fr = r - r0; val fg = g - g0; val fb = b - b0
    var outR = 0f; var outG = 0f; var outB = 0f
    for (i in 0..1) for (j in 0..1) for (k in 0..1) {
        val weight = (if (i == 0) 1 - fr else fr) * (if (j == 0) 1 - fg else fg) * (if (k == 0) 1 - fb else fb)
        val t = table[((r0 + i) * TABLE + g0 + j) * TABLE + b0 + k]
        outR += (t shr 16 and 255) * weight
        outG += (t shr 8 and 255) * weight
        outB += (t and 255) * weight
    }
    return (c and 0xFF000000.toInt()) or (outR.roundToInt() shl 16) or (outG.roundToInt() shl 8) or outB.roundToInt()
}

// Strokes in order, mapped by the same [matrix] as the photo. A blur stroke shows a blurred copy of the picture as it
// is when the stroke is drawn, so it also blurs earlier colour strokes under it.
private fun drawStrokes(canvas: Canvas, picture: Bitmap, matrix: Matrix, srcW: Int, srcH: Int, strokes: List<BrushStroke>) {
    if (strokes.isEmpty()) return
    val scale = matrix.mapRadius(1f)
    var blurred: Bitmap? = null
    for (s in strokes) {
        val width = s.width * min(srcW, srcH) * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
            if (s.softness > 0f) maskFilter = BlurMaskFilter(s.softness * width / 2, BlurMaskFilter.Blur.NORMAL)
        }
        if (s.blur) {
            val copy = blurred ?: blurredCopy(picture).also { blurred = it }
            paint.shader = BitmapShader(copy, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(picture.width / copy.width.toFloat(), picture.height / copy.height.toFloat()) })
            }
        } else {
            paint.color = s.color
            blurred = null // the picture changed; the next blur stroke blurs this stroke too
        }
        canvas.drawPath(strokePath(s, matrix, srcW.toFloat(), srcH.toFloat()), paint)
    }
}

/** [s]'s points (fractions of a [srcW]×[srcH] photo) through [matrix], smoothed into curves through the midpoints; a single point is a dot. */
fun strokePath(s: BrushStroke, matrix: Matrix, srcW: Float, srcH: Float): Path {
    val pts = FloatArray(s.points.size * 2)
    s.points.forEachIndexed { i, (x, y) -> pts[i * 2] = x * srcW; pts[i * 2 + 1] = y * srcH }
    matrix.mapPoints(pts)
    return Path().apply {
        moveTo(pts[0], pts[1])
        if (s.points.size == 1) lineTo(pts[0] + 0.01f, pts[1])
        for (i in 1 until s.points.size - 1) {
            quadTo(pts[i * 2], pts[i * 2 + 1], (pts[i * 2] + pts[i * 2 + 2]) / 2, (pts[i * 2 + 1] + pts[i * 2 + 3]) / 2)
        }
        if (s.points.size > 1) lineTo(pts[pts.size - 2], pts[pts.size - 1])
    }
}

// A heavily blurred copy, about 40 px on its short side: halved step by step so every pixel is averaged in.
private fun blurredCopy(picture: Bitmap): Bitmap {
    var b = picture
    while (min(b.width, b.height) > 80) {
        val next = Bitmap.createScaledBitmap(b, (b.width / 2).coerceAtLeast(1), (b.height / 2).coerceAtLeast(1), true)
        if (b !== picture) b.recycle()
        b = next
    }
    val small = min(b.width, b.height)
    if (small <= 40) return if (b === picture) b.copy(Bitmap.Config.ARGB_8888, false) else b
    val f = 40f / small
    return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
        .also { if (b !== picture) b.recycle() }
}
