package com.secretvault.app.core.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.secretvault.app.core.camera.VaultFileNameGenerator
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.applyExifOrientation
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.processing.ExifSanitizer
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ImportProgress(
    val current: Int = 0,
    val total: Int = 0,
    val activeFileName: String = "",
    val percentage: Float = 0f,
    val isImporting: Boolean = false
)

data class ImportResult(
    val successfulCount: Int,
    val failedCount: Int,
    val importedItems: List<MediaItem>
)

class BatchImportManager(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val mediaRepository: MediaRepository
) {

    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    @Volatile
    private var isCancelled = false

    fun cancelImport() {
        isCancelled = true
    }

    suspend fun importUris(
        uris: List<Uri>,
        targetAlbumId: String = AlbumEntity.ALBUM_IMPORTS_ID
    ): ImportResult = withContext(Dispatchers.IO) {
        isCancelled = false
        val total = uris.size
        var successCount = 0
        var failCount = 0
        val importedItems = mutableListOf<MediaItem>()

        _progress.value = ImportProgress(
            current = 0,
            total = total,
            activeFileName = "",
            percentage = 0f,
            isImporting = true
        )

        for ((index, uri) in uris.withIndex()) {
            if (isCancelled) break

            val originalName = queryFileName(uri) ?: "imported_${System.currentTimeMillis()}"
            _progress.value = ImportProgress(
                current = index + 1,
                total = total,
                activeFileName = originalName,
                percentage = ((index) / total.toFloat()),
                isImporting = true
            )

            try {
                val mimeType = context.contentResolver.getType(uri) ?: guessMimeType(originalName)
                val isVideo = mimeType.startsWith("video/") || originalName.endsWith(".mp4", ignoreCase = true)

                val item = if (isVideo) {
                    importVideo(uri, originalName, mimeType, targetAlbumId)
                } else {
                    importPhoto(uri, originalName, mimeType, targetAlbumId)
                }

                if (item != null) {
                    importedItems.add(item)
                    successCount++
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                e.printStackTrace()
                failCount++
            }
        }

        _progress.value = ImportProgress(
            current = total,
            total = total,
            activeFileName = "",
            percentage = 1f,
            isImporting = false
        )

        ImportResult(
            successfulCount = successCount,
            failedCount = failCount,
            importedItems = importedItems
        )
    }

    private suspend fun importPhoto(
        uri: Uri,
        originalName: String,
        mimeType: String,
        targetAlbumId: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val id = "photo_" + UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val filename = VaultFileNameGenerator.generateFileName(MediaType.PHOTO, timestamp)

        // 1. Copy source stream to temp cache file
        val tempFile = File(context.cacheDir, "import_temp_${System.currentTimeMillis()}.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // 2. Strip sensitive EXIF metadata in-place
            ExifSanitizer.sanitizeInPlace(tempFile)

            // 3. Extract dimensions & micro-thumbnail (200x200)
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tempFile.absolutePath, boundsOptions)
            val orientation = runCatching {
                ExifInterface(tempFile).let { it.rotationDegrees to it.isFlipped }
            }.getOrNull()
            val rawWidth = boundsOptions.outWidth.coerceAtLeast(1)
            val rawHeight = boundsOptions.outHeight.coerceAtLeast(1)
            val rotation = orientation?.first ?: 0
            val width = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
            val height = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

            // Generate thumbnail with sample size
            val sampleSize = (width / 200).coerceAtLeast(1)
            val thumbOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decodedThumb = BitmapFactory.decodeFile(tempFile.absolutePath, thumbOptions)
            val rawThumb = decodedThumb?.let { applyExifOrientation(it, orientation) }
            val thumbBitmap = if (rawThumb != null) {
                val scaled = Bitmap.createScaledBitmap(rawThumb, 200, (200f * height / width).toInt().coerceAtLeast(1), true)
                if (scaled != rawThumb) rawThumb.recycle()
                scaled
            } else {
                Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            }

            val thumbStream = ByteArrayOutputStream()
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 80, thumbStream)
            val thumbBytes = thumbStream.toByteArray()
            thumbBitmap.recycle()
            thumbStream.close()

            val encThumbBytes = cryptoEngine.encryptBytes(thumbBytes)
            SecureMemory.wipe(thumbBytes)

            val thumbFile = File(context.filesDir, "vault_thumbs/$id.thumb")
            thumbFile.parentFile?.mkdirs()
            FileOutputStream(thumbFile).use { it.write(encThumbBytes) }

            // 4. Encrypt full photo into vault
            val encFile = File(context.filesDir, "vault_media/$id.enc")
            encFile.parentFile?.mkdirs()
            cryptoEngine.encryptFile(tempFile, encFile)

            val item = MediaItem(
                id = id,
                filename = filename,
                originalName = originalName,
                mediaType = MediaType.PHOTO,
                mimeType = mimeType,
                encryptedPath = encFile.absolutePath,
                thumbnailPath = thumbFile.absolutePath,
                sizeBytes = encFile.length(),
                width = width,
                height = height,
                createdAt = timestamp,
                albumId = targetAlbumId
            )

            mediaRepository.insertMedia(item)
            item
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun importVideo(
        uri: Uri,
        originalName: String,
        mimeType: String,
        targetAlbumId: String
    ): MediaItem? = withContext(Dispatchers.IO) {
        val id = "video_" + UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val filename = VaultFileNameGenerator.generateFileName(MediaType.VIDEO, timestamp)

        val tempFile = File(context.cacheDir, "import_vtemp_${System.currentTimeMillis()}.mp4")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // 1. Extract metadata and thumbnail
            var durationMs = 0L
            var width = 1920
            var height = 1080
            var thumbBitmap: Bitmap? = null

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(tempFile.absolutePath)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080
                thumbBitmap = retriever.frameAtTime
                retriever.release()
            } catch (e: Exception) {
                // Ignore retriever errors
            }

            // 2. Encrypt micro-thumbnail
            val thumbFile = File(context.filesDir, "vault_thumbs/$id.thumb")
            thumbFile.parentFile?.mkdirs()
            if (thumbBitmap != null) {
                val scaled = Bitmap.createScaledBitmap(thumbBitmap, 200, (200f * height / width).toInt().coerceAtLeast(1), true)
                val thumbStream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, thumbStream)
                val encThumb = cryptoEngine.encryptBytes(thumbStream.toByteArray())
                FileOutputStream(thumbFile).use { it.write(encThumb) }
                scaled.recycle()
                thumbBitmap.recycle()
            }

            // 3. Encrypt video file
            val encFile = File(context.filesDir, "vault_media/$id.enc")
            encFile.parentFile?.mkdirs()
            cryptoEngine.encryptFile(tempFile, encFile)

            val item = MediaItem(
                id = id,
                filename = filename,
                originalName = originalName,
                mediaType = MediaType.VIDEO,
                mimeType = if (mimeType.isNotBlank()) mimeType else "video/mp4",
                encryptedPath = encFile.absolutePath,
                thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null,
                sizeBytes = encFile.length(),
                durationMs = durationMs,
                width = width,
                height = height,
                createdAt = timestamp,
                albumId = targetAlbumId
            )

            mediaRepository.insertMedia(item)
            item
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun guessMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".webp", true) -> "image/webp"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mkv", true) -> "video/x-matroska"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }
}
