package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.secretvault.app.core.image.drawPaintStrokes
import com.secretvault.app.core.image.frameSize
import com.secretvault.app.core.image.gpuPhotoPreview
import com.secretvault.app.core.image.photoMatrix
import com.secretvault.app.core.image.renderPhoto
import com.secretvault.app.core.processing.Adjustments
import com.secretvault.app.core.processing.BrushStroke
import com.secretvault.app.core.processing.CropRect
import com.secretvault.app.core.processing.PhotoEdit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoRenderDeviceTest {
    @Test fun turnsAndFlipsTransformTheDisplayedFrameAndCropTogether() {
        val width = 400
        val height = 300
        val points = floatArrayOf(80f, 90f, 320f, 240f, 200f, 150f)
        for (turn in 0..3) for (horizontal in listOf(false, true)) for (vertical in listOf(false, true)) {
            val edit = PhotoEdit(quarterTurns = turn, flipH = horizontal, flipV = vertical,
                straighten = 12f, crop = CropRect(0.1f, 0.2f, 0.6f, 0.8f))
            val before = points.copyOf().also { photoMatrix(width, height, edit, false, 1f).mapPoints(it) }
            val (w, h) = frameSize(width, height, edit)
            val turned = FloatArray(before.size)
            val flippedH = before.copyOf()
            val flippedV = before.copyOf()
            for (i in before.indices step 2) {
                turned[i] = h - before[i + 1]
                turned[i + 1] = before[i]
                flippedH[i] = w - before[i]
                flippedV[i + 1] = h - before[i + 1]
            }
            assertArrayEquals(turned, points.copyOf().also { photoMatrix(width, height, edit.turned(), false, 1f).mapPoints(it) }, 0.001f)
            assertArrayEquals(flippedH, points.copyOf().also { photoMatrix(width, height, edit.flippedH(), false, 1f).mapPoints(it) }, 0.001f)
            assertArrayEquals(flippedV, points.copyOf().also { photoMatrix(width, height, edit.flippedV(), false, 1f).mapPoints(it) }, 0.001f)
        }
    }

    @Test fun paintAndHidingStrokesMatchSavedPixelsInEitherOrder() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val paint = BrushStroke(listOf(0.2f to 0.5f, 0.8f to 0.5f), 0.04f, 0f, Color.RED, blur = false)
        val blur = paint.copy(width = 0.3f, blur = true, strength = 1f)
        assertTrue(gpuPhotoPreview(PhotoEdit(strokes = listOf(paint)), shaderAvailable = true))
        assertFalse(gpuPhotoPreview(PhotoEdit(), shaderAvailable = false))
        try {
            for (mosaic in listOf(false, true)) {
                val hide = blur.copy(mosaic = mosaic)
                val orders = listOf(listOf(paint, hide), listOf(hide, paint))
                val savedPixels = mutableListOf<IntArray>()
                for (strokes in orders) {
                    val edit = PhotoEdit(strokes = strokes)
                    val gpu = gpuPhotoPreview(edit, shaderAvailable = true)
                    val base = if (gpu) edit.copy(filter = null, filterStrength = 1f,
                        adjustments = Adjustments(), grain = 0, strokes = emptyList()) else edit
                    val preview = requireNotNull(renderPhoto(source, base, colour = !gpu))
                    val saved = requireNotNull(renderPhoto(source, edit))
                    try {
                        if (gpu) drawPaintStrokes(Canvas(preview), photoMatrix(source.width, source.height, edit, true, 1f),
                            source.width, source.height, strokes)
                        fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
                            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        }
                        val output = pixels(saved)
                        assertArrayEquals(output, pixels(preview))
                        savedPixels += output
                    } finally {
                        preview.recycle()
                        saved.recycle()
                    }
                }
                assertFalse("Painting before hiding must differ from painting after it", savedPixels[0].contentEquals(savedPixels[1]))
            }
        } finally {
            source.recycle()
        }
    }
}
