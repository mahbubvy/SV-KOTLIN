package com.secretvault.app.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.secretvault.app.core.crypto.ChunkedCipherStream
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import java.io.File
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import javax.crypto.SecretKey
import kotlin.math.min

class EncryptedMediaDataSource(
    private val cryptoEngine: VaultCryptoEngine,
    private val cipherStream: ChunkedCipherStream = ChunkedCipherStream()
) : BaseDataSource(/* isNetwork = */ false) {

    private var dataSpec: DataSpec? = null
    private var raf: RandomAccessFile? = null
    private var baseIv: ByteArray? = null
    private var masterKey: SecretKey? = null
    private var chunkSize: Int = ChunkedCipherStream.DEFAULT_CHUNK_SIZE
    private var headerSize: Int = ChunkedCipherStream.HEADER_SIZE
    private var totalPlaintextSize: Long = 0L
    private var streamFooter: ChunkedCipherStream.StreamFooter? = null
    private var currentPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var opened: Boolean = false

    // Fast in-memory 1-chunk cache to eliminate redundant AES cycles on small sequential reads
    private var cachedChunkIndex: Int = -1
    private var cachedChunkData: ByteArray? = null

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val uriString = dataSpec.uri.toString()
        val targetFile = if (uriString.startsWith("file://")) {
            File(java.net.URI(uriString).path)
        } else {
            File(dataSpec.uri.path ?: dataSpec.uri.schemeSpecificPart)
        }

        if (!targetFile.exists()) {
            throw IllegalArgumentException("Encrypted media file not found: ${targetFile.absolutePath}")
        }

        val fileLength = targetFile.length()
        val randomAccessFile = RandomAccessFile(targetFile, "r")
        this.raf = randomAccessFile

        if (fileLength < ChunkedCipherStream.HEADER_SIZE) {
            throw GeneralSecurityException("File too short for header")
        }
        randomAccessFile.seek(0)
        val header = cipherStream.readHeader(randomAccessFile)
        this.chunkSize = header.chunkSize
        this.headerSize = header.headerSize
        this.baseIv = header.baseIv
        val fileKey = cryptoEngine.getFileKey(header)
        this.masterKey = fileKey
        val footer = if (header.version == ChunkedCipherStream.COMPLETION_VERSION) {
            cipherStream.readFooter(randomAccessFile, header, fileKey)
        } else null
        this.streamFooter = footer

        if (footer != null) {
            this.totalPlaintextSize = footer.plaintextSize
        } else {
            val fullChunkStorage = ChunkedCipherStream.CHUNK_HEADER_SIZE + chunkSize + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES
            val payloadStorage = (fileLength - headerSize).coerceAtLeast(0L)
            val numFullChunks = payloadStorage / fullChunkStorage
            val remainder = payloadStorage % fullChunkStorage
            val overhead = ChunkedCipherStream.CHUNK_HEADER_SIZE + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES
            val lastChunkPlain = if (remainder > overhead) remainder - overhead else 0L
            this.totalPlaintextSize = (numFullChunks * chunkSize) + lastChunkPlain
        }
        if (dataSpec.position > totalPlaintextSize) {
            throw IllegalArgumentException("Position ${dataSpec.position} exceeds total size $totalPlaintextSize")
        }

        this.currentPosition = dataSpec.position
        this.bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalPlaintextSize - dataSpec.position
        }

        this.opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(targetBuffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT

        val fileRaf = raf ?: return C.RESULT_END_OF_INPUT
        val iv = baseIv ?: return C.RESULT_END_OF_INPUT
        val key = masterKey ?: return C.RESULT_END_OF_INPUT

        val bytesToRead = min(length.toLong(), bytesRemaining).toInt()
        var bytesCopied = 0

        while (bytesCopied < bytesToRead) {
            val chunkIdx = (currentPosition / chunkSize).toInt()
            val offsetInChunk = (currentPosition % chunkSize).toInt()

            // Fetch decrypted chunk (from cache or decrypt from disk)
            val chunk = getOrDecryptChunk(fileRaf, chunkIdx, key, iv)
            if (chunk == null || offsetInChunk >= chunk.size) {
                break
            }

            val availableInChunk = chunk.size - offsetInChunk
            val toCopy = min((bytesToRead - bytesCopied), availableInChunk)

            System.arraycopy(chunk, offsetInChunk, targetBuffer, offset + bytesCopied, toCopy)
            bytesCopied += toCopy
            currentPosition += toCopy
            bytesRemaining -= toCopy
        }

        if (bytesCopied == 0) {
            return C.RESULT_END_OF_INPUT
        }

        bytesTransferred(bytesCopied)
        return bytesCopied
    }

    private fun getOrDecryptChunk(
        fileRaf: RandomAccessFile,
        chunkIndex: Int,
        key: SecretKey,
        baseIv: ByteArray
    ): ByteArray? {
        if (cachedChunkIndex == chunkIndex && cachedChunkData != null) {
            return cachedChunkData
        }

        val chunkFilePos = cipherStream.calculateChunkOffset(chunkIndex, chunkSize, headerSize)
        streamFooter?.let {
            if (chunkIndex >= it.chunkCount) throw GeneralSecurityException("Chunk is outside authenticated stream")
        }
        if (chunkFilePos + 4 > fileRaf.length()) {
            if (streamFooter != null) throw GeneralSecurityException("Missing authenticated chunk")
            return null
        }

        fileRaf.seek(chunkFilePos)
        val chunkLen = fileRaf.readInt()
        if (chunkLen <= ChunkedCipherStream.GCM_TAG_LENGTH_BYTES || chunkLen > chunkSize + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES || chunkFilePos + 4 + chunkLen > fileRaf.length()) {
            if (streamFooter != null) throw GeneralSecurityException("Invalid authenticated chunk length")
            return null
        }
        streamFooter?.let { footer ->
            val expectedPlainSize = minOf(chunkSize.toLong(), footer.plaintextSize - chunkIndex.toLong() * chunkSize).toInt()
            if (chunkLen != expectedPlainSize + ChunkedCipherStream.GCM_TAG_LENGTH_BYTES) {
                throw GeneralSecurityException("Chunk length does not match authenticated stream size")
            }
        }

        val cipherBuffer = ByteArray(chunkLen)
        fileRaf.readFully(cipherBuffer)

        val decrypted = cipherStream.decryptChunk(
            ciphertext = cipherBuffer,
            offset = 0,
            length = chunkLen,
            key = key,
            baseIv = baseIv,
            chunkIndex = chunkIndex
        )

        SecureMemory.wipe(cipherBuffer)

        cachedChunkData?.let { SecureMemory.wipe(it) }
        cachedChunkIndex = chunkIndex
        cachedChunkData = decrypted
        return decrypted
    }

    override fun getUri(): Uri? = dataSpec?.uri

    override fun close() {
        if (opened) {
            opened = false
            try {
                raf?.close()
            } catch (e: Exception) {
                // Ignore close error
            }
            raf = null
            dataSpec = null
            masterKey = null
            streamFooter = null
            cachedChunkData?.let { SecureMemory.wipe(it) }
            cachedChunkData = null
            cachedChunkIndex = -1
            baseIv?.let { SecureMemory.wipe(it) }
            baseIv = null
            transferEnded()
        }
    }

    class Factory(
        private val cryptoEngine: VaultCryptoEngine
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return EncryptedMediaDataSource(cryptoEngine)
        }
    }
}
