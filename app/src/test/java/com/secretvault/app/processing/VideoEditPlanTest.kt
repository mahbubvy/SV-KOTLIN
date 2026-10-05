package com.secretvault.app.processing

import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.core.processing.VideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoEditPlanTest {

    @Test
    fun trimKeepsSelectedRange() {
        assertEquals(listOf(VideoSegment(2_000, 8_000)), VideoEditPlan.trimmed(10_000, VideoSegment(2_000, 8_000)))
    }

    @Test
    fun removeSectionKeepsBothSides() {
        assertEquals(
            listOf(VideoSegment(0, 3_000), VideoSegment(5_000, 10_000)),
            VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(3_000, 5_000)))
        )
    }

    @Test
    fun removeSectionAtEitherEdgeLeavesOneSegment() {
        assertEquals(listOf(VideoSegment(4_000, 10_000)), VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(0, 4_000))))
        assertEquals(listOf(VideoSegment(0, 6_000)), VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(6_000, 10_000))))
    }

    @Test
    fun severalSectionsInAnyOrderAreAllRemoved() {
        assertEquals(
            listOf(VideoSegment(0, 1_000), VideoSegment(2_000, 5_000), VideoSegment(6_000, 10_000)),
            VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(5_000, 6_000), VideoSegment(1_000, 2_000)))
        )
    }

    @Test
    fun overlappingSectionsMerge() {
        assertEquals(
            listOf(VideoSegment(0, 2_000), VideoSegment(7_000, 10_000)),
            VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(2_000, 5_000), VideoSegment(4_000, 7_000), VideoSegment(3_000, 4_000)))
        )
    }

    @Test
    fun tinyLeftoverSliverIsDropped() {
        assertEquals(listOf(VideoSegment(5_000, 10_000)), VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(50, 5_000))))
    }

    @Test
    fun resultShorterThanOneSecondIsRejected() {
        assertTrue(VideoEditPlan.trimmed(10_000, VideoSegment(2_000, 2_900)).isEmpty())
        assertTrue(VideoEditPlan.withSectionsRemoved(10_000, listOf(VideoSegment(500, 9_600))).isEmpty())
        assertEquals(1, VideoEditPlan.trimmed(10_000, VideoSegment(2_000, 3_000)).size)
    }

    @Test
    fun outOfRangeInputIsClamped() {
        assertEquals(listOf(VideoSegment(0, 10_000)), VideoEditPlan.trimmed(10_000, VideoSegment(-500, 12_000)))
    }

    @Test
    fun newSectionIsCentredOnTap() {
        assertEquals(VideoSegment(4_000, 6_000), VideoEditPlan.newSectionAt(5_000, 10_000, emptyList()))
    }

    @Test
    fun newSectionIsSqueezedByNeighboursAndEdges() {
        assertEquals(VideoSegment(0, 2_000), VideoEditPlan.newSectionAt(300, 10_000, emptyList()))
        assertEquals(VideoSegment(8_000, 10_000), VideoEditPlan.newSectionAt(9_800, 10_000, emptyList()))
        assertEquals(
            VideoSegment(3_000, 4_000),
            VideoEditPlan.newSectionAt(3_500, 10_000, listOf(VideoSegment(1_000, 3_000), VideoSegment(4_000, 6_000)))
        )
    }

    @Test
    fun noNewSectionInsideExistingOneOrWithoutRoom() {
        assertNull(VideoEditPlan.newSectionAt(2_000, 10_000, listOf(VideoSegment(1_000, 3_000))))
        assertNull(VideoEditPlan.newSectionAt(3_020, 10_000, listOf(VideoSegment(1_000, 3_000), VideoSegment(3_050, 6_000))))
    }

    @Test
    fun rangesMapOntoTheOutputTimeline() {
        val whole = listOf(VideoSegment(0, 10_000))
        assertEquals(listOf(VideoSegment(2_000, 3_000)), VideoEditPlan.toOutput(listOf(VideoSegment(2_000, 3_000)), whole))
        // Trim to 4..8 s: a range at 3..5 s keeps only its 4..5 s part, which lands at 0..1 s.
        assertEquals(listOf(VideoSegment(0, 1_000)), VideoEditPlan.toOutput(listOf(VideoSegment(3_000, 5_000)), listOf(VideoSegment(4_000, 8_000))))
        // Remove 3..5 s: a range spanning the cut becomes two pieces that touch in the output.
        val kept = listOf(VideoSegment(0, 3_000), VideoSegment(5_000, 10_000))
        assertEquals(listOf(VideoSegment(2_000, 3_000), VideoSegment(3_000, 4_000)),
            VideoEditPlan.toOutput(listOf(VideoSegment(2_000, 6_000)), kept))
        // A range entirely inside the removed part disappears; a later one shifts left by the cut.
        assertEquals(listOf(VideoSegment(5_000, 6_000)),
            VideoEditPlan.toOutput(listOf(VideoSegment(3_500, 4_500), VideoSegment(7_000, 8_000)), kept))
        assertTrue(VideoEditPlan.toOutput(emptyList(), kept).isEmpty())
    }

    @Test
    fun editedFileNameIncrementsWhenTaken() {
        assertEquals("SV_01_edited.mp4", VideoEditPlan.editedFileName("SV_01.mp4", emptySet()))
        assertEquals("SV_01_edited_1.mp4", VideoEditPlan.editedFileName("SV_01.mp4", setOf("SV_01_edited.mp4")))
        assertEquals(
            "SV_01_edited_2.mp4",
            VideoEditPlan.editedFileName("SV_01_edited_1.mp4", setOf("SV_01_edited.mp4", "SV_01_edited_1.mp4"))
        )
        assertEquals("clip_edited", VideoEditPlan.editedFileName("clip", emptySet()))
    }
}
