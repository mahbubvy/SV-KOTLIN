package com.secretvault.app.core.backup

import java.security.MessageDigest

/**
 * Standard content fingerprinting for media duplicate detection.
 * Matches SecretVault reference implementation in vault-content-fingerprint.ts:
 * Fixed-size plaintext chunks are SHA-256 hashed individually, and then the concatenated
 * hashes are SHA-256 hashed to produce a 64-character lowercase hex string.
 */
class SvContentFingerprint {
    private val chunkHashes = mutableListOf<ByteArray>()

    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes, offset, length)
        chunkHashes.add(md.digest())
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun finish(): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (h in chunkHashes) {
            md.update(h)
        }
        return md.digest().toHexString()
    }

    companion object {
        fun identity(mediaType: String, sizeBytes: Long, fingerprint: String): String {
            return "${mediaType.lowercase()}:$sizeBytes:$fingerprint"
        }
    }
}
