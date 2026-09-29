package com.secretvault.app.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages runtime authentication state and "Keep Unlocked" session behavior.
 */
class SessionManager {

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _keepUnlocked = MutableStateFlow(false)
    val keepUnlocked: StateFlow<Boolean> = _keepUnlocked.asStateFlow()

    private var lastActiveTimestamp: Long = System.currentTimeMillis()

    fun unlock() {
        _isUnlocked.value = true
        lastActiveTimestamp = System.currentTimeMillis()
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun setKeepUnlocked(enabled: Boolean) {
        _keepUnlocked.value = enabled
    }

    fun updateActivity() {
        lastActiveTimestamp = System.currentTimeMillis()
    }

    fun onAppBackgrounded() {
        lock()
    }

    fun onScreenOff() {
        // Strict privacy: screen off always locks
        lock()
    }
}
