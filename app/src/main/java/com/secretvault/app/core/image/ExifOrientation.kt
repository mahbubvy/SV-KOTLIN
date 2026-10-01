package com.secretvault.app.core.image

import android.graphics.Bitmap
import android.graphics.Matrix

internal fun applyExifOrientation(bitmap: Bitmap, orientation: Pair<Int, Boolean>?): Bitmap {
    val (rotation, flipped) = orientation ?: return bitmap
    if (rotation == 0 && !flipped) return bitmap

    val matrix = Matrix().apply {
        if (flipped) postScale(-1f, 1f)
        postRotate(rotation.toFloat())
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}
