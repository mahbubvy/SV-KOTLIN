package com.secretvault.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secretvault.app.core.security.PinManager
import com.secretvault.app.core.security.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode {
    SETUP_ENTER_PIN,
    SETUP_CONFIRM_PIN,
    UNLOCK_PIN,
    LOCKED_OUT
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.UNLOCK_PIN,
    val pinLength: Int = 0,
    val title: String = "Enter PIN",
    val subtitle: String = "Access SecretVault",
    val errorMessage: String? = null,
    val hasError: Boolean = false,
    val isKeepUnlocked: Boolean = false,
    val isBiometricAvailable: Boolean = false,
    val remainingLockoutSeconds: Int = 0
)

sealed class AuthEvent {
    object UnlockSuccess : AuthEvent()
    object SetupSuccess : AuthEvent()
    object TriggerBiometric : AuthEvent()
}

class AuthViewModel(
    private val pinManager: PinManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AuthEvent>(replay = 1, extraBufferCapacity = 5)
    val events: SharedFlow<AuthEvent> = _events.asSharedFlow()

    private var currentEnteredPin = StringBuilder()
    private var setupFirstPin: String? = null
    private var lockoutCountdownJob: Job? = null

    init {
        val isPinConfigured = pinManager.isPinSet()
        val isLocked = pinManager.isLockedOut()
        val initialMode = when {
            isLocked -> AuthMode.LOCKED_OUT
            !isPinConfigured -> AuthMode.SETUP_ENTER_PIN
            else -> AuthMode.UNLOCK_PIN
        }
        _uiState.value = AuthUiState(
            mode = initialMode,
            pinLength = 0,
            title = getTitleForMode(initialMode),
            subtitle = getSubtitleForMode(initialMode),
            isBiometricAvailable = false,
            isKeepUnlocked = sessionManager.keepUnlocked.value
        )
        if (isLocked) {
            startLockoutCountdown()
        }
    }

    fun initialize(isBiometricAvailable: Boolean) {
        currentEnteredPin.clear()
        val isPinConfigured = pinManager.isPinSet()
        val isLocked = pinManager.isLockedOut()

        val mode = when {
            isLocked -> AuthMode.LOCKED_OUT
            !isPinConfigured -> AuthMode.SETUP_ENTER_PIN
            else -> AuthMode.UNLOCK_PIN
        }

        _uiState.value = _uiState.value.copy(
            mode = mode,
            pinLength = 0,
            hasError = false,
            errorMessage = null,
            title = getTitleForMode(mode),
            subtitle = getSubtitleForMode(mode),
            isBiometricAvailable = isBiometricAvailable && isPinConfigured && !isLocked,
            isKeepUnlocked = sessionManager.keepUnlocked.value
        )

        if (isLocked) {
            startLockoutCountdown()
        } else if (isBiometricAvailable && isPinConfigured) {
            viewModelScope.launch {
                _events.emit(AuthEvent.TriggerBiometric)
            }
        }
    }

    fun onDigit(digit: Char) {
        if (_uiState.value.mode == AuthMode.LOCKED_OUT || currentEnteredPin.length >= 4) {
            return
        }

        currentEnteredPin.append(digit)
        _uiState.value = _uiState.value.copy(
            pinLength = currentEnteredPin.length,
            hasError = false,
            errorMessage = null
        )

        if (currentEnteredPin.length == 4) {
            processEnteredPin(currentEnteredPin.toString())
        }
    }

    fun onBackspace() {
        if (currentEnteredPin.isNotEmpty()) {
            currentEnteredPin.deleteCharAt(currentEnteredPin.length - 1)
            _uiState.value = _uiState.value.copy(
                pinLength = currentEnteredPin.length,
                hasError = false,
                errorMessage = null
            )
        }
    }

    fun toggleKeepUnlocked(checked: Boolean) {
        _uiState.value = _uiState.value.copy(isKeepUnlocked = checked)
        sessionManager.setKeepUnlocked(checked)
    }

    fun onBiometricSuccess() {
        sessionManager.setKeepUnlocked(_uiState.value.isKeepUnlocked)
        sessionManager.unlock()
        viewModelScope.launch {
            _events.emit(AuthEvent.UnlockSuccess)
        }
    }

    private fun processEnteredPin(pin: String) {
        viewModelScope.launch {
            when (_uiState.value.mode) {
                AuthMode.SETUP_ENTER_PIN -> {
                    setupFirstPin = pin
                    currentEnteredPin.clear()
                    _uiState.value = _uiState.value.copy(
                        mode = AuthMode.SETUP_CONFIRM_PIN,
                        pinLength = 0,
                        title = "Confirm PIN",
                        subtitle = "Re-enter your 4-digit PIN"
                    )
                }
                AuthMode.SETUP_CONFIRM_PIN -> {
                    if (pin == setupFirstPin) {
                        pinManager.setupPin(pin)
                        sessionManager.setKeepUnlocked(_uiState.value.isKeepUnlocked)
                        sessionManager.unlock()
                        _events.emit(AuthEvent.SetupSuccess)
                    } else {
                        // Mismatch error
                        showError("PINs do not match. Try again.")
                        setupFirstPin = null
                        currentEnteredPin.clear()
                        _uiState.value = _uiState.value.copy(
                            mode = AuthMode.SETUP_ENTER_PIN,
                            pinLength = 0,
                            title = "Create PIN",
                            subtitle = "Enter a 4-digit master PIN"
                        )
                    }
                }
                AuthMode.UNLOCK_PIN -> {
                    if (pinManager.verifyPin(pin)) {
                        sessionManager.setKeepUnlocked(_uiState.value.isKeepUnlocked)
                        sessionManager.unlock()
                        _events.emit(AuthEvent.UnlockSuccess)
                    } else {
                        if (pinManager.isLockedOut()) {
                            _uiState.value = _uiState.value.copy(
                                mode = AuthMode.LOCKED_OUT,
                                pinLength = 0
                            )
                            startLockoutCountdown()
                        } else {
                            showError("Incorrect PIN")
                            currentEnteredPin.clear()
                            _uiState.value = _uiState.value.copy(pinLength = 0)
                        }
                    }
                }
                AuthMode.LOCKED_OUT -> Unit
            }
        }
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(
            hasError = true,
            errorMessage = message
        )
    }

    private fun startLockoutCountdown() {
        lockoutCountdownJob?.cancel()
        lockoutCountdownJob = viewModelScope.launch {
            while (pinManager.isLockedOut()) {
                val remaining = pinManager.getRemainingLockoutSeconds()
                _uiState.value = _uiState.value.copy(
                    remainingLockoutSeconds = remaining,
                    title = "Locked Out",
                    subtitle = "Try again in ${remaining}s",
                    errorMessage = "Too many failed attempts."
                )
                delay(1000L)
            }
            // Lockout ended
            currentEnteredPin.clear()
            _uiState.value = _uiState.value.copy(
                mode = AuthMode.UNLOCK_PIN,
                pinLength = 0,
                title = "Enter PIN",
                subtitle = "Access SecretVault",
                errorMessage = null,
                hasError = false,
                remainingLockoutSeconds = 0
            )
        }
    }

    private fun getTitleForMode(mode: AuthMode): String = when (mode) {
        AuthMode.SETUP_ENTER_PIN -> "Create PIN"
        AuthMode.SETUP_CONFIRM_PIN -> "Confirm PIN"
        AuthMode.UNLOCK_PIN -> "Enter PIN"
        AuthMode.LOCKED_OUT -> "Locked Out"
    }

    private fun getSubtitleForMode(mode: AuthMode): String = when (mode) {
        AuthMode.SETUP_ENTER_PIN -> "Enter a 4-digit master PIN"
        AuthMode.SETUP_CONFIRM_PIN -> "Re-enter your 4-digit PIN"
        AuthMode.UNLOCK_PIN -> "Access SecretVault"
        AuthMode.LOCKED_OUT -> "Too many failed attempts"
    }
}
