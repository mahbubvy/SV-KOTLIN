package com.secretvault.app.stream

import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.io.DataOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class StreamCodecDeviceTest {
    @Test fun decodesGeneratedFixtureOverWifi() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        val fixture = File(context.cacheDir, "stream-fixture-${UUID.randomUUID()}.mp4")
        val extractor = MediaExtractor()
        try {
            if (role == "send") {
                instrumentation.context.assets.open("video-edit-fixture.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
                extractor.setDataSource(fixture.absolutePath)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                fun csd(name: String) = format.getByteBuffer(name)!!.duplicate().let { data -> ByteArray(data.remaining()).also(data::get) }
                val config = StreamConfig(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), 30, 0, csd("csd-0"), csd("csd-1"))
                val timeout = Executors.newSingleThreadScheduledExecutor()
                val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
                StreamTls.listen(address).use { host ->
                    timeout.schedule({ host.close() }, 60, TimeUnit.SECONDS)
                    try {
                        invitationFile.writeText(host.invitation.encode())
                        host.accept().use { socket ->
                            val output = DataOutputStream(socket.outputStream)
                            StreamProtocol.writeConfig(output, config)
                            val buffer = ByteBuffer.allocate(StreamProtocol.MAX_FRAME)
                            repeat(12) { index ->
                                buffer.clear()
                                val length = extractor.readSampleData(buffer, 0)
                                assertTrue(length > 0)
                                val bytes = ByteArray(length)
                                buffer.position(0); buffer.get(bytes)
                                StreamProtocol.writeFrame(output, StreamFrame(index.toLong(), extractor.sampleTime, extractor.sampleFlags and 1, bytes))
                                extractor.advance(); SystemClock.sleep(40)
                            }
                            assertEquals(-1, socket.inputStream.read())
                        }
                    } finally { timeout.shutdownNow() }
                }
            } else {
                val texture = SurfaceTexture(false)
                texture.setDefaultBufferSize(640, 360)
                val surface = Surface(texture)
                val session = StreamSession(context)
                try {
                    session.view(StreamInvitation.parse(invitationFile.readText()), surface)
                    val until = SystemClock.elapsedRealtime() + 5000
                    while (!session.state.value.live && session.state.value.busy && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
                    assertTrue("No rendered Wi-Fi image: ${session.state.value.message}", session.state.value.live)
                    assertEquals(640, session.state.value.config!!.width)
                    assertNotNull(session.state.value.startupMs)
                    SystemClock.sleep(600)
                    assertTrue("Fixture session ended before disconnect", session.state.value.live)
                    session.close()
                    val stoppedBy = SystemClock.elapsedRealtime() + 3000
                    while (session.state.value.busy && SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(20)
                    assertFalse("Session cleanup did not complete", session.state.value.busy)
                    assertFalse(session.state.value.live)
                    assertNull(session.state.value.invitation)
                } finally { session.close(); surface.release(); texture.release() }
            }
        } finally { extractor.release(); fixture.delete(); invitationFile.delete() }
    }

    @Test fun decodesGeneratedFixtureWithoutOpeningTheCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "stream-fixture-${UUID.randomUUID()}.mp4")
        val extractor = MediaExtractor()
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        val rendered = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        var decoder: StreamDecoder? = null
        try {
            instrumentation.context.assets.open("video-edit-fixture.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "video/avc" }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            fun csd(name: String): ByteArray = format.getByteBuffer(name)!!.duplicate().let { data -> ByteArray(data.remaining()).also(data::get) }
            val config = StreamConfig(format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), 30, 0, csd("csd-0"), csd("csd-1"))
            texture.setDefaultBufferSize(config.width, config.height)
            decoder = StreamDecoder(config, surface, { rendered.incrementAndGet() }, failure::set)
            val buffer = ByteBuffer.allocate(StreamProtocol.MAX_FRAME)
            repeat(12) { index ->
                if (rendered.get() > 0) return@repeat
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                assertTrue(size > 0)
                val bytes = ByteArray(size)
                buffer.position(0); buffer.get(bytes)
                decoder.offer(StreamFrame(index.toLong(), extractor.sampleTime, extractor.sampleFlags and 1, bytes))
                extractor.advance()
                SystemClock.sleep(40)
            }
            val until = SystemClock.elapsedRealtime() + 3000
            while (rendered.get() == 0 && failure.get() == null && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
            failure.get()?.let { throw AssertionError("Decoder failed", it) }
            assertTrue("Decoder did not render the fixture", rendered.get() > 0)
        } finally {
            decoder?.close(); extractor.release(); surface.release(); texture.release(); file.delete()
        }
    }
}
