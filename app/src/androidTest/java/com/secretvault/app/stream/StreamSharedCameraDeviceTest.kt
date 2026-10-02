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
import java.util.Base64
import com.secretvault.app.ui.stream.streamPreviewTransform
import android.media.MediaExtractor
import android.media.MediaFormat

@RunWith(AndroidJUnit4::class)
class StreamSharedCameraDeviceTest {
    @Test fun streamsTheActiveCameraPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        val native = InstrumentationRegistry.getArguments().getString("cameraSource") == "native"
        val secure = InstrumentationRegistry.getArguments().getString("securePairing") == "true"
        val changes = InstrumentationRegistry.getArguments().getString("cameraChanges") == "true"
        val seconds = InstrumentationRegistry.getArguments().getString("streamSeconds")?.toIntOrNull()?.coerceIn(10, 300) ?: 10
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
        lateinit var cmf: CmfHighFpsCameraView
        lateinit var viewer: TextureView
        val prefs = context.getSharedPreferences("stream_shared_device_test", android.content.Context.MODE_PRIVATE)
        val pins = StreamPinManager(context, prefs)
        val lenses = AtomicReference<List<CameraLensOption>>(emptyList())
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    if (role == "send") {
                        val gl = StreamPreviewRenderer(failure::set).also { renderer = it }
                        val manager = CameraManager(activity, (context.applicationContext as SecretVaultApp).mediaSaveQueue).also { camera = it }
                        preview = PreviewView(activity).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
                        cmf = CmfHighFpsCameraView(activity)
                        activity.setContentView(FrameLayout(activity).apply {
                            addView(preview, FrameLayout.LayoutParams(-1, -1))
                            if (native) { preview.visibility = android.view.View.GONE; addView(cmf, FrameLayout.LayoutParams(-1, -1)) }
                        })
                        (if (native) cmf else preview).doOnLayout {
                            manager.startCamera(activity, preview, cmf, if (native) CameraMode.VIDEO else CameraMode.PHOTO, LensFacing.BACK,
                                FlashMode.OFF, if (native) VideoMode.FHD_60 else VideoMode.FHD_30, VideoOrientation.PORTRAIT, false, null,
                                lenses::set, { _, _ -> }, { ready.countDown() }, { failure.set(AssertionError(it)); ready.countDown() }, gl)
                            if (native) preview.layout(0, 0, cmf.width, cmf.height)
                        }
                    } else {
                        val view = TextureView(activity).also { viewer = it }
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
                    if (secure) {
                        val pin = FIXTURE_PIN.toCharArray()
                        try { pins.setPin(pin) } finally { pin.fill('\u0000') }
                    }
                    session.sendShared(requireNotNull(renderer), 90, preview.width.toFloat() / preview.height, if (secure) pins else null)
                    await { if (secure) session.state.value.endpoint != null else session.state.value.invitation != null }
                    invitationFile.writeText(if (secure) "svstream2://secure/" + Base64.getUrlEncoder().withoutPadding().encodeToString(requireNotNull(session.state.value.endpoint).publicBytes()) else requireNotNull(session.state.value.invitation))
                } else if (secure) {
                    val endpoint = StreamEndpoint.fromPublicBytes(Base64.getUrlDecoder().decode(invitationFile.readText().trim().substringAfter("/secure/")))
                    session.view(endpoint, FIXTURE_PIN.toCharArray(), requireNotNull(surface))
                } else session.view(StreamInvitation.parse(invitationFile.readText().trim()), requireNotNull(surface))
                await(60000) { session.state.value.live }
                if (role == "view") scenario.onActivity {
                    viewer.setTransform(streamPreviewTransform(viewer.width, viewer.height, requireNotNull(session.state.value.config), false, 0))
                }
                await { if (role == "send") session.state.value.encodedFps > 0 else session.state.value.renderedFps > 0 }
                var minimum = Int.MAX_VALUE
                repeat(if (role == "send") seconds else seconds + if (changes) 15 else 5) {
                    SystemClock.sleep(1000)
                    val state = session.state.value
                    assertTrue("Shared camera stopped: ${state.message}", state.live)
                    val fps = if (role == "send") state.encodedFps else state.renderedFps
                    assertTrue("Shared preview produced no frames", fps > 0); minimum = minOf(minimum, fps)
                    failure.get()?.let { throw AssertionError("Preview renderer failed", it) }
                    if (changes && role == "send" && !native && it in listOf(2, 5, 8)) {
                        val lens = if (it == 5) lenses.get().firstOrNull { option -> option.id != null } else null
                        val rebound = CountDownLatch(1)
                        scenario.onActivity { activity ->
                            requireNotNull(camera).startCamera(activity, preview, cmf, CameraMode.PHOTO,
                                if (it == 2) LensFacing.FRONT else LensFacing.BACK, FlashMode.OFF, VideoMode.FHD_30,
                                VideoOrientation.PORTRAIT, false, lens?.physicalCameraId, lenses::set, { _, _ -> }, {
                                    requireNotNull(camera).setZoomRatio(lens?.zoomRatio ?: 1f) { error -> failure.set(AssertionError(error)) }
                                    session.refreshCameraFrame(); rebound.countDown()
                                }, { error -> failure.set(AssertionError(error)); rebound.countDown() }, renderer)
                        }
                        assertTrue("Camera rebind did not finish", rebound.await(10, TimeUnit.SECONDS))
                        SystemClock.sleep(1500)
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "Camera source change $it passed without a second camera or stream reconnect") })
                    }
                    if (it > 0 && it % 30 == 0) instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: ${it}s live; minimum $minimum FPS") })
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: shared preview; minimum $minimum FPS") })
                if (role == "send") {
                    if (!native) scenario.onActivity { assertEquals(PreviewView.StreamState.STREAMING, preview.previewStreamState.value) }
                    await(15000) { !session.state.value.busy }
                    if (native) {
                        val restored = CountDownLatch(1)
                        scenario.onActivity { activity ->
                            requireNotNull(camera).startCamera(activity, preview, cmf, CameraMode.VIDEO, LensFacing.BACK,
                                FlashMode.OFF, VideoMode.FHD_60, VideoOrientation.PORTRAIT, false, null, {}, { _, _ -> },
                                { restored.countDown() }, { failure.set(AssertionError(it)); restored.countDown() })
                        }
                        assertTrue("Ordinary CMF camera did not restart", restored.await(10, TimeUnit.SECONDS))
                        failure.get()?.let { throw it }
                        scenario.onActivity { assertTrue("CMF recording failed after Stop", cmf.startRecording()) }
                        SystemClock.sleep(2500)
                        val finished = CountDownLatch(1)
                        val recording = AtomicReference<File?>()
                        scenario.onActivity { cmf.stopRecording { file, _ -> recording.set(file); finished.countDown() } }
                        assertTrue(finished.await(10, TimeUnit.SECONDS))
                        val file = requireNotNull(recording.get()) { "Ordinary recording was empty after Stop" }
                        val extractor = MediaExtractor()
                        try {
                            extractor.setDataSource(file.absolutePath)
                            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                            val format = extractor.getTrackFormat(track)
                            assertEquals(1920, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(1080, format.getInteger(MediaFormat.KEY_HEIGHT))
                            extractor.selectTrack(track)
                            val first = extractor.sampleTime
                            var last = first; var count = 0
                            while (extractor.sampleTime >= 0) { last = extractor.sampleTime; count++; extractor.advance() }
                            val fps = (count - 1) * 1_000_000.0 / (last - first)
                            assertTrue("CMF ordinary recording lost 60 FPS: $fps", fps in 55.0..65.0)
                            instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "Ordinary CMF recording after Stop: 1920x1080, ${"%.1f".format(java.util.Locale.US, fps)} FPS; temporary test file deleted") })
                        } finally { extractor.release(); file.delete() }
                    }
                } else { session.close(); await { !session.state.value.busy } }
                scenario.onActivity { camera?.release() }
                renderer?.close()
            }
        } finally {
            session.close()
            instrumentation.runOnMainSync { camera?.release() }
            renderer?.close(); surface?.release(); invitationFile.delete(); prefs.edit().clear().commit()
        }
    }

    private fun await(timeout: Long = 10000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Shared stream did not reach the expected state", condition())
    }
    companion object { private const val FIXTURE_PIN = "9164" }
}
