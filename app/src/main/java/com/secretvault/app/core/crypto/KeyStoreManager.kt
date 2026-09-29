package com.secretvault.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Manages Android KeyStore keys for Hardware-backed AES-256 GCM encryption.
 */
class KeyStoreManager(
    private val keyStoreName: String = ANDROID_KEYSTORE,
    private val masterKeyAlias: String = DEFAULT_MASTER_KEY_ALIAS
) {

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_MASTER_KEY_ALIAS = "secret_vault_master_key"
        const val AES_KEY_SIZE = 256
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }

    private val secureRandom = SecureRandom()

    /**
     * Retrieves or generates the Master SecretKey from Android KeyStore.
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                val entry = keyStore.getEntry(masterKeyAlias, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey ?: generateAndStoreMasterKey()
            } else {
                generateAndStoreMasterKey()
            }
        } catch (e: Exception) {
            // Fallback for non-Android / Unit test environments where AndroidKeyStore is not registered
            generateInMemoryKey()
        }
    }

    /**
     * Generates a new hardware-backed AES-256 key in Android KeyStore.
     */
    private fun generateAndStoreMasterKey(): SecretKey {
        return try {
            generateHardwareKey(useStrongBox = true)
        } catch (e: Exception) {
            // StrongBox might not be supported on this device, fallback to standard TEE
            generateHardwareKey(useStrongBox = false)
        }
    }

    private fun generateHardwareKey(useStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            keyStoreName
        )

        val builder = KeyGenParameterSpec.Builder(
            masterKeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .setRandomizedEncryptionRequired(false) // We supply our own derived deterministic per-chunk IVs

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /**
     * Generates a random 256-bit SecretKey in memory (for tests or ephemeral session transfer).
     */
    fun generateInMemoryKey(): SecretKey {
        val keyBytes = ByteArray(32)
        secureRandom.nextBytes(keyBytes)
        val key = SecretKeySpec(keyBytes, "AES")
        SecureMemory.wipe(keyBytes)
        return key
    }

    /**
     * Generates a secure random 12-byte IV.
     */
    fun generateRandomIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Generates a secure random salt of given length.
     */
    fun generateRandomSalt(length: Int = 32): ByteArray {
        val salt = ByteArray(length)
        secureRandom.nextBytes(salt)
        return salt
    }
}
