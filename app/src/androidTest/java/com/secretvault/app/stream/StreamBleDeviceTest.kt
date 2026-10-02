package com.secretvault.app.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Inet4Address
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StreamBleDeviceTest {
    @Test fun bluetoothAndWifiFindTheSamePinAuthenticatedSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        assertTrue("Grant the Bluetooth role permissions for this hardware check", StreamBleDiscovery.allowed(context, role == "send"))
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.first { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val address = cm.getLinkProperties(wifi)!!.linkAddresses.first { it.address is Inet4Address }.address
        val ble = StreamBleDiscovery(context)
        val nsd = StreamDiscovery(context)
        val sameNameNsd = StreamDiscovery(context)
        val prefs = context.getSharedPreferences("stream_ble_device_test", Context.MODE_PRIVATE)
        val pins = StreamPinManager(context, prefs)
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        val executor = Executors.newSingleThreadExecutor()
        var host: StreamTls.Host? = null
        var sameNameHost: StreamTls.Host? = null
        try {
            if (role == "send") {
                val pin = FIXTURE_PIN.toCharArray()
                try { pins.setPin(pin) } finally { pin.fill('\u0000') }
                val listener = StreamTls.listen(address, pins).also { host = it }
                val endpoint = requireNotNull(listener.endpoint)
                val other = StreamTls.listen(address, pins).also { sameNameHost = it }.endpoint!!
                instrumentation.runOnMainSync { ble.advertise(endpoint); nsd.advertise(endpoint); sameNameNsd.advertise(other) }
                await { ble.state.value.message == "Bluetooth discovery on" }
                invitationFile.writeText("svstream2://ble/" + Base64.getUrlEncoder().withoutPadding().encodeToString(endpoint.publicBytes()) + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(other.publicBytes()))
                executor.submit {
                    listener.accept().use { peer ->
                        assertEquals(0x42544532, DataInputStream(peer.inputStream).readInt())
                        DataOutputStream(peer.outputStream).apply { writeInt(0x42544532); flush() }
                    }
                }.get(30, TimeUnit.SECONDS)
            } else {
                val details = invitationFile.readText().trim().substringAfter("/ble/").split('/')
                val expected = StreamEndpoint.fromPublicBytes(Base64.getUrlDecoder().decode(details[0]))
                val other = StreamEndpoint.fromPublicBytes(Base64.getUrlDecoder().decode(details[1]))
                val began = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { ble.search(); nsd.search() }
                await { ble.state.value.cameras.any { it.sessionId == expected.sessionId } }
                val endpoint = ble.state.value.cameras.first { it.sessionId == expected.sessionId }
                assertEquals(expected, endpoint)
                val bluetoothMs = SystemClock.elapsedRealtime() - began
                await { nsd.state.value.cameras.any { it.sessionId == expected.sessionId } }
                await { nsd.state.value.cameras.any { it.sessionId == other.sessionId } }
                assertEquals("NSD name conflicts must be visible as distinct labels", 2, nsd.state.value.cameras.filter { it.sessionId in setOf(expected.sessionId, other.sessionId) }.map { it.name }.toSet().size)
                assertEquals(1, (ble.state.value.cameras + nsd.state.value.cameras).distinctBy { it.sessionId }.count { it.sessionId == expected.sessionId })
                instrumentation.runOnMainSync { ble.stopSearching(); nsd.stopSearching() }
                StreamTls.connect(endpoint, FIXTURE_PIN.toCharArray(), wifi.socketFactory).use { peer ->
                    DataOutputStream(peer.outputStream).apply { writeInt(0x42544532); flush() }
                    assertEquals(0x42544532, DataInputStream(peer.inputStream).readInt())
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "BLE-only discovery ${bluetoothMs}ms; Wi-Fi/BLE deduplicated; PIN-authenticated TLS passed") })
            }
        } finally {
            instrumentation.runOnMainSync { ble.close(); nsd.close(); sameNameNsd.close() }
            host?.close(); sameNameHost?.close(); executor.shutdownNow(); invitationFile.delete(); prefs.edit().clear().commit()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 12000
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
        assertTrue("Bluetooth discovery did not reach the expected state", condition())
    }
    companion object { private const val FIXTURE_PIN = "9164" }
}
