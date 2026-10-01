package com.secretvault.app.core.image

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import com.secretvault.app.core.crypto.VaultCryptoEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

data class EncryptedMediaUri(val filePath: String)

class EncryptedThumbnailKeyer : Keyer<EncryptedMediaUri> {
    override fun key(data: EncryptedMediaUri, options: Options): String {
        return data.filePath
    }
}

class EncryptedThumbnailFetcher(
    private val data: EncryptedMediaUri,
    private val options: Options,
    private val cryptoEngine: VaultCryptoEngine
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val file = File(data.filePath)
        if (!file.exists()) {
            throw IllegalArgumentException("File does not exist: ${data.filePath}")
        }

        // Safety guard: If the file is very large and likely a video, don't read entire payload into RAM
        if (file.name.startsWith("video_") && !file.name.endsWith(".thumb") && file.length() > 20 * 1024 * 1024) {
            val fallback = createFallbackBitmap(options, isVideo = true)
            return@withContext DrawableResult(
                drawable = BitmapDrawable(options.context.resources, fallback),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        }

        val encryptedBytes = file.readBytes()
        val decryptedBytes = cryptoEngine.decryptBytes(encryptedBytes)
        val exifOrientation = runCatching {
            ExifInterface(ByteArrayInputStream(decryptedBytes)).let {
                it.rotationDegrees to it.isFlipped
            }
        }.getOrNull()

        val bitmap: android.graphics.Bitmap = try {
            // First pass: decode bounds
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)

            val rotation = exifOrientation?.first ?: 0
            val rawWidth = if (rotation == 90 || rotation == 270) decodeOptions.outHeight else decodeOptions.outWidth
            val rawHeight = if (rotation == 90 || rotation == 270) decodeOptions.outWidth else decodeOptions.outHeight

            if (rawWidth > 0 && rawHeight > 0) {
                // Calculate appropriate sample size for screen/display
                val displayMetrics = options.context.resources.displayMetrics
                val targetWidth = displayMetrics.widthPixels.coerceAtLeast(1080)
                val targetHeight = displayMetrics.heightPixels.coerceAtLeast(1920)

                var sampleSize = 1
                while ((rawWidth / sampleSize) > targetWidth * 1.5 || (rawHeight / sampleSize) > targetHeight * 1.5) {
                    sampleSize *= 2
                }

                decodeOptions.inJustDecodeBounds = false
                decodeOptions.inSampleSize = sampleSize
                decodeOptions.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888

                var decoded = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)
                if (decoded == null && sampleSize == 1) {
                    // Retry with sampleSize = 2 under memory pressure
                    decodeOptions.inSampleSize = 2
                    decodeOptions.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    decoded = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)
                }
                decoded?.let { applyExifOrientation(it, exifOrientation) }
                    ?: createFallbackBitmap(options, isVideo = file.name.startsWith("video_"))
            } else {
                createFallbackBitmap(options, isVideo = file.name.startsWith("video_"))
            }
        } catch (e: OutOfMemoryError) {
            System.gc()
            createFallbackBitmap(options, isVideo = file.name.startsWith("video_"))
        } catch (e: Exception) {
            createFallbackBitmap(options, isVideo = file.name.startsWith("video_"))
        } finally {
            com.secretvault.app.core.crypto.SecureMemory.wipe(decryptedBytes)
        }

        val drawable = BitmapDrawable(options.context.resources, bitmap)
        DrawableResult(
            drawable = drawable,
            isSampled = false,
            dataSource = DataSource.DISK
        )
    }

    private fun createFallbackBitmap(options: Options, isVideo: Boolean): android.graphics.Bitmap {
        val size = 200
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1E293B") // Dark slate background
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        if (isVideo) {
            // Draw a subtle play icon indicator in center
            paint.color = android.graphics.Color.parseColor("#38BDF8") // Accent cyan
            val path = android.graphics.Path().apply {
                val cx = size / 2f
                val cy = size / 2f
                moveTo(cx - 20f, cy - 30f)
                lineTo(cx + 30f, cy)
                lineTo(cx - 20f, cy + 30f)
                close()
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    class Factory(
        private val cryptoEngine: VaultCryptoEngine
    ) : Fetcher.Factory<EncryptedMediaUri> {
        override fun create(data: EncryptedMediaUri, options: Options, imageLoader: ImageLoader): Fetcher {
            return EncryptedThumbnailFetcher(data, options, cryptoEngine)
        }
    }
}
