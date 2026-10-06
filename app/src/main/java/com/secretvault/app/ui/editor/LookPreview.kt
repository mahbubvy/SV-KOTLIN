package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import com.secretvault.app.core.processing.Adjustments
import com.secretvault.app.core.processing.BlurUniforms
import com.secretvault.app.core.processing.VideoProject

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

// The export's blur (BlurRegionsEffect.kt) in AGSL: the same regions, in the view's pixels.
private val BLUR_SHADER = """
uniform shader content;
uniform int count;
uniform float4 areas[${VideoProject.MAX_REGIONS}];
uniform float4 looks[${VideoProject.MAX_REGIONS}];

half4 main(float2 p) {
    half4 color = content.eval(p);
    for (int i = 0; i < ${VideoProject.MAX_REGIONS}; i++) {
        if (i >= count) break;
        float4 a = areas[i];
        float4 l = looks[i];
        float2 d = p - a.xy;
        float2 q = float2(d.x * l.x + d.y * l.y, d.y * l.x - d.x * l.y) / a.zw;
        bool inside = mod(l.z, 2.0) > 0.5 ? dot(q, q) <= 1.0 : abs(q.x) <= 1.0 && abs(q.y) <= 1.0;
        if (!inside) continue;
        if (l.z > 1.5) {
            half4 sum = half4(0.0);
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 4; y++) sum += content.eval(p + float2(float(x), float(y)) * (l.w / 4.0));
            }
            color = sum / 81.0;
        } else {
            color = content.eval((floor(p / l.w) + 0.5) * l.w);
        }
    }
    return color;
}
"""

/** The preview's blur regions, with one compiled shader reused for every frame. */
@RequiresApi(33)
internal class BlurPreview {
    private val shader = RuntimeShader(BLUR_SHADER)

    fun effect(u: BlurUniforms): RenderEffect? {
        if (u.count == 0) return null
        shader.setIntUniform("count", u.count)
        shader.setFloatUniform("areas", u.areas)
        shader.setFloatUniform("looks", u.looks)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
