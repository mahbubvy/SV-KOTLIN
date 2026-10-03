package com.secretvault.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.player.DecryptingMediaDataSource
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.InputStream

class GalleryThumbnailManager(
    context: Context,
    private val crypto: VaultCryptoEngine,
    private val repository: MediaRepository,
    private val isUnlocked: () -> Boolean,
) {
    private val directory = File(context.filesDir, "vault_thumbs")
    private val decoder = Semaphore(1)

    suspend fun refresh(item: MediaItem) = withContext(Dispatchers.IO) {
        if (isCurrent(item.thumbnailPath) || !isUnlocked()) return@withContext
        decoder.withPermit {
            val current = repository.getMediaById(item.id) ?: return@withPermit
            if (isCurrent(current.thumbnailPath) || !isUnlocked()) return@withPermit
            val original = File(current.encryptedPath)
            if (!original.isFile) return@withPermit
            val target = File(directory, original.nameWithoutExtension + GALLERY_THUMBNAIL_SUFFIX)
            var published = false
            try {
                val bitmap = (if (current.mediaType == MediaType.VIDEO) videoFrame(original) else photoFrame(original))
                    ?: return@withPermit
                val encrypted = try { encryptedGalleryThumbnail(bitmap, crypto) } finally { bitmap.recycle() }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    if (!isUnlocked()) return@withContext
                    directory.mkdirs()
                    target.writeBytes(encrypted)
                    published = repository.replaceThumbnail(current, target.absolutePath)
                    if (published) current.thumbnailPath?.let { oldPath ->
                        val old = File(oldPath)
                        if (old != target && old.parentFile?.canonicalFile == directory.canonicalFile) old.delete()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Log.w("GalleryThumbnail", "Preview refresh failed: ${failure.javaClass.simpleName}") }
            finally { if (!published) target.delete() }
        }
    }

    private fun isCurrent(path: String?): Boolean = path?.endsWith(GALLERY_THUMBNAIL_SUFFIX) == true && File(path).isFile

    private fun videoFrame(file: File): Bitmap? = DecryptingMediaDataSource(crypto, file).use { source ->
        val retriever = MediaMetadataRetriever()
        try { retriever.setDataSource(source); retriever.galleryFrame() } finally { retriever.release() }
    }

    private fun photoFrame(file: File): Bitmap? = DecryptingMediaDataSource(crypto, file).use { source ->
        fun stream() = object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                val read = source.readAt(position, bytes, offset, count)
                if (read > 0) position += read
                return read
            }
        }
        val orientation = runCatching { ExifInterface(stream()).let { it.rotationDegrees to it.isFlipped } }.getOrNull()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = galleryThumbnailSampleSize(bounds.outWidth, bounds.outHeight) }
        stream().use { BitmapFactory.decodeStream(it, null, options) }?.let { applyExifOrientation(it, orientation) }
    }
}
