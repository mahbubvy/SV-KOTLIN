package com.secretvault.app.core.stream

import android.content.Context
import android.content.SharedPreferences
import com.secretvault.app.core.crypto.KeyStoreManager
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

class StreamPinManager(context: Context, private val prefs: SharedPreferences =
    context.getSharedPreferences("sv_stream_config", Context.MODE_PRIVATE),
    private val clock: () -> Long = System::currentTimeMillis) {
    private val keys = KeyStoreManager(masterKeyAlias = "sv_stream_pin_key")
    private val aad = "SV streaming PIN v2".toByteArray(Charsets.US_ASCII)

    fun isPinRequired(): Boolean = prefs.getBoolean("require_pin", true)
    fun setPinRequired(required: Boolean) {
        check(prefs.edit().putBoolean("require_pin", required).commit()) { "Could not save streaming PIN preference" }
    }

    /** File sharing has its own switch and is open by default; the receiver still accepts every transfer. */
    fun isSharePinRequired(): Boolean = prefs.getBoolean("share_require_pin", false)
    fun setSharePinRequired(required: Boolean) {
        check(prefs.edit().putBoolean("share_require_pin", required).commit()) { "Could not save file sharing PIN preference" }
    }

    fun isConfigured(): Boolean {
        val pin = try { readPin() } catch (_: IOException) { null } ?: return false
        pin.fill('\u0000')
        return true
    }

    fun setPin(pin: CharArray) = synchronized(lock) {
        require(pin.size == PIN_DIGITS && pin.all { it in '0'..'9' }) { "Use four digits for the streaming PIN" }
        val bytes = ByteArray(pin.size) { pin[it].code.toByte() }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keys.getOrCreateMasterKey()); cipher.updateAAD(aad)
            val sealed = cipher.iv + cipher.doFinal(bytes)
            check(prefs.edit().putString("pin", Base64.getEncoder().encodeToString(sealed)).commit()) { "Could not save streaming PIN" }
        } finally { bytes.fill(0) }
    }

    fun readPin(): CharArray? = synchronized(lock) {
        val value = prefs.getString("pin", null) ?: return@synchronized null
        try {
            require(value.length <= 64)
            val sealed = Base64.getDecoder().decode(value)
            require(sealed.size == 12 + 16 + PIN_DIGITS || sealed.size == 34)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.getOrCreateMasterKey(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val bytes = cipher.doFinal(sealed, 12, sealed.size - 12)
            try {
                require((bytes.size == PIN_DIGITS || bytes.size == 6) && bytes.all { it in 48..57 })
                if (bytes.size == PIN_DIGITS) CharArray(bytes.size) { bytes[it].toInt().toChar() } else null
            } finally { bytes.fill(0) }
        } catch (error: Exception) { throw IOException("Streaming PIN could not be read. Set a new streaming PIN.", error) }
    }

    fun cooldownMillis(): Long = synchronized(lock) {
        (prefs.getLong("blocked_until", 0) - clock()).coerceIn(0, 30_000)
    }

    fun beginPairingAttempt() = synchronized(lock) {
        val remaining = cooldownMillis()
        if (remaining > 0) throw IOException("Try pairing again in ${(remaining + 999) / 1000} seconds")
        val expired = prefs.getLong("blocked_until", 0) != 0L
        val attempts = (if (expired) 0 else prefs.getInt("attempts", 0)).coerceIn(0, 4) + 1
        check(prefs.edit().putInt("attempts", attempts)
            .putLong("blocked_until", if (attempts == 5) clock() + 30_000 else 0).commit()) { "Could not save pairing retry budget" }
    }

    fun pairingSucceeded() = synchronized(lock) {
        check(prefs.edit().putInt("attempts", 0).putLong("blocked_until", 0).commit()) { "Could not reset pairing retry budget" }
    }

    companion object {
        const val PIN_DIGITS = 4
        private val lock = Any()
    }
}
