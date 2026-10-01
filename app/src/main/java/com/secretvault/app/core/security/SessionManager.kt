package com.secretvault.app.core.security

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages runtime authentication state and "Keep Unlocked" session behavior.
 */
class SessionManager(
    private val preferences: SharedPreferences? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val bootCount: Int = 0,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        const val INACTIVITY_TIMEOUT_MS = 10 * 60_000L
        private const val KEY_EXPIRY = "keep_open_expiry"
        private const val KEY_BOOT = "keep_open_boot"
    }

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _keepUnlocked = MutableStateFlow(false)
    val keepUnlocked: StateFlow<Boolean> = _keepUnlocked.asStateFlow()

    private var externalPickerInProgress = false
    private var returningFromPicker = false
    private var idleDeadline: Long? = null
    private var expiryJob: Job? = null

    init {
        val deadline = preferences?.getLong(KEY_EXPIRY, 0L) ?: 0L
        val savedBoot = preferences?.getInt(KEY_BOOT, -1) ?: -1
        val remaining = deadline - clock()
        if (bootCount >= 0 && savedBoot == bootCount && remaining in 1..INACTIVITY_TIMEOUT_MS) {
            _keepUnlocked.value = true
            _isUnlocked.value = true
            idleDeadline = deadline
            scheduleExpiry()
        } else {
            persistKeepOpen()
        }
    }

    @Synchronized
    fun unlock() {
        _isUnlocked.value = true
    }

    @Synchronized
    fun lock() {
        _isUnlocked.value = false
        setKeepUnlocked(false)
    }

    @Synchronized
    fun setKeepUnlocked(enabled: Boolean) {
        if (enabled && !_isUnlocked.value) return
        if (!enabled && idleDeadline != null) _isUnlocked.value = false
        _keepUnlocked.value = enabled
        idleDeadline = null
        expiryJob?.cancel()
        expiryJob = null
        persistKeepOpen()
    }

    @Synchronized
    fun updateActivity() {
        if (_keepUnlocked.value && idleDeadline == null) persistKeepOpen()
    }

    @Synchronized
    fun setExternalPickerInProgress(inProgress: Boolean) {
        externalPickerInProgress = inProgress
    }

    @Synchronized
    fun onAppBackgrounded() {
        returningFromPicker = returningFromPicker || externalPickerInProgress
        if (_keepUnlocked.value) startIdleTimer()
        else if (!externalPickerInProgress) lock()
    }

    @Synchronized
    fun onScreenOff() {
        if (_keepUnlocked.value) startIdleTimer() else lock()
    }

    @Synchronized
    fun onAppForegrounded(): Boolean {
        expireIfNeeded()
        val openCamera = _keepUnlocked.value && _isUnlocked.value && !returningFromPicker
        returningFromPicker = false
        if (_keepUnlocked.value) {
            idleDeadline = null
            expiryJob?.cancel()
            expiryJob = null
            persistKeepOpen()
        }
        return openCamera
    }

    private fun startIdleTimer() {
        if (idleDeadline == null) {
            idleDeadline = clock() + INACTIVITY_TIMEOUT_MS
            persistKeepOpen()
            scheduleExpiry()
        }
        expireIfNeeded()
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        val deadline = idleDeadline ?: return
        expiryJob = scope.launch {
            delay((deadline - clock()).coerceAtLeast(0L))
            expireIfNeeded()
        }
    }

    @Synchronized
    private fun expireIfNeeded() {
        if (idleDeadline?.let { clock() >= it } == true) lock()
    }

    private fun persistKeepOpen() {
        val editor = preferences?.edit() ?: return
        if (_keepUnlocked.value) {
            // A bounded saved lease also expires if Android kills the background process.
            editor.putLong(KEY_EXPIRY, idleDeadline ?: (clock() + INACTIVITY_TIMEOUT_MS))
                .putInt(KEY_BOOT, bootCount)
        } else {
            editor.remove(KEY_EXPIRY).remove(KEY_BOOT)
        }
        editor.apply()
    }
}
