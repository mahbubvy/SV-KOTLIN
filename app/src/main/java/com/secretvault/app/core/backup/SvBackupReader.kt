package com.secretvault.app.core.backup

import kotlinx.coroutines.ensureActive
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import kotlin.coroutines.coroutineContext

/**
 * Interface for consuming items and media chunks as they are authenticated.
 */
interface SvBackupConsumer {
    /** Called when the manifest record is authenticated and validated. */
    suspend fun onManifest(manifest: SvBackupManifest)

    /** Called for each sequential authenticated media chunk. */
    suspend fun onMediaChunk(
        item: SvBackupItem,
        chunkIndex: Int,
        totalChunks: Int,
        chunkBytes: ByteArray
    )

    /** Called after the final summary record and EOF have been successfully verified. */
    suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary)
}

/**
 * Bounded streaming parser for .svbackup v1 archives.
 * Decrypts and authenticates records incrementally without loading the entire archive into memory.
 */
class SvBackupReader(
    private val inputStream: InputStream,
    private val password: String
) {

    /**
     * Parses the archive stream, authenticating records in order and forwarding to [consumer].
     * Throws [GeneralSecurityException] on authentication failure (wrong password or tampering).
     * Throws [IllegalArgumentException] or [EOFException] on malformed framing or truncated stream.
     */
    suspend fun readArchive(consumer: SvBackupConsumer) {
        val header = readExactly(SvBackupCrypto.HEADER_SIZE)
        val salt = SvBackupCrypto.parseAndValidateHeader(header)

        val key = SvBackupCrypto.deriveKey(password, salt)
        var recordIndex = 0
        var previousTag = ByteArray(SvBackupCrypto.TAG_SIZE) // 16 zeroes for record 0

        try {
            coroutineContext.ensureActive()

            // 1. Record 0: Manifest (Type 1)
            val rh0 = readExactly(SvBackupCrypto.RECORD_HEADER_SIZE)
            val rh0Buf = ByteBuffer.wrap(rh0)
            val type0 = rh0[0].toInt()
            val index0 = rh0Buf.getInt(1)
            val length0 = rh0Buf.getInt(5)

            require(type0 == SvBackupCrypto.TYPE_MANIFEST) { "First record must be Manifest (Type 1), got $type0" }
            require(index0 == 0) { "First record index must be 0, got $index0" }
            require(length0 in 1..SvBackupCrypto.MAX_MANIFEST_SIZE) { "Invalid manifest size: $length0" }

            val sealed0 = readExactly(length0 + SvBackupCrypto.TAG_SIZE)
            val aad0 = SvBackupCrypto.buildAad(header, rh0, previousTag)
            val plainManifest = SvBackupCrypto.decryptRecord(key, index0, aad0, sealed0)
            previousTag = sealed0.copyOfRange(length0, length0 + SvBackupCrypto.TAG_SIZE)

            val manifest = SvBackupManifest.fromJson(String(plainManifest, StandardCharsets.UTF_8))
            consumer.onManifest(manifest)

            val totalDeclaredBytes = manifest.totalBytes()
            var totalConsumedBytes = 0L

            // 2. Records 1..N: Media Chunks (Type 2)
            for (item in manifest.items) {
                coroutineContext.ensureActive()
                var remaining = item.size
                var chunkIdx = 0
                val totalChunks = if (item.size == 0L) 0 else ((item.size + SvBackupCrypto.CHUNK_SIZE - 1) / SvBackupCrypto.CHUNK_SIZE).toInt()

                while (remaining > 0L) {
                    recordIndex++
                    coroutineContext.ensureActive()

                    val rh = readExactly(SvBackupCrypto.RECORD_HEADER_SIZE)
                    val rhBuf = ByteBuffer.wrap(rh)
                    val type = rh[0].toInt()
                    val seq = rhBuf.getInt(1)
                    val length = rhBuf.getInt(5)

                    require(type == SvBackupCrypto.TYPE_MEDIA_CHUNK) {
                        "Expected media chunk (Type 2) for item ${item.id}, got Type $type at index $seq"
                    }
                    require(seq == recordIndex) {
                        "Sequence mismatch: expected $recordIndex, got $seq"
                    }

                    val expectedChunkLen = minOf(remaining, SvBackupCrypto.CHUNK_SIZE.toLong()).toInt()
                    require(length == expectedChunkLen) {
                        "Chunk length mismatch: expected $expectedChunkLen, got $length at index $seq"
                    }

                    val sealed = readExactly(length + SvBackupCrypto.TAG_SIZE)
                    val aad = SvBackupCrypto.buildAad(header, rh, previousTag)
                    val plainChunk = SvBackupCrypto.decryptRecord(key, seq, aad, sealed)
                    previousTag = sealed.copyOfRange(length, length + SvBackupCrypto.TAG_SIZE)

                    try {
                        consumer.onMediaChunk(item, chunkIdx, totalChunks, plainChunk)
                    } finally {
                        Arrays.fill(plainChunk, 0.toByte())
                    }

                    remaining -= length
                    totalConsumedBytes += length
                    chunkIdx++
                }
            }

            // 3. Final Summary Record (Type 3)
            recordIndex++
            coroutineContext.ensureActive()

            val rhFinal = readExactly(SvBackupCrypto.RECORD_HEADER_SIZE)
            val rhFinalBuf = ByteBuffer.wrap(rhFinal)
            val typeFinal = rhFinal[0].toInt()
            val seqFinal = rhFinalBuf.getInt(1)
            val lengthFinal = rhFinalBuf.getInt(5)

            require(typeFinal == SvBackupCrypto.TYPE_FINAL_SUMMARY) {
                "Expected final summary (Type 3), got Type $typeFinal at index $seqFinal"
            }
            require(seqFinal == recordIndex) {
                "Summary sequence mismatch: expected $recordIndex, got $seqFinal"
            }
            require(lengthFinal in 1..4096) { "Invalid final summary length: $lengthFinal" }

            val sealedFinal = readExactly(lengthFinal + SvBackupCrypto.TAG_SIZE)
            val aadFinal = SvBackupCrypto.buildAad(header, rhFinal, previousTag)
            val plainFinal = SvBackupCrypto.decryptRecord(key, seqFinal, aadFinal, sealedFinal)

            val summary = SvBackupSummary.fromJson(String(plainFinal, StandardCharsets.UTF_8))
            require(summary.items == manifest.items.size) {
                "Summary item count mismatch: declared ${summary.items}, manifest has ${manifest.items.size}"
            }
            require(summary.bytes == totalDeclaredBytes) {
                "Summary byte count mismatch: declared ${summary.bytes}, manifest has $totalDeclaredBytes"
            }
            require(totalConsumedBytes == totalDeclaredBytes) {
                "Consumed byte count mismatch: consumed $totalConsumedBytes, declared $totalDeclaredBytes"
            }

            // 4. True EOF Verification (no trailing data permitted)
            val nextByte = inputStream.read()
            require(nextByte == -1) { "Incomplete or trailing backup data: extra bytes after summary" }

            // 5. Notify complete
            consumer.onComplete(manifest, summary)
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }

    private fun readExactly(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var totalRead = 0
        while (totalRead < n) {
            val count = inputStream.read(buffer, totalRead, n - totalRead)
            if (count == -1) {
                throw EOFException("Unexpected end of archive stream (needed $n bytes, got $totalRead)")
            }
            totalRead += count
        }
        return buffer
    }
}
