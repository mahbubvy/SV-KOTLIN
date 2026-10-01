package com.secretvault.app.security

import android.content.SharedPreferences
import com.secretvault.app.core.security.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    @Test
    fun tenMinutesAwayDisablesKeepOpenAndRequiresAuthentication() = runTest {
        val session = SessionManager(clock = { testScheduler.currentTime }, scope = backgroundScope)
        session.setKeepUnlocked(true)
        assertFalse(session.keepUnlocked.value)
        session.unlock()
        session.setKeepUnlocked(true)
        session.onAppBackgrounded()

        advanceTimeBy(SessionManager.INACTIVITY_TIMEOUT_MS - 1)
        assertTrue(session.isUnlocked.value)
        session.onScreenOff()
        advanceTimeBy(1)
        runCurrent()
        assertFalse(session.keepUnlocked.value)
        assertFalse(session.isUnlocked.value)
        assertFalse(session.onAppForegrounded())
    }

    @Test
    fun returnResetsTimeoutAndPickerReturnDoesNotRedirectToCamera() = runTest {
        val session = SessionManager(clock = { testScheduler.currentTime }, scope = backgroundScope)
        session.unlock()
        session.setKeepUnlocked(true)
        session.onScreenOff()
        advanceTimeBy(5 * 60_000L)
        assertTrue(session.onAppForegrounded())
        advanceTimeBy(SessionManager.INACTIVITY_TIMEOUT_MS)
        runCurrent()
        assertTrue(session.isUnlocked.value)

        session.setExternalPickerInProgress(true)
        session.onAppBackgrounded()
        session.setExternalPickerInProgress(false)
        assertFalse(session.onAppForegrounded())
        assertTrue(session.isUnlocked.value)
        session.onAppBackgrounded()
        advanceTimeBy(SessionManager.INACTIVITY_TIMEOUT_MS)
        runCurrent()
        assertFalse(session.keepUnlocked.value)
    }

    @Test
    fun savedKeepOpenRestoresOnlyBeforeOriginalDeadlineAndOnSameBoot() = runTest {
        val values = mutableMapOf<String, Any>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.getLong(any(), any()) } answers { values[firstArg<String>()] as? Long ?: secondArg<Long>() }
        every { prefs.getInt(any(), any()) } answers { values[firstArg<String>()] as? Int ?: secondArg<Int>() }
        every { prefs.edit() } returns editor
        every { editor.putLong(any(), any()) } answers { values[firstArg<String>()] = secondArg<Long>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg<String>()] = secondArg<Int>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        var elapsed = 100_000L
        val original = SessionManager(prefs, { elapsed }, 7, backgroundScope)
        original.unlock()
        original.setKeepUnlocked(true)
        original.onAppBackgrounded()

        elapsed += 9 * 60_000L
        val restarted = SessionManager(prefs, { elapsed }, 7, backgroundScope)
        assertTrue(restarted.keepUnlocked.value)
        assertTrue(restarted.isUnlocked.value)
        elapsed += 60_000L
        assertFalse(restarted.onAppForegrounded())
        assertFalse(restarted.keepUnlocked.value)
        assertFalse(restarted.isUnlocked.value)
        assertFalse(SessionManager(prefs, { elapsed }, 7, backgroundScope).isUnlocked.value)

        original.unlock()
        original.setKeepUnlocked(true)
        val rebooted = SessionManager(prefs, { elapsed }, 8, backgroundScope)
        assertFalse(rebooted.keepUnlocked.value)
        assertFalse(rebooted.isUnlocked.value)
    }

    @Test
    fun keepOpenSurvivesScreenLockButManualLockDisablesIt() {
        val session = SessionManager()
        session.unlock()
        session.setKeepUnlocked(true)
        session.onAppBackgrounded()
        assertTrue(session.isUnlocked.value)

        session.setExternalPickerInProgress(true)
        session.onScreenOff()
        assertTrue(session.isUnlocked.value)
        assertTrue(session.keepUnlocked.value)

        session.lock()
        assertFalse(session.isUnlocked.value)
        assertFalse(session.keepUnlocked.value)
        session.unlock()
        session.setExternalPickerInProgress(false)
        session.setKeepUnlocked(false)
        session.onAppBackgrounded()
        assertFalse(session.isUnlocked.value)
    }

    @Test
    fun backgroundingLocksExceptWhileExternalPickerIsOpen() {
        val session = SessionManager()
        session.unlock()

        session.onAppBackgrounded()
        assertFalse(session.isUnlocked.value)

        session.unlock()
        session.setExternalPickerInProgress(true)
        session.onAppBackgrounded()
        assertTrue(session.isUnlocked.value)

        session.setExternalPickerInProgress(false)
        session.onAppBackgrounded()
        assertFalse(session.isUnlocked.value)
    }
}
