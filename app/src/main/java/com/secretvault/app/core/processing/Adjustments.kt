package com.secretvault.app.core.processing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class Adjustment(val label: String) {
    BRIGHTNESS("Brightness"), EXPOSURE("Exposure"), CONTRAST("Contrast"), SATURATION("Saturation"), VIBRANCE("Vibrance"),
    SHADOWS("Shadows"), HIGHLIGHTS("Highlights"), WARMTH("Warmth"), TINT("Tint"), FADE("Fade")
}

/** A clip's colour adjustments, each −100..100; anything not set is 0 (unchanged). */
data class Adjustments(private val values: Map<Adjustment, Int> = emptyMap()) {
    operator fun get(adjustment: Adjustment): Int = values[adjustment] ?: 0
    fun with(adjustment: Adjustment, value: Int): Adjustments {
        val v = value.coerceIn(-100, 100)
        return Adjustments(if (v == 0) values - adjustment else values + (adjustment to v))
    }
    val isNone: Boolean get() = values.isEmpty()

    /**
     * The adjusted colour of [r], [g], [b] (0..1), as 0xFFRRGGBB. Every adjustment is a function of the pixel alone,
     * so the whole look fits in a colour lookup table ([lutCube]) that the preview and the export both apply.
     */
    fun apply(r: Float, g: Float, b: Float): Int {
        fun v(a: Adjustment) = this[a] / 100f
        val c = floatArrayOf(r, g, b)
        fun each(f: (Float) -> Float) { for (i in 0..2) c[i] = f(c[i]) }
        fun luma() = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2]

        val exposure = 2f.pow(v(Adjustment.EXPOSURE) * 1.5f)
        each { it * exposure }
        val brightness = v(Adjustment.BRIGHTNESS) * 0.25f
        each { it + brightness }
        val contrast = 1f + v(Adjustment.CONTRAST) * 0.8f
        each { (it - 0.5f) * contrast + 0.5f }
        // Shadows lift or deepen the dark parts and Highlights the bright parts, fading out toward the other end.
        val l = luma().coerceIn(0f, 1f)
        val tone = v(Adjustment.SHADOWS) * 0.35f * (1 - l) * (1 - l) + v(Adjustment.HIGHLIGHTS) * 0.35f * l * l
        each { it + tone }
        c[0] += v(Adjustment.WARMTH) * 0.1f
        c[2] -= v(Adjustment.WARMTH) * 0.1f
        c[1] -= v(Adjustment.TINT) * 0.1f // + is magenta, − is green
        val gray = luma()
        val saturation = 1f + v(Adjustment.SATURATION)
        each { gray + (it - gray) * saturation }
        // Vibrance boosts dull colours more than already strong ones.
        val strength = (max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))).coerceIn(0f, 1f)
        val vibrance = 1f + v(Adjustment.VIBRANCE) * (1 - strength)
        val gray2 = luma()
        each { gray2 + (it - gray2) * vibrance }
        // Fade lifts the blacks (+) or crushes them (−).
        val fade = v(Adjustment.FADE)
        if (fade > 0) each { fade * 0.2f + it * (1 - fade * 0.2f) } else if (fade < 0) each { (it + fade * 0.1f) / (1 + fade * 0.1f) }

        fun byte(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (byte(c[0]) shl 16) or (byte(c[1]) shl 8) or byte(c[2])
    }

    /** A [size]³ lookup table, indexed [r][g][b], for Media3's `SingleColorLut.createFromCube`. */
    fun lutCube(size: Int = 33): Array<Array<IntArray>> = Array(size) { r ->
        Array(size) { g -> IntArray(size) { b -> apply(r / (size - 1f), g / (size - 1f), b / (size - 1f)) } }
    }
}
