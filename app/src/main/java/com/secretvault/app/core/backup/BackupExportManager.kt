package com.secretvault.app.core.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.VaultDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Arrays
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed class BackupScope {
    object FullVault : BackupScope()
    data class SelectedAlbums(val albumIds: Set<String>) : BackupScope()
    data class SelectedMedia(val mediaIds: Set<String>) : BackupScope()
}

data class VaultSummary(
    val totalAlbums: Int,
    val totalItems: Int,
    val totalPhotos: Int,
    val totalVideos: Int,
    val estimatedSizeBytes: Long
)

data class BackupExportProgress(
    val isExporting: Boolean = false,
    val stage: String = "",
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val progressPercentage: Float = 0f,
    val activeFileName: String = ""
)

data class BackupExportResult(
    val success: Boolean,
    val exportedItemsCount: Int = 0,
    val exportedAlbumsCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Manages streaming export of vault contents to a standard .svbackup (version 1) archive.
 * Follows SVBACKUP wire contract rules: bounded memory, zero plaintext on disk, and clean abort.
 */
class BackupExportManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val database: VaultDatabase
) {
    private val _progress = MutableStateFlow(BackupExportProgress())
    val progress: StateFlow<BackupExportProgress> = _progress.asStateFlow()

    @Volatile
    private var isCancelled = false

    companion object {
        private val UUID_REGEX = Regex("^[0-9a-f-]{36}$", RegexOption.IGNORE_CASE)

        fun toCanonicalUuid(id: String): String {
            return if (UUID_REGEX.matches(id)) {
                id.lowercase()
            } else {
                UUID.nameUUIDFromBytes(id.toByteArray(StandardCharsets.UTF_8)).toString()
            }
        }
    }

    fun cancelExport() {
        isCancelled = true
    }

    suspend fun getVaultSummary(): VaultSummary = withContext(Dispatchers.IO) {
        val albums = database.albumDao().getAllAlbumsList()
        val media = database.mediaDao().getAllList().filter {
            val f = File(it.encryptedPath)
            f.exists() && f.isFile
        }
        var photos = 0
        var videos = 0
        var totalBytes = 0L
        for (m in media) {
            if (m.mediaType.uppercase() == "VIDEO") videos++ else photos++
            totalBytes += cryptoEngine.calculatePlaintextSize(File(m.encryptedPath))
        }
        VaultSummary(
            totalAlbums = albums.size,
            totalItems = media.size,
            totalPhotos = photos,
            totalVideos = videos,
            estimatedSizeBytes = totalBytes
        )
    }

    suspend fun exportBackup(
        destinationUri: Uri,
        password: String,
        scope: BackupScope = BackupScope.FullVault
    ): BackupExportResult = withContext(Dispatchers.IO) {
        isCancelled = false
        _progress.value = BackupExportProgress(
            isExporting = true,
            stage = "Gathering items for export...",
            progressPercentage = 0f
        )

        var rawOutputStream: OutputStream? = null

        try {
            coroutineContext.ensureActive()

            // 1. Gather all albums and items based on scope
            val rawAlbums = database.albumDao().getAllAlbumsList()
            val rawMedia = database.mediaDao().getAllList()

            val scopedMedia = when (scope) {
                is BackupScope.FullVault -> rawMedia
                is BackupScope.SelectedAlbums -> rawMedia.filter { it.albumId in scope.albumIds }
                is BackupScope.SelectedMedia -> rawMedia.filter { it.id in scope.mediaIds }
            }

            // Filter media items to ensure physical files exist on disk
            val validMedia = scopedMedia.filter { item ->
                val f = File(item.encryptedPath)
                f.exists() && f.isFile
            }

            val validMediaMap = validMedia.associateBy { it.id }
            val presentAlbumIds = validMedia.map { it.albumId }.toSet()

            val scopedAlbums = when (scope) {
                is BackupScope.FullVault -> rawAlbums
                is BackupScope.SelectedAlbums -> rawAlbums.filter { it.id in scope.albumIds }
                is BackupScope.SelectedMedia -> rawAlbums.filter { it.id in presentAlbumIds }
            }

            // Construct albums, ensuring system album IDs and names conform to SVBACK01
            val localToManifestAlbumId = mutableMapOf<String, String>()
            val albums = scopedAlbums.map { album ->
                val (exportId, exportName, exportKind) = when {
                    album.isSystem || album.id == com.secretvault.app.core.database.entity.AlbumEntity.ALBUM_CAMERA_ID ||
                        album.id == "system-camera" || album.name.equals("Camera", ignoreCase = true) ->
                        Triple(SvBackupManifest.SYSTEM_ALBUM_CAMERA, "Camera", "system")

                    album.isSystem || album.id == com.secretvault.app.core.database.entity.AlbumEntity.ALBUM_IMPORTS_ID ||
                        album.id == "system-imports" || album.name.equals("Imports", ignoreCase = true) ->
                        Triple(SvBackupManifest.SYSTEM_ALBUM_IMPORTS, "Imports", "system")

                    album.isSystem || album.id == com.secretvault.app.core.database.entity.AlbumEntity.ALBUM_UNSORTED_ID ||
                        album.id == "system-unsorted" || album.name.equals("Unsorted", ignoreCase = true) ->
                        Triple(SvBackupManifest.SYSTEM_ALBUM_UNSORTED, "Unsorted", "system")

                    else ->
                        Triple(album.id, album.name.trim(), "custom")
                }
                localToManifestAlbumId[album.id] = exportId

                val validCoverId = if (album.coverMediaId != null) {
                    val coverItem = validMediaMap[album.coverMediaId]
                    if (coverItem != null &&
                        coverItem.albumId == album.id &&
                        coverItem.mediaType.uppercase() == "PHOTO"
                    ) {
                        toCanonicalUuid(album.coverMediaId)
                    } else null
                } else null

                val createdAtIso = try {
                    Instant.ofEpochMilli(album.createdAt).toString()
                } catch (e: Exception) {
                    Instant.now().toString()
                }

                SvBackupAlbum(
                    id = exportId,
                    name = exportName,
                    kind = exportKind,
                    createdAt = createdAtIso,
                    coverItemId = validCoverId
                )
            }

            // Map media items to canonical 36-char UUIDs
            val localToExportMediaId = validMedia.associate { it.id to toCanonicalUuid(it.id) }
            val exportToLocalMedia = validMedia.associateBy { localToExportMediaId[it.id]!! }

            // Construct items with exact plaintext sizes
            val items = validMedia.map { item ->
                val exportUuid = localToExportMediaId[item.id]!!
                val exportAlbumId = localToManifestAlbumId[item.albumId] ?: item.albumId
                val isVideo = item.mediaType.uppercase() == "VIDEO"
                val encFile = File(item.encryptedPath)
                val plaintextSize = cryptoEngine.calculatePlaintextSize(encFile)

                val createdAtIso = try {
                    Instant.ofEpochMilli(item.createdAt).toString()
                } catch (e: Exception) {
                    Instant.now().toString()
                }

                val sanitized = item.originalName
                    .replace('/', '_')
                    .replace('\\', '_')
                    .replace('\u0000', '_')
                    .trim()
                val safeFilename = if (sanitized.isEmpty() || sanitized == "." || sanitized == "..") {
                    if (isVideo) "video_${exportUuid}.mp4" else "photo_${exportUuid}.jpg"
                } else if (sanitized.length > 160) {
                    val ext = sanitized.substringAfterLast('.', "")
                    val base = sanitized.substringBeforeLast('.')
                    if (ext.isNotEmpty()) base.take(150) + "." + ext.take(8) else sanitized.take(160)
                } else {
                    sanitized
                }

                val mime = item.mimeType.trim().ifEmpty {
                    if (isVideo) "video/mp4" else "image/jpeg"
                }

                SvBackupItem(
                    id = exportUuid,
                    albumId = exportAlbumId,
                    originalFilename = safeFilename,
                    mediaType = if (isVideo) "video" else "photo",
                    mimeType = mime,
                    size = plaintextSize,
                    createdAt = createdAtIso
                )
            }

            val manifest = SvBackupManifest(
                version = 1,
                createdAt = Instant.now().toString(),
                albums = albums,
                items = items
            )

            // 2. Open destination stream via Storage Access Framework
            rawOutputStream = context.contentResolver.openOutputStream(destinationUri, "wt")
                ?: return@withContext BackupExportResult(false, errorMessage = "Cannot open destination file for writing")

            val bufferedOutput = BufferedOutputStream(rawOutputStream, SvBackupCrypto.CHUNK_SIZE)
            val writer = SvBackupWriter(bufferedOutput, password)

            val totalBytes = manifest.totalBytes()
            var currentItemIndex = 0

            // 3. Write archive with streaming media chunks
            writer.writeArchive(
                manifest = manifest,
                mediaProvider = { manifestItem, chunkConsumer ->
                    if (isCancelled) throw CancellationException("Export cancelled by user")
                    coroutineContext.ensureActive()

                    currentItemIndex++
                    _progress.value = BackupExportProgress(
                        isExporting = true,
                        stage = "Exporting media files...",
                        currentItem = currentItemIndex,
                        totalItems = items.size,
                        progressPercentage = if (items.isNotEmpty()) (currentItemIndex.toFloat() / items.size) else 1f,
                        activeFileName = manifestItem.originalFilename
                    )

                    val entity = exportToLocalMedia[manifestItem.id]
                        ?: throw IllegalStateException("Missing media item for id ${manifestItem.id}")

                    val encFile = File(entity.encryptedPath)
                    BufferedInputStream(FileInputStream(encFile), 128 * 1024).use { encInput ->
                        val collector = ChunkedDecryptedCollector(SvBackupCrypto.CHUNK_SIZE) { chunk ->
                            if (isCancelled) throw CancellationException("Export cancelled by user")
                            chunkConsumer(chunk)
                        }
                        cryptoEngine.decryptStream(encInput, collector)
                        collector.finish()
                    }
                }
            )

            bufferedOutput.flush()

            _progress.value = BackupExportProgress(
                isExporting = false,
                stage = "Export complete",
                currentItem = items.size,
                totalItems = items.size,
                progressPercentage = 1f
            )

            BackupExportResult(
                success = true,
                exportedItemsCount = items.size,
                exportedAlbumsCount = albums.size
            )
        } catch (e: CancellationException) {
            safeDeleteOutput(destinationUri)
            _progress.value = BackupExportProgress(isExporting = false)
            BackupExportResult(false, errorMessage = "Export was cancelled")
        } catch (e: Exception) {
            safeDeleteOutput(destinationUri)
            _progress.value = BackupExportProgress(isExporting = false)
            BackupExportResult(false, errorMessage = "Export failed: ${e.message}")
        } finally {
            try {
                rawOutputStream?.close()
            } catch (_: Exception) {}
        }
    }

    private fun safeDeleteOutput(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // Some providers don't support deleteDocument; ignore
        }
    }

    /**
     * Accumulates decrypted bytes up to [chunkSize] (256 KiB) before delivering to [onChunk].
     */
    private class ChunkedDecryptedCollector(
        private val chunkSize: Int,
        private val onChunk: (ByteArray) -> Unit
    ) : OutputStream() {
        private val buffer = ByteArray(chunkSize)
        private var bufferPos = 0

        override fun write(b: Int) {
            buffer[bufferPos++] = b.toByte()
            if (bufferPos == chunkSize) {
                flushChunk()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var currentOff = off
            var remaining = len
            while (remaining > 0) {
                val toCopy = minOf(remaining, chunkSize - bufferPos)
                System.arraycopy(b, currentOff, buffer, bufferPos, toCopy)
                bufferPos += toCopy
                currentOff += toCopy
                remaining -= toCopy
                if (bufferPos == chunkSize) {
                    flushChunk()
                }
            }
        }

        private fun flushChunk() {
            if (bufferPos > 0) {
                val chunk = buffer.copyOf(bufferPos)
                try {
                    onChunk(chunk)
                } finally {
                    Arrays.fill(chunk, 0.toByte())
                }
                bufferPos = 0
            }
        }

        fun finish() {
            flushChunk()
            SecureMemory.wipe(buffer)
        }
    }
}
