package com.secretvault.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance vault encryption and decryption engine.
 * Supports streaming, range queries for media streaming, and memory-safe byte processing.
 */
class VaultCryptoEngine(
    private val keyStoreManager: KeyStoreManager = KeyStoreManager(),
    private val chunkSize: Int = ChunkedCipherStream.DEFAULT_CHUNK_SIZE
) {

    private val cipherStream = ChunkedCipherStream(chunkSize)

    fun getMasterKey(): SecretKey = keyStoreManager.getOrCreateMasterKey()

    private fun wrapCipher(mode: Int, key: SecretKey, header: ChunkedCipherStream.StreamHeader): Cipher =
        Cipher.getInstance(ChunkedCipherStream.CIPHER_ALGORITHM).apply {
            init(mode, key, GCMParameterSpec(128, header.baseIv))
            updateAAD(header.authenticationData())
        }

    private fun prepareEncryption(key: SecretKey): Pair<ChunkedCipherStream.StreamHeader, SecretKey> {
        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            val header = ChunkedCipherStream.StreamHeader(
                ChunkedCipherStream.ENVELOPE_VERSION, chunkSize, keyStoreManager.generateRandomIv()
            )
            val wrapped = wrapCipher(Cipher.ENCRYPT_MODE, key, header).doFinal(rawKey)
            return header.copy(wrappedKey = wrapped) to SecretKeySpec(rawKey, "AES")
        } finally {
            SecureMemory.wipe(rawKey)
        }
    }

    fun getFileKey(header: ChunkedCipherStream.StreamHeader, key: SecretKey = getMasterKey()): SecretKey {
        if (header.version == ChunkedCipherStream.VERSION) return key
        val wrapped = header.wrappedKey ?: throw GeneralSecurityException("Missing file key")
        val rawKey = wrapCipher(Cipher.DECRYPT_MODE, key, header).doFinal(wrapped)
        try {
            if (rawKey.size != 32) throw GeneralSecurityException("Invalid file key")
            return SecretKeySpec(rawKey, "AES")
        } finally {
            SecureMemory.wipe(rawKey)
        }
    }

    /**
     * Encrypts an [InputStream] to an [OutputStream] using chunked AES-256-GCM.
     */
    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey(),
        totalBytes: Long = -1L,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val (header, contentKey) = prepareEncryption(key)
        val baseIv = header.baseIv
        cipherStream.writeHeader(output, baseIv, chunkSize, header.wrappedKey)

        val buffer = ByteArray(chunkSize)
        val lenBuffer = ByteArray(4)
        var chunkIndex = 0
        var bytesProcessed = 0L

        try {
            while (true) {
                var chunkBytesRead = 0
                while (chunkBytesRead < chunkSize) {
                    val r = input.read(buffer, chunkBytesRead, chunkSize - chunkBytesRead)
                    if (r == -1) break
                    chunkBytesRead += r
                }

                if (chunkBytesRead == 0) {
                    break
                }

                val encryptedChunk = cipherStream.encryptChunk(
                    plaintext = buffer,
                    offset = 0,
                    length = chunkBytesRead,
                    key = contentKey,
                    baseIv = baseIv,
                    chunkIndex = chunkIndex
                )

                // Write chunk length (4 bytes)
                ByteBuffer.wrap(lenBuffer).order(ByteOrder.BIG_ENDIAN).putInt(encryptedChunk.size)
                output.write(lenBuffer)
                // Write encrypted payload
                output.write(encryptedChunk)

                bytesProcessed += chunkBytesRead
                chunkIndex++

                if (totalBytes > 0 && onProgress != null) {
                    val progress = (bytesProcessed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }

                if (chunkBytesRead < chunkSize) {
                    // Last chunk read
                    break
                }
            }
            output.flush()
        } finally {
            SecureMemory.wipe(buffer)
            SecureMemory.wipe(baseIv)
        }
    }

    /**
     * Creates an [OutputStream] that encrypts plaintext into chunked AES-256-GCM as it is written.
     */
    fun createEncryptingOutputStream(
        output: OutputStream,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey()
    ): OutputStream {
        val (header, contentKey) = prepareEncryption(key)
        return VaultEncryptingOutputStream(output, contentKey, cipherStream, header.baseIv, chunkSize, header.wrappedKey)
    }

    /**
     * Decrypts an encrypted [InputStream] to an [OutputStream].
     */
    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey(),
        totalBytes: Long = -1L,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val header = cipherStream.readHeader(input)
        val contentKey = getFileKey(header, key)
        val baseIv = header.baseIv
        val lenBuffer = ByteArray(4)
        var chunkIndex = 0
        var bytesProcessed = 0L

        try {
            while (true) {
                var read = 0
                while (read < 4) {
                    val r = input.read(lenBuffer, read, 4 - read)
                    if (r == -1) break
                    read += r
                }

                if (read == 0) {
                    // Normal EOF
                    break
                }

                if (read < 4) {
                    throw GeneralSecurityException("Truncated chunk length at chunk $chunkIndex")
                }

                val chunkLength = ByteBuffer.wrap(lenBuffer).order(ByteOrder.BIG_ENDIAN).int
                if (chunkLength <= ChunkedCipherStream.GCM_TAG_LENGTH_BYTES || chunkLength > header.chunkSize + 1024) {
                    throw GeneralSecurityException("Invalid chunk size: $chunkLength at chunk $chunkIndex")
                }

                val chunkCiphertext = ByteArray(chunkLength)
                var chunkRead = 0
                while (chunkRead < chunkLength) {
                    val r = input.read(chunkCiphertext, chunkRead, chunkLength - chunkRead)
                    if (r == -1) {
                        throw GeneralSecurityException("Premature EOF in chunk payload at chunk $chunkIndex")
                    }
                    chunkRead += r
                }

                val decryptedChunk = cipherStream.decryptChunk(
                    ciphertext = chunkCiphertext,
                    offset = 0,
                    length = chunkLength,
                    key = contentKey,
                    baseIv = baseIv,
                    chunkIndex = chunkIndex
                )

                output.write(decryptedChunk)
                SecureMemory.wipe(decryptedChunk)
                SecureMemory.wipe(chunkCiphertext)

                bytesProcessed += (chunkLength + 4)
                chunkIndex++

                if (totalBytes > 0 && onProgress != null) {
                    val progress = (bytesProcessed.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
            output.flush()
        } finally {
            SecureMemory.wipe(baseIv)
        }
    }

    /**
     * Encrypts a source file to a destination file.
     */
    fun encryptFile(
        source: File,
        destination: File,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey(),
        onProgress: ((Float) -> Unit)? = null
    ) {
        java.io.BufferedInputStream(FileInputStream(source), 128 * 1024).use { input ->
            java.io.BufferedOutputStream(FileOutputStream(destination), 128 * 1024).use { output ->
                encryptStream(input, output, key, source.length(), onProgress)
            }
        }
    }

    /**
     * Decrypts a source file to a destination file.
     */
    fun decryptFile(
        source: File,
        destination: File,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey(),
        onProgress: ((Float) -> Unit)? = null
    ) {
        val totalBytes = source.length()
        java.io.BufferedInputStream(FileInputStream(source), 128 * 1024).use { input ->
            java.io.BufferedOutputStream(FileOutputStream(destination), 128 * 1024).use { output ->
                decryptStream(input, output, key, totalBytes, onProgress)
            }
        }
    }

    /**
     * Calculates the exact decrypted plaintext size of an encrypted file in O(1) time
     * by parsing the ChunkedCipherStream header and chunk framing.
     */
    fun calculatePlaintextSize(encFile: File): Long {
        if (!encFile.exists() || encFile.length() < ChunkedCipherStream.HEADER_SIZE) return 0L
        val fileLength = encFile.length()
        val header = try {
            FileInputStream(encFile).use { fis ->
                cipherStream.readHeader(fis)
            }
        } catch (e: Exception) {
            return 0L
        }

        val chunkSize = header.chunkSize
        val overhead = ChunkedCipherStream.CHUNK_HEADER_SIZE + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES
        val fullChunkStorage = chunkSize + overhead
        val payloadStorage = (fileLength - header.headerSize).coerceAtLeast(0L)
        val numFullChunks = payloadStorage / fullChunkStorage
        val remainder = payloadStorage % fullChunkStorage
        val lastChunkPlain = if (remainder > overhead) remainder - overhead else 0L
        return (numFullChunks * chunkSize) + lastChunkPlain
    }

    /**
     * In-memory encryption of a byte array (e.g., micro-thumbnail).
     */
    fun encryptBytes(
        plainBytes: ByteArray,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey()
    ): ByteArray {
        val input = ByteArrayInputStream(plainBytes)
        val output = ByteArrayOutputStream()
        encryptStream(input, output, key)
        return output.toByteArray()
    }

    /**
     * In-memory decryption of an encrypted byte array.
     */
    fun decryptBytes(
        encryptedBytes: ByteArray,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey()
    ): ByteArray {
        val input = ByteArrayInputStream(encryptedBytes)
        val output = ByteArrayOutputStream()
        decryptStream(input, output, key)
        return output.toByteArray()
    }

    /**
     * Decrypts a specific byte range directly from a [RandomAccessFile] in O(1) chunk access time.
     * This powers smooth video streaming and scrubbing without decrypting the entire file.
     */
    fun decryptRange(
        raf: RandomAccessFile,
        startOffset: Long,
        length: Long,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey()
    ): ByteArray {
        require(startOffset >= 0 && length >= 0 && length <= Int.MAX_VALUE && startOffset <= Long.MAX_VALUE - length)
        if (length == 0L) return ByteArray(0)
        raf.seek(0)
        val header = cipherStream.readHeader(raf)
        val contentKey = getFileKey(header, key)
        val readChunkSize = header.chunkSize
        val baseIv = header.baseIv

        val endOffset = startOffset + length
        val startChunk = (startOffset / readChunkSize).toInt()
        val endChunk = ((endOffset - 1) / readChunkSize).toInt()

        val output = ByteArrayOutputStream(length.toInt())

        try {
            for (chunkIdx in startChunk..endChunk) {
                // Seek to chunk
                val chunkFilePos = cipherStream.calculateChunkOffset(chunkIdx, readChunkSize, header.headerSize)
                raf.seek(chunkFilePos)
                val chunkLen = raf.readInt()
                if (chunkLen <= ChunkedCipherStream.GCM_TAG_LENGTH_BYTES || chunkLen > readChunkSize + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES) {
                    throw GeneralSecurityException("Invalid chunk size")
                }

                val cipherBuffer = ByteArray(chunkLen)
                raf.readFully(cipherBuffer)

                val decryptedChunk = cipherStream.decryptChunk(
                    ciphertext = cipherBuffer,
                    offset = 0,
                    length = chunkLen,
                    key = contentKey,
                    baseIv = baseIv,
                    chunkIndex = chunkIdx
                )

                // Calculate the slice of this chunk to include
                val chunkStartInPlaintext = chunkIdx.toLong() * readChunkSize
                val sliceStart = (startOffset - chunkStartInPlaintext).coerceAtLeast(0L).toInt()
                val sliceEnd = (endOffset - chunkStartInPlaintext).coerceAtMost(decryptedChunk.size.toLong()).toInt()

                if (sliceEnd > sliceStart) {
                    output.write(decryptedChunk, sliceStart, sliceEnd - sliceStart)
                }

                SecureMemory.wipe(decryptedChunk)
                SecureMemory.wipe(cipherBuffer)
            }
            return output.toByteArray()
        } finally {
            SecureMemory.wipe(baseIv)
        }
    }

    /**
     * Reads the uncompressed plaintext size by scanning chunk length headers.
     */
    fun getPlaintextSize(encryptedFile: File): Long {
        RandomAccessFile(encryptedFile, "r").use { raf ->
            if (raf.length() < ChunkedCipherStream.HEADER_SIZE) return 0L
            raf.seek(0)
            cipherStream.readHeader(raf)

            var totalPlaintext: Long = 0L
            val fileLength = raf.length()
            while (raf.filePointer < fileLength) {
                if (fileLength - raf.filePointer < 4) break
                val chunkLen = raf.readInt()
                if (chunkLen < ChunkedCipherStream.GCM_TAG_LENGTH_BYTES) break
                val plainBytesInChunk = chunkLen - ChunkedCipherStream.GCM_TAG_LENGTH_BYTES
                totalPlaintext += plainBytesInChunk
                raf.skipBytes(chunkLen)
            }
            return totalPlaintext
        }
    }

    /**
     * Decrypts a specific byte range directly from an encrypted [File] in O(1) chunk access time.
     */
    fun decryptRange(
        encryptedFile: File,
        startOffset: Long,
        length: Long,
        key: SecretKey = keyStoreManager.getOrCreateMasterKey()
    ): ByteArray {
        RandomAccessFile(encryptedFile, "r").use { raf ->
            return decryptRange(raf, startOffset, length, key)
        }
    }
}

/**
 * An [OutputStream] that accepts unencrypted plaintext bytes and streams out chunked AES-256-GCM ciphertext.
 * Plaintext is retained only in a single bounded chunk buffer (64 KiB) and wiped on close.
 */
class VaultEncryptingOutputStream(
    private val output: OutputStream,
    private val key: SecretKey,
    private val cipherStream: ChunkedCipherStream,
    private val baseIv: ByteArray,
    private val chunkSize: Int,
    private val wrappedKey: ByteArray? = null
) : OutputStream() {
    private val buffer = ByteArray(chunkSize)
    private val lenBuffer = ByteArray(4)
    private var bufferPos = 0
    private var chunkIndex = 0
    private var headerWritten = false

    private fun ensureHeader() {
        if (!headerWritten) {
            cipherStream.writeHeader(output, baseIv, chunkSize, wrappedKey)
            headerWritten = true
        }
    }

    override fun write(b: Int) {
        ensureHeader()
        buffer[bufferPos++] = b.toByte()
        if (bufferPos == chunkSize) {
            flushChunk()
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureHeader()
        var currentOff = off
        var remaining = len
        while (remaining > 0) {
            val toCopy = minOf(remaining, chunkSize - bufferPos)
            System.arraycopy(b, currentOff, buffer, bufferPos, toCopy)
            bufferPos += toCopy
            currentOff += toCopy
            remaining -= toCopy
            if (bufferPos == chunkSize) {
                flushChunk()
            }
        }
    }

    private fun flushChunk() {
        if (bufferPos > 0) {
            val encryptedChunk = cipherStream.encryptChunk(
                plaintext = buffer,
                offset = 0,
                length = bufferPos,
                key = key,
                baseIv = baseIv,
                chunkIndex = chunkIndex
            )
            ByteBuffer.wrap(lenBuffer).order(ByteOrder.BIG_ENDIAN).putInt(encryptedChunk.size)
            output.write(lenBuffer)
            output.write(encryptedChunk)
            chunkIndex++
            bufferPos = 0
        }
    }

    override fun flush() {
        output.flush()
    }

    override fun close() {
        try {
            ensureHeader()
            flushChunk()
            output.flush()
        } finally {
            SecureMemory.wipe(buffer)
            SecureMemory.wipe(baseIv)
            output.close()
        }
    }
}
