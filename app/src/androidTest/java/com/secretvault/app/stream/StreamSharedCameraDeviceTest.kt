package com.secretvault.app.stream

import android.app.KeyguardManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.core.view.doOnLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.camera.*
import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StreamSharedCameraDeviceTest {
    @Test fun streamsTheActiveCameraPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        val native = InstrumentationRegistry.getArguments().getString("cameraSource") == "native"
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        assertFalse("Unlock the phone for the live camera check", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val session = StreamSession(context)
        val failure = AtomicReference<Throwable?>()
        val ready = CountDownLatch(1)
        var renderer: StreamPreviewRenderer? = null
        var camera: CameraManager? = null
        var surface: Surface? = null
        lateinit var preview: PreviewView
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    if (role == "send") {
                        val gl = StreamPreviewRenderer(failure::set).also { renderer = it }
                        val manager = CameraManager(activity, (context.applicationContext as SecretVaultApp).mediaSaveQueue).also { camera = it }
                        preview = PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        val cmf = CmfHighFpsCameraView(activity)
                        activity.setContentView(FrameLayout(activity).apply {
                            addView(preview, FrameLayout.LayoutParams(-1, -1))
                            if (native) { preview.visibility = android.view.View.GONE; addView(cmf, FrameLayout.LayoutParams(-1, -1)) }
                        })
                        (if (native) cmf else preview).doOnLayout {
                            manager.startCamera(activity, preview, cmf, if (native) CameraMode.VIDEO else CameraMode.PHOTO, LensFacing.BACK,
                                FlashMode.OFF, if (native) VideoMode.FHD_60 else VideoMode.FHD_30, VideoOrientation.PORTRAIT, false, null,
                                {}, { _, _ -> }, { ready.countDown() }, { failure.set(AssertionError(it)); ready.countDown() }, gl)
                            if (native) preview.layout(0, 0, cmf.width, cmf.height)
                        }
                    } else {
                        val view = TextureView(activity)
                        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                texture.setDefaultBufferSize(1280, 720); surface = Surface(texture); ready.countDown()
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) })
                    }
                }
                assertTrue("Preview surface did not start", ready.await(10, TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Shared camera failed", it) }
                if (role == "send") {
                    session.sendShared(requireNotNull(renderer), 90, preview.width.toFloat() / preview.height)
                    await { session.state.value.invitation != null }
                    invitationFile.writeText(requireNotNull(session.state.value.invitation))
                } else session.view(StreamInvitation.parse(invitationFile.readText().trim()), requireNotNull(surface))
                await(60000) { session.state.value.live }
                await { if (role == "send") session.state.value.encodedFps > 0 else session.state.value.renderedFps > 0 }
                var minimum = Int.MAX_VALUE
                repeat(if (role == "send") 10 else 15) {
                    SystemClock.sleep(1000)
                    val state = session.state.value
                    assertTrue("Shared camera stopped: ${state.message}", state.live)
                    val fps = if (role == "send") state.encodedFps else state.renderedFps
                    assertTrue("Shared preview produced no frames", fps > 0); minimum = minOf(minimum, fps)
                    failure.get()?.let { throw AssertionError("Preview renderer failed", it) }
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: shared preview; minimum $minimum FPS") })
                if (role == "send") {
                    if (!native) scenario.onActivity { assertEquals(PreviewView.StreamState.STREAMING, preview.previewStreamState.value) }
                    await(15000) { !session.state.value.busy }
                } else { session.close(); await { !session.state.value.busy } }
                scenario.onActivity { camera?.release() }
                renderer?.close()
            }
        } finally {
            session.close()
            instrumentation.runOnMainSync { camera?.release() }
            renderer?.close(); surface?.release(); invitationFile.delete()
        }
    }

    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Shared stream did not reach the expected state", condition())
    }
}
