package com.secretvault.app.core.processing

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One-tap looks, each a colour function of the pixel alone; [PhotoEdit.filterStrength] blends it with the original. */
enum class PhotoFilter(val label: String) {
    VIVID("Vivid"), WARM("Warm"), COOL("Cool"), MONO("Mono"), NOIR("Noir"), SEPIA("Sepia"), VINTAGE("Vintage"),
    FADE("Fade"), DRAMATIC("Dramatic");

    private val look: Adjustments by lazy {
        fun of(vararg v: Pair<Adjustment, Int>) = v.fold(Adjustments()) { a, (k, x) -> a.with(k, x) }
        when (this) {
            VIVID -> of(Adjustment.SATURATION to 35, Adjustment.VIBRANCE to 25, Adjustment.CONTRAST to 15)
            WARM -> of(Adjustment.WARMTH to 40, Adjustment.SATURATION to 5)
            COOL -> of(Adjustment.WARMTH to -40, Adjustment.TINT to -5)
            MONO -> of(Adjustment.SATURATION to -100)
            NOIR -> of(Adjustment.SATURATION to -100, Adjustment.CONTRAST to 45, Adjustment.SHADOWS to -25)
            SEPIA -> of(Adjustment.SATURATION to -100, Adjustment.CONTRAST to -5)
            VINTAGE -> of(Adjustment.FADE to 45, Adjustment.WARMTH to 25, Adjustment.SATURATION to -25, Adjustment.CONTRAST to -10)
            FADE -> of(Adjustment.FADE to 60, Adjustment.CONTRAST to -15, Adjustment.SATURATION to -15)
            DRAMATIC -> of(Adjustment.CONTRAST to 50, Adjustment.SHADOWS to -30, Adjustment.HIGHLIGHTS to -20, Adjustment.SATURATION to -10)
        }
    }

    /** The filtered colour of [r], [g], [b] (0..1), as 0..1 channels. */
    fun apply(r: Float, g: Float, b: Float): FloatArray {
        val c = look.apply(r, g, b)
        val out = floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)
        // Sepia tones the grey it gets from the look: warm browns in the darks, cream in the lights.
        if (this == SEPIA) { out[0] = min(1f, out[0] * 1.07f + 0.05f); out[2] = out[2] * 0.82f }
        return out
    }
}

/** A crop in fractions of the straightened frame, from the top-left. */
data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top

    /** The same area after the frame turns a quarter clockwise. */
    fun turned() = CropRect(1 - bottom, left, 1 - top, right)
    fun flippedH() = CropRect(1 - right, top, 1 - left, bottom)
    fun flippedV() = CropRect(left, 1 - bottom, right, 1 - top)

    companion object {
        val FULL = CropRect()

        /** The biggest crop of [ratio] (width / height, in pixels) centred in a [frameW]×[frameH] frame. */
        fun centred(ratio: Float, frameW: Int, frameH: Int): CropRect {
            val w = min(1f, ratio * frameH / frameW)
            val h = min(1f, frameW / (ratio * frameH))
            return CropRect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
        }
    }
}

/**
 * One brush stroke, in fractions of the original photo (upright, before any turn, flip, straighten or crop), so
 * changing those later keeps it on the same spot. [width] is a fraction of the photo's short side; [softness] 0..1
 * feathers its edge; a [blur] stroke blurs what's under it instead of painting [color].
 */
data class BrushStroke(val points: List<Pair<Float, Float>>, val width: Float, val softness: Float, val color: Int, val blur: Boolean)

/**
 * Everything done to a photo, applied in this order when it's drawn: turn ([quarterTurns] clockwise), flip, straighten
 * ([straighten] degrees clockwise, zoomed in to leave no empty corners), crop, filter (blended by [filterStrength]),
 * [adjustments], [grain] 0..100, then [strokes].
 */
data class PhotoEdit(
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,
    val crop: CropRect = CropRect.FULL,
    val filter: PhotoFilter? = null,
    val filterStrength: Float = 1f,
    val adjustments: Adjustments = Adjustments(),
    val grain: Int = 0,
    val strokes: List<BrushStroke> = emptyList()
) {
    val turnsSideways: Boolean get() = quarterTurns % 2 != 0

    /** Whether the colour stage changes anything, so drawing can skip it. */
    val changesColour: Boolean get() = !adjustments.isNone || (filter != null && filterStrength > 0f)

    /** A quarter turn clockwise, with the crop and drawing kept on the same part of the photo. */
    fun turned() = copy(quarterTurns = (quarterTurns + 1) % 4, crop = crop.turned())
    fun flippedH() = copy(flipH = !flipH, crop = crop.flippedH())
    fun flippedV() = copy(flipV = !flipV, crop = crop.flippedV())

    /** The colour of [r], [g], [b] (0..1) after the filter and adjustments, as 0xFFRRGGBB. */
    fun colour(r: Float, g: Float, b: Float): Int {
        val f = filter
        if (f == null || filterStrength <= 0f) return adjustments.apply(r, g, b)
        val c = f.apply(r, g, b)
        val s = filterStrength
        return adjustments.apply(r + (c[0] - r) * s, g + (c[1] - g) * s, b + (c[2] - b) * s)
    }

    /** [colour] as a [size]³ table, flattened as `[(r * size + g) * size + b]`. */
    fun colourTable(size: Int = 33): IntArray = IntArray(size * size * size).also { t ->
        for (r in 0 until size) for (g in 0 until size) for (b in 0 until size) {
            t[(r * size + g) * size + b] = colour(r / (size - 1f), g / (size - 1f), b / (size - 1f))
        }
    }
}

/**
 * How much a [w]×[h] frame's picture must grow when turned [degrees] so it still covers the whole frame: the frame's
 * corners, turned back, must land inside the grown picture on both axes.
 */
fun straightenZoom(degrees: Float, w: Int, h: Int): Float {
    val a = Math.toRadians(abs(degrees).toDouble())
    return (cos(a) + max(w.toDouble() / h, h.toDouble() / w) * sin(a)).toFloat()
}
