package com.secretvault.app.crypto

import com.secretvault.app.core.backup.SvBackupConsumer
import com.secretvault.app.core.backup.SvBackupItem
import com.secretvault.app.core.backup.SvBackupManifest
import com.secretvault.app.core.backup.SvBackupReader
import com.secretvault.app.core.backup.SvBackupSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.Base64

class SvBackupReaderTest {

    private val fixtureBase64 = "U1ZCQUNLMDEACSfAAAQAALH6fc2uiLNt4bPDxn0dNRwBAAAAAAAAAUTy+VEyuMjAaO7U0RyELAXBq2gXHp0M6t1cBw3CjOCaXPecNVr/rarOCa2oTCPsdmYZBwo7AnI8mO5mvk4Cpxz53eo/GS8GQZTUGUzlQxpKpm7vpwRfVY2oPzX35RrMj1x4XQ06PQH2gpTuzmx/eJZcPmwmsVQFBneZ6PqyvWKEh9M//in+PZUjN/Zu7agxSnWEP+79o+8aljkTOZ+XeFHBD49cQoPM5/6kq8yRxvVHEixjPcMg+mMGdwBGtjXNlJEoAtECYN4ztjdJlYNDAyPWJWPk189kGzMCuevWoUdC2/I+yjeT3sQV8uvfBSfM8JUfXZuJRoK168xCBUTCGQP3HhhxwPq+6hGjc04sjSckgMBaQVPX8DIy2+IuqBT6kG+Y49xOGtby8zlfG7MhYUjQV5EGlrbaPXSqU2sFeZTSH08p0oO0hFbhx2TWKp9ephF5wq8RAgAAAAEAAAAFL5ZV9eAyeF/gIOTkJObzkwImEvGVAwAAAAIAAAAVDyX3U2lcVSn2B3R8t7btIo1nFQSDlQECNnTbZTb1ZxcoMczWyw=="
    private val fixturePassword = "Test password — 2026"

    @Test
    fun testSuccessfulStreamParsingOfReferenceArchive() = runBlocking {
        val archiveBytes = Base64.getDecoder().decode(fixtureBase64)
        val reader = SvBackupReader(ByteArrayInputStream(archiveBytes), fixturePassword)

        var parsedManifest: SvBackupManifest? = null
        val receivedMedia = mutableListOf<Byte>()
        var parsedSummary: SvBackupSummary? = null

        reader.readArchive(object : SvBackupConsumer {
            override suspend fun onManifest(manifest: SvBackupManifest) {
                parsedManifest = manifest
            }

            override suspend fun onMediaChunk(
                item: SvBackupItem,
                chunkIndex: Int,
                totalChunks: Int,
                chunkBytes: ByteArray
            ) {
                assertEquals("test.jpg", item.originalFilename)
                assertEquals(0, chunkIndex)
                assertEquals(1, totalChunks)
                receivedMedia.addAll(chunkBytes.toList())
            }

            override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {
                parsedSummary = summary
            }
        })

        assertNotNull(parsedManifest)
        assertEquals(1, parsedManifest!!.items.size)
        assertEquals(1, parsedManifest!!.albums.size)
        assertEquals("test.jpg", parsedManifest!!.items[0].originalFilename)
        assertEquals("hello", String(receivedMedia.toByteArray(), StandardCharsets.UTF_8))

        assertNotNull(parsedSummary)
        assertEquals(1, parsedSummary!!.items)
        assertEquals(5L, parsedSummary!!.bytes)
    }

    @Test
    fun testTruncatedArchiveThrowsEOF() {
        val archiveBytes = Base64.getDecoder().decode(fixtureBase64)
        // Truncate before the summary record
        val truncated = archiveBytes.copyOfRange(0, archiveBytes.size - 25)
        val reader = SvBackupReader(ByteArrayInputStream(truncated), fixturePassword)

        assertThrows(EOFException::class.java) {
            runBlocking {
                reader.readArchive(object : SvBackupConsumer {
                    override suspend fun onManifest(manifest: SvBackupManifest) {}
                    override suspend fun onMediaChunk(item: SvBackupItem, chunkIndex: Int, totalChunks: Int, chunkBytes: ByteArray) {}
                    override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {}
                })
            }
        }
    }

    @Test
    fun testTrailingBytesThrowsException() {
        val archiveBytes = Base64.getDecoder().decode(fixtureBase64)
        // Append 1 trailing byte
        val withTrailing = ByteArray(archiveBytes.size + 1)
        System.arraycopy(archiveBytes, 0, withTrailing, 0, archiveBytes.size)
        withTrailing[archiveBytes.size] = 0x42

        val reader = SvBackupReader(ByteArrayInputStream(withTrailing), fixturePassword)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                reader.readArchive(object : SvBackupConsumer {
                    override suspend fun onManifest(manifest: SvBackupManifest) {}
                    override suspend fun onMediaChunk(item: SvBackupItem, chunkIndex: Int, totalChunks: Int, chunkBytes: ByteArray) {}
                    override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {}
                })
            }
        }
        assertTrue(ex.message!!.contains("trailing"))
    }

    @Test
    fun testWrongPasswordThrowsSecurityException() {
        val archiveBytes = Base64.getDecoder().decode(fixtureBase64)
        val reader = SvBackupReader(ByteArrayInputStream(archiveBytes), "Incorrect Password")

        assertThrows(GeneralSecurityException::class.java) {
            runBlocking {
                reader.readArchive(object : SvBackupConsumer {
                    override suspend fun onManifest(manifest: SvBackupManifest) {}
                    override suspend fun onMediaChunk(item: SvBackupItem, chunkIndex: Int, totalChunks: Int, chunkBytes: ByteArray) {}
                    override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {}
                })
            }
        }
    }

    @Test
    fun testManifestRejectsPathTraversalFilename() {
        val badManifestJson = """
        {
            "version": 1,
            "createdAt": "2026-09-07T12:00:00.000Z",
            "albums": [
                { "id": "alb-1", "name": "Main", "kind": "custom", "createdAt": "2026-09-07T12:00:00.000Z" }
            ],
            "items": [
                {
                    "id": "item-1",
                    "albumId": "alb-1",
                    "originalFilename": "../../etc/passwd",
                    "mediaType": "photo",
                    "mimeType": "image/jpeg",
                    "size": 100,
                    "createdAt": "2026-09-07T12:00:00.000Z"
                }
            ]
        }
        """.trimIndent()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            SvBackupManifest.fromJson(badManifestJson)
        }
        assertTrue(ex.message!!.contains("path"))
    }

    @Test
    fun testManifestRejectsDuplicateAlbumNames() {
        val badManifestJson = """
        {
            "version": 1,
            "createdAt": "2026-09-07T12:00:00.000Z",
            "albums": [
                { "id": "alb-1", "name": "Vacation", "kind": "custom", "createdAt": "2026-09-07T12:00:00.000Z" },
                { "id": "alb-2", "name": " vacation ", "kind": "custom", "createdAt": "2026-09-07T12:00:00.000Z" }
            ],
            "items": []
        }
        """.trimIndent()

        val ex = assertThrows(IllegalArgumentException::class.java) {
            SvBackupManifest.fromJson(badManifestJson)
        }
        assertTrue(ex.message!!.contains("Duplicate album name"))
    }
}
