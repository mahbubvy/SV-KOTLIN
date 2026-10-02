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
        val pin = charArrayOf('4', '7', '2', '9', '1', '6')
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
        } finally { pin.fill('\u0000'); context.deleteSharedPreferences(name) }
    }
}
