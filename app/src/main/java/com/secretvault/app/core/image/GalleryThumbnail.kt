package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

internal const val GALLERY_THUMBNAIL_SIZE = 512
internal const val GALLERY_THUMBNAIL_SUFFIX = ".gallery2.thumb"

internal fun galleryThumbnailSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (max(width, height) / sample > 2048 || min(width, height) / (sample * 2) >= GALLERY_THUMBNAIL_SIZE) sample *= 2
    return sample
}

internal fun createGalleryThumbnail(source: Bitmap): Bitmap {
    val edge = min(source.width, source.height)
    val crop = Bitmap.createBitmap(source, (source.width - edge) / 2, (source.height - edge) / 2, edge, edge)
    val size = min(edge, GALLERY_THUMBNAIL_SIZE)
    val scaled = Bitmap.createScaledBitmap(crop, size, size, true)
    if (scaled !== crop && crop !== source) crop.recycle()
    return if (scaled === source) requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) else scaled
}

internal fun encryptedGalleryThumbnail(source: Bitmap, crypto: VaultCryptoEngine): ByteArray {
    val thumbnail = createGalleryThumbnail(source)
    try {
        val bytes = ByteArrayOutputStream().use { output ->
            check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, output))
            output.toByteArray()
        }
        try { return crypto.encryptBytes(bytes) } finally { SecureMemory.wipe(bytes) }
    } finally { thumbnail.recycle() }
}

internal fun MediaMetadataRetriever.galleryFrame(): Bitmap? =
    if (Build.VERSION.SDK_INT >= 27) getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
        ?: getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024)
    else getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: frameAtTime
