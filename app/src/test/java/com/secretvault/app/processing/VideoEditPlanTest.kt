package com.secretvault.app.processing

import com.secretvault.app.core.processing.VideoEditMode.REMOVE_SECTION
import com.secretvault.app.core.processing.VideoEditMode.TRIM
import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.core.processing.VideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditPlanTest {

    @Test
    fun trimKeepsSelectedRange() {
        assertEquals(listOf(VideoSegment(2_000, 8_000)), VideoEditPlan.keptSegments(TRIM, 10_000, 2_000, 8_000))
    }

    @Test
    fun removeSectionKeepsBothSides() {
        assertEquals(
            listOf(VideoSegment(0, 3_000), VideoSegment(5_000, 10_000)),
            VideoEditPlan.keptSegments(REMOVE_SECTION, 10_000, 3_000, 5_000)
        )
    }

    @Test
    fun removeSectionAtEitherEdgeLeavesOneSegment() {
        assertEquals(listOf(VideoSegment(4_000, 10_000)), VideoEditPlan.keptSegments(REMOVE_SECTION, 10_000, 0, 4_000))
        assertEquals(listOf(VideoSegment(0, 6_000)), VideoEditPlan.keptSegments(REMOVE_SECTION, 10_000, 6_000, 10_000))
    }

    @Test
    fun tinyLeftoverSliverIsDropped() {
        assertEquals(listOf(VideoSegment(5_000, 10_000)), VideoEditPlan.keptSegments(REMOVE_SECTION, 10_000, 50, 5_000))
    }

    @Test
    fun resultShorterThanOneSecondIsRejected() {
        assertTrue(VideoEditPlan.keptSegments(TRIM, 10_000, 2_000, 2_900).isEmpty())
        assertTrue(VideoEditPlan.keptSegments(REMOVE_SECTION, 10_000, 500, 9_600).isEmpty())
        assertEquals(1, VideoEditPlan.keptSegments(TRIM, 10_000, 2_000, 3_000).size)
    }

    @Test
    fun outOfRangeInputIsClamped() {
        assertEquals(listOf(VideoSegment(0, 10_000)), VideoEditPlan.keptSegments(TRIM, 10_000, -500, 12_000))
    }

    @Test
    fun editedNameAddsSuffixOnce() {
        assertEquals("VID_001 (edited).mp4", VideoEditPlan.editedName("VID_001.mp4"))
        assertEquals("VID_001 (edited).mp4", VideoEditPlan.editedName("VID_001 (edited).mp4"))
        assertEquals("clip (edited)", VideoEditPlan.editedName("clip"))
    }
}
