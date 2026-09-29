package com.secretvault.app.core.crypto

import java.io.InputStream
import java.io.OutputStream
import java.io.DataInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Handles Chunked AES-256-GCM streaming encryption, decryption, and random-access chunk lookup.
 */
class ChunkedCipherStream(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE
) {

    companion object {
        val MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())
        const val VERSION: Byte = 1
        const val ENVELOPE_VERSION: Byte = 2
        const val COMPLETION_VERSION: Byte = 3
        val FOOTER_MAGIC = byteArrayOf('S'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '3'.code.toByte())
        const val FOOTER_SIZE = 4 + 12 + 16
        const val FOOTER_MAGIC_INT = 0x53564633
        private const val FOOTER_IV_INDEX = Int.MIN_VALUE
        const val WRAPPED_KEY_SIZE = 32 + 16
        const val DEFAULT_CHUNK_SIZE = 64 * 1024 // 64 KB
        const val HEADER_SIZE = 4 + 1 + 4 + 12 // 21 Bytes
        const val GCM_TAG_LENGTH_BYTES = 16
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_IV_LENGTH = 12
        const val CHUNK_HEADER_SIZE = 4 // 4 bytes for chunk encrypted length
        const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    }

    /**
     * Header metadata parsed from an encrypted stream.
     */
    data class StreamHeader(
        val version: Byte,
        val chunkSize: Int,
        val baseIv: ByteArray,
        val wrappedKey: ByteArray? = null
    ) {
        val headerSize: Int get() = HEADER_SIZE + (wrappedKey?.size ?: 0)

        fun authenticationData(): ByteArray = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN).put(MAGIC).put(version).putInt(chunkSize).put(baseIv).array()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StreamHeader
            return version == other.version && chunkSize == other.chunkSize && baseIv.contentEquals(other.baseIv) && wrappedKey.contentEquals(other.wrappedKey)
        }

        override fun hashCode(): Int {
            var result = version.toInt()
            result = 31 * result + chunkSize
            result = 31 * result + baseIv.contentHashCode()
            result = 31 * result + (wrappedKey?.contentHashCode() ?: 0)
            return result
        }
    }

    /**
     * Derives a deterministic unique 12-byte IV for chunk [chunkIndex] from [baseIv].
     */
    data class StreamFooter(val plaintextSize: Long, val chunkCount: Int)

    fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Int): ByteArray {
        val iv = baseIv.clone()
        val indexBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(chunkIndex).array()
        for (i in 0..3) {
            iv[8 + i] = (iv[8 + i].toInt() xor indexBytes[i].toInt()).toByte()
        }
        return iv
    }

    /**
     * Writes a legacy header, or an envelope header with its wrapped file key.
     */
    fun writeHeader(
        output: OutputStream,
        baseIv: ByteArray,
        customChunkSize: Int = chunkSize,
        wrappedKey: ByteArray? = null,
        formatVersion: Byte = if (wrappedKey == null) VERSION else ENVELOPE_VERSION
    ) {
        require(customChunkSize in 1..16 * 1024 * 1024)
        require(wrappedKey == null || wrappedKey.size == WRAPPED_KEY_SIZE)
        require(formatVersion == VERSION || formatVersion == ENVELOPE_VERSION || formatVersion == COMPLETION_VERSION)
        require((formatVersion == VERSION) == (wrappedKey == null))
        output.write(MAGIC)
        output.write(formatVersion.toInt())
        val sizeBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(customChunkSize)
        output.write(sizeBuffer.array())
        output.write(baseIv)
        if (wrappedKey != null) output.write(wrappedKey)
    }

    /**
     * Reads either supported header format without decrypting media payloads.
     */
    fun readHeader(input: InputStream): StreamHeader {
        val magicBuffer = ByteArray(4)
        var read = 0
        while (read < 4) {
            val r = input.read(magicBuffer, read, 4 - read)
            if (r == -1) throw GeneralSecurityException("Invalid file: Unexpected EOF while reading header magic")
            read += r
        }

        if (!magicBuffer.contentEquals(MAGIC)) {
            throw GeneralSecurityException("Invalid file: Magic header mismatch")
        }

        val version = input.read().toByte()
        if (version != VERSION && version != ENVELOPE_VERSION && version != COMPLETION_VERSION) {
            throw GeneralSecurityException("Unsupported version: $version")
        }

        val chunkSizeBuffer = ByteArray(4)
        read = 0
        while (read < 4) {
            val r = input.read(chunkSizeBuffer, read, 4 - read)
            if (r == -1) throw GeneralSecurityException("Invalid file: Unexpected EOF while reading chunk size")
            read += r
        }
        val readChunkSize = ByteBuffer.wrap(chunkSizeBuffer).order(ByteOrder.BIG_ENDIAN).int
        if (readChunkSize !in 1..16 * 1024 * 1024) throw GeneralSecurityException("Invalid chunk size")

        val baseIv = ByteArray(GCM_IV_LENGTH)
        read = 0
        while (read < GCM_IV_LENGTH) {
            val r = input.read(baseIv, read, GCM_IV_LENGTH - read)
            if (r == -1) throw GeneralSecurityException("Invalid file: Unexpected EOF while reading base IV")
            read += r
        }

        val wrappedKey = if (version >= ENVELOPE_VERSION) {
            ByteArray(WRAPPED_KEY_SIZE).also { DataInputStream(input).readFully(it) }
        } else null
        return StreamHeader(version, readChunkSize, baseIv, wrappedKey)
    }

    fun readHeader(file: RandomAccessFile): StreamHeader = readHeader(object : InputStream() {
        override fun read(): Int = file.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = file.read(b, off, len)
    })

    /**
     * Encrypts a single block of plaintext.
     */
    fun encryptChunk(
        plaintext: ByteArray,
        offset: Int,
        length: Int,
        key: SecretKey,
        baseIv: ByteArray,
        chunkIndex: Int
    ): ByteArray {
        val chunkIv = deriveChunkIv(baseIv, chunkIndex)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val encrypted = cipher.doFinal(plaintext, offset, length)
        SecureMemory.wipe(chunkIv)
        return encrypted
    }

    /**
     * Decrypts a single block of ciphertext with authentication tag verification.
     */
    fun decryptChunk(
        ciphertext: ByteArray,
        offset: Int,
        length: Int,
        key: SecretKey,
        baseIv: ByteArray,
        chunkIndex: Int
    ): ByteArray {
        val chunkIv = deriveChunkIv(baseIv, chunkIndex)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, chunkIv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = cipher.doFinal(ciphertext, offset, length)
        SecureMemory.wipe(chunkIv)
        return decrypted
    }

    /**
     * Calculates the exact file offset of chunk [chunkIndex] for fixed-chunk-size files.
     */
    fun writeFooter(output: OutputStream, header: StreamHeader, key: SecretKey, plaintextSize: Long, chunkCount: Int) {
        validateFooter(plaintextSize, chunkCount, header.chunkSize)
        val plaintext = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putLong(plaintextSize).putInt(chunkCount).array()
        val encrypted = footerCipher(Cipher.ENCRYPT_MODE, header, key).doFinal(plaintext)
        try {
            output.write(FOOTER_MAGIC)
            output.write(encrypted)
        } finally {
            SecureMemory.wipe(plaintext)
            SecureMemory.wipe(encrypted)
        }
    }

    fun readFooter(file: RandomAccessFile, header: StreamHeader, key: SecretKey): StreamFooter {
        if (header.version != COMPLETION_VERSION || file.length() < header.headerSize + FOOTER_SIZE) {
            throw GeneralSecurityException("Missing encrypted stream completion record")
        }
        file.seek(file.length() - FOOTER_SIZE)
        val marker = ByteArray(FOOTER_MAGIC.size)
        file.readFully(marker)
        if (!marker.contentEquals(FOOTER_MAGIC)) throw GeneralSecurityException("Invalid stream completion marker")
        val encrypted = ByteArray(FOOTER_SIZE - FOOTER_MAGIC.size)
        file.readFully(encrypted)
        val footer = decryptFooter(encrypted, header, key)
        val expectedLength = header.headerSize.toLong() + footer.plaintextSize +
            footer.chunkCount.toLong() * (CHUNK_HEADER_SIZE + GCM_TAG_LENGTH_BYTES) + FOOTER_SIZE
        if (expectedLength < 0 || expectedLength != file.length()) {
            throw GeneralSecurityException("Encrypted stream is truncated or has trailing data")
        }
        return footer
    }

    fun decryptFooter(encrypted: ByteArray, header: StreamHeader, key: SecretKey): StreamFooter {
        if (encrypted.size != 12 + GCM_TAG_LENGTH_BYTES) throw GeneralSecurityException("Invalid completion record size")
        val plaintext = footerCipher(Cipher.DECRYPT_MODE, header, key).doFinal(encrypted)
        try {
            val buffer = ByteBuffer.wrap(plaintext).order(ByteOrder.BIG_ENDIAN)
            val footer = StreamFooter(buffer.long, buffer.int)
            validateFooter(footer.plaintextSize, footer.chunkCount, header.chunkSize)
            return footer
        } finally {
            SecureMemory.wipe(plaintext)
        }
    }

    private fun footerCipher(mode: Int, header: StreamHeader, key: SecretKey): Cipher {
        val iv = deriveChunkIv(header.baseIv, FOOTER_IV_INDEX)
        return try {
            Cipher.getInstance(CIPHER_ALGORITHM).apply {
                init(mode, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                updateAAD(header.authenticationData())
                updateAAD(FOOTER_MAGIC)
            }
        } finally {
            SecureMemory.wipe(iv)
        }
    }

    private fun validateFooter(plaintextSize: Long, chunkCount: Int, chunkSize: Int) {
        if (plaintextSize < 0 || chunkCount < 0) throw GeneralSecurityException("Invalid stream completion metadata")
        val expectedChunks = if (plaintextSize == 0L) 0L else (plaintextSize - 1) / chunkSize + 1
        if (expectedChunks != chunkCount.toLong()) throw GeneralSecurityException("Invalid stream chunk count")
    }
    fun calculateChunkOffset(chunkIndex: Int, chunkSize: Int = this.chunkSize, headerSize: Int = HEADER_SIZE): Long {
        val chunkTotalCipherSize = CHUNK_HEADER_SIZE + chunkSize + GCM_TAG_LENGTH_BYTES
        return headerSize.toLong() + (chunkIndex.toLong() * chunkTotalCipherSize.toLong())
    }
}
