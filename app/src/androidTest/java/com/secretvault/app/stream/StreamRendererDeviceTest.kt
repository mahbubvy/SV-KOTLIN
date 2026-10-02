package com.secretvault.app.stream

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.core.stream.*
import com.secretvault.app.ui.stream.streamPreviewTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StreamRendererDeviceTest {
    @Test fun generatedChartSurvivesGpuEncodingAndPortraitRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val failure = AtomicReference<Throwable?>()
        val surfacesReady = CountDownLatch(2)
        val frames = AtomicInteger()
        val configuration = AtomicReference<StreamConfig>()
        lateinit var local: TextureView
        lateinit var remote: TextureView
        val surfaces = mutableListOf<Surface>()
        var renderer: StreamPreviewRenderer? = null
        var input: StreamPreviewRenderer.NativeInput? = null
        var encoder: StreamEncoder? = null
        var decoder: StreamDecoder? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun view() = TextureView(activity).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(1280, 720); surfaces.add(Surface(texture)); surfacesReady.countDown()
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                    local = view(); remote = view()
                    activity.setContentView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(local, LinearLayout.LayoutParams(-1, 0, 1f))
                        addView(remote, LinearLayout.LayoutParams(-1, 0, 1f))
                    })
                }
                assertTrue(surfacesReady.await(5, TimeUnit.SECONDS))
                val gl = StreamPreviewRenderer(failure::set).also { renderer = it }
                input = gl.nativePreview(surfaces[0], Size(640, 360), Size(1280, 720))
                var sequence = 0L
                encoder = StreamEncoder(90, { config ->
                    configuration.set(config)
                    decoder = StreamDecoder(config, surfaces[1], { frames.incrementAndGet() }, failure::set)
                    instrumentation.runOnMainSync {
                        remote.setTransform(streamPreviewTransform(remote.width, remote.height, config, false, 0))
                    }
                }, { frame -> decoder?.offer(frame.copy(sequence = sequence++)) }, failure::set)
                gl.attachEncoder(requireNotNull(encoder).inputSurface, 90, 16f / 9)
                repeat(40) {
                    val canvas = requireNotNull(input).surface.lockCanvas(null)
                    val paint = Paint()
                    val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
                    colors.forEachIndexed { index, color ->
                        paint.color = color
                        val left = (index % 2) * 320f; val top = (index / 2) * 180f
                        canvas.drawRect(left, top, left + 320f, top + 180f, paint)
                    }
                    input!!.surface.unlockCanvasAndPost(canvas); SystemClock.sleep(40)
                    failure.get()?.let { throw AssertionError("Generated chart pipeline failed", it) }
                }
                assertTrue("Decoder did not display generated frames", frames.get() > 0)
                scenario.onActivity {
                    val raw = requireNotNull(remote.getBitmap(640, 360))
                    val bitmap = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                        android.graphics.Matrix().apply { postRotate(configuration.get().rotation.toFloat()) }, true)
                    raw.recycle()
                    try {
                        fun dominant(x: Int, y: Int): Int {
                            val color = bitmap.getPixel(x, y)
                            return listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).minBy { candidate ->
                                kotlin.math.abs(Color.red(color) - Color.red(candidate)) +
                                    kotlin.math.abs(Color.green(color) - Color.green(candidate)) +
                                    kotlin.math.abs(Color.blue(color) - Color.blue(candidate))
                            }
                        }
                        val actual = intArrayOf(dominant(166, 295), dominant(194, 295), dominant(166, 345), dominant(194, 345))
                        val localBitmap = requireNotNull(local.getBitmap(640, 360))
                        val localColors = try { intArrayOf(localBitmap.getPixel(160, 90), localBitmap.getPixel(480, 90),
                            localBitmap.getPixel(160, 270), localBitmap.getPixel(480, 270)) } finally { localBitmap.recycle() }
                        assertArrayEquals("Generated chart: local=${localColors.toList()} decoded=${actual.toList()}",
                            intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW), actual)
                    } finally { bitmap.recycle() }
                }
            }
        } finally {
            renderer?.let { runCatching { it.detachEncoder() } }
            encoder?.close(); decoder?.close(); input?.close(); renderer?.close(); surfaces.forEach(Surface::release)
        }
    }
}
