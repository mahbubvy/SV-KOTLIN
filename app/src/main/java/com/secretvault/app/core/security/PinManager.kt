package com.secretvault.app.core.security

import android.content.Context
import android.content.SharedPreferences
import com.secretvault.app.core.crypto.SecureMemory
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Handles PIN setup, verification, and PBKDF2 hashing with salt.
 */
class PinManager(
    private val context: Context,
    private val preferences: SharedPreferences? = null
) {

    companion object {
        private const val PREFS_NAME = "vault_security_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_TIMESTAMP = "lockout_timestamp"

        private const val PBKDF2_ITERATIONS = 100_000
        private const val HASH_KEY_LENGTH = 256
        private const val SALT_LENGTH = 32
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L // 30 seconds lockout after 5 fails
    }

    private val secureRandom = SecureRandom()

    private val prefs: SharedPreferences by lazy {
        preferences ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks whether a PIN has already been configured.
     */
    fun isPinSet(): Boolean {
        return prefs.contains(KEY_PIN_HASH) && prefs.contains(KEY_PIN_SALT)
    }

    /**
     * Sets up a new 4-digit PIN.
     */
    fun setupPin(pin: String): Boolean {
        if (pin.length != 4 || !pin.all { it.isDigit() }) {
            return false
        }
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)

        val hash = hashPin(pin.toCharArray(), salt)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val hashB64 = Base64.getEncoder().encodeToString(hash)

        prefs.edit()
            .putString(KEY_PIN_SALT, saltB64)
            .putString(KEY_PIN_HASH, hashB64)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_TIMESTAMP, 0L)
            .apply()

        SecureMemory.wipe(salt)
        SecureMemory.wipe(hash)
        return true
    }

    /**
     * Verifies the entered PIN against the stored hash.
     */
    fun verifyPin(pin: String): Boolean {
        if (isLockedOut()) {
            return false
        }

        val saltB64 = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val storedHashB64 = prefs.getString(KEY_PIN_HASH, null) ?: return false

        val salt = Base64.getDecoder().decode(saltB64)
        val storedHash = Base64.getDecoder().decode(storedHashB64)

        val enteredHash = hashPin(pin.toCharArray(), salt)
        val matches = slowEquals(storedHash, enteredHash)

        SecureMemory.wipe(salt)
        SecureMemory.wipe(storedHash)
        SecureMemory.wipe(enteredHash)

        if (matches) {
            resetFailedAttempts()
        } else {
            recordFailedAttempt()
        }

        return matches
    }

    /**
     * Changes PIN if [oldPin] is correct.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!verifyPin(oldPin)) return false
        return setupPin(newPin)
    }

    /**
     * Checks if the user is currently locked out due to too many failed attempts.
     */
    fun isLockedOut(): Boolean {
        val lockoutTime = prefs.getLong(KEY_LOCKOUT_TIMESTAMP, 0L)
        if (lockoutTime == 0L) return false

        val elapsed = System.currentTimeMillis() - lockoutTime
        if (elapsed < LOCKOUT_DURATION_MS) {
            return true
        } else {
            // Lockout expired
            resetFailedAttempts()
            return false
        }
    }

    fun getRemainingLockoutSeconds(): Int {
        val lockoutTime = prefs.getLong(KEY_LOCKOUT_TIMESTAMP, 0L)
        if (lockoutTime == 0L) return 0
        val remainingMs = LOCKOUT_DURATION_MS - (System.currentTimeMillis() - lockoutTime)
        return (remainingMs / 1000L).coerceAtLeast(0L).toInt()
    }

    private fun recordFailedAttempt() {
        val current = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, current)
        if (current >= MAX_FAILED_ATTEMPTS) {
            editor.putLong(KEY_LOCKOUT_TIMESTAMP, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_TIMESTAMP, 0L)
            .apply()
    }

    fun isBiometricEnabled(): Boolean {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    private fun hashPin(pinChars: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pinChars, salt, PBKDF2_ITERATIONS, HASH_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        SecureMemory.wipe(pinChars)
        spec.clearPassword()
        return hash
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    private fun slowEquals(a: ByteArray, b: ByteArray): Boolean {
        var diff = a.size xor b.size
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
