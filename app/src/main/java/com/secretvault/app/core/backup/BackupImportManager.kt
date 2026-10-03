package com.secretvault.app.core.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.VaultDatabase
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.database.entity.MediaEntity
import com.secretvault.app.core.image.applyExifOrientation
import com.secretvault.app.core.image.GALLERY_THUMBNAIL_SUFFIX
import com.secretvault.app.core.image.createGalleryThumbnail
import com.secretvault.app.core.image.galleryThumbnailSampleSize
import com.secretvault.app.core.image.galleryFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.UUID

data class BackupImportProgress(
    val isImporting: Boolean = false,
    val stage: String = "",
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val progressPercentage: Float = 0f,
    val activeFileName: String = ""
)

data class BackupImportResult(
    val success: Boolean,
    val importedItemsCount: Int = 0,
    val importedAlbumsCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * Manages the secure, staged, all-or-nothing restoration of .svbackup v1 archives into the vault.
 * Follows SVBACKUP-Kotlin-Importer-Handoff.md rules.
 */
class BackupImportManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val database: VaultDatabase
) {

    private val _progress = MutableStateFlow(BackupImportProgress())
    val progress: StateFlow<BackupImportProgress> = _progress.asStateFlow()

    @Volatile
    private var isCancelled = false

    fun cancelImport() {
        isCancelled = true
    }

    private class StagedMediaEntry(
        val item: SvBackupItem,
        val localId: String,
        val encFile: File,
        val thumbFile: File,
        val fp: SvContentFingerprint,
        var encStream: OutputStream? = null,
        var width: Int = 1080,
        var height: Int = 1080,
        var durationMs: Long = 0L
    )

    suspend fun importBackup(
        uri: Uri,
        password: String
    ): BackupImportResult = withContext(Dispatchers.IO) {
        isCancelled = false
        val attemptId = UUID.randomUUID().toString()
        val stagingDir = File(context.filesDir, "vault_staging/$attemptId").apply { mkdirs() }

        val createdEncFiles = mutableListOf<File>()
        val createdThumbFiles = mutableListOf<File>()
        val replacedThumbBackups = mutableMapOf<File, File?>()
        val stagedItems = mutableMapOf<String, StagedMediaEntry>()

        _progress.value = BackupImportProgress(
            isImporting = true,
            stage = "Opening backup archive...",
            progressPercentage = 0.05f
        )

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext BackupImportResult(success = false, errorMessage = "Failed to open archive stream")

            var manifestRef: SvBackupManifest? = null
            var summaryRef: SvBackupSummary? = null

            inputStream.use { stream ->
                val reader = SvBackupReader(stream, password)

                reader.readArchive(object : SvBackupConsumer {
                    override suspend fun onManifest(manifest: SvBackupManifest) {
                        if (isCancelled) throw InterruptedException("Import cancelled by user")
                        manifestRef = manifest

                        for (item in manifest.items) {
                            val isVideo = item.mediaType.equals("video", ignoreCase = true)
                            val localId = (if (isVideo) "video_" else "photo_") + UUID.randomUUID().toString().take(8)
                            val encFile = File(stagingDir, "$localId.enc")
                            val thumbFile = File(stagingDir, "$localId.thumb")
                            val fp = SvContentFingerprint()
                            stagedItems[item.id] = StagedMediaEntry(item, localId, encFile, thumbFile, fp)
                        }

                        _progress.value = BackupImportProgress(
                            isImporting = true,
                            stage = "Validating archive manifest...",
                            totalItems = manifest.items.size,
                            progressPercentage = 0.1f
                        )
                    }

                    override suspend fun onMediaChunk(
                        item: SvBackupItem,
                        chunkIndex: Int,
                        totalChunks: Int,
                        chunkBytes: ByteArray
                    ) {
                        if (isCancelled) throw InterruptedException("Import cancelled by user")

                        val entry = stagedItems[item.id]
                            ?: throw IllegalStateException("Unexpected item id: ${item.id}")

                        entry.fp.update(chunkBytes)
                        if (entry.encStream == null) {
                            entry.encStream = cryptoEngine.createEncryptingOutputStream(FileOutputStream(entry.encFile))
                        }
                        entry.encStream!!.write(chunkBytes)

                        if (chunkIndex == totalChunks - 1 || item.size == 0L) {
                            entry.encStream!!.close()
                            entry.encStream = null
                        }

                        val manifest = manifestRef
                        val itemIndex = manifest?.items?.indexOfFirst { it.id == item.id } ?: 0
                        val total = manifest?.items?.size ?: 1
                        val chunkFrac = if (totalChunks > 0) (chunkIndex + 1f) / totalChunks else 1f
                        val progress = 0.1f + 0.5f * ((itemIndex + chunkFrac) / total.toFloat())

                        _progress.value = BackupImportProgress(
                            isImporting = true,
                            stage = "Authenticating & encrypting media...",
                            currentItem = itemIndex + 1,
                            totalItems = total,
                            progressPercentage = progress.coerceIn(0f, 0.6f),
                            activeFileName = item.originalFilename
                        )
                    }

                    override suspend fun onComplete(manifest: SvBackupManifest, summary: SvBackupSummary) {
                        manifestRef = manifest
                        summaryRef = summary
                    }
                })
            }

            val manifest = manifestRef ?: throw IllegalStateException("Backup manifest was not parsed")
            if (isCancelled) throw InterruptedException("Import cancelled by user")

            // Ensure any unclosed streams (e.g. 0-byte items) are closed
            for (entry in stagedItems.values) {
                entry.encStream?.close()
                entry.encStream = null
            }

            // Stage 2: Generate thumbnails from staged encrypted files
            val totalStaged = stagedItems.size
            for ((index, entry) in stagedItems.values.withIndex()) {
                if (isCancelled) throw InterruptedException("Import cancelled by user")

                _progress.value = BackupImportProgress(
                    isImporting = true,
                    stage = "Processing media preview...",
                    currentItem = index + 1,
                    totalItems = totalStaged,
                    progressPercentage = 0.6f + 0.15f * ((index + 1f) / totalStaged.toFloat()),
                    activeFileName = entry.item.originalFilename
                )

                if (entry.encFile.exists() && entry.encFile.length() > 0) {
                    if (entry.item.mediaType.equals("video", ignoreCase = true)) {
                        val tmpVideo = File(stagingDir, "tmp_${entry.localId}.raw")
                        try {
                            FileOutputStream(tmpVideo).use { out ->
                                BufferedInputStream(FileInputStream(entry.encFile)).use { encIn ->
                                    cryptoEngine.decryptStream(encIn, out)
                                }
                            }
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(tmpVideo.absolutePath)
                                val frame = retriever.galleryFrame()
                                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                entry.durationMs = dur?.toLongOrNull() ?: 0L
                                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                                if (rot == 90 || rot == 270) {
                                    entry.width = rawH; entry.height = rawW
                                } else {
                                    entry.width = rawW; entry.height = rawH
                                }

                                val thumbBitmap = if (frame != null) {
                                    createGalleryThumbnail(frame).also {
                                        if (it != frame) frame.recycle()
                                    }
                                } else {
                                    Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                                }

                                val thumbBytes = compressBitmapToJpeg(thumbBitmap)
                                val encThumb = cryptoEngine.encryptBytes(thumbBytes)
                                SecureMemory.wipe(thumbBytes)
                                FileOutputStream(entry.thumbFile).use { it.write(encThumb) }
                            } finally {
                                retriever.release()
                            }
                        } finally {
                            secureWipeFile(tmpVideo)
                        }
                    } else {
                        // Photo: decrypt directly in memory
                        BufferedInputStream(FileInputStream(entry.encFile)).use { encIn ->
                            val memStream = ByteArrayOutputStream()
                            cryptoEngine.decryptStream(encIn, memStream)
                            val photoBytes = memStream.toByteArray()
                            try {
                                val orientation = runCatching {
                                    ExifInterface(ByteArrayInputStream(photoBytes)).let {
                                        it.rotationDegrees to it.isFlipped
                                    }
                                }.getOrNull()
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, bounds)
                                val rawWidth = bounds.outWidth.coerceAtLeast(1)
                                val rawHeight = bounds.outHeight.coerceAtLeast(1)
                                val rotation = orientation?.first ?: 0
                                val w = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
                                val h = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
                                entry.width = w
                                entry.height = h

                                val sampleSize = galleryThumbnailSampleSize(rawWidth, rawHeight)
                                val thumbOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                                val decodedThumb = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, thumbOpts)
                                val rawThumb = decodedThumb?.let { applyExifOrientation(it, orientation) }
                                val thumbBitmap = if (rawThumb != null) {
                                    createGalleryThumbnail(rawThumb).also {
                                        if (it != rawThumb) rawThumb.recycle()
                                    }
                                } else {
                                    Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                                }

                                val thumbBytes = compressBitmapToJpeg(thumbBitmap)
                                val encThumb = cryptoEngine.encryptBytes(thumbBytes)
                                SecureMemory.wipe(thumbBytes)
                                FileOutputStream(entry.thumbFile).use { it.write(encThumb) }
                            } finally {
                                SecureMemory.wipe(photoBytes)
                            }
                        }
                    }
                }
            }

            // Stage 3: Map albums by ID + kind first, then trimmed case-insensitive name
            val existingAlbums = database.albumDao().getAllAlbumsList()
            val albumIdMap = mutableMapOf<String, String>()
            val albumsToInsert = mutableListOf<AlbumEntity>()

            for (backupAlbum in manifest.albums) {
                if (backupAlbum.kind == "system") {
                    val systemId = when {
                        backupAlbum.id == "system-camera" || backupAlbum.id == AlbumEntity.ALBUM_CAMERA_ID ||
                            backupAlbum.name.equals("Camera", ignoreCase = true) -> AlbumEntity.ALBUM_CAMERA_ID

                        backupAlbum.id == "system-imports" || backupAlbum.id == AlbumEntity.ALBUM_IMPORTS_ID ||
                            backupAlbum.name.equals("Imports", ignoreCase = true) -> AlbumEntity.ALBUM_IMPORTS_ID

                        else -> AlbumEntity.ALBUM_UNSORTED_ID
                    }
                    albumIdMap[backupAlbum.id] = systemId
                } else {
                    val trimmedName = backupAlbum.name.trim()
                    val existingMatch = existingAlbums.firstOrNull { it.id == backupAlbum.id && !it.isSystem }
                        ?: existingAlbums.firstOrNull { it.name.trim().equals(trimmedName, ignoreCase = true) }

                    if (existingMatch != null) {
                        albumIdMap[backupAlbum.id] = existingMatch.id
                    } else {
                        val newAlbumId = "album_" + UUID.randomUUID().toString().take(8)
                        val createdAtMillis = parseDateToMillis(backupAlbum.createdAt)
                        val newAlbum = AlbumEntity(
                            id = newAlbumId,
                            name = trimmedName,
                            isSystem = false,
                            coverMediaId = null,
                            createdAt = createdAtMillis
                        )
                        albumsToInsert.add(newAlbum)
                        albumIdMap[backupAlbum.id] = newAlbumId
                    }
                }
            }

            // Stage 4: Deduplicate media against existing vault items using content fingerprint
            _progress.value = BackupImportProgress(
                isImporting = true,
                stage = "Checking for duplicate media...",
                progressPercentage = 0.8f
            )

            val existingMedia = database.mediaDao().getAllList()
            val existingMediaById = existingMedia.associateBy { it.id }
            val existingFingerprintMap = mutableMapOf<String, String>()

            val candidateList = stagedItems.values.toList()
            for (existing in existingMedia) {
                if (isCancelled) throw InterruptedException("Import cancelled by user")
                val encFile = File(existing.encryptedPath)
                if (encFile.exists() && encFile.isFile) {
                    val plainSize = cryptoEngine.calculatePlaintextSize(encFile)
                    val hasMatch = candidateList.any {
                        it.item.mediaType.equals(existing.mediaType, ignoreCase = true) && it.item.size == plainSize
                    }
                    if (hasMatch) {
                        val fpHash = computeContentFingerprintOfEncryptedFile(encFile)
                        val identity = SvContentFingerprint.identity(existing.mediaType, plainSize, fpHash)
                        existingFingerprintMap[identity] = existing.id
                    }
                }
            }

            // Resolve unique vs duplicate items and move staged files to vault_media/
            val resolvedItemMap = mutableMapOf<String, String>()
            val mediaToInsert = mutableListOf<MediaEntity>()

            for (staged in candidateList) {
                if (isCancelled) throw InterruptedException("Import cancelled by user")
                val item = staged.item
                val identity = SvContentFingerprint.identity(item.mediaType, item.size, staged.fp.finish())
                val duplicateId = existingFingerprintMap[identity]

                if (duplicateId != null) {
                    // Refresh duplicate photo thumbnails from the source in case their orientation was lost before.
                    resolvedItemMap[item.id] = duplicateId
                    val existingThumb = if (item.mediaType.equals("photo", ignoreCase = true)) {
                        existingMediaById[duplicateId]?.thumbnailPath?.let(::File)
                    } else {
                        null
                    }
                    if (existingThumb != null && staged.thumbFile.exists()) {
                        if (existingThumb !in replacedThumbBackups) {
                            val backup = if (existingThumb.exists()) {
                                File(stagingDir, "thumb_backup_${existingThumb.name}").also {
                                    existingThumb.copyTo(it, overwrite = true)
                                }
                            } else null
                            replacedThumbBackups[existingThumb] = backup
                        }
                        existingThumb.parentFile?.mkdirs()
                        staged.thumbFile.copyTo(existingThumb, overwrite = true)
                    }
                    staged.encFile.delete()
                    staged.thumbFile.delete()
                } else {
                    // New unique item: promote staged files to vault_media/ and vault_thumbs/
                    resolvedItemMap[item.id] = staged.localId
                    val targetEncFile = File(context.filesDir, "vault_media/${staged.localId}.enc").apply { parentFile?.mkdirs() }
                    val targetThumbFile = File(context.filesDir, "vault_thumbs/${staged.localId}$GALLERY_THUMBNAIL_SUFFIX").apply { parentFile?.mkdirs() }

                    staged.encFile.copyTo(targetEncFile, overwrite = true)
                    staged.encFile.delete()
                    createdEncFiles.add(targetEncFile)

                    if (staged.thumbFile.exists()) {
                        staged.thumbFile.copyTo(targetThumbFile, overwrite = true)
                        staged.thumbFile.delete()
                        createdThumbFiles.add(targetThumbFile)
                    }

                    val targetAlbumId = albumIdMap[item.albumId] ?: AlbumEntity.ALBUM_IMPORTS_ID
                    mediaToInsert.add(
                        MediaEntity(
                            id = staged.localId,
                            filename = item.originalFilename,
                            originalName = item.originalFilename,
                            mediaType = if (item.mediaType.equals("video", ignoreCase = true)) "VIDEO" else "PHOTO",
                            mimeType = item.mimeType,
                            encryptedPath = targetEncFile.absolutePath,
                            thumbnailPath = targetThumbFile.absolutePath,
                            sizeBytes = targetEncFile.length(),
                            width = staged.width,
                            height = staged.height,
                            durationMs = staged.durationMs,
                            createdAt = parseDateToMillis(item.createdAt),
                            albumId = targetAlbumId
                        )
                    )
                    existingFingerprintMap[identity] = staged.localId
                }
            }

            // Stage 5: Resolve album covers (without replacing existing covers)
            val coversToUpdate = mutableListOf<Pair<String, String>>()
            for (backupAlbum in manifest.albums) {
                val targetAlbumId = albumIdMap[backupAlbum.id] ?: continue
                val existingAlbum = existingAlbums.firstOrNull { it.id == targetAlbumId }
                val newAlbum = albumsToInsert.firstOrNull { it.id == targetAlbumId }

                // Do not replace an existing cover!
                if (existingAlbum?.coverMediaId == null && newAlbum?.coverMediaId == null) {
                    if (backupAlbum.coverItemId != null) {
                        val resolvedCoverId = resolvedItemMap[backupAlbum.coverItemId]
                        if (resolvedCoverId != null) {
                            val isPhoto = mediaToInsert.any { it.id == resolvedCoverId && it.albumId == targetAlbumId && it.mediaType == "PHOTO" }
                                || existingMedia.any { it.id == resolvedCoverId && it.albumId == targetAlbumId && it.mediaType.equals("photo", ignoreCase = true) }

                            if (isPhoto) {
                                if (newAlbum != null) {
                                    val idx = albumsToInsert.indexOf(newAlbum)
                                    albumsToInsert[idx] = newAlbum.copy(coverMediaId = resolvedCoverId)
                                } else if (existingAlbum != null) {
                                    coversToUpdate.add(Pair(targetAlbumId, resolvedCoverId))
                                }
                            }
                        }
                    }
                }
            }

            // Stage 6: Atomic database commit
            _progress.value = BackupImportProgress(
                isImporting = true,
                stage = "Committing changes to vault...",
                progressPercentage = 0.95f
            )

            database.withTransaction {
                database.albumDao().insertAll(albumsToInsert)
                database.mediaDao().insertAll(mediaToInsert)
                for ((albId, covId) in coversToUpdate) {
                    database.albumDao().updateCover(albId, covId)
                }
            }

            _progress.value = BackupImportProgress(
                isImporting = false,
                stage = "Import complete",
                currentItem = mediaToInsert.size,
                totalItems = mediaToInsert.size,
                progressPercentage = 1f
            )

            BackupImportResult(
                success = true,
                importedItemsCount = mediaToInsert.size,
                importedAlbumsCount = albumsToInsert.size
            )
        } catch (e: Exception) {
            e.printStackTrace()
            // Clean up any partially created encrypted vault files on failure
            for (file in createdEncFiles) {
                if (file.exists()) file.delete()
            }
            for (file in createdThumbFiles) {
                if (file.exists()) file.delete()
            }
            for ((thumbnail, backup) in replacedThumbBackups) {
                if (backup?.exists() == true) {
                    backup.copyTo(thumbnail, overwrite = true)
                } else {
                    thumbnail.delete()
                }
            }

            _progress.value = BackupImportProgress(isImporting = false)

            val errorMsg = when {
                e is InterruptedException -> "Import cancelled"
                e.message?.contains("Tag mismatch", ignoreCase = true) == true ||
                e.message?.contains("AEADBadTagException", ignoreCase = true) == true -> "Wrong backup password or damaged archive"
                e.message?.contains("Unsupported backup format") == true -> "Unsupported backup format or version"
                else -> e.message ?: "Backup import failed"
            }

            BackupImportResult(
                success = false,
                errorMessage = errorMsg
            )
        } finally {
            // Securely wipe and delete staging directory
            secureWipeDirectory(stagingDir)
        }
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val bytes = stream.toByteArray()
        bitmap.recycle()
        stream.close()
        return bytes
    }

    private fun parseDateToMillis(dateStr: String): Long {
        return try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun secureWipeDirectory(dir: File) {
        try {
            if (!dir.exists()) return
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    secureWipeDirectory(file)
                } else {
                    secureWipeFile(file)
                }
            }
            dir.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun secureWipeFile(file: File) {
        try {
            if (file.exists() && file.isFile) {
                val length = file.length()
                if (length > 0) {
                    RandomAccessFile(file, "rws").use { raf ->
                        val zeros = ByteArray(4096)
                        var written = 0L
                        while (written < length) {
                            val toWrite = minOf(zeros.size.toLong(), length - written).toInt()
                            raf.write(zeros, 0, toWrite)
                            written += toWrite
                        }
                    }
                }
                file.delete()
            }
        } catch (e: Exception) {
            file.delete()
        }
    }

    private fun computeContentFingerprintOfEncryptedFile(file: File): String {
        val fp = SvContentFingerprint()
        val adapter = object : java.io.OutputStream() {
            override fun write(b: Int) {
                fp.update(byteArrayOf(b.toByte()), 0, 1)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                fp.update(b, off, len)
            }
        }
        file.inputStream().buffered().use { inStream ->
            cryptoEngine.decryptStream(inStream, adapter)
        }
        return fp.finish()
    }

    /**
     * Inspects an archive's manifest in O(1) time without restoring or writing files to disk.
     * Useful for pre-restore preview sheets.
     */
    suspend fun inspectArchive(uri: Uri, password: String): SvBackupManifest = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open backup archive stream")
        stream.use { s ->
            val header = ByteArray(SvBackupCrypto.HEADER_SIZE)
            var read = 0
            while (read < SvBackupCrypto.HEADER_SIZE) {
                val r = s.read(header, read, SvBackupCrypto.HEADER_SIZE - read)
                if (r == -1) throw java.io.EOFException("Unexpected end of archive header")
                read += r
            }
            val salt = SvBackupCrypto.parseAndValidateHeader(header)
            val key = SvBackupCrypto.deriveKey(password, salt)
            try {
                val rh0 = ByteArray(SvBackupCrypto.RECORD_HEADER_SIZE)
                read = 0
                while (read < SvBackupCrypto.RECORD_HEADER_SIZE) {
                    val r = s.read(rh0, read, SvBackupCrypto.RECORD_HEADER_SIZE - read)
                    if (r == -1) throw java.io.EOFException("Unexpected end of record 0 header")
                    read += r
                }
                val rh0Buf = java.nio.ByteBuffer.wrap(rh0)
                val type0 = rh0[0].toInt()
                val index0 = rh0Buf.getInt(1)
                val length0 = rh0Buf.getInt(5)
                require(type0 == SvBackupCrypto.TYPE_MANIFEST) { "First record must be Manifest (Type 1)" }
                require(index0 == 0) { "First record index must be 0" }
                val sealed0 = ByteArray(length0 + SvBackupCrypto.TAG_SIZE)
                read = 0
                while (read < sealed0.size) {
                    val r = s.read(sealed0, read, sealed0.size - read)
                    if (r == -1) throw java.io.EOFException("Unexpected end of manifest payload")
                    read += r
                }
                val previousTag = ByteArray(SvBackupCrypto.TAG_SIZE)
                val aad0 = SvBackupCrypto.buildAad(header, rh0, previousTag)
                val plain = SvBackupCrypto.decryptRecord(key, index0, aad0, sealed0)
                SvBackupManifest.fromJson(String(plain, java.nio.charset.StandardCharsets.UTF_8))
            } finally {
                java.util.Arrays.fill(key, 0.toByte())
            }
        }
    }
}
