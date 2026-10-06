package com.secretvault.app.core.processing

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A clip's blur regions showing at one moment, as the blur shaders take them (preview and export alike): per region,
 * [areas] holds centre x, centre y, half width, half height and [looks] holds cos and sin of the turn, the kind
 * (+1 oval, +2 blur rather than pixelate) and the block size or blur radius. All in pixels from the top-left.
 */
class BlurUniforms(val count: Int, val areas: FloatArray, val looks: FloatArray)

/**
 * [regions] (in source time) at [sourceMs], over a picture [width]×[height] pixels whose top-left is at [left],[top].
 * Strength is measured against the picture's short side, so a smaller preview looks like the full-size export.
 */
fun blurUniforms(regions: List<Sticker>, sourceMs: Long, left: Float, top: Float, width: Float, height: Float): BlurUniforms {
    val areas = FloatArray(VideoProject.MAX_REGIONS * 4)
    val looks = FloatArray(VideoProject.MAX_REGIONS * 4)
    val short = min(width, height)
    var n = 0
    for (b in regions) {
        val look = b.source as? StickerSource.Blur ?: continue
        if (sourceMs !in b.startMs until b.endMs || n == VideoProject.MAX_REGIONS) continue
        val p = b.placementAt(sourceMs)
        val halfW = p.widthFraction * width / 2
        val a = Math.toRadians(p.angle.toDouble())
        areas[n * 4] = left + p.centerX * width
        areas[n * 4 + 1] = top + p.centerY * height
        areas[n * 4 + 2] = halfW
        areas[n * 4 + 3] = halfW * p.stretch
        looks[n * 4] = cos(a).toFloat()
        looks[n * 4 + 1] = sin(a).toFloat()
        looks[n * 4 + 2] = (if (look.oval) 1f else 0f) + (if (look.style == BlurStyle.BLUR) 2f else 0f)
        looks[n * 4 + 3] = short * when (look.style) {
            BlurStyle.PIXELATE -> 0.015f + 0.06f * look.strength
            BlurStyle.BLUR -> 0.01f + 0.05f * look.strength
        }
        n++
    }
    return BlurUniforms(n, areas, looks)
}
