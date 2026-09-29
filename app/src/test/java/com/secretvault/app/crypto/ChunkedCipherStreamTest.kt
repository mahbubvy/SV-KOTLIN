package com.secretvault.app.crypto

import com.secretvault.app.core.crypto.ChunkedCipherStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class ChunkedCipherStreamTest {

    private val cipherStream = ChunkedCipherStream(chunkSize = 64 * 1024)
    private val mockKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun testHeaderSerialization() {
        val baseIv = ByteArray(12) { (it + 1).toByte() }
        val output = ByteArrayOutputStream()
        cipherStream.writeHeader(output, baseIv, 65536)

        val headerBytes = output.toByteArray()
        assertEquals(21, headerBytes.size)

        val input = ByteArrayInputStream(headerBytes)
        val parsed = cipherStream.readHeader(input)

        assertEquals(ChunkedCipherStream.VERSION, parsed.version)
        assertEquals(65536, parsed.chunkSize)
        assertArrayEquals(baseIv, parsed.baseIv)
    }

    @Test
    fun testDerivedIvUniquenessPerChunk() {
        val baseIv = ByteArray(12) { 0x05 }
        val iv0 = cipherStream.deriveChunkIv(baseIv, 0)
        val iv1 = cipherStream.deriveChunkIv(baseIv, 1)
        val iv2 = cipherStream.deriveChunkIv(baseIv, 2)
        val iv1000 = cipherStream.deriveChunkIv(baseIv, 1000)

        assertFalse(iv0.contentEquals(iv1))
        assertFalse(iv1.contentEquals(iv2))
        assertFalse(iv0.contentEquals(iv1000))
    }

    @Test
    fun testChunkOffsetCalculation() {
        val offsetChunk0 = cipherStream.calculateChunkOffset(0, 65536)
        assertEquals(21L, offsetChunk0)

        val chunkTotalCipher = 4 + 65536 + 16 // 65556
        val offsetChunk1 = cipherStream.calculateChunkOffset(1, 65536)
        assertEquals(21L + chunkTotalCipher, offsetChunk1)
    }
}
