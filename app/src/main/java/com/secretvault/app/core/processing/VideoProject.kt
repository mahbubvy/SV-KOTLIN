package com.secretvault.app.core.processing

import com.secretvault.app.core.model.MediaItem
import java.util.UUID

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

    private fun fit(sticker: Sticker): Sticker {
        val start = sticker.startMs.coerceIn(0L, durationMs - MIN_CLIP_MS)
        return sticker.copy(
            startMs = start,
            endMs = sticker.endMs.coerceIn(start + MIN_CLIP_MS, durationMs),
            centerX = sticker.centerX.coerceIn(0f, 1f),
            centerY = sticker.centerY.coerceIn(0f, 1f),
            widthFraction = sticker.widthFraction.coerceIn(0.05f, 1f)
        )
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
 * An image over the video from [startMs] to [endMs] of the output. Placement is in fractions of the frame:
 * [centerX]/[centerY] from the top-left, [widthFraction] of the frame's width (height follows the image's shape).
 */
data class Sticker(
    val source: StickerSource,
    val startMs: Long,
    val endMs: Long,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val widthFraction: Float = 0.3f,
    val id: String = UUID.randomUUID().toString()
)
