package com.secretvault.app.share

import android.content.Context
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.share.EphemeralShareManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EphemeralShareManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cleanupPreservesBothSharedPhotosUntilExpiryIncludingAfterProcessRestart() = runBlocking {
        val context = mockk<Context>()
        every { context.cacheDir } returns tempFolder.root
        val crypto = mockk<VaultCryptoEngine>()
        val directory = tempFolder.newFolder("vault_shared")
        val photos = listOf(File(directory, "first.jpg"), File(directory, "second.jpg"))
        val content = byteArrayOf(1, 2, 3, 4)
        photos.forEach {
            it.writeBytes(content)
            assertTrue(it.setLastModified(System.currentTimeMillis() + 60_000))
        }
        val expired = File(directory, "expired.jpg").apply { writeBytes(content) }
        val manager = EphemeralShareManager(context, crypto)

        try {
            manager.purgeAllSharedFiles().join()
            photos.forEach {
                assertTrue(it.exists())
                assertArrayEquals(content, it.readBytes())
            }
            assertFalse(expired.exists())

            val restarted = EphemeralShareManager(context, crypto)
            try {
                restarted.purgeAllSharedFiles().join()
                photos.forEach { assertArrayEquals(content, it.readBytes()) }
            } finally {
                photos.forEach { it.setLastModified(0) }
                restarted.purgeAllSharedFiles().join()
            }
            photos.forEach { assertFalse(it.exists()) }

            val revoked = File(directory, "revoked.jpg").apply {
                writeBytes(content)
                assertTrue(setLastModified(System.currentTimeMillis() + 60_000))
            }
            manager.purgeAllSharedFiles(force = true).join()
            assertFalse(revoked.exists())
        } finally {
            photos.forEach { it.setLastModified(0) }
            manager.purgeAllSharedFiles().join()
        }
    }
}
