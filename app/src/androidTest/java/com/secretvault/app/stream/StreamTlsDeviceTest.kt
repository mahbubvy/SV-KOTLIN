package com.secretvault.app.stream

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretvault.app.core.stream.StreamInvitation
import com.secretvault.app.core.stream.StreamTls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import androidx.test.platform.app.InstrumentationRegistry
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.io.File
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class StreamTlsDeviceTest {
    @Test fun wifiRoundTrip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val file = File(context.cacheDir, "stream-test-invitation")
        if (role == "send") {
            val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
            val timeout = Executors.newSingleThreadScheduledExecutor()
            StreamTls.listen(address).use { host ->
                timeout.schedule({ host.close() }, 30, TimeUnit.SECONDS)
                try {
                    file.writeText(host.invitation.encode())
                    host.accept().use { socket ->
                        socket.outputStream.write(42)
                        assertEquals(43, socket.inputStream.read())
                    }
                } finally { file.delete(); timeout.shutdownNow() }
            }
        } else {
            try {
                StreamTls.connect(StreamInvitation.parse(file.readText()), network.socketFactory).use { socket ->
                    assertEquals(42, socket.inputStream.read())
                    socket.outputStream.write(43)
                }
            } finally { file.delete() }
        }
    }

    @Test fun validatesPairingRejectsSecondViewerAndCancelsAccept() {
        val executor = Executors.newSingleThreadExecutor()
        val host = StreamTls.listen(InetAddress.getLoopbackAddress())
        try {
            val initial = executor.submit<javax.net.ssl.SSLSocket> { host.accept() }
            StreamTls.connect(host.invitation).use { client ->
                initial.get(8, TimeUnit.SECONDS).use { server ->
                    server.outputStream.write(37)
                    assertEquals(37, client.inputStream.read())
                }
            }
            host.releasePeer()
            for (wrongPin in listOf(true, false)) {
                val rejection = executor.submit<Boolean> {
                    try { host.accept().close(); false } catch (_: IOException) { true }
                }
                val valid = host.invitation
                val wrong = StreamInvitation(valid.host, valid.port,
                    if (wrongPin) ByteArray(32) else valid.fingerprint,
                    if (wrongPin) valid.token else ByteArray(16))
                assertThrows(IOException::class.java) { StreamTls.connect(wrong).close() }
                assertTrue(rejection.get(8, TimeUnit.SECONDS))
            }
            val accept = executor.submit<javax.net.ssl.SSLSocket> { host.accept() }
            val paired = try { StreamTls.connect(host.invitation) }
            catch (error: IOException) {
                val server = runCatching { accept.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
                throw AssertionError("Client: ${error.stackTraceToString()}\nServer: ${server?.stackTraceToString()}")
            }
            paired.use { client ->
                accept.get(8, TimeUnit.SECONDS).use { server ->
                    server.outputStream.write(42)
                    assertEquals(42, client.inputStream.read())
                    val second = executor.submit<Boolean> {
                        try { host.accept().close(); false } catch (_: IOException) { true }
                    }
                    assertThrows(IOException::class.java) { StreamTls.connect(host.invitation).close() }
                    assertTrue(second.get(8, TimeUnit.SECONDS))
                }
            }
            host.releasePeer()
            val waiting = executor.submit<Boolean> {
                try { host.accept().close(); false } catch (_: IOException) { true }
            }
            host.close()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
            StreamTls.listen(InetAddress.getLoopbackAddress()).use { restarted ->
                assertFalse(host.invitation.token.contentEquals(restarted.invitation.token))
                assertFalse(host.invitation.fingerprint.contentEquals(restarted.invitation.fingerprint))
            }
        } finally { host.close(); executor.shutdownNow() }
    }
}
