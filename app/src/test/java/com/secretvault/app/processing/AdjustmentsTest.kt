package com.secretvault.app.processing

import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.core.processing.Adjustments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {
    private fun rgb(color: Int) = Triple((color shr 16) and 255, (color shr 8) and 255, color and 255)

    @Test fun noAdjustmentsLeavesColoursAlone() {
        val none = Adjustments()
        assertTrue(none.isNone)
        val cube = none.lutCube(5)
        assertEquals(Triple(255, 0, 64), rgb(cube[4][0][1]))
        assertEquals(Triple(128, 128, 128), rgb(cube[2][2][2]))
        assertTrue(none.with(Adjustment.FADE, 0).isNone) // setting back to 0 clears it
    }

    @Test fun eachAdjustmentPushesTheRightWay() {
        fun look(a: Adjustment, v: Int, r: Float = 0.6f, g: Float = 0.4f, b: Float = 0.3f) = rgb(Adjustments().with(a, v).apply(r, g, b))
        val (r, g, b) = rgb(Adjustments().apply(0.6f, 0.4f, 0.3f))

        val gray = look(Adjustment.SATURATION, -100)
        assertTrue(gray.first == gray.second && gray.second == gray.third)
        assertTrue(look(Adjustment.EXPOSURE, 50).first > r)
        assertTrue(look(Adjustment.BRIGHTNESS, -50).second < g)
        assertTrue(look(Adjustment.WARMTH, 100).let { it.first > r && it.third < b })
        assertTrue(look(Adjustment.TINT, 100).second < g)
        assertTrue(look(Adjustment.SHADOWS, 100, 0.1f, 0.1f, 0.1f).first > 26) // a dark pixel lifts
        assertTrue(look(Adjustment.FADE, 100, 0f, 0f, 0f).first > 0) // black turns grey
        assertTrue(look(Adjustment.CONTRAST, 100).let { it.first - it.third > r - b })
        assertEquals(100, Adjustments().with(Adjustment.EXPOSURE, 500)[Adjustment.EXPOSURE]) // clamped
    }
}
