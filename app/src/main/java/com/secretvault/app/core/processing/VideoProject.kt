package com.secretvault.app.core.processing

import com.secretvault.app.core.model.MediaItem
import java.util.UUID
import kotlin.math.abs

/** One piece of a project: [media] played from [startMs] to [endMs] of its own timeline. */
data class Clip(
    val media: MediaItem,
    val sourceDurationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = sourceDurationMs,
    val muted: Boolean = false,
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
        return replace(index, listOf(clip.copy(endMs = cut), clip.copy(startMs = cut, id = UUID.randomUUID().toString())))
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

    companion object {
        const val MIN_CLIP_MS = 500L
        const val MAX_STICKERS = 10
        const val DEFAULT_STICKER_MS = 3_000L
    }
}

sealed interface StickerSource {
    data class Emoji(val text: String) : StickerSource
    data class Photo(val media: MediaItem) : StickerSource
}

/**
 * Where a sticker sits, in fractions of the frame: [centerX]/[centerY] from the top-left, [widthFraction] of the
 * frame's width (height follows the image's shape).
 */
data class Placement(val centerX: Float = 0.5f, val centerY: Float = 0.5f, val widthFraction: Float = 0.3f) {
    fun fitted() = Placement(centerX.coerceIn(0f, 1f), centerY.coerceIn(0f, 1f), widthFraction.coerceIn(0.05f, 1f))
}

/** The sticker's [placement] [atMs] after the sticker appears, so keyframes move with the sticker. */
data class Keyframe(val atMs: Long, val placement: Placement)

/**
 * An image over the video from [startMs] to [endMs] of the output. Without [keys] it stays at [placement]; with
 * keys it glides in straight lines from one to the next, holding the first before it and the last after it.
 */
data class Sticker(
    val source: StickerSource,
    val startMs: Long,
    val endMs: Long,
    val placement: Placement = Placement(),
    val keys: List<Keyframe> = emptyList(), // sorted by atMs
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
            mix(a.placement.widthFraction, b.placement.widthFraction))
    }

    /** The keyframe the playhead at [outputMs] is on, allowing for a playhead that lands a little off it. */
    fun keyAt(outputMs: Long): Keyframe? = keys.firstOrNull { abs(it.atMs - (outputMs - startMs)) <= KEY_SNAP_MS }

    /** Changes the placement shown at [outputMs]: the whole sticker without keyframes, else the keyframe there (added if new). */
    fun placeAt(outputMs: Long, change: (Placement) -> Placement): Sticker {
        val placed = change(placementAt(outputMs)).fitted()
        if (keys.isEmpty()) return copy(placement = placed)
        val existing = keyAt(outputMs)
        val at = existing?.atMs ?: (outputMs - startMs).coerceIn(0L, endMs - startMs)
        return copy(keys = (keys.filter { it !== existing } + Keyframe(at, placed)).sortedBy { it.atMs })
    }

    /** Adds a keyframe at [outputMs] holding what's shown there, or removes the one already there. */
    fun toggleKeyAt(outputMs: Long): Sticker {
        val existing = keyAt(outputMs)
        return when {
            // The last one going: stay where it was rather than jump back.
            existing != null && keys.size == 1 -> copy(keys = emptyList(), placement = existing.placement)
            existing != null -> copy(keys = keys - existing)
            else -> copy(keys = (keys + Keyframe((outputMs - startMs).coerceIn(0L, endMs - startMs), placementAt(outputMs))).sortedBy { it.atMs })
        }
    }

    companion object {
        const val KEY_SNAP_MS = 150L
    }
}
