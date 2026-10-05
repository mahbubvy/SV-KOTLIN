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
