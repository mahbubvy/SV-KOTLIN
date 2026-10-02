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

    fun isConfigured(): Boolean = prefs.contains("pin")

    fun setPin(pin: CharArray) = synchronized(lock) {
        require(pin.size == 6 && pin.all { it in '0'..'9' }) { "Use six digits for the streaming PIN" }
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
            require(sealed.size == 34)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keys.getOrCreateMasterKey(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            val bytes = cipher.doFinal(sealed, 12, sealed.size - 12)
            try {
                require(bytes.size == 6 && bytes.all { it in 48..57 })
                CharArray(bytes.size) { bytes[it].toInt().toChar() }
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

    companion object { private val lock = Any() }
}
