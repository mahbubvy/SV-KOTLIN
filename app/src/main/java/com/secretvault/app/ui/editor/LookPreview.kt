package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.secretvault.app.core.processing.Adjustments

private const val LUT_SIZE = 33

// Looks each pixel up in the clip's colour table: slices of blue side by side, red across a slice, green down it.
// Mixing the two nearest blue slices (and the bitmap's own linear filtering inside a slice) makes it trilinear.
private const val LUT_SHADER = """
uniform shader content;
uniform shader lut;
uniform float size;

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 rgb = clamp(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, 1.0) * (size - 1.0);
    float b0 = floor(rgb.b);
    float b1 = min(b0 + 1.0, size - 1.0);
    half3 lo = lut.eval(float2(b0 * size + rgb.r + 0.5, rgb.g + 0.5)).rgb;
    half3 hi = lut.eval(float2(b1 * size + rgb.r + 0.5, rgb.g + 0.5)).rgb;
    return half4(mix(lo, hi, half(rgb.b - b0)) * c.a, c.a);
}
"""

/**
 * The preview's version of a clip's colour adjustments: a shader over the player's view that applies the same lookup
 * table the export gives Media3, so both look alike. Media3 1.5.1's own player effects stall a clipped playlist.
 */
@RequiresApi(33)
internal fun lookEffect(adjustments: Adjustments): RenderEffect {
    val cube = adjustments.lutCube(LUT_SIZE)
    val pixels = IntArray(LUT_SIZE * LUT_SIZE * LUT_SIZE)
    for (r in 0 until LUT_SIZE) for (g in 0 until LUT_SIZE) for (b in 0 until LUT_SIZE) {
        pixels[g * LUT_SIZE * LUT_SIZE + b * LUT_SIZE + r] = cube[r][g][b]
    }
    val lut = Bitmap.createBitmap(pixels, LUT_SIZE * LUT_SIZE, LUT_SIZE, Bitmap.Config.ARGB_8888)
    val shader = RuntimeShader(LUT_SHADER).apply {
        setInputShader("lut", BitmapShader(lut, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        setFloatUniform("size", LUT_SIZE.toFloat())
    }
    return RenderEffect.createRuntimeShaderEffect(shader, "content")
}

// The photo editor's colour and grain on the GPU: the same table lookup as above, then grain on a grid of [cell]-pixel
// squares. The grain pattern differs from the saved photo's (a different random), its look doesn't.
private const val PHOTO_SHADER = """
uniform shader content;
uniform shader lut;
uniform float size;
uniform float useTable;
uniform float grain;
uniform float cell;

float noise(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) * 2.0 - 1.0; }

half4 main(float2 p) {
    half4 c = content.eval(p);
    float3 rgb = clamp(float3(c.rgb) / max(float(c.a), 0.0001), 0.0, 1.0);
    if (useTable > 0.5) {
        float3 at = rgb * (size - 1.0);
        float b0 = floor(at.b);
        float b1 = min(b0 + 1.0, size - 1.0);
        float3 lo = float3(lut.eval(float2(b0 * size + at.r + 0.5, at.g + 0.5)).rgb);
        float3 hi = float3(lut.eval(float2(b1 * size + at.r + 0.5, at.g + 0.5)).rgb);
        rgb = mix(lo, hi, at.b - b0);
    }
    if (grain > 0.0) rgb = clamp(rgb + noise(floor(p / cell)) * grain, 0.0, 1.0);
    return half4(half3(rgb) * c.a, c.a);
}
"""

/** The photo editor's GPU preview of colour and grain, with one compiled shader reused for every change. */
@RequiresApi(33)
internal class PhotoLook {
    private val shader = RuntimeShader(PHOTO_SHADER)

    /**
     * [table] is [PhotoEdit.colourTable] at [size] (null for no colour change); [grain] is 0..100 and [cell] the size
     * of a grain in screen pixels. Null when there's nothing to do.
     */
    @Synchronized
    fun effect(table: IntArray?, size: Int, grain: Int, cell: Float): RenderEffect? {
        if (table == null && grain == 0) return null
        val lut = table ?: IntArray(1)
        val lutSize = if (table == null) 1 else size
        // Slices of blue side by side, red across a slice, green down it, as the shader reads them.
        val pixels = IntArray(lutSize * lutSize * lutSize)
        for (r in 0 until lutSize) for (g in 0 until lutSize) for (b in 0 until lutSize) {
            pixels[g * lutSize * lutSize + b * lutSize + r] = lut[(r * lutSize + g) * lutSize + b]
        }
        val bitmap = Bitmap.createBitmap(pixels, lutSize * lutSize, lutSize, Bitmap.Config.ARGB_8888)
        shader.setInputShader("lut", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR })
        shader.setFloatUniform("size", lutSize.toFloat())
        shader.setFloatUniform("useTable", if (table == null) 0f else 1f)
        shader.setFloatUniform("grain", grain / 100f * 0.16f)
        shader.setFloatUniform("cell", cell.coerceAtLeast(1f))
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
