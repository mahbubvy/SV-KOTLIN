package com.secretvault.app.stream

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.core.stream.StreamPinManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StreamPinDeviceTest {
    @Test fun pinStorageAndRetryBudgetSurviveNewManagerAndRejectCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stream_pin_test_${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        var now = 1_000_000L
        val manager = StreamPinManager(context, prefs) { now }
        val pin = charArrayOf('4', '7', '2', '9')
        try {
            assertNull(manager.readPin())
            manager.setPin(pin)
            val first = prefs.getString("pin", null)
            manager.setPin(pin)
            assertNotEquals("PIN encryption reused an IV", first, prefs.getString("pin", null))
            assertArrayEquals(pin, StreamPinManager(context, prefs).readPin())
            assertFalse(prefs.all.values.any { it == pin.concatToString() })
            repeat(5) { manager.beginPairingAttempt() }
            val restarted = StreamPinManager(context, prefs) { now }
            assertEquals(30_000, restarted.cooldownMillis())
            assertThrows(java.io.IOException::class.java) { restarted.beginPairingAttempt() }
            now += 30_001; restarted.beginPairingAttempt(); restarted.pairingSucceeded()
            assertEquals(0, restarted.cooldownMillis())
            prefs.edit().putString("pin", "broken").commit()
            assertThrows(java.io.IOException::class.java) { restarted.readPin() }
            assertThrows(IllegalArgumentException::class.java) { restarted.setPin(charArrayOf('1')) }
            assertThrows(IllegalArgumentException::class.java) { restarted.setPin(charArrayOf('1', '2', '3', '4', '5', '6')) }
            val legacy = byteArrayOf(49, 50, 51, 52, 53, 54)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, com.secretvault.app.core.crypto.KeyStoreManager(masterKeyAlias = "sv_stream_pin_key").getOrCreateMasterKey())
            cipher.updateAAD("SV streaming PIN v2".toByteArray(Charsets.US_ASCII))
            prefs.edit().putString("pin", java.util.Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(legacy))).commit()
            legacy.fill(0)
            assertNull("Old six-digit PIN must require explicit replacement", restarted.readPin())
            assertFalse(restarted.isConfigured())
            restarted.setPin(pin)
            assertTrue(restarted.isConfigured())
            assertArrayEquals(pin, restarted.readPin())
        } finally { pin.fill('\u0000'); context.deleteSharedPreferences(name) }
    }
}
