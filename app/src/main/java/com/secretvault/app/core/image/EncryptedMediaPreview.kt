package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class MediaPreview(val width: Int, val height: Int, val durationMs: Long)

/**
 * Writes the encrypted gallery thumbnail for an already encrypted vault file and returns its upright size and duration.
 * Videos are briefly decrypted into [scratchDir] for MediaMetadataRetriever and wiped straight after.
 */
fun writeEncryptedPreview(cryptoEngine: VaultCryptoEngine, encFile: File, isVideo: Boolean, thumbFile: File, scratchDir: File): MediaPreview {
    if (isVideo) {
        scratchDir.mkdirs()
        val tmpVideo = File.createTempFile("tmp_", ".raw", scratchDir)
        try {
            FileOutputStream(tmpVideo).use { out ->
                BufferedInputStream(FileInputStream(encFile)).use { encIn -> cryptoEngine.decryptStream(encIn, out) }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tmpVideo.absolutePath)
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
        } finally {
            secureWipeFile(tmpVideo)
        }
    }

    // Photo: decrypt directly in memory
    val photoBytes = BufferedInputStream(FileInputStream(encFile)).use { encIn ->
        ByteArrayOutputStream().also { cryptoEngine.decryptStream(encIn, it) }.toByteArray()
    }
    try {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(photoBytes)).let { it.rotationDegrees to it.isFlipped }
        }.getOrNull()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, bounds)
        val rawWidth = bounds.outWidth.coerceAtLeast(1)
        val rawHeight = bounds.outHeight.coerceAtLeast(1)
        val rotation = orientation?.first ?: 0
        val thumbOpts = BitmapFactory.Options().apply { inSampleSize = galleryThumbnailSampleSize(rawWidth, rawHeight) }
        val rawThumb = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size, thumbOpts)?.let { applyExifOrientation(it, orientation) }
        val thumbBitmap = if (rawThumb != null) {
            createGalleryThumbnail(rawThumb).also { if (it != rawThumb) rawThumb.recycle() }
        } else Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        writeEncryptedThumbnail(cryptoEngine, thumbBitmap, thumbFile)
        return if (rotation == 90 || rotation == 270) MediaPreview(rawHeight, rawWidth, 0L) else MediaPreview(rawWidth, rawHeight, 0L)
    } finally {
        SecureMemory.wipe(photoBytes)
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
