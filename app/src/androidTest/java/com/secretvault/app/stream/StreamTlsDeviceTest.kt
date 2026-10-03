package com.secretvault.app.stream

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretvault.app.core.stream.StreamInvitation
import com.secretvault.app.core.stream.StreamTls
import com.secretvault.app.core.stream.StreamPinManager
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
    @Test fun openStreamingWorksWithoutPinAndCannotDowngradeProtectedListener() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "stream_open_test_${java.util.UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
        val pins = StreamPinManager(context, prefs)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
        val executor = Executors.newSingleThreadExecutor()
        val pin = charArrayOf('7', '4', '2', '6')
        try {
            pins.setPinRequired(false)
            assertFalse(pins.isConfigured())
            StreamTls.listen(address, pins).use { host ->
                val endpoint = requireNotNull(host.endpoint)
                assertFalse(endpoint.requiresPin)
                val accepted = executor.submit<javax.net.ssl.SSLSocket> { host.accept() }
                StreamTls.connect(endpoint).use { client ->
                    accepted.get(8, TimeUnit.SECONDS).use { server ->
                        server.outputStream.write(42); server.outputStream.flush()
                        assertEquals(42, client.inputStream.read())
                    }
                }
            }
            pins.setPin(pin); pins.setPinRequired(true)
            StreamTls.listen(address, pins).use { host ->
                val endpoint = requireNotNull(host.endpoint)
                assertTrue(endpoint.requiresPin)
                val rejected = executor.submit<Boolean> {
                    try { host.accept().close(); false } catch (_: IOException) { true }
                }
                assertThrows(IOException::class.java) { StreamTls.connect(endpoint.copy(requiresPin = false)).close() }
                assertTrue("Open handshake bypassed required PIN", rejected.get(8, TimeUnit.SECONDS))
                val accepted = executor.submit<javax.net.ssl.SSLSocket> { host.accept() }
                StreamTls.connect(endpoint, pin.copyOf()).use { client ->
                    accepted.get(8, TimeUnit.SECONDS).use { server ->
                        server.outputStream.write(43); server.outputStream.flush()
                        assertEquals(43, client.inputStream.read())
                    }
                }
            }
        } finally { pin.fill('\u0000'); executor.shutdownNow(); context.deleteSharedPreferences(prefsName) }
    }

    @Test fun pinPairingRejectsWrongPinAndChangedSessionBeforeMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val address = cm.getLinkProperties(network)!!.linkAddresses.first { it.address is Inet4Address }.address
        val name = "stream_pairing_test_${java.util.UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val pins = com.secretvault.app.core.stream.StreamPinManager(context, prefs)
        val executor = Executors.newSingleThreadExecutor()
        pins.setPin(charArrayOf('4', '7', '2', '9'))
        try {
            StreamTls.listen(address, pins).use { host ->
                val endpoint = requireNotNull(host.endpoint)
                val first = executor.submit<javax.net.ssl.SSLSocket> { host.accept() }
                val since = android.os.SystemClock.elapsedRealtime()
                StreamTls.connect(endpoint, charArrayOf('4', '7', '2', '9'), network.socketFactory).use { client ->
                    first.get(15, TimeUnit.SECONDS).use { server -> server.outputStream.write(42); assertEquals(42, client.inputStream.read()) }
                }
                instrumentation.sendStatus(0, android.os.Bundle().apply { putLong("streamPairingMs", android.os.SystemClock.elapsedRealtime() - since) })
                host.releasePeer()
                for (changedSession in listOf(false, true)) {
                    val rejection = executor.submit<Boolean> { try { host.accept().close(); false } catch (_: IOException) { true } }
                    val peer = if (changedSession) endpoint.copy(sessionId = "f".repeat(32)) else endpoint
                    assertThrows(IOException::class.java) { StreamTls.connect(peer,
                        if (changedSession) charArrayOf('4', '7', '2', '9') else charArrayOf('4', '7', '2', '8'),
                        network.socketFactory).close() }
                    assertTrue(rejection.get(15, TimeUnit.SECONDS))
                }
                val downgrade = executor.submit<Boolean> { try { host.accept().close(); false } catch (_: IOException) { true } }
                assertThrows(IOException::class.java) { StreamTls.connect(host.invitation, network.socketFactory).close() }
                assertTrue(downgrade.get(15, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow(); context.deleteSharedPreferences(name) }
    }

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
