package com.secretvault.app.processing

import com.secretvault.app.core.processing.FaceBox
import com.secretvault.app.core.processing.FaceSample
import com.secretvault.app.core.processing.FaceTrack
import com.secretvault.app.core.processing.Placement
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.StickerSource
import com.secretvault.app.core.processing.faceTracks
import com.secretvault.app.core.processing.toBlur
import com.secretvault.app.core.processing.coveredBy
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

    @Test fun aFaceWithARegionOnItIsCovered() {
        val still = faceTracks((0..10).map { FaceSample(it * 200L, listOf(box(0.5f, 0.5f))) }).single()
        val onIt = still.toBlur(10_000, 1f, 200)
        assertEquals(true, still.coveredBy(listOf(onIt)))
        assertEquals(false, still.coveredBy(listOf(onIt.copy(placement = onIt.placement.copy(centerX = 0.9f))))) // elsewhere
        assertEquals(false, still.coveredBy(listOf(onIt.copy(startMs = 1_000, endMs = 4_000)))) // most samples, but not all
        assertEquals(false, still.coveredBy(listOf(onIt.copy(placement = onIt.placement.copy(widthFraction = 0.05f)))))
        assertEquals(false, still.coveredBy(listOf(onIt.copy(source = StickerSource.Emoji("😀")))))

        val wide = still.toBlur(10_000, 2f, 200)
        assertEquals(true, still.coveredBy(listOf(wide), aspect = 2f))
        assertEquals(false, still.coveredBy(listOf(wide), aspect = 1f))
    }

    @Test fun coverageUsesTheRegionsShapeAndRotation() {
        val square = FaceTrack(listOf(0L to box(0.5f, 0.5f)))
        val region = Sticker(StickerSource.Blur(oval = false), 0, 1_000, Placement(widthFraction = 0.14f))
        assertEquals(true, square.coveredBy(listOf(region)))
        assertEquals(false, square.coveredBy(listOf(region.copy(source = StickerSource.Blur(oval = true)))))

        val tall = FaceTrack(listOf(0L to FaceBox(0.48f, 0.42f, 0.52f, 0.58f)))
        val turned = region.copy(placement = Placement(widthFraction = 0.2f, stretch = 0.3f, rotation = 90f))
        assertEquals(true, tall.coveredBy(listOf(turned)))
        assertEquals(false, tall.coveredBy(listOf(turned.copy(placement = turned.placement.copy(rotation = 0f)))))
    }

    @Test fun regionsLeanWithTheHead() {
        val tilted = faceTracks(listOf(FaceSample(0, listOf(box(0.5f, 0.5f).copy(roll = 20f))))).single()
        assertEquals(20f, tilted.toBlur(10_000, 1f, 200).placement.rotation)
    }
}
