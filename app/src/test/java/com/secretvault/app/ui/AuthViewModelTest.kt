package com.secretvault.app.ui

import com.secretvault.app.core.security.PinManager
import com.secretvault.app.core.security.SessionManager
import com.secretvault.app.ui.auth.AuthEvent
import com.secretvault.app.ui.auth.AuthMode
import com.secretvault.app.ui.auth.AuthViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockPinManager: PinManager
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockPinManager = mockk(relaxed = true)
        sessionManager = SessionManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testFirstTimeLaunchStartsInSetupMode() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns false
        every { mockPinManager.isLockedOut() } returns false

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = false)

        assertEquals(AuthMode.SETUP_ENTER_PIN, viewModel.uiState.value.mode)
        assertEquals(0, viewModel.uiState.value.pinLength)
    }

    @Test
    fun testSetupPinSuccessFlow() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns false
        every { mockPinManager.isLockedOut() } returns false
        every { mockPinManager.setupPin("4321") } returns true

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = false)

        var emittedEvent: AuthEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { emittedEvent = it }
        }

        // Enter PIN first time
        "4321".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()

        // Should transition to confirm
        assertEquals(AuthMode.SETUP_CONFIRM_PIN, viewModel.uiState.value.mode)
        assertEquals(0, viewModel.uiState.value.pinLength)

        // Enter matching confirm PIN
        "4321".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()

        verify { mockPinManager.setupPin("4321") }
        assertTrue(sessionManager.isUnlocked.value)
        assertEquals(AuthEvent.SetupSuccess, emittedEvent)
        job.cancel()
    }

    @Test
    fun testSetupPinMismatchResetsToEnterPin() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns false
        every { mockPinManager.isLockedOut() } returns false

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = false)

        // Enter PIN first time
        "1111".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()

        assertEquals(AuthMode.SETUP_CONFIRM_PIN, viewModel.uiState.value.mode)

        // Enter mismatching confirm PIN
        "2222".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()

        assertEquals(AuthMode.SETUP_ENTER_PIN, viewModel.uiState.value.mode)
        assertTrue(viewModel.uiState.value.hasError)
        assertEquals("PINs do not match. Try again.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testUnlockWithValidPin() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns true
        every { mockPinManager.isLockedOut() } returns false
        every { mockPinManager.verifyPin("9876") } returns true

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = false)

        var emittedEvent: AuthEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { emittedEvent = it }
        }

        assertEquals(AuthMode.UNLOCK_PIN, viewModel.uiState.value.mode)

        "9876".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()

        assertTrue(sessionManager.isUnlocked.value)
        assertEquals(AuthEvent.UnlockSuccess, emittedEvent)
        job.cancel()
    }

    @Test
    fun unlockingPreservesKeepOpenChoiceFromHome() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns true
        every { mockPinManager.isLockedOut() } returns false

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = false)

        assertFalse(sessionManager.keepUnlocked.value)

        sessionManager.unlock()
        sessionManager.setKeepUnlocked(true)
        every { mockPinManager.verifyPin("1234") } returns true
        "1234".forEach { viewModel.onDigit(it) }
        advanceUntilIdle()
        assertTrue(sessionManager.isUnlocked.value)
        assertTrue(sessionManager.keepUnlocked.value)
    }

    @Test
    fun testBiometricSuccessUnlocksSession() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns true
        every { mockPinManager.isLockedOut() } returns false
        every { mockPinManager.isBiometricEnabled() } returns true

        viewModel = AuthViewModel(mockPinManager, sessionManager)
        viewModel.initialize(isBiometricAvailable = true)

        var emittedEvent: AuthEvent? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { emittedEvent = it }
        }

        viewModel.onBiometricSuccess()
        advanceUntilIdle()

        assertTrue(sessionManager.isUnlocked.value)
        assertEquals(AuthEvent.UnlockSuccess, emittedEvent)
        job.cancel()
    }

    @Test
    fun fingerprintPreferenceControlsPromptAndLateUnlockCallback() = runTest(testDispatcher) {
        every { mockPinManager.isPinSet() } returns true
        every { mockPinManager.isLockedOut() } returns false
        every { mockPinManager.isBiometricEnabled() } returns false
        viewModel = AuthViewModel(mockPinManager, sessionManager)
        var emittedEvent: AuthEvent? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { emittedEvent = it }
        }

        viewModel.initialize(isBiometricAvailable = true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBiometricAvailable)
        assertEquals(null, emittedEvent)
        viewModel.onBiometricSuccess()
        assertFalse(sessionManager.isUnlocked.value)

        every { mockPinManager.isBiometricEnabled() } returns true
        viewModel.initialize(isBiometricAvailable = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isBiometricAvailable)
        assertEquals(AuthEvent.TriggerBiometric, emittedEvent)

        every { mockPinManager.isBiometricEnabled() } returns false
        viewModel.onBiometricSuccess()
        assertFalse(sessionManager.isUnlocked.value)
        every { mockPinManager.isBiometricEnabled() } returns true
        every { mockPinManager.isLockedOut() } returns true
        viewModel.onBiometricSuccess()
        assertFalse(sessionManager.isUnlocked.value)
    }
}
