package com.secretvault.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Manages Android KeyStore keys for hardware-backed AES-256 GCM encryption.
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
     * Retrieves or generates the master key from Android KeyStore.
     * Keystore failures are propagated; persistent data must never use a disposable key.
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
        return if (keyStore.containsAlias(masterKeyAlias)) {
            readExistingMasterKey(keyStore)
        } else {
            generateAndStoreMasterKey()
        }
    }

    private fun readExistingMasterKey(keyStore: KeyStore): SecretKey {
        val entry = keyStore.getEntry(masterKeyAlias, null)
        if (entry !is KeyStore.SecretKeyEntry) {
            throw KeyStoreException("The existing vault master key could not be loaded")
        }
        return entry.secretKey
    }

    /** Generates a hardware-backed key, falling back from unavailable StrongBox to TEE. */
    private fun generateAndStoreMasterKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) generateStrongBoxOrTeeKey()
        else generateHardwareKey(useStrongBox = false)

    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateStrongBoxOrTeeKey(): SecretKey {
        return try {
            generateHardwareKey(useStrongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            val keyStore = KeyStore.getInstance(keyStoreName).apply { load(null) }
            if (keyStore.containsAlias(masterKeyAlias)) {
                readExistingMasterKey(keyStore)
            } else {
                generateHardwareKey(useStrongBox = false)
            }
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
            .setRandomizedEncryptionRequired(false) // The chunked format derives each chunk IV.

        if (useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /** Generates a secure random 12-byte IV. */
    fun generateRandomIv(): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)
        return iv
    }

    /** Generates a secure random salt of given length. */
    fun generateRandomSalt(length: Int = 32): ByteArray {
        val salt = ByteArray(length)
        secureRandom.nextBytes(salt)
        return salt
    }
}