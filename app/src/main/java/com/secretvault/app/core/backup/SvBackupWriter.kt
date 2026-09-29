package com.secretvault.app.core.backup

import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import kotlin.coroutines.coroutineContext

/**
 * Interface for providing plaintext media chunks to [SvBackupWriter].
 */
fun interface SvBackupMediaProvider {
    /**
     * For the given manifest item, streams chunks of at most [SvBackupCrypto.CHUNK_SIZE] (256 KiB)
     * by invoking [chunkConsumer] sequentially.
     */
    suspend fun provideChunks(item: SvBackupItem, chunkConsumer: (ByteArray) -> Unit)
}

/**
 * Bounded streaming writer for .svbackup (version 1) archives.
 * Encrypts and authenticates records incrementally without loading the entire archive into memory.
 */
class SvBackupWriter(
    private val outputStream: OutputStream,
    private val password: String
) {
    /**
     * Writes the complete encrypted .svbackup archive.
     * @param manifest The validated manifest describing all albums and items.
     * @param mediaProvider A callback that supplies plaintext chunks for each item in manifest order.
     * @param onProgress Optional progress callback reporting (bytesWritten, totalBytes).
     */
    suspend fun writeArchive(
        manifest: SvBackupManifest,
        mediaProvider: SvBackupMediaProvider,
        onProgress: ((bytesWritten: Long, totalBytes: Long) -> Unit)? = null
    ) {
        val totalDeclaredBytes = manifest.totalBytes()
        var totalWrittenBytes = 0L

        // 1. Generate 16-byte random salt and write 32-byte header
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val header = SvBackupCrypto.buildHeader(salt)
        outputStream.write(header)

        val key = SvBackupCrypto.deriveKey(password, salt)
        var recordIndex = 0
        var previousTag = ByteArray(SvBackupCrypto.TAG_SIZE) // 16 zeroes for record 0

        try {
            coroutineContext.ensureActive()

            // 2. Record 0: Manifest (Type 1)
            val manifestJsonBytes = manifest.toJson().toByteArray(StandardCharsets.UTF_8)
            val rh0 = SvBackupCrypto.buildRecordHeader(
                type = SvBackupCrypto.TYPE_MANIFEST,
                index = 0,
                ciphertextLength = manifestJsonBytes.size
            )
            val aad0 = SvBackupCrypto.buildAad(header, rh0, previousTag)
            val sealed0 = SvBackupCrypto.encryptRecord(key, 0, aad0, manifestJsonBytes)

            outputStream.write(rh0)
            outputStream.write(sealed0)
            // In AES-GCM, the last 16 bytes of sealedBytes is the authentication tag
            previousTag = sealed0.copyOfRange(sealed0.size - SvBackupCrypto.TAG_SIZE, sealed0.size)

            // 3. Records 1..N: Media Chunks (Type 2)
            for (item in manifest.items) {
                coroutineContext.ensureActive()
                var itemRemaining = item.size

                if (item.size > 0L) {
                    mediaProvider.provideChunks(item) { chunkBytes ->
                        recordIndex++

                        val rh = SvBackupCrypto.buildRecordHeader(
                            type = SvBackupCrypto.TYPE_MEDIA_CHUNK,
                            index = recordIndex,
                            ciphertextLength = chunkBytes.size
                        )
                        val aad = SvBackupCrypto.buildAad(header, rh, previousTag)
                        val sealed = SvBackupCrypto.encryptRecord(key, recordIndex, aad, chunkBytes)

                        outputStream.write(rh)
                        outputStream.write(sealed)
                        previousTag = sealed.copyOfRange(sealed.size - SvBackupCrypto.TAG_SIZE, sealed.size)

                        totalWrittenBytes += chunkBytes.size
                        itemRemaining -= chunkBytes.size
                        onProgress?.invoke(totalWrittenBytes, totalDeclaredBytes)
                    }

                    require(itemRemaining == 0L) {
                        "Provider produced inconsistent byte count for item ${item.id}: expected ${item.size}, remaining $itemRemaining"
                    }
                }
            }

            require(totalWrittenBytes == totalDeclaredBytes) {
                "Total written bytes mismatch: wrote $totalWrittenBytes, declared $totalDeclaredBytes"
            }

            // 4. Record N+1: Final Summary (Type 3)
            recordIndex++
            coroutineContext.ensureActive()

            val summary = SvBackupSummary(items = manifest.items.size, bytes = totalDeclaredBytes)
            val summaryJsonBytes = summary.toJson().toByteArray(StandardCharsets.UTF_8)

            val rhFinal = SvBackupCrypto.buildRecordHeader(
                type = SvBackupCrypto.TYPE_FINAL_SUMMARY,
                index = recordIndex,
                ciphertextLength = summaryJsonBytes.size
            )
            val aadFinal = SvBackupCrypto.buildAad(header, rhFinal, previousTag)
            val sealedFinal = SvBackupCrypto.encryptRecord(key, recordIndex, aadFinal, summaryJsonBytes)

            outputStream.write(rhFinal)
            outputStream.write(sealedFinal)

            outputStream.flush()
        } finally {
            Arrays.fill(key, 0.toByte())
        }
    }
}
