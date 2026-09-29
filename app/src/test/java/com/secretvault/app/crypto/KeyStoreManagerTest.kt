package com.secretvault.app.crypto

import com.secretvault.app.core.crypto.KeyStoreManager
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyStoreException

class KeyStoreManagerTest {
    @Test
    fun keystoreFailureDoesNotReturnAnEphemeralMasterKey() {
        val manager = KeyStoreManager(keyStoreName = "MissingKeyStoreProvider")

        assertThrows(KeyStoreException::class.java) {
            manager.getOrCreateMasterKey()
        }
    }

    @Test
    fun keyGenerationFailureIsPropagated() {
        val manager = KeyStoreManager(keyStoreName = "JCEKS")

        assertThrows(java.security.NoSuchProviderException::class.java) {
            manager.getOrCreateMasterKey()
        }
    }
}