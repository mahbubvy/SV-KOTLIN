package com.secretvault.app.crypto

import com.secretvault.app.core.backup.SvBackupCrypto
import com.secretvault.app.core.backup.SvContentFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

class SvContentFingerprintTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test fun fingerprintUsesBackupBoundariesRegardlessOfStreamWrites() {
        for (size in listOf(0, 31, SvBackupCrypto.CHUNK_SIZE, SvBackupCrypto.CHUNK_SIZE + 1, 700_003)) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            val reference = MessageDigest.getInstance("SHA-256")
            for (offset in bytes.indices step SvBackupCrypto.CHUNK_SIZE) {
                reference.update(MessageDigest.getInstance("SHA-256").apply {
                    update(bytes, offset, minOf(SvBackupCrypto.CHUNK_SIZE, size - offset))
                }.digest())
            }
            val expected = reference.digest().toHexString()
            for (writeSize in listOf(1, 65_536, 123_457, SvBackupCrypto.CHUNK_SIZE, 800_000)) {
                val fingerprint = SvContentFingerprint()
                fingerprint.update(bytes, 0, 0)
                for (offset in bytes.indices step writeSize) {
                    fingerprint.update(bytes, offset, minOf(writeSize, size - offset))
                }
                assertEquals("size=$size writeSize=$writeSize", expected, fingerprint.finish())
                assertEquals(expected, fingerprint.finish())
            }
            if (size > 0) {
                bytes[size - 1] = (bytes[size - 1].toInt() xor 1).toByte()
                assertNotEquals(expected, SvContentFingerprint().apply { update(bytes) }.finish())
            }
        }
    }
}
