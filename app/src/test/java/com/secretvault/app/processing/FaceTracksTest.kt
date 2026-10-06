package com.secretvault.app.processing

import com.secretvault.app.core.processing.FaceBox
import com.secretvault.app.core.processing.FaceSample
import com.secretvault.app.core.processing.faceTracks
import com.secretvault.app.core.processing.toBlur
import org.junit.Assert.assertEquals
import org.junit.Test

class FaceTracksTest {
    private fun box(x: Float, y: Float, size: Float = 0.1f) = FaceBox(x - size / 2, y - size / 2, x + size / 2, y + size / 2)

    @Test fun facesAreLinkedAcrossSamplesAndGaps() {
        // Face A walks right across 2 s, missed between 0.8 s and 1.4 s; face B stays put on the left;
        // a face at A's last spot 1.5 s after A was last seen is someone new.
        val samples = (0..10).map { i ->
            val at = i * 200L
            val a = box(0.3f + i * 0.04f, 0.5f).takeIf { at !in 800L..1_200L }
            FaceSample(at, listOfNotNull(a, box(0.1f, 0.2f)))
        } + FaceSample(3_500, listOf(box(0.7f, 0.5f)))
        val tracks = faceTracks(samples)
        assertEquals(3, tracks.size)
        assertEquals(listOf(8, 11, 1), tracks.map { it.points.size }.sortedDescending().let { listOf(it[1], it[0], it[2]) })

        // A straight walk needs only its two ends as keys, and the region glides through the missed part.
        val walk = tracks.first { it.points.size == 8 }.toBlur(sourceDurationMs = 10_000, aspect = 1f, sampleMs = 200)
        assertEquals(2, walk.keys.size)
        assertEquals(0.5f, walk.placementAt(1_000).centerX, 0.001f)
        assertEquals(0.15f, walk.placementAt(1_000).widthFraction, 0.001f) // the face's width with room around it
        assertEquals(0L to 2_200L, walk.startMs to walk.endMs)

        // A face that never moves is one placement without keys.
        assertEquals(0, tracks.first { it.points.size == 11 }.toBlur(10_000, 1f, 200).keys.size)
    }
}
