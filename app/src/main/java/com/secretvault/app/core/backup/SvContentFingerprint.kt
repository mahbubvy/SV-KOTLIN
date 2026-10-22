package com.secretvault.app.core.backup

import java.security.MessageDigest

/**
 * Standard content fingerprinting for media duplicate detection.
 * Matches SecretVault reference implementation in vault-content-fingerprint.ts:
 * Fixed-size plaintext chunks are SHA-256 hashed individually, and then the concatenated
 * hashes are SHA-256 hashed to produce a 64-character lowercase hex string.
 */
class SvContentFingerprint {
    private val chunkDigest = MessageDigest.getInstance("SHA-256")
    private val digest = MessageDigest.getInstance("SHA-256")
    private var chunkBytes = 0
    private var fingerprint: String? = null

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(fingerprint == null) { "Fingerprint already finalized" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        var position = offset
        val end = offset + length
        // Archive records and vault decryption writes have different boundaries.
        while (position < end) {
            val count = minOf(end - position, SvBackupCrypto.CHUNK_SIZE - chunkBytes)
            chunkDigest.update(bytes, position, count)
            chunkBytes += count
            position += count
            if (chunkBytes == SvBackupCrypto.CHUNK_SIZE) {
                digest.update(chunkDigest.digest())
                chunkBytes = 0
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun finish(): String {
        fingerprint?.let { return it }
        if (chunkBytes > 0) digest.update(chunkDigest.digest())
        return digest.digest().toHexString().also { fingerprint = it }
    }

    companion object {
        fun identity(mediaType: String, sizeBytes: Long, fingerprint: String): String {
            return "${mediaType.lowercase()}:$sizeBytes:$fingerprint"
        }
    }
}
