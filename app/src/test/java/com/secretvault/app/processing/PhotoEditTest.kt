package com.secretvault.app.processing

import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.core.processing.Adjustments
import com.secretvault.app.core.processing.CropRect
import com.secretvault.app.core.processing.PhotoEdit
import com.secretvault.app.core.processing.PhotoFilter
import com.secretvault.app.core.processing.straightenZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PhotoEditTest {
    private fun channels(c: Int) = listOf(c shr 16 and 255, c shr 8 and 255, c and 255)

    @Test fun noEditLeavesColoursAlone() {
        val none = PhotoEdit()
        assertFalse(none.changesColour)
        assertEquals(listOf(128, 64, 32), channels(none.colour(128 / 255f, 64 / 255f, 32 / 255f)))
        // A filter at no strength is no filter.
        assertEquals(none.colour(0.3f, 0.6f, 0.2f), none.copy(filter = PhotoFilter.NOIR, filterStrength = 0f).colour(0.3f, 0.6f, 0.2f))
    }

    @Test fun filtersDoWhatTheirNamesSay() {
        fun look(f: PhotoFilter) = channels(PhotoEdit(filter = f).colour(0.8f, 0.4f, 0.2f))
        val mono = look(PhotoFilter.MONO)
        assertTrue(mono.max() - mono.min() <= 1) // grey
        val sepia = look(PhotoFilter.SEPIA)
        assertTrue(sepia[0] > sepia[1] && sepia[1] > sepia[2]) // brown
        val (r, _, b) = look(PhotoFilter.WARM)
        val (r0, _, b0) = look(PhotoFilter.COOL)
        assertTrue(r > r0 && b < b0)
        // Half strength lands between the photo and the full filter.
        val half = channels(PhotoEdit(filter = PhotoFilter.MONO, filterStrength = 0.5f).colour(0.8f, 0.4f, 0.2f))
        assertTrue(half[0] in mono[0]..204 && half[2] in 51..mono[2])
        // Adjustments still apply on top of a filter.
        val brighter = PhotoEdit(filter = PhotoFilter.MONO, adjustments = Adjustments().with(Adjustment.BRIGHTNESS, 50))
        assertTrue(channels(brighter.colour(0.8f, 0.4f, 0.2f))[0] > mono[0])
    }

    @Test fun straighteningZoomsJustEnoughToCoverTheFrame() {
        assertEquals(1f, straightenZoom(0f, 4000, 3000), 0.0001f)
        val w = 4000.0; val h = 3000.0
        for (deg in listOf(-30f, -7f, 12f, 30f)) {
            val z = straightenZoom(deg, 4000, 3000).toDouble()
            val a = Math.toRadians(deg.toDouble())
            // Every frame corner, turned back into the picture, is inside the zoomed picture.
            for ((x, y) in listOf(w / 2 to h / 2, w / 2 to -h / 2)) {
                val px = x * cos(a) + y * sin(a)
                val py = -x * sin(a) + y * cos(a)
                assertTrue(abs(px) <= z * w / 2 + 0.01 && abs(py) <= z * h / 2 + 0.01)
            }
        }
    }

    private fun assertNear(a: CropRect, b: CropRect) =
        listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).forEach { assertEquals(0f, it, 0.0001f) }

    @Test fun cropFollowsTurnsAndFlips() {
        val crop = CropRect(0.1f, 0.2f, 0.5f, 0.9f)
        assertNear(crop, crop.turned().turned().turned().turned())
        // A quarter turn clockwise: the strip near the left edge ends up near the top.
        assertNear(CropRect(0.1f, 0.1f, 0.8f, 0.5f), crop.turned())
        assertNear(crop, crop.flippedH().flippedH())
        assertNear(CropRect(0.5f, 0.2f, 0.9f, 0.9f), crop.flippedH())
        assertNear(CropRect(0.1f, 0.1f, 0.5f, 0.8f), crop.flippedV())
        // A turned edit keeps its crop on the same part of the photo, and its sides swap.
        val turned = PhotoEdit(crop = crop).turned()
        assertTrue(turned.turnsSideways)
        assertNear(crop.turned(), turned.crop)
    }

    @Test fun presetCropsAreCentredAndAsBigAsFits() {
        val square = CropRect.centred(1f, 4000, 3000)
        assertEquals(0.75f, square.width, 0.0001f)
        assertEquals(1f, square.height, 0.0001f)
        assertEquals(0.125f, square.left, 0.0001f)
        val wide = CropRect.centred(16f / 9f, 3000, 4000)
        assertEquals(1f, wide.width, 0.0001f)
        assertEquals(3000f / (16f / 9f) / 4000f, wide.height, 0.0001f)
    }

    @Test fun cropCornersDragAndKeepTheirShape() {
        // Free: the bottom-right corner follows the finger; the top-left stays.
        val free = CropRect.FULL.dragCorner(right = true, bottom = true, x = 0.6f, y = 0.7f, ratio = null, frameW = 4000, frameH = 3000)
        assertNear(CropRect(0f, 0f, 0.6f, 0.7f), free)
        // Square on a 4:3 frame: as big as fits under the finger, still square in pixels.
        val square = CropRect.FULL.dragCorner(right = true, bottom = true, x = 0.6f, y = 0.9f, ratio = 1f, frameW = 4000, frameH = 3000)
        assertEquals(1f, square.width * 4000 / (square.height * 3000), 0.001f)
        assertEquals(0.6f, square.right, 0.0001f)
        // Never smaller than the minimum, and a moved box stays inside the frame.
        assertEquals(CropRect.MIN, CropRect.FULL.dragCorner(true, true, 0f, 0f, null, 100, 100).width, 0.0001f)
        assertNear(CropRect(0.5f, 0.5f, 1f, 1f), CropRect(0.2f, 0.2f, 0.7f, 0.7f).moved(0.9f, 0.3f))
    }

    @Test fun fixedRatioCropCanResizeAtAnEdgeAfterShrinking() {
        val square = CropRect.FULL.dragCorner(true, true, 0f, 0f, 1f, 4000, 3000)
        assertTrue(square.width >= CropRect.MIN - 0.0001f)
        assertTrue(square.height >= CropRect.MIN - 0.0001f)
        val atEdge = square.moved(1f, 1f)
        val resized = atEdge.dragCorner(true, true, 1f, 1f, 1f, 4000, 3000)
        assertEquals(1f, resized.width * 4000 / (resized.height * 3000), 0.001f)
        assertNear(atEdge, resized)

        // A panoramic photo may not have room for MIN on both axes at this shape.
        val narrow = CropRect.centred(9f / 16, 12000, 1000).moved(1f, 0f)
        val narrowResized = narrow.dragCorner(true, true, 1f, 1f, 9f / 16, 12000, 1000)
        assertTrue(narrowResized.width > 0f && narrowResized.height > 0f)
        assertEquals(9f / 16, narrowResized.width * 12000 / (narrowResized.height * 1000), 0.001f)
        assertTrue(narrowResized.right <= 1f && narrowResized.bottom <= 1f)
    }
}
