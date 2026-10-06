package com.secretvault.app.core.processing

import com.secretvault.app.core.model.MediaItem
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One piece of a project: [media] played from [startMs] to [endMs] of its own timeline. [blurs] are kept in the
 * source's own time and frame, so trimming, splitting and framing the clip keep them on what they cover.
 */
data class Clip(
    val media: MediaItem,
    val sourceDurationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = sourceDurationMs,
    val muted: Boolean = false,
    val framing: Framing = Framing(),
    val adjustments: Adjustments = Adjustments(),
    val blurs: List<Sticker> = emptyList(),
    val id: String = UUID.randomUUID().toString()
) {
    val durationMs: Long get() = endMs - startMs
}

/** The multi-clip editor's state. Every operation returns a new project and ignores invalid requests. */
data class VideoProject(val clips: List<Clip> = emptyList(), val stickers: List<Sticker> = emptyList()) {
    val durationMs: Long get() = clips.sumOf { it.durationMs }

    /** Where clip [index] starts in the exported video. */
    fun outputStartOf(index: Int): Long = clips.take(index).sumOf { it.durationMs }

    /** The clip index playing at [outputMs] and the offset into it; the last clip owns the very end. */
    fun clipAt(outputMs: Long): Pair<Int, Long>? {
        var start = 0L
        clips.forEachIndexed { index, clip ->
            if (outputMs < start + clip.durationMs || index == clips.lastIndex) {
                return index to (outputMs - start).coerceIn(0L, clip.durationMs)
            }
            start += clip.durationMs
        }
        return null
    }

    fun add(media: MediaItem, sourceDurationMs: Long): VideoProject =
        if (sourceDurationMs < MIN_CLIP_MS) this else withClips(clips + Clip(media, sourceDurationMs))

    /** Cuts the clip under [outputMs] in two; ignored too close to a clip edge. */
    fun split(outputMs: Long): VideoProject {
        val (index, offset) = clipAt(outputMs) ?: return this
        val clip = clips[index]
        if (offset < MIN_CLIP_MS || clip.durationMs - offset < MIN_CLIP_MS) return this
        val cut = clip.startMs + offset
        // The second half gets its own blur ids, so every layer id in the project is unique.
        return replace(index, listOf(clip.copy(endMs = cut),
            clip.copy(startMs = cut, id = UUID.randomUUID().toString(), blurs = clip.blurs.map { it.copy(id = UUID.randomUUID().toString()) })))
    }

    /** Sets clip [index] to play [startMs]..[endMs] of its source, kept inside the source and at least [MIN_CLIP_MS]. */
    fun trim(index: Int, startMs: Long, endMs: Long): VideoProject {
        val clip = clips.getOrNull(index) ?: return this
        val start = startMs.coerceIn(0L, clip.sourceDurationMs - MIN_CLIP_MS)
        val end = endMs.coerceIn(start + MIN_CLIP_MS, clip.sourceDurationMs)
        return replace(index, listOf(clip.copy(startMs = start, endMs = end)))
    }

