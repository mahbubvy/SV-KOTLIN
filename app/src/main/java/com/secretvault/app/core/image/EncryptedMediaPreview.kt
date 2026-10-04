package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.player.DecryptingMediaDataSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

data class MediaPreview(val width: Int, val height: Int, val durationMs: Long)

/**
 * Writes the encrypted gallery thumbnail for an already encrypted vault file and returns its upright size and duration.
 * Reads encrypted originals through bounded random-access buffers.
 */
fun writeEncryptedPreview(cryptoEngine: VaultCryptoEngine, encFile: File, isVideo: Boolean, thumbFile: File, scratchDir: File): MediaPreview {
    if (isVideo) {
        DecryptingMediaDataSource(cryptoEngine, encFile).use { source ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source)
                val frame = retriever.galleryFrame()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                val thumbBitmap = if (frame != null) {
                    createGalleryThumbnail(frame).also { if (it != frame) frame.recycle() }
                } else Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                writeEncryptedThumbnail(cryptoEngine, thumbBitmap, thumbFile)
                return if (rot == 90 || rot == 270) MediaPreview(rawH, rawW, durationMs) else MediaPreview(rawW, rawH, durationMs)
            } finally {
                retriever.release()
            }
        }
    }

    DecryptingMediaDataSource(cryptoEngine, encFile).use { source ->
        fun stream() = object : InputStream() {
            private var position = 0L
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                val read = source.readAt(position, bytes, offset, count)
                if (read > 0) position += read
                return read
            }
        }
        val orientation = runCatching {
            stream().use { ExifInterface(it).let { exif -> exif.rotationDegrees to exif.isFlipped } }
        }.getOrNull()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
        val rawWidth = bounds.outWidth.coerceAtLeast(1)
        val rawHeight = bounds.outHeight.coerceAtLeast(1)
        val rotation = orientation?.first ?: 0
        val thumbOpts = BitmapFactory.Options().apply { inSampleSize = galleryThumbnailSampleSize(rawWidth, rawHeight) }
        val rawThumb = stream().use { BitmapFactory.decodeStream(it, null, thumbOpts) }?.let { applyExifOrientation(it, orientation) }
        val thumbBitmap = if (rawThumb != null) {
            createGalleryThumbnail(rawThumb).also { if (it != rawThumb) rawThumb.recycle() }
        } else Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        writeEncryptedThumbnail(cryptoEngine, thumbBitmap, thumbFile)
        return if (rotation == 90 || rotation == 270) MediaPreview(rawHeight, rawWidth, 0L) else MediaPreview(rawWidth, rawHeight, 0L)
    }
}

private fun writeEncryptedThumbnail(cryptoEngine: VaultCryptoEngine, bitmap: Bitmap, thumbFile: File) {
    val thumbBytes = ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        stream.toByteArray()
    }
    bitmap.recycle()
    val encThumb = cryptoEngine.encryptBytes(thumbBytes)
    SecureMemory.wipe(thumbBytes)
    thumbFile.parentFile?.mkdirs()
    FileOutputStream(thumbFile).use { it.write(encThumb) }
}

/** Overwrites a file with zeros before deleting it. */
fun secureWipeFile(file: File) {
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
