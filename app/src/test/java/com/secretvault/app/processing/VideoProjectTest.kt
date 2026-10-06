package com.secretvault.app.processing

import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.processing.Framing
import com.secretvault.app.core.processing.Placement
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.StickerSource
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.processing.VideoProjectHistory
import com.secretvault.app.core.processing.snapAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProjectTest {
    private fun media(id: String) = MediaItem(id, "$id.mp4", "$id.mp4", MediaType.VIDEO, "video/mp4", "/v/$id.enc", null, 1, albumId = "a")
    private val a = media("a")
    private val b = media("b")
    // a: 10 s, b: 4 s
    private val project = VideoProject().add(a, 10_000).add(b, 4_000)

    @Test fun undoRedoGroupsGesturesAndDropsRedoAfterANewEdit() {
        val initial = VideoProjectHistory(project)
        val changed = initial.beginGesture().change(project.trim(0, 1_000, 9_000))
            .change(project.trim(0, 2_000, 8_000)).endGesture()
        assertEquals(project, changed.undo().current)
        assertTrue(!changed.undo().canUndo)
        assertEquals(changed.current, changed.undo().redo().current)
        assertTrue(!changed.undo().change(project.toggleMute(0)).canRedo)
        assertSame(initial, initial.change(project))
        assertTrue(!initial.beginGesture().endGesture().canUndo)
        assertEquals(project, initial.undo().current)
        assertEquals(project, initial.redo().current)
        val capped = (1..105).fold(initial) { h, _ -> h.change(h.current.toggleMute(0)) }
        assertTrue(!(1..100).fold(capped) { h, _ -> h.undo() }.canUndo)
    }

    @Test fun addBuildsTheTimeline() {
        assertEquals(14_000, project.durationMs)
        assertEquals(10_000, project.outputStartOf(1))
        assertSame(project, project.add(a, 400)) // too short to be a clip
    }

    @Test fun clipAtFindsClipAndOffset() {
        assertEquals(0 to 0L, project.clipAt(0))
        assertEquals(0 to 9_999L, project.clipAt(9_999))
        assertEquals(1 to 0L, project.clipAt(10_000))
        assertEquals(1 to 4_000L, project.clipAt(14_000)) // end belongs to the last clip
        assertNull(VideoProject().clipAt(0))
    }

    @Test fun splitCutsTheClipUnderThePlayhead() {
        val split = project.split(3_000)
        assertEquals(listOf(a to (0L to 3_000L), a to (3_000L to 10_000L), b to (0L to 4_000L)),
            split.clips.map { it.media to (it.startMs to it.endMs) })
        assertEquals(14_000, split.durationMs)
        assertTrue(split.clips[0].id != split.clips[1].id)
        // A split inside the second clip uses its own source time.
        assertEquals(1_000L, project.split(11_000).clips[1].endMs)
        // Too close to an edge: nothing happens.
        assertSame(project, project.split(200))
        assertSame(project, project.split(9_800))
    }

    @Test fun trimStaysInsideTheSourceAndKeepsAMinimumLength() {
        assertEquals(2_000L to 8_000L, project.trim(0, 2_000, 8_000).clips[0].let { it.startMs to it.endMs })
        assertEquals(0L to 10_000L, project.trim(0, -5, 99_000).clips[0].let { it.startMs to it.endMs })
        assertEquals(5_000L to 5_500L, project.trim(0, 5_000, 5_100).clips[0].let { it.startMs to it.endMs })
        assertEquals(9_500L to 10_000L, project.trim(0, 10_000, 10_000).clips[0].let { it.startMs to it.endMs })
        assertSame(project, project.trim(7, 0, 1))
    }

    @Test fun moveDeleteAndMute() {
        assertEquals(listOf(b, a), project.move(0, 1).clips.map { it.media })
        assertEquals(listOf(b, a), project.move(1, -1).clips.map { it.media })
        assertSame(project, project.move(0, -1))
        assertSame(project, project.move(1, 1))
        assertEquals(listOf(b), project.delete(0).clips.map { it.media })
        assertSame(project, project.delete(5))
        val muted = project.toggleMute(1)
        assertEquals(listOf(false, true), muted.clips.map { it.muted })
        assertEquals(listOf(false, false), muted.toggleMute(1).clips.map { it.muted })
    }

    @Test fun stickersStayInsideTheVideo() {
        val smile = StickerSource.Emoji("😀")
        val withSticker = project.addSticker(smile, 13_000)
        val s = withSticker.stickers.single()
        assertEquals(13_000L to 14_000L, s.startMs to s.endMs) // the default 3 s is cut at the end
        assertEquals(13_500L, withSticker.addSticker(smile, 99_000).stickers[1].startMs) // room for the shortest sticker

        val moved = withSticker.updateSticker(s.id) { it.copy(startMs = -5, endMs = 2_000).placeAt(0) { p -> p.copy(centerX = 2f, widthFraction = 0f) } }.stickers.single()
        assertEquals(listOf(0L, 2_000L), listOf(moved.startMs, moved.endMs))
        assertEquals(1f, moved.placement.centerX)
        assertEquals(0.05f, moved.placement.widthFraction)

        // Deleting the 10 s clip leaves 4 s, so the sticker is pulled back inside it.
        val shrunk = withSticker.delete(0).stickers.single()
        assertEquals(3_500L to 4_000L, shrunk.startMs to shrunk.endMs)
        assertTrue(withSticker.delete(0).delete(0).stickers.isEmpty())

        val full = (1..VideoProject.MAX_STICKERS).fold(project) { p, _ -> p.addSticker(smile, 0) }
        assertSame(full, full.addSticker(smile, 0))
        assertTrue(full.deleteSticker(full.stickers[0].id).stickers.size == VideoProject.MAX_STICKERS - 1)

        // A duplicate is a new sticker just down and right of the original, with the same times.
        val twice = withSticker.duplicateSticker(s.id).stickers
        assertEquals(2, twice.map { it.id }.distinct().size)
        assertEquals(s.startMs to s.endMs, twice[1].startMs to twice[1].endMs)
        assertEquals(0.55f, twice[1].placement.centerX, 0.0001f)
        assertSame(full, full.duplicateSticker(full.stickers[0].id))
    }

    @Test fun keyframesGlideBetweenPlacements() {
        // Shown from 10 s to 14 s; keys at 1 s (left) and 3 s (right, twice as wide).
        val still = Sticker(StickerSource.Emoji("x"), 10_000, 14_000, Placement(0.2f, 0.5f, 0.2f))
        assertEquals(Placement(0.2f, 0.5f, 0.2f), still.placementAt(13_000)) // no keys: one place
        // Dragging at the moment it was placed just moves it; at another moment it starts moving from there to here.
        assertTrue(still.placeAt(10_100) { it.copy(centerX = 0.6f) }.keys.isEmpty())
        assertEquals(listOf(0L to 0.2f, 2_000L to 0.6f),
            still.placeAt(12_000) { it.copy(centerX = 0.6f) }.keys.map { it.atMs to it.placement.centerX })

        val moving = still.toggleKeyAt(11_000).placeAt(13_000) { it.copy(centerX = 0.8f, widthFraction = 0.4f) }
        assertEquals(listOf(1_000L, 3_000L), moving.keys.map { it.atMs })
        assertEquals(0.2f, moving.placementAt(10_000).centerX) // holds the first key before it
        assertEquals(0.5f, moving.placementAt(12_000).centerX, 0.0001f) // halfway
        assertEquals(0.3f, moving.placementAt(12_000).widthFraction, 0.0001f)
        assertEquals(0.8f, moving.placementAt(14_000).centerX) // holds the last key after it

        // Dragging near a key moves that key instead of adding one.
        assertEquals(2, moving.placeAt(13_100) { it.copy(centerY = 0.1f) }.keys.size)
        // Removing the last key leaves the sticker where that key had it.
        val back = moving.toggleKeyAt(13_000).toggleKeyAt(11_050)
        assertTrue(back.keys.isEmpty())
        assertEquals(0.2f, back.placement.centerX)
    }

    @Test fun framingSnapsQuarterTurnsAndStaysInBounds() {
        assertEquals(90f, snapAngle(86f))
        assertEquals(-180f, snapAngle(-183f))
        assertEquals(80f, snapAngle(80f))
        val framed = project.frame(1) { Framing(rotation = 92f, zoom = 9f, offsetX = -3f) }.clips[1].framing
        assertEquals(Framing(rotation = 92f, zoom = 5f, offsetX = -1f), framed)
        assertEquals(90f, framed.angle)
        assertEquals(framed, project.frame(1) { Framing(rotation = 92f, zoom = 9f, offsetX = -3f) }.split(12_000).clips[2].framing) // halves keep it
        // Sticker keys turn too, halfway between 0° and 40° at the midpoint.
        val s = Sticker(StickerSource.Emoji("x"), 0, 4_000).placeAt(1_000) { it.copy(rotation = 40f) }
        assertEquals(20f, s.placementAt(500).rotation, 0.0001f)
    }

    @Test fun adjustmentsArePerClipAndCanBeCopiedToAll() {
        val bright = project.adjust(0) { it.with(com.secretvault.app.core.processing.Adjustment.BRIGHTNESS, 30) }
        assertTrue(bright.clips[1].adjustments.isNone)
        assertEquals(bright.clips[0].adjustments, bright.split(3_000).clips[1].adjustments) // halves keep it
        assertEquals(bright.clips[0].adjustments, bright.adjustAllLike(0).clips[1].adjustments)
    }
}
