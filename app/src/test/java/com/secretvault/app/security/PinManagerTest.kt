package com.secretvault.app.security

import android.content.Context
import android.content.SharedPreferences
import com.secretvault.app.core.security.PinManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinManagerTest {

    private val testStorage = mutableMapOf<String, Any>()
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private lateinit var pinManager: PinManager

    @Before
    fun setUp() {
        testStorage.clear()
        mockPrefs = mockk()
        mockEditor = mockk(relaxed = true)
        mockContext = mockk()

        every { mockPrefs.contains(any()) } answers {
            testStorage.containsKey(firstArg<String>())
        }
        every { mockPrefs.getString(any(), any()) } answers {
            (testStorage[firstArg<String>()] as? String) ?: secondArg<String?>()
        }
        every { mockPrefs.getInt(any(), any()) } answers {
            (testStorage[firstArg<String>()] as? Int) ?: secondArg<Int>()
        }
        every { mockPrefs.getLong(any(), any()) } answers {
            (testStorage[firstArg<String>()] as? Long) ?: secondArg<Long>()
        }
        every { mockPrefs.getBoolean(any(), any()) } answers {
            (testStorage[firstArg<String>()] as? Boolean) ?: secondArg<Boolean>()
        }

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } answers {
            testStorage[firstArg<String>()] = secondArg<String>()
            mockEditor
        }
        every { mockEditor.putInt(any(), any()) } answers {
            testStorage[firstArg<String>()] = secondArg<Int>()
            mockEditor
        }
        every { mockEditor.putLong(any(), any()) } answers {
            testStorage[firstArg<String>()] = secondArg<Long>()
            mockEditor
        }
        every { mockEditor.putBoolean(any(), any()) } answers {
            testStorage[firstArg<String>()] = secondArg<Boolean>()
            mockEditor
        }

        pinManager = PinManager(mockContext, mockPrefs)
    }

    @Test
    fun testPinLifecycle() {
        assertFalse(pinManager.isPinSet())

        // Invalid lengths / non-digit
        assertFalse(pinManager.setupPin("123"))
        assertFalse(pinManager.setupPin("12345"))
        assertFalse(pinManager.setupPin("abcd"))

        // Setup valid 4-digit PIN
        assertTrue(pinManager.setupPin("1357"))
        assertTrue(pinManager.isPinSet())

        // Verify correct PIN
        assertTrue(pinManager.verifyPin("1357"))

        // Verify incorrect PIN
        assertFalse(pinManager.verifyPin("9999"))
        assertFalse(pinManager.verifyPin("0000"))

        // Change PIN
        assertTrue(pinManager.changePin("1357", "2468"))
        assertTrue(pinManager.verifyPin("2468"))
        assertFalse(pinManager.verifyPin("1357"))
    }

    @Test
    fun testLockoutAfterMaxFailures() {
        assertTrue(pinManager.setupPin("7777"))

        for (i in 1..PinManager.MAX_FAILED_ATTEMPTS) {
            pinManager.verifyPin("0000")
        }

        assertTrue(pinManager.isLockedOut())
        // Even with correct PIN, verification fails during lockout
        assertFalse(pinManager.verifyPin("7777"))
    }
}
