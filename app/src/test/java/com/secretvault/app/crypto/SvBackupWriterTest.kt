package com.secretvault.app.crypto

import com.secretvault.app.core.backup.SvBackupAlbum
import com.secretvault.app.core.backup.SvBackupConsumer
import com.secretvault.app.core.backup.SvBackupCrypto
import com.secretvault.app.core.backup.SvBackupItem
import com.secretvault.app.core.backup.SvBackupManifest
import com.secretvault.app.core.backup.SvBackupReader
import com.secretvault.app.core.backup.SvBackupSummary
import com.secretvault.app.core.backup.SvBackupWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Random

class SvBackupWriterTest {

    @Test
    fun testWriterAndReaderRoundTripWithMultiChunkMedia() = runBlocking {
        val password = "StrongBackupPassword123!"

        // 1. Synthesize test data
        val photoData = "Synthetic photo payload bytes for unit test".toByteArray(StandardCharsets.UTF_8)
        
        // Multi-chunk video: 300 KiB = 307,200 bytes (chunk size is 262,144 bytes, so this spans 2 chunks)
        val videoSize = 300 * 1024
        val videoData = ByteArray(videoSize)
        Random(42).nextBytes(videoData)

        val photoId = "d3b07384-d113-469b-8218-12e964b4c73b"
        val videoId = "e4c18495-e224-570c-9329-23f075c5d84c"

        val albums = listOf(
            SvBackupAlbum(
                id = "album_vacation",
                name = "Vacation 2026",
                kind = "custom",
                createdAt = "2026-09-26T12:00:00Z",
                coverItemId = photoId
            ),
            SvBackupAlbum(
                id = "album_empty",
                name = "Empty Album",
                kind = "custom",
                createdAt = "2026-09-26T12:00:00Z",
                coverItemId = null
            )
        )

        val items = listOf(
            SvBackupItem(
                id = photoId,
                albumId = "album_vacation",
                originalFilename = "beach.jpg",
                mediaType = "photo",
                mimeType = "image/jpeg",
                size = photoData.size.toLong(),
                createdAt = "2026-09-26T12:01:00Z"
            ),
            SvBackupItem(
                id = videoId,
                albumId = "album_vacation",
                originalFilename = "waves.mp4",
                mediaType = "video",
                mimeType = "video/mp4",
                size = videoData.size.toLong(),
                createdAt = "2026-09-26T12:02:00Z"
            )
        )

        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T12:00:00Z",
            albums = albums,
            items = items
        )

        // 2. Write archive using SvBackupWriter
        val outStream = ByteArrayOutputStream()
        val writer = SvBackupWriter(outStream, password)

        writer.writeArchive(
            manifest = manifest,
            mediaProvider = { item, chunkConsumer ->
                val source = if (item.id == photoId) photoData else videoData
                var offset = 0
                while (offset < source.size) {
                    val len = minOf(source.size - offset, SvBackupCrypto.CHUNK_SIZE)
                    val chunk = source.copyOfRange(offset, offset + len)
                    chunkConsumer(chunk)
                    offset += len
                }
            }
        )

        val archiveBytes = outStream.toByteArray()
        // Header (32) + Manifest record + Photo record + Video chunk 1 + Video chunk 2 + Summary record
        // All encrypted under AES-GCM
        assert(archiveBytes.size > photoData.size + videoData.size)

        // 3. Read back archive using SvBackupReader
        val reader = SvBackupReader(ByteArrayInputStream(archiveBytes), password)
        var restoredManifest: SvBackupManifest? = null
        val restoredPhoto = ByteArrayOutputStream()
        val restoredVideo = ByteArrayOutputStream()
        var restoredSummary: SvBackupSummary? = null

        reader.readArchive(object : SvBackupConsumer {
            override suspend fun onManifest(manifest: SvBackupManifest) {
                restoredManifest = manifest
            }

            override suspend fun onMediaChunk(
                item: SvBackupItem,
                chunkIndex: Int,
                totalChunks: Int,
                chunkBytes: ByteArray
            ) {
                if (item.id == photoId) {
                    restoredPhoto.write(chunkBytes)
                } else if (item.id == videoId) {
                    restoredVideo.write(chunkBytes)
                }
            }

            override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {
                restoredSummary = summary
            }
        })

        // 4. Validate complete fidelity
        assertNotNull(restoredManifest)
        assertEquals(2, restoredManifest!!.albums.size)
        assertEquals("album_vacation", restoredManifest!!.albums[0].id)
        assertEquals(photoId, restoredManifest!!.albums[0].coverItemId)
        assertEquals("album_empty", restoredManifest!!.albums[1].id)

        assertEquals(2, restoredManifest!!.items.size)
        assertEquals("beach.jpg", restoredManifest!!.items[0].originalFilename)
        assertEquals("waves.mp4", restoredManifest!!.items[1].originalFilename)

        assertArrayEquals(photoData, restoredPhoto.toByteArray())
        assertArrayEquals(videoData, restoredVideo.toByteArray())

        assertNotNull(restoredSummary)
        assertEquals(2, restoredSummary!!.items)
        assertEquals((photoData.size + videoData.size).toLong(), restoredSummary!!.bytes)
    }

    @Test
    fun testWriterAndReaderWithUnicodeAndSpecialPassword() = runBlocking {
        val unicodePassword = "🔒Pässwörd With Spaces & Accents — 2026"
        val payload = "Unicode password test payload".toByteArray(StandardCharsets.UTF_8)
        val testItemId = "11111111-2222-3333-4444-555555555555"

        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T12:00:00Z",
            albums = listOf(
                SvBackupAlbum("album_1", "Test Album", "custom", "2026-09-26T12:00:00Z", null)
            ),
            items = listOf(
                SvBackupItem(testItemId, "album_1", "test.txt", "photo", "text/plain", payload.size.toLong(), "2026-09-26T12:00:00Z")
            )
        )

        val outStream = ByteArrayOutputStream()
        val writer = SvBackupWriter(outStream, unicodePassword)
        writer.writeArchive(
            manifest = manifest,
            mediaProvider = { _, chunkConsumer ->
                chunkConsumer(payload)
            }
        )

        val reader = SvBackupReader(ByteArrayInputStream(outStream.toByteArray()), unicodePassword)
        val readPayload = ByteArrayOutputStream()

        reader.readArchive(object : SvBackupConsumer {
            override suspend fun onManifest(manifest: SvBackupManifest) {}
            override suspend fun onMediaChunk(item: SvBackupItem, chunkIndex: Int, totalChunks: Int, chunkBytes: ByteArray) {
                readPayload.write(chunkBytes)
            }
            override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {}
        })

        assertArrayEquals(payload, readPayload.toByteArray())
    }
}
