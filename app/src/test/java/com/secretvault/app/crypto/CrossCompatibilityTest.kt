package com.secretvault.app.crypto

import com.secretvault.app.core.backup.SvBackupAlbum
import com.secretvault.app.core.backup.SvBackupItem
import com.secretvault.app.core.backup.SvBackupManifest
import com.secretvault.app.core.database.entity.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates manifest against SecretVault's exact wire rules from:
 * - K:\Projects\SecretVault\src\services\vault-backup-manifest.ts
 * - K:\Projects\SecretVault\src\constants\vault.ts
 */
class CrossCompatibilityTest {

    private val systemAlbums = listOf(
        "system-camera" to "Camera",
        "system-imports" to "Imports",
        "system-unsorted" to "Unsorted"
    )
    private val uuidRegex = Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)

    private fun validateSecretVaultManifest(manifest: SvBackupManifest) {
        require(manifest.version == 1) { "Invalid version: ${manifest.version}" }
        val albumIds = mutableSetOf<String>()
        val albumNames = mutableSetOf<String>()
        for (album in manifest.albums) {
            require(album.id.length in 1..80) { "Invalid album id length" }
            require(album.name.trim().isNotEmpty() && album.name.length <= 80) { "Invalid album name" }
            require(album.kind in listOf("system", "custom")) { "Invalid album kind" }
            if (album.kind == "system") {
                require(systemAlbums.any { it.first == album.id && it.second == album.name }) {
                    "System album '${album.id}' ('${album.name}') does not match SecretVault SYSTEM_ALBUMS!"
                }
            }
            val lowerName = album.name.trim().lowercase()
            require(albumIds.add(album.id)) { "Duplicate album ID: ${album.id}" }
            require(albumNames.add(lowerName)) { "Duplicate album name: $lowerName" }
        }

        val itemIds = mutableSetOf<String>()
        for (item in manifest.items) {
            require(uuidRegex.matches(item.id)) {
                "Item ID '${item.id}' does not match UUID regex /^[0-9a-f-]{36}$/i"
            }
            require(itemIds.add(item.id)) { "Duplicate item ID: ${item.id}" }
            require(albumIds.contains(item.albumId)) { "Item album ID not in manifest: ${item.albumId}" }
            require(item.mediaType in listOf("photo", "video")) { "Invalid media type: ${item.mediaType}" }
            require(item.originalFilename.length in 1..512) { "Invalid filename length" }
            require(!item.originalFilename.contains('/') && !item.originalFilename.contains('\\') && !item.originalFilename.contains('\u0000')) {
                "Filename contains illegal characters"
            }
            require(item.size >= 0) { "Negative size: ${item.size}" }
        }

        for (album in manifest.albums) {
            if (album.coverItemId != null) {
                val cover = manifest.items.find { it.id == album.coverItemId }
                requireNotNull(cover) { "Cover item ${album.coverItemId} missing" }
                require(cover.mediaType == "photo") { "Cover item must be photo" }
                require(cover.albumId == album.id) { "Cover item must belong to album" }
            }
        }
    }

    @Test
    fun testExistingExporterManifestFailsSecretVaultValidationDueToSystemAlbumId() {
        // This simulates what BackupExportManager was exporting for Camera album
        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T12:00:00Z",
            albums = listOf(
                SvBackupAlbum(
                    id = AlbumEntity.ALBUM_CAMERA_ID, // "album_camera"
                    name = "Camera",
                    kind = "system",
                    createdAt = "2026-09-26T12:00:00Z",
                    coverItemId = null
                )
            ),
            items = emptyList()
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            validateSecretVaultManifest(manifest)
        }
        println("Verified failure 1: ${error.message}")
        assert(error.message!!.contains("does not match SecretVault SYSTEM_ALBUMS"))
    }

    @Test
    fun testExistingExporterManifestFailsSecretVaultValidationDueToNonUuidItemId() {
        // This simulates what BackupExportManager was exporting for items with ID like "photo_abc12345"
        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T12:00:00Z",
            albums = listOf(
                SvBackupAlbum(
                    id = "system-camera",
                    name = "Camera",
                    kind = "system",
                    createdAt = "2026-09-26T12:00:00Z",
                    coverItemId = null
                )
            ),
            items = listOf(
                SvBackupItem(
                    id = "photo_12345678", // Non-UUID internal ID
                    albumId = "system-camera",
                    originalFilename = "IMG_001.jpg",
                    mediaType = "photo",
                    mimeType = "image/jpeg",
                    size = 100L,
                    createdAt = "2026-09-26T12:00:00Z"
                )
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            validateSecretVaultManifest(manifest)
        }
        println("Verified failure 2: ${error.message}")
        assert(error.message!!.contains("does not match UUID regex"))
    }

    @Test
    fun testUpdatedExporterManifestPassesSecretVaultValidation() {
        // Test that our updated toCanonicalUuid and system album mapping produce a 100% compliant manifest
        val photoUuid = com.secretvault.app.core.backup.BackupExportManager.toCanonicalUuid("photo_12345678")
        val videoUuid = com.secretvault.app.core.backup.BackupExportManager.toCanonicalUuid("video_87654321")

        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T12:00:00.000Z",
            albums = listOf(
                SvBackupAlbum(
                    id = SvBackupManifest.SYSTEM_ALBUM_CAMERA,
                    name = "Camera",
                    kind = "system",
                    createdAt = "2026-09-26T12:00:00.000Z",
                    coverItemId = photoUuid
                ),
                SvBackupAlbum(
                    id = SvBackupManifest.SYSTEM_ALBUM_IMPORTS,
                    name = "Imports",
                    kind = "system",
                    createdAt = "2026-09-26T12:00:00.000Z",
                    coverItemId = null
                ),
                SvBackupAlbum(
                    id = "custom-album-001",
                    name = "Summer Trip",
                    kind = "custom",
                    createdAt = "2026-09-26T12:00:00.000Z",
                    coverItemId = null
                )
            ),
            items = listOf(
                SvBackupItem(
                    id = photoUuid,
                    albumId = SvBackupManifest.SYSTEM_ALBUM_CAMERA,
                    originalFilename = "IMG_001.jpg",
                    mediaType = "photo",
                    mimeType = "image/jpeg",
                    size = 1024L,
                    createdAt = "2026-09-26T12:00:00.000Z"
                ),
                SvBackupItem(
                    id = videoUuid,
                    albumId = "custom-album-001",
                    originalFilename = "VID_002.mp4",
                    mediaType = "video",
                    mimeType = "video/mp4",
                    size = 2048L,
                    createdAt = "2026-09-26T12:00:00.000Z"
                )
            )
        )

        // Must not throw any exception!
        validateSecretVaultManifest(manifest)
    }

    @Test
    fun testImportArchiveGeneratedBySecretVaultNode() = kotlinx.coroutines.runBlocking {
        val nodeArchive = java.io.File("k:/gemini/scratch/node_export.svbackup")
        if (!nodeArchive.exists()) {
            println("Skipping testImportArchiveGeneratedBySecretVaultNode: node_export.svbackup does not exist")
            return@runBlocking
        }

        val password = "CrossVaultPassword2026!#"
        val reader = com.secretvault.app.core.backup.SvBackupReader(
            java.io.FileInputStream(nodeArchive),
            password
        )

        var manifest: SvBackupManifest? = null
        val receivedMedia = mutableMapOf<String, java.io.ByteArrayOutputStream>()
        var summary: com.secretvault.app.core.backup.SvBackupSummary? = null

        reader.readArchive(object : com.secretvault.app.core.backup.SvBackupConsumer {
            override suspend fun onManifest(m: SvBackupManifest) {
                manifest = m
            }

            override suspend fun onMediaChunk(
                item: SvBackupItem,
                chunkIndex: Int,
                totalChunks: Int,
                chunkBytes: ByteArray
            ) {
                val stream = receivedMedia.getOrPut(item.id) { java.io.ByteArrayOutputStream() }
                stream.write(chunkBytes)
            }

            override suspend fun onComplete(
                m: SvBackupManifest,
                s: com.secretvault.app.core.backup.SvBackupSummary
            ) {
                summary = s
            }
        })

        org.junit.Assert.assertNotNull(manifest)
        assertEquals(2, manifest!!.albums.size)
        assertEquals(2, manifest!!.items.size)

        val photoItem = manifest!!.items.find { it.mediaType == "photo" }!!
        val videoItem = manifest!!.items.find { it.mediaType == "video" }!!

        val expectedPhoto = java.io.File("k:/gemini/scratch/expected_photo.bin").readBytes()
        val expectedVideo = java.io.File("k:/gemini/scratch/expected_video.bin").readBytes()

        org.junit.Assert.assertArrayEquals(expectedPhoto, receivedMedia[photoItem.id]!!.toByteArray())
        org.junit.Assert.assertArrayEquals(expectedVideo, receivedMedia[videoItem.id]!!.toByteArray())

        org.junit.Assert.assertNotNull(summary)
        assertEquals(2, summary!!.items)
        assertEquals((expectedPhoto.size + expectedVideo.size).toLong(), summary!!.bytes)
    }

    @Test
    fun testExportArchiveForSecretVaultNode() = kotlinx.coroutines.runBlocking {
        val scratchDir = java.io.File("k:/gemini/scratch")
        if (!scratchDir.exists()) scratchDir.mkdirs()

        val expectedPhoto = java.io.File(scratchDir, "expected_photo.bin")
        val expectedVideo = java.io.File(scratchDir, "expected_video.bin")

        val photoBytes = if (expectedPhoto.exists()) expectedPhoto.readBytes() else ByteArray(128) { (it % 256).toByte() }
        val videoBytes = if (expectedVideo.exists()) expectedVideo.readBytes() else ByteArray(262144 + 42) { ((it + 5) % 256).toByte() }

        if (!expectedPhoto.exists()) expectedPhoto.writeBytes(photoBytes)
        if (!expectedVideo.exists()) expectedVideo.writeBytes(videoBytes)

        val photoUuid = "11111111-2222-3333-4444-555555555555"
        val videoUuid = "66666666-7777-8888-9999-000000000000"

        val manifest = SvBackupManifest(
            version = 1,
            createdAt = "2026-09-26T14:00:00.000Z",
            albums = listOf(
                SvBackupAlbum(
                    id = SvBackupManifest.SYSTEM_ALBUM_CAMERA,
                    name = "Camera",
                    kind = "system",
                    createdAt = "2026-09-26T14:00:00.000Z",
                    coverItemId = photoUuid
                ),
                SvBackupAlbum(
                    id = "custom-album-vacation",
                    name = "Vacation",
                    kind = "custom",
                    createdAt = "2026-09-26T14:00:00.000Z",
                    coverItemId = null
                )
            ),
            items = listOf(
                SvBackupItem(
                    id = photoUuid,
                    albumId = SvBackupManifest.SYSTEM_ALBUM_CAMERA,
                    originalFilename = "camera_photo.jpg",
                    mediaType = "photo",
                    mimeType = "image/jpeg",
                    size = photoBytes.size.toLong(),
                    createdAt = "2026-09-26T14:00:00.000Z"
                ),
                SvBackupItem(
                    id = videoUuid,
                    albumId = "custom-album-vacation",
                    originalFilename = "vacation_clip.mp4",
                    mediaType = "video",
                    mimeType = "video/mp4",
                    size = videoBytes.size.toLong(),
                    createdAt = "2026-09-26T14:00:00.000Z"
                )
            )
        )

        val outArchive = java.io.File(scratchDir, "kotlin_export.svbackup")
        val password = "CrossVaultPassword2026!#"

        java.io.FileOutputStream(outArchive).buffered().use { outStream ->
            val writer = com.secretvault.app.core.backup.SvBackupWriter(outStream, password)
            writer.writeArchive(manifest, { item, chunkConsumer ->
                val bytes = if (item.id == photoUuid) photoBytes else videoBytes
                var offset = 0
                val chunkSize = com.secretvault.app.core.backup.SvBackupCrypto.CHUNK_SIZE
                while (offset < bytes.size) {
                    val len = minOf(chunkSize, bytes.size - offset)
                    val chunk = bytes.copyOfRange(offset, offset + len)
                    chunkConsumer(chunk)
                    offset += len
                }
            })
        }

        assertTrue(outArchive.exists())
        assertTrue(outArchive.length() > 0)
    }
}
