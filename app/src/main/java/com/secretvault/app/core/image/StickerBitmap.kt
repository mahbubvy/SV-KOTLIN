package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.processing.StickerSource
import java.io.File

/** The image a sticker shows, for both the editor preview and the export. Photos stay in memory only. */
fun stickerBitmap(cryptoEngine: VaultCryptoEngine, source: StickerSource): Bitmap? = when (source) {
    is StickerSource.Emoji -> emojiBitmap(source.text)
    is StickerSource.Photo -> runCatching { decodeEncryptedImage(cryptoEngine, File(source.media.encryptedPath), 1024) }.getOrNull()
}

/** Same key for the same image, so one bitmap serves every sticker that uses it. */
val StickerSource.key: String
    get() = when (this) {
        is StickerSource.Emoji -> "emoji:$text"
        is StickerSource.Photo -> "photo:${media.id}"
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
