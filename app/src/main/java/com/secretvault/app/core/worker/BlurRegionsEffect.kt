package com.secretvault.app.core.worker

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.processing.blurUniforms

private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

// Same regions as the preview's shader (LookPreview.kt): pixels from the top-left, turned into each region's own axes.
private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform int uCount;
uniform vec4 uAreas[${VideoProject.MAX_BLURS}];
uniform vec4 uLooks[${VideoProject.MAX_BLURS}];
varying vec2 vTexSamplingCoord;

vec4 at(vec2 p) { return texture2D(uTexSampler, vec2(p.x / uSize.x, 1.0 - p.y / uSize.y)); }

void main() {
    vec2 p = vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y) * uSize;
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    for (int i = 0; i < ${VideoProject.MAX_BLURS}; i++) {
        if (i >= uCount) break;
        vec4 a = uAreas[i];
        vec4 l = uLooks[i];
        vec2 d = p - a.xy;
        vec2 q = vec2(d.x * l.x + d.y * l.y, d.y * l.x - d.x * l.y) / a.zw;
        bool inside = mod(l.z, 2.0) > 0.5 ? dot(q, q) <= 1.0 : abs(q.x) <= 1.0 && abs(q.y) <= 1.0;
        if (!inside) continue;
        if (l.z > 1.5) {
            vec4 sum = vec4(0.0);
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 4; y++) sum += at(p + vec2(float(x), float(y)) * (l.w / 4.0));
            }
            color = sum / 81.0;
        } else {
            color = at((floor(p / l.w) + 0.5) * l.w);
        }
    }
    gl_FragColor = color;
}
"""

/**
 * Pixelates or blurs one clip's blur regions on its own upright frames, before they're fitted into the output, so
 * the regions use the clip's frame and source time. Times count from the first frame this clip's program sees, which
 * is the clip's start.
 */
@OptIn(UnstableApi::class)
internal class BlurRegionsEffect(private val blurs: List<Sticker>, private val clipStartMs: Long) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(blurs, clipStartMs)

    private class Program(private val blurs: List<Sticker>, private val clipStartMs: Long) : BaseGlShaderProgram(false, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var width = 0
        private var height = 0
        private var firstUs = C.TIME_UNSET

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
            val u = blurUniforms(blurs, clipStartMs + (presentationTimeUs - firstUs) / 1_000, 0f, 0f, width.toFloat(), height.toFloat())
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                program.setIntUniform("uCount", u.count)
                program.bindAttributesAndUniforms()
                // Arrays go straight to GL; GlProgram sets one element at a time.
                GLES20.glUniform4fv(program.getUniformLocation("uAreas"), VideoProject.MAX_BLURS, u.areas, 0)
                GLES20.glUniform4fv(program.getUniformLocation("uLooks"), VideoProject.MAX_BLURS, u.looks, 0)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
        }
    }
}
