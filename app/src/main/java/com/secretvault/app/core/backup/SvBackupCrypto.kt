package com.secretvault.app.core.backup

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level cryptographic primitives for reading .svbackup (version 1) archives.
 * Follows the wire contract specified in SVBACKUP-Kotlin-Importer-Handoff.md.
 */
object SvBackupCrypto {
    val MAGIC = "SVBACK01".toByteArray(StandardCharsets.US_ASCII)
    const val HEADER_SIZE = 32
    const val RECORD_HEADER_SIZE = 9
    const val TAG_SIZE = 16
    const val NONCE_SIZE = 12
    const val AAD_SIZE = 57 // 32 header + 9 recordHeader + 16 previousTag
    const val CHUNK_SIZE = 262144 // 256 KiB
    const val EXPECTED_ITERATIONS = 600000
    const val MAX_MANIFEST_SIZE = 16 * 1024 * 1024 // 16 MiB

    const val TYPE_MANIFEST = 1
    const val TYPE_MEDIA_CHUNK = 2
    const val TYPE_FINAL_SUMMARY = 3

    /**
     * Validates the 32-byte header and extracts the 16-byte salt.
     * Throws IllegalArgumentException if the header is invalid.
     */
    fun parseAndValidateHeader(header: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "Invalid header size: ${header.size}" }
        val magic = header.copyOfRange(0, 8)
        require(magic.contentEquals(MAGIC)) { "Invalid magic: not SVBACK01" }

        val buf = ByteBuffer.wrap(header)
        val iterations = buf.getInt(8)
        require(iterations == EXPECTED_ITERATIONS) { "Unsupported iterations: $iterations (expected $EXPECTED_ITERATIONS)" }

        val chunkSize = buf.getInt(12)
        require(chunkSize == CHUNK_SIZE) { "Unsupported chunk size: $chunkSize (expected $CHUNK_SIZE)" }

        return header.copyOfRange(16, 32)
    }

    /**
     * Derives the 256-bit AES key from the password and 16-byte salt using PBKDF2-HMAC-SHA256 (600,000 iterations).
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val passwordChars = password.toCharArray()
        val spec = PBEKeySpec(passwordChars, salt, EXPECTED_ITERATIONS, 256)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            Arrays.fill(passwordChars, '\u0000')
        }
    }

    /**
     * Constructs the 12-byte nonce: 8 zero bytes + unsigned 32-bit big-endian index.
     */
    fun buildNonce(index: Int): ByteArray {
        return ByteBuffer.allocate(NONCE_SIZE)
            .putLong(0L)
            .putInt(index)
            .array()
    }

    /**
     * Constructs the 57-byte AAD: header (32B) || recordHeader (9B) || previousTag (16B).
     */
    fun buildAad(header: ByteArray, recordHeader: ByteArray, previousTag: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE) { "Header must be 32 bytes" }
        require(recordHeader.size == RECORD_HEADER_SIZE) { "Record header must be 9 bytes" }
        require(previousTag.size == TAG_SIZE) { "Previous tag must be 16 bytes" }

        val aad = ByteArray(AAD_SIZE)
        System.arraycopy(header, 0, aad, 0, HEADER_SIZE)
        System.arraycopy(recordHeader, 0, aad, HEADER_SIZE, RECORD_HEADER_SIZE)
        System.arraycopy(previousTag, 0, aad, HEADER_SIZE + RECORD_HEADER_SIZE, TAG_SIZE)
        return aad
    }

    /**
     * Decrypts and authenticates a single record sealed payload (ciphertext + 16-byte tag).
     * @param key The 32-byte derived AES key.
     * @param index The expected record index.
     * @param aad The 57-byte AAD.
     * @param sealedBytes The ciphertext followed by 16-byte GCM tag.
     * @return Decrypted plaintext bytes.
     * @throws GeneralSecurityException if authentication fails (wrong password or corrupted bytes).
     */
    fun decryptRecord(
        key: ByteArray,
        index: Int,
        aad: ByteArray,
        sealedBytes: ByteArray
    ): ByteArray {
        val nonce = buildNonce(index)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealedBytes)
    }

    /**
     * Builds the 32-byte header for SVBACK01 archives.
     */
    fun buildHeader(salt: ByteArray): ByteArray {
        require(salt.size == 16) { "Salt must be 16 bytes" }
        return ByteBuffer.allocate(HEADER_SIZE)
            .put(MAGIC)
            .putInt(EXPECTED_ITERATIONS)
            .putInt(CHUNK_SIZE)
            .put(salt)
            .array()
    }

    /**
     * Builds the 9-byte record header.
     */
    fun buildRecordHeader(type: Int, index: Int, ciphertextLength: Int): ByteArray {
        return ByteBuffer.allocate(RECORD_HEADER_SIZE)
            .put(type.toByte())
            .putInt(index)
            .putInt(ciphertextLength)
            .array()
    }

    /**
     * Encrypts a record payload under AES-256-GCM.
     * Returns ciphertext concatenated with the 16-byte authentication tag.
     */
    fun encryptRecord(
        key: ByteArray,
        index: Int,
        aad: ByteArray,
        plainBytes: ByteArray
    ): ByteArray {
        val nonce = buildNonce(index)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plainBytes)
    }
}
