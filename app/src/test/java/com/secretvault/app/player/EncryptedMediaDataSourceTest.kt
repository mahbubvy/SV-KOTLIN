package com.secretvault.app.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.secretvault.app.core.crypto.KeyStoreManager
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.player.EncryptedMediaDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.spec.SecretKeySpec

class EncryptedMediaDataSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var cryptoEngine: VaultCryptoEngine
    private val rawKey = ByteArray(32) { (it * 3).toByte() }

    @Before
    fun setUp() {
        io.mockk.mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } answers {
            val file = firstArg<File>()
            val mockUri = mockk<Uri>(relaxed = true)
            every { mockUri.path } returns file.absolutePath
            every { mockUri.schemeSpecificPart } returns file.absolutePath
            every { mockUri.toString() } returns file.toURI().toString()
            mockUri
        }

        keyStoreManager = mockk(relaxed = true)
        every { keyStoreManager.getOrCreateMasterKey() } returns SecretKeySpec(rawKey, "AES")
        every { keyStoreManager.generateRandomIv() } returns ByteArray(12) { it.toByte() }
        cryptoEngine = VaultCryptoEngine(keyStoreManager)
    }

    @org.junit.After
    fun tearDown() {
        io.mockk.unmockkAll()
    }

    @Test
    fun testOpenAndReadEncryptedDataSource() {
        // Create 200 KB mock plain video data
        val plainBytes = ByteArray(200 * 1024) { (it % 256).toByte() }
        val inputFile = tempFolder.newFile("test_video.mp4")
        inputFile.writeBytes(plainBytes)

        val encFile = tempFolder.newFile("test_video.enc")
        cryptoEngine.encryptFile(inputFile, encFile)

        val dataSource = EncryptedMediaDataSource(cryptoEngine)
        val listener = mockk<TransferListener>(relaxed = true)
        dataSource.addTransferListener(listener)
        val dataSpec = DataSpec(Uri.fromFile(encFile))

        val length = dataSource.open(dataSpec)
        assertEquals(plainBytes.size.toLong(), length)

        // Read 10 KB from start
        val readBuffer = ByteArray(10 * 1024)
        val bytesRead = dataSource.read(readBuffer, 0, readBuffer.size)
        assertEquals(readBuffer.size, bytesRead)

        val expectedSlice = plainBytes.copyOfRange(0, 10 * 1024)
        assertArrayEquals(expectedSlice, readBuffer)

        dataSource.close()
        dataSource.close()
        verify(exactly = 1) { listener.onTransferEnd(dataSource, any(), false) }
    }

    @Test
    fun testRandomSeekRead() {
        // Create 150 KB plain video data
        val plainBytes = ByteArray(150 * 1024) { ((it * 7) % 256).toByte() }
        val inputFile = tempFolder.newFile("test_seek.mp4")
        inputFile.writeBytes(plainBytes)

        val encFile = tempFolder.newFile("test_seek.enc")
        cryptoEngine.encryptFile(inputFile, encFile)

        val dataSource = EncryptedMediaDataSource(cryptoEngine)
        // Seek to offset 80,000 bytes (spans chunk boundary)
        val seekOffset = 80_000L
        val readLength = 32_000L
        val dataSpec = DataSpec(Uri.fromFile(encFile), seekOffset, readLength)

        val returnedLength = dataSource.open(dataSpec)
        assertEquals(readLength, returnedLength)

        val readBuffer = ByteArray(readLength.toInt())
        val bytesRead = dataSource.read(readBuffer, 0, readBuffer.size)
        assertEquals(readBuffer.size, bytesRead)

        val expectedSlice = plainBytes.copyOfRange(seekOffset.toInt(), (seekOffset + readLength).toInt())
        assertArrayEquals(expectedSlice, readBuffer)

        dataSource.close()
    }
    @Test
    fun failedOpenClosesFileAndDoesNotSendTransferEnd() {
        val corruptFile = tempFolder.newFile("truncated.enc").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val dataSource = EncryptedMediaDataSource(cryptoEngine)
        val listener = mockk<TransferListener>(relaxed = true)
        dataSource.addTransferListener(listener)

        assertThrows(java.security.GeneralSecurityException::class.java) {
            dataSource.open(DataSpec(Uri.fromFile(corruptFile)))
        }
        val rafField = EncryptedMediaDataSource::class.java.getDeclaredField("raf").apply { isAccessible = true }
        assertNull(rafField.get(dataSource))
        dataSource.close()
        verify(exactly = 0) { listener.onTransferEnd(dataSource, any(), false) }
    }
}
