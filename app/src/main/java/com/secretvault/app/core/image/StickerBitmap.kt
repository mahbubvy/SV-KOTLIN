package com.secretvault.app.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.processing.StickerSource
import java.io.File

/** Bigger vault GIFs show as a still picture rather than holding the whole file in memory. */
private const val MAX_GIF_BYTES = 20L * 1024 * 1024

/**
 * The picture a sticker shows, for both the editor preview and the export. A GIF loops: [frameAt] redraws its one
 * bitmap for the given time, so each user (preview, export) needs its own instance. Photos stay in memory only.
 */
class StickerImage private constructor(private val bitmap: Bitmap, private val movie: Movie?) {
    val width get() = bitmap.width
    val height get() = bitmap.height

    private var drawn = false

    /** The first frame, which never changes; for small copies drawn alongside the moving one. */
    val poster: Bitmap = if (movie == null) bitmap else frameAt(0).copy(Bitmap.Config.ARGB_8888, false)

    /** The picture [ms] into the sticker. A GIF reuses one bitmap, redrawn only when its frame changes. */
    @Suppress("DEPRECATION")
    fun frameAt(ms: Long): Bitmap {
        val m = movie ?: return bitmap
        if (m.setTime((ms.coerceAtLeast(0) % m.duration()).toInt()) || !drawn) {
            drawn = true
            bitmap.eraseColor(0) // also tells Media3 the pixels changed
            m.draw(Canvas(bitmap), 0f, 0f)
        }
        return bitmap
    }

    companion object {
        fun load(context: Context, cryptoEngine: VaultCryptoEngine, source: StickerSource): StickerImage? {
            gifBytes(context, cryptoEngine, source)?.let(::movie)?.let { m ->
                return StickerImage(Bitmap.createBitmap(m.width(), m.height(), Bitmap.Config.ARGB_8888), m)
            }
            return stickerBitmap(context, cryptoEngine, source)?.let { StickerImage(it, null) }
        }
    }
}

/** Same key for the same image, so one picture serves every sticker that uses it. */
val StickerSource.key: String
    get() = when (this) {
        is StickerSource.Emoji -> "emoji:$text"
        is StickerSource.Photo -> "photo:${media.id}"
        is StickerSource.Pack -> "pack:$asset"
    }

private fun gifBytes(context: Context, cryptoEngine: VaultCryptoEngine, source: StickerSource): ByteArray? = runCatching {
    when (source) {
        is StickerSource.Emoji -> null
        is StickerSource.Pack -> source.asset.takeIf { it.endsWith(".gif", ignoreCase = true) }?.let { context.assets.open(it).use { s -> s.readBytes() } }
        is StickerSource.Photo -> source.media.takeIf { (it.mimeType == "image/gif" || it.originalName.endsWith(".gif", true)) && it.sizeBytes <= MAX_GIF_BYTES }
            ?.let { readEncryptedBytes(cryptoEngine, File(it.encryptedPath)) }
    }
}.getOrNull()

/** A GIF with more than one frame, or null for a still one. */
@Suppress("DEPRECATION")
private fun movie(bytes: ByteArray): Movie? =
    Movie.decodeByteArray(bytes, 0, bytes.size)?.takeIf { it.duration() > 0 && it.width() > 0 && it.height() > 0 }

private fun stickerBitmap(context: Context, cryptoEngine: VaultCryptoEngine, source: StickerSource): Bitmap? = when (source) {
    is StickerSource.Emoji -> emojiBitmap(source.text)
    is StickerSource.Photo -> runCatching { decodeEncryptedImage(cryptoEngine, File(source.media.encryptedPath), 1024) }.getOrNull()
    is StickerSource.Pack -> runCatching { context.assets.open(source.asset).use(BitmapFactory::decodeStream) }.getOrNull()
}

private fun emojiBitmap(text: String): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 256f }
    val metrics = paint.fontMetrics
    val width = paint.measureText(text).toInt().coerceAtLeast(1)
    val height = (metrics.descent - metrics.ascent).toInt().coerceAtLeast(1)
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawText(text, 0f, -metrics.ascent, paint)
    }
}
