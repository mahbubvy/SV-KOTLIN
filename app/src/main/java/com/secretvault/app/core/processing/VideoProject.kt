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
data class VideoProject(val clips: List<Clip> = emptyList()) {
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
        if (sourceDurationMs < MIN_CLIP_MS) this else copy(clips = clips + Clip(media, sourceDurationMs))

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
        return copy(clips = clips.toMutableList().apply { add(target, removeAt(index)) })
    }

    fun delete(index: Int): VideoProject =
        if (index !in clips.indices) this else copy(clips = clips.filterIndexed { i, _ -> i != index })

    fun toggleMute(index: Int): VideoProject {
        val clip = clips.getOrNull(index) ?: return this
        return replace(index, listOf(clip.copy(muted = !clip.muted)))
    }

    private fun replace(index: Int, with: List<Clip>) =
        copy(clips = clips.subList(0, index) + with + clips.subList(index + 1, clips.size))

    companion object {
        const val MIN_CLIP_MS = 500L
    }
}
