package com.secretvault.app.core.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.secretvault.app.core.camera.VaultFileNameGenerator
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.image.GALLERY_THUMBNAIL_SUFFIX
import com.secretvault.app.core.image.encryptedGalleryThumbnail
import com.secretvault.app.core.image.galleryFrame
import com.secretvault.app.core.processing.FaceBlurProcessor
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MediaSaveQueue(
    private val context: Context,
    private val cryptoEngine: VaultCryptoEngine,
    private val mediaRepository: MediaRepository,
    private val faceBlurProcessor: FaceBlurProcessor = FaceBlurProcessor()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _savingQueueCount = MutableStateFlow(0)
    val savingQueueCount: StateFlow<Int> = _savingQueueCount.asStateFlow()

    private val _isSavingVideo = MutableStateFlow(false)
    val isSavingVideo: StateFlow<Boolean> = _isSavingVideo.asStateFlow()

    private val _videoSaveProgress = MutableStateFlow(0f)
    val videoSaveProgress: StateFlow<Float> = _videoSaveProgress.asStateFlow()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    private val wakeLock = powerManager?.newWakeLock(
        android.os.PowerManager.PARTIAL_WAKE_LOCK,
        "SecretVault:MediaSaveQueue"
    )?.apply { setReferenceCounted(false) }

    init {
        recoverOrphanedRecordings()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock.acquire(10 * 60 * 1000L /* 10 min max timeout */)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLockIfNeeded() {
        try {
            if (_savingQueueCount.value == 0 && wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recoverOrphanedRecordings() {
        scope.launch {
            try {
                val cacheDir = context.cacheDir
                val orphanedFiles = cacheDir.listFiles { _, name ->
                    name.startsWith("temp_rec_") && name.endsWith(".mp4")
                }
                orphanedFiles?.forEach { file ->
                    if (file.length() > 0) {
                        enqueueVideo(file, durationMs = 0L)
                    } else {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Queues an in-memory captured photo for processing, face blurring, micro-thumbnailing, and encryption.
     */
    fun enqueuePhoto(
        jpegBytes: ByteArray,
        rotationDegrees: Int = 0,
        autoFaceBlur: Boolean = true,
        onError: () -> Unit = {},
        onComplete: ((MediaItem) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val mediaItem = processAndSavePhoto(jpegBytes, rotationDegrees, autoFaceBlur)
                onComplete?.invoke(mediaItem)
            } catch (e: Exception) {
                e.printStackTrace()
                onError()
            }
        }
    }

    /**
     * Queues a completed video recording for thumbnail extraction, encryption, and metadata storage.
     */
    fun enqueueVideo(
        tempVideoFile: File,
        durationMs: Long,
        onComplete: ((MediaItem) -> Unit)? = null,
        onError: () -> Unit = {}
    ) {
        _savingQueueCount.value += 1
        _isSavingVideo.value = true
        _videoSaveProgress.value = 0.05f
        acquireWakeLock()
        scope.launch {
            try {
                val mediaItem = saveVideo(tempVideoFile, durationMs) { progress ->
                    _videoSaveProgress.value = (0.05f + progress * 0.95f).coerceIn(0f, 1f)
                }
                onComplete?.invoke(mediaItem)
                androidx.core.content.ContextCompat.getMainExecutor(context).execute {
                    android.widget.Toast.makeText(context, "Encrypted video saved", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onError()
                androidx.core.content.ContextCompat.getMainExecutor(context).execute {
                    android.widget.Toast.makeText(context, "Could not save video", android.widget.Toast.LENGTH_SHORT).show()
                }
            } finally {
                val remaining = (_savingQueueCount.value - 1).coerceAtLeast(0)
                _savingQueueCount.value = remaining
                if (remaining == 0) {
                    _isSavingVideo.value = false
                    _videoSaveProgress.value = 0f
                }
                releaseWakeLockIfNeeded()
            }
        }
    }

    private suspend fun processAndSavePhoto(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        autoFaceBlur: Boolean
    ): MediaItem = withContext(Dispatchers.IO) {
        val id = "photo_" + UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val filename = VaultFileNameGenerator.generateFileName(MediaType.PHOTO, timestamp)

        var rawBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        var bitmap = if (rotationDegrees != 0 && rawBitmap != null) {
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            if (rotated != rawBitmap) rawBitmap.recycle()
            rotated
        } else {
            rawBitmap ?: Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        }

        if (autoFaceBlur) {
            try {
                bitmap = faceBlurProcessor.processFaceBlur(bitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        storePhoto(bitmap, id, timestamp, filename, AlbumEntity.ALBUM_CAMERA_ID, quality = 92)
    }

    /**
     * Stores an edited photo as a new vault item named [name] in [albumId]: thumbnailed, compressed and encrypted in
     * memory, never written as plaintext. Recycles [bitmap].
     */
    suspend fun savePhoto(bitmap: Bitmap, albumId: String, name: String): MediaItem = withContext(Dispatchers.IO) {
        storePhoto(bitmap, "photo_" + UUID.randomUUID().toString().take(8), System.currentTimeMillis(), name, albumId, quality = 95)
    }

    private suspend fun storePhoto(bitmap: Bitmap, id: String, timestamp: Long, filename: String, albumId: String, quality: Int): MediaItem {
        val width = bitmap.width
        val height = bitmap.height

        val encryptedThumbBytes = encryptedGalleryThumbnail(bitmap, cryptoEngine)
        val thumbFile = File(context.filesDir, "vault_thumbs/$id$GALLERY_THUMBNAIL_SUFFIX")
        thumbFile.parentFile?.mkdirs()
        FileOutputStream(thumbFile).use { it.write(encryptedThumbBytes) }

        // 2. Compress and Encrypt Full Image
        val fullImageStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fullImageStream)
        val fullBytes = fullImageStream.toByteArray()
        bitmap.recycle()
        fullImageStream.close()

        val encFile = File(context.filesDir, "vault_media/$id.enc")
        encFile.parentFile?.mkdirs()
        cryptoEngine.encryptStream(ByteArrayInputStream(fullBytes), FileOutputStream(encFile), totalBytes = fullBytes.size.toLong())

        SecureMemory.wipe(fullBytes)

        val item = MediaItem(
            id = id,
            filename = filename,
            originalName = filename,
            mediaType = MediaType.PHOTO,
            mimeType = "image/jpeg",
            encryptedPath = encFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            sizeBytes = encFile.length(),
            width = width,
            height = height,
            createdAt = timestamp,
            albumId = albumId
        )

        mediaRepository.insertMedia(item)
        return item
    }

    /**
     * Thumbnails, encrypts and stores a plaintext video, then deletes [tempFile].
     * [onProgress] reports encryption progress from 0 to 1.
     */
    suspend fun saveVideo(
        tempFile: File,
        durationMs: Long,
        albumId: String = AlbumEntity.ALBUM_CAMERA_ID,
        name: String? = null,
        onProgress: (Float) -> Unit = {}
    ): MediaItem = withContext(Dispatchers.IO) {
        val id = "video_" + UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis()
        val filename = name ?: VaultFileNameGenerator.generateFileName(MediaType.VIDEO, timestamp)

        var width = 1920
        var height = 1080
        var thumbBitmap: Bitmap? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(tempFile.absolutePath)
            thumbBitmap = retriever.galleryFrame()
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (wStr != null) width = wStr.toIntOrNull() ?: 1920
            if (hStr != null) height = hStr.toIntOrNull() ?: 1080
        } catch (e: Exception) {
            // Ignore retriever failure
        } finally { retriever.release() }

        val thumbFile = File(context.filesDir, "vault_thumbs/$id$GALLERY_THUMBNAIL_SUFFIX")
        thumbFile.parentFile?.mkdirs()

        if (thumbBitmap != null) {
            val encThumbBytes = try { encryptedGalleryThumbnail(thumbBitmap, cryptoEngine) } finally { thumbBitmap.recycle() }
            FileOutputStream(thumbFile).use { it.write(encThumbBytes) }
        }

        // 2. Encrypt Video File
        val encFile = File(context.filesDir, "vault_media/$id.enc")
        encFile.parentFile?.mkdirs()
        cryptoEngine.encryptFile(tempFile, encFile, onProgress = onProgress)

        // 3. Delete raw unencrypted temp file
        if (tempFile.exists()) {
            tempFile.delete()
        }

        val item = MediaItem(
            id = id,
            filename = filename,
            originalName = filename,
            mediaType = MediaType.VIDEO,
            mimeType = "video/mp4",
            encryptedPath = encFile.absolutePath,
            thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else null,
            sizeBytes = encFile.length(),
            durationMs = durationMs,
            width = width,
            height = height,
            createdAt = timestamp,
            albumId = albumId
        )

        mediaRepository.insertMedia(item)
        item
    }
}