    /** Moves clip [index] by [by] places (−1 left, +1 right). */
    fun move(index: Int, by: Int): VideoProject {
        val target = index + by
        if (index !in clips.indices || target !in clips.indices) return this
        return withClips(clips.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun delete(index: Int): VideoProject =
        if (index !in clips.indices) this else withClips(clips.filterIndexed { i, _ -> i != index })

    fun frame(index: Int, change: (Framing) -> Framing): VideoProject {
        val clip = clips.getOrNull(index) ?: return this
        return replace(index, listOf(clip.copy(framing = change(clip.framing).fitted())))
    }

    fun adjust(index: Int, change: (Adjustments) -> Adjustments): VideoProject {
        val clip = clips.getOrNull(index) ?: return this
        return replace(index, listOf(clip.copy(adjustments = change(clip.adjustments))))
    }

    /** Gives every clip the adjustments of clip [index]. */
    fun adjustAllLike(index: Int): VideoProject {
        val adjustments = clips.getOrNull(index)?.adjustments ?: return this
        return withClips(clips.map { it.copy(adjustments = adjustments) })
    }

    fun toggleMute(index: Int): VideoProject {
        val clip = clips.getOrNull(index) ?: return this
        return replace(index, listOf(clip.copy(muted = !clip.muted)))
    }

    private fun replace(index: Int, with: List<Clip>) =
        withClips(clips.subList(0, index) + with + clips.subList(index + 1, clips.size))

    /** Adds a [DEFAULT_STICKER_MS] sticker starting at [atMs]; ignored at [MAX_STICKERS] or with no video. */
    fun addSticker(source: StickerSource, atMs: Long): VideoProject {
        if (stickers.size >= MAX_STICKERS || durationMs < MIN_CLIP_MS) return this
        val start = atMs.coerceIn(0L, durationMs - MIN_CLIP_MS)
        return copy(stickers = stickers + fit(Sticker(source, start, start + DEFAULT_STICKER_MS)))
    }

    /** Changes sticker [id]; its times stay inside the video and its placement inside the frame. */
    fun updateSticker(id: String, change: (Sticker) -> Sticker): VideoProject =
        copy(stickers = stickers.map { if (it.id == id) fit(change(it)) else it })

    fun deleteSticker(id: String): VideoProject = copy(stickers = stickers.filter { it.id != id })

    /** Adds a copy of sticker [id] a little down and right of it, keys and all; ignored at [MAX_STICKERS]. */
    fun duplicateSticker(id: String): VideoProject {
        val s = stickers.firstOrNull { it.id == id } ?: return this
        if (stickers.size >= MAX_STICKERS) return this
        return copy(stickers = stickers + s.nudged())
    }

    /**
     * Every sticker and blur region in output time, as the timeline and preview show them. A blur region is its clip's
     * copy moved by the clip's place in the output (keys are relative to the start, so they move with it); it can
     * reach past its clip's trimmed ends, where it isn't shown.
     */
    val layers: List<Sticker>
        get() = stickers + clips.flatMapIndexed { i, c -> c.blurs.map { it.shifted(outputStartOf(i) - c.startMs) } }

    /** The clip holding blur region [id], or -1 for a sticker or an unknown id. */
    fun blurClipOf(id: String): Int = clips.indexOfFirst { c -> c.blurs.any { it.id == id } }

    /** Adds a blur region to the clip at [atOutputMs], from there to the clip's end; ignored at [MAX_BLURS]. */
    fun addBlur(atOutputMs: Long): VideoProject {
        val (index, offset) = clipAt(atOutputMs) ?: return this
        val clip = clips[index]
        if (clip.blurs.size >= MAX_BLURS) return this
        val start = minOf(clip.startMs + offset, clip.endMs - MIN_CLIP_MS)
        val blur = Sticker(StickerSource.Blur(), start, clip.endMs, Placement(widthFraction = 0.3f, stretch = 1.3f))
        return replace(index, listOf(clip.copy(blurs = clip.blurs + blur)))
    }

    /** Changes sticker or blur region [id] as seen in output time (see [layers]); blur regions are kept in their source. */
    fun updateLayer(id: String, change: (Sticker) -> Sticker): VideoProject {
        val index = blurClipOf(id)
        if (index < 0) return updateSticker(id, change)
        val clip = clips[index]
        val shift = outputStartOf(index) - clip.startMs
        return replace(index, listOf(clip.copy(blurs = clip.blurs.map { if (it.id == id) fitBlur(change(it.shifted(shift)).shifted(-shift), clip) else it })))
    }

    fun deleteLayer(id: String): VideoProject =
        copy(stickers = stickers.filter { it.id != id }, clips = clips.map { c -> if (c.blurs.any { it.id == id }) c.copy(blurs = c.blurs.filter { it.id != id }) else c })

    fun canDuplicate(id: String): Boolean {
        val index = blurClipOf(id)
        return if (index < 0) stickers.size < MAX_STICKERS else clips[index].blurs.size < MAX_BLURS
    }

    /** Adds a copy of sticker or blur region [id] a little down and right of it, keys and all. */
    fun duplicateLayer(id: String): VideoProject {
        val index = blurClipOf(id)
        if (index < 0) return duplicateSticker(id)
        val clip = clips[index]
        if (!canDuplicate(id)) return this
        val copy = clip.blurs.first { it.id == id }.nudged()
        return replace(index, listOf(clip.copy(blurs = clip.blurs + copy)))
    }

    // Stickers follow the video's length; with no video left there is nothing to put them on.
    private fun withClips(newClips: List<Clip>): VideoProject {
        val next = copy(clips = newClips)
        return if (next.durationMs < MIN_CLIP_MS) next.copy(stickers = emptyList()) else next.copy(stickers = stickers.map(next::fit))
    }

    // Placements are kept in the frame as they're made (Sticker.placeAt), so only the times need fitting here.
    private fun fit(sticker: Sticker): Sticker {
        val start = sticker.startMs.coerceIn(0L, durationMs - MIN_CLIP_MS)
        return sticker.copy(startMs = start, endMs = sticker.endMs.coerceIn(start + MIN_CLIP_MS, durationMs))
    }

    // A blur region stays inside its source; it may reach past the clip's trimmed ends.
    private fun fitBlur(blur: Sticker, clip: Clip): Sticker {
        val start = blur.startMs.coerceIn(0L, clip.sourceDurationMs - MIN_CLIP_MS)
        return blur.copy(startMs = start, endMs = blur.endMs.coerceIn(start + MIN_CLIP_MS, clip.sourceDurationMs))
    }

    companion object {
        const val MIN_CLIP_MS = 500L
        const val MAX_STICKERS = 10
        /** Per clip; the export shader has room for this many. */
        const val MAX_BLURS = 16
        const val DEFAULT_STICKER_MS = 3_000L
    }
}

/**
 * How a clip sits in the frame: turned [rotation] degrees clockwise (shown as [angle]), scaled by [zoom] from the
 * fitted size, and moved by [offsetX]/[offsetY] fractions of the frame (right and down).
 */
data class Framing(val rotation: Float = 0f, val zoom: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f) {
    val angle: Float get() = snapAngle(rotation)
    fun fitted() = copy(zoom = zoom.coerceIn(0.25f, 5f), offsetX = offsetX.coerceIn(-1f, 1f), offsetY = offsetY.coerceIn(-1f, 1f))
}

/**
 * [degrees] pulled to the nearest quarter turn when within 5°, so straight angles are easy to hit. Callers keep the
 * unsnapped value, so a slow twist can still move off a quarter turn.
 */
fun snapAngle(degrees: Float): Float {
    val quarter = (degrees / 90f).roundToInt() * 90f
    return if (abs(degrees - quarter) <= 5f) quarter else degrees
}

sealed interface StickerSource {
    data class Emoji(val text: String) : StickerSource
    data class Photo(val media: MediaItem) : StickerSource
    /** A bundled sticker, by its path in the app's assets. */
    data class Pack(val asset: String) : StickerSource
    /** A blur region over part of a clip: pixelate or blur, an oval or a rectangle, [strength] 0..1. */
    data class Blur(val style: BlurStyle = BlurStyle.PIXELATE, val oval: Boolean = true, val strength: Float = 0.5f) : StickerSource
}

enum class BlurStyle { PIXELATE, BLUR }

/**
 * Where a sticker or blur region sits, in fractions of its frame: [centerX]/[centerY] from the top-left,
 * [widthFraction] of the frame's width, turned [rotation] degrees clockwise (shown as [angle]). Its height is the
 * width times the image's shape (1 for a blur region) times [stretch], which only blur regions change.
 */
data class Placement(
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val widthFraction: Float = 0.3f,
    val rotation: Float = 0f,
    val stretch: Float = 1f
) {
    val angle: Float get() = snapAngle(rotation)
    fun fitted() = copy(centerX = centerX.coerceIn(0f, 1f), centerY = centerY.coerceIn(0f, 1f),
        widthFraction = widthFraction.coerceIn(0.05f, 1f), stretch = stretch.coerceIn(0.1f, 10f))
}

/** The sticker's [placement] [atMs] after the sticker appears, so keyframes move with the sticker. */
data class Keyframe(val atMs: Long, val placement: Placement)

/**
 * An image or blur region over the video from [startMs] to [endMs]: output time for a sticker, its clip's source time
 * for a blur region. Without [keys] it stays at [placement], last set [placedAtMs] after it appears; with keys it
 * glides in straight lines from one to the next, holding the first before it and the last after it.
 */
data class Sticker(
    val source: StickerSource,
    val startMs: Long,
    val endMs: Long,
    val placement: Placement = Placement(),
    val keys: List<Keyframe> = emptyList(), // sorted by atMs
    val placedAtMs: Long = 0L,
    val id: String = UUID.randomUUID().toString()
) {
    fun placementAt(outputMs: Long): Placement {
        if (keys.isEmpty()) return placement
        val at = outputMs - startMs
        val next = keys.indexOfFirst { it.atMs >= at }
        if (next == 0) return keys.first().placement
        if (next < 0) return keys.last().placement
        val a = keys[next - 1]
        val b = keys[next]
        val f = (at - a.atMs).toFloat() / (b.atMs - a.atMs)
        fun mix(x: Float, y: Float) = x + (y - x) * f
        return Placement(mix(a.placement.centerX, b.placement.centerX), mix(a.placement.centerY, b.placement.centerY),
            mix(a.placement.widthFraction, b.placement.widthFraction), mix(a.placement.rotation, b.placement.rotation),
            mix(a.placement.stretch, b.placement.stretch))
    }

    /** The keyframe the playhead at [outputMs] is on, allowing for a playhead that lands a little off it. */
    fun keyAt(outputMs: Long): Keyframe? = keys.firstOrNull { abs(it.atMs - (outputMs - startMs)) <= KEY_SNAP_MS }

    /**
     * Changes the placement shown at [outputMs]. Without keyframes, at the moment it was placed this just moves it;
     * at any other moment it starts moving, After Effects style: one key where it was placed and one here. With
     * keyframes, it changes the key there, or adds one.
     */
    fun placeAt(outputMs: Long, change: (Placement) -> Placement): Sticker {
        val placed = change(placementAt(outputMs)).fitted()
        val at = (outputMs - startMs).coerceIn(0L, endMs - startMs)
        if (keys.isEmpty()) {
            val placedAt = placedAtMs.coerceIn(0L, endMs - startMs)
            return if (abs(at - placedAt) <= KEY_SNAP_MS) copy(placement = placed, placedAtMs = at)
            else copy(keys = listOf(Keyframe(placedAt, placement), Keyframe(at, placed)).sortedBy { it.atMs })
        }
        val existing = keyAt(outputMs)
        return copy(keys = (keys.filter { it !== existing } + Keyframe(existing?.atMs ?: at, placed)).sortedBy { it.atMs })
    }

    /** Adds a keyframe at [outputMs] holding what's shown there, or removes the one already there. */
    fun toggleKeyAt(outputMs: Long): Sticker {
        val existing = keyAt(outputMs)
        return when {
            // The last one going: stay where it was rather than jump back.
            existing != null && keys.size == 1 -> copy(keys = emptyList(), placement = existing.placement, placedAtMs = existing.atMs)
            existing != null -> copy(keys = keys - existing)
            else -> copy(keys = (keys + Keyframe((outputMs - startMs).coerceIn(0L, endMs - startMs), placementAt(outputMs))).sortedBy { it.atMs })
        }
    }

    /** The same sticker [byMs] later; its keys, relative to the start, move with it. */
    fun shifted(byMs: Long): Sticker = copy(startMs = startMs + byMs, endMs = endMs + byMs)

    /** A copy with a new id, a little down and right. */
    fun nudged(): Sticker {
        fun nudge(p: Placement) = p.copy(centerX = p.centerX + 0.05f, centerY = p.centerY + 0.05f).fitted()
        return copy(placement = nudge(placement), keys = keys.map { it.copy(placement = nudge(it.placement)) }, id = UUID.randomUUID().toString())
    }

    companion object {
        const val KEY_SNAP_MS = 150L
    }
}
