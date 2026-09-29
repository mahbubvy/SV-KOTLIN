package com.secretvault.app.crypto

import com.secretvault.app.core.crypto.KeyStoreManager
import com.secretvault.app.core.crypto.ChunkedCipherStream
import com.secretvault.app.core.crypto.VaultCryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class VaultCryptoEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cryptoEngine: VaultCryptoEngine
    private lateinit var key: SecretKey

    @Before
    fun setUp() {
        cryptoEngine = VaultCryptoEngine(chunkSize = 64 * 1024)
        val keyBytes = ByteArray(32) { (it * 3).toByte() }
        key = SecretKeySpec(keyBytes, "AES")
    }

    @Test
    fun testSmallBufferEncryptDecrypt() {
        val original = "SecretVault Ultra Privacy Message 2026".toByteArray(Charsets.UTF_8)
        val encrypted = cryptoEngine.encryptBytes(original, key)
        val decrypted = cryptoEngine.decryptBytes(encrypted, key)

        assertArrayEquals(original, decrypted)
        assertFalse(original.contentEquals(encrypted))
    }

    @Test
    fun testMultiChunkEncryptDecrypt() {
        // 200 KB -> Spans across 4 chunks (64KB each)
        val original = ByteArray(200 * 1024)
        Random.nextBytes(original)

        val encrypted = cryptoEngine.encryptBytes(original, key)
        val decrypted = cryptoEngine.decryptBytes(encrypted, key)

        assertArrayEquals(original, decrypted)
    }

    @Test
    fun testFileEncryptionAndDecryption() {
        val sourceFile = tempFolder.newFile("plain.dat")
        val encFile = tempFolder.newFile("vault.enc")
        val restoredFile = tempFolder.newFile("restored.dat")

        val data = ByteArray(150 * 1024)
        Random.nextBytes(data)
        sourceFile.writeBytes(data)

        cryptoEngine.encryptFile(sourceFile, encFile, key)
        assertTrue(encFile.length() > sourceFile.length())

        cryptoEngine.decryptFile(encFile, restoredFile, key)
        assertEquals(sourceFile.length(), restoredFile.length())
        assertArrayEquals(data, restoredFile.readBytes())
    }

    @Test
    fun testTamperedCiphertextThrowsSecurityException() {
        val original = "Sensitive payload".toByteArray()
        val encrypted = cryptoEngine.encryptBytes(original, key)

        // Tamper with one byte in the ciphertext payload
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5].toInt() xor 0xFF).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            cryptoEngine.decryptBytes(encrypted, key)
        }
    }

    @Test
    fun testRandomRangeDecryptionForMediaStreaming() {
        // Create 256 KB file (4 full chunks of 64KB)
        val original = ByteArray(256 * 1024)
        for (i in original.indices) {
            original[i] = (i % 256).toByte()
        }

        val encFile = tempFolder.newFile("streaming.enc")
        val sourceFile = tempFolder.newFile("streaming_src.dat")
        sourceFile.writeBytes(original)
        cryptoEngine.encryptFile(sourceFile, encFile, key)

        RandomAccessFile(encFile, "r").use { raf ->
            // Test 1: Range strictly inside chunk 1 (from 70,000 to 75,000)
            val range1 = cryptoEngine.decryptRange(raf, 70000L, 5000L, key)
            assertEquals(5000, range1.size)
            val expected1 = original.copyOfRange(70000, 75000)
            assertArrayEquals(expected1, range1)

            // Test 2: Range crossing chunk boundary (from 60,000 to 130,000)
            val range2 = cryptoEngine.decryptRange(raf, 60000L, 70000L, key)
            assertEquals(70000, range2.size)
            val expected2 = original.copyOfRange(60000, 130000)
            assertArrayEquals(expected2, range2)
        }
    }

    @Test
    fun legacyFilesRemainReadableThroughAllReadPaths() {
        val chunks = ChunkedCipherStream()
        val iv = ByteArray(12) { (it + 1).toByte() }
        val original = ByteArray(65536 + 137) { (it * 17).toByte() }
        val output = ByteArrayOutputStream()
        chunks.writeHeader(output, iv, 65536)
        val framed = java.io.DataOutputStream(output)
        for ((index, offset) in original.indices.step(65536).withIndex()) {
            val encrypted = chunks.encryptChunk(original, offset, minOf(65536, original.size - offset), key, iv, index)
            framed.writeInt(encrypted.size)
            framed.write(encrypted)
        }
        val file = tempFolder.newFile("legacy.enc").apply { writeBytes(output.toByteArray()) }
        assertArrayEquals(original, cryptoEngine.decryptBytes(file.readBytes(), key))
        assertArrayEquals(original.copyOfRange(65520, 65570), cryptoEngine.decryptRange(file, 65520, 50, key))
        assertEquals(original.size.toLong(), cryptoEngine.calculatePlaintextSize(file))
        assertEquals(original.size.toLong(), cryptoEngine.getPlaintextSize(file))
        val decoded = tempFolder.newFile("legacy.out")
        cryptoEngine.decryptFile(file, decoded, key)
        assertArrayEquals(original, decoded.readBytes())
    }

    @Test
    fun envelopeWriterAuthenticatesKeyAndHeaderAndSupportsEmptyFiles() {
        val output = ByteArrayOutputStream()
        val original = ByteArray(70001) { (it * 3).toByte() }
        cryptoEngine.createEncryptingOutputStream(output, key).use { it.write(original) }
        val encoded = output.toByteArray()
        val header = ChunkedCipherStream().readHeader(ByteArrayInputStream(encoded))
        assertEquals(ChunkedCipherStream.ENVELOPE_VERSION, header.version)
        assertEquals(69, header.headerSize)
        assertArrayEquals(original, cryptoEngine.decryptBytes(encoded, key))
        for (offset in listOf(8, 21, 68, 80, encoded.lastIndex)) {
            val modified = encoded.clone()
            modified[offset] = (modified[offset].toInt() xor 1).toByte()
            assertThrows(GeneralSecurityException::class.java) { cryptoEngine.decryptBytes(modified, key) }
        }
        val empty = cryptoEngine.encryptBytes(ByteArray(0), key)
        assertArrayEquals(ByteArray(0), cryptoEngine.decryptBytes(empty, key))
        val damaged = empty.clone().apply { this[30] = (this[30].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { cryptoEngine.decryptBytes(damaged, key) }
        assertThrows(java.io.EOFException::class.java) { cryptoEngine.decryptBytes(encoded.copyOf(50), key) }
    }

    @Test
    fun keystoreFailureLeavesSourceRecordingAvailableAndWritesNoCiphertext() {
        val sourceFile = tempFolder.newFile("temp_rec.mp4")
        val destination = File(tempFolder.root, "saved.enc")
        val original = ByteArray(256) { it.toByte() }
        sourceFile.writeBytes(original)
        val engine = VaultCryptoEngine(
            keyStoreManager = KeyStoreManager(keyStoreName = "MissingKeyStoreProvider"),
            chunkSize = 64 * 1024
        )

        assertThrows(KeyStoreException::class.java) {
            engine.encryptFile(sourceFile, destination)
        }

        assertTrue(sourceFile.exists())
        assertArrayEquals(original, sourceFile.readBytes())
        assertFalse(destination.exists())
    }
}
