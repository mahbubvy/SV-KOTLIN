package com.secretvault.app.core.processing

data class VideoSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

enum class VideoEditMode { TRIM, REMOVE_SECTION }

object VideoEditPlan {
    const val MIN_RESULT_MS = 1_000L

    // Slivers this short hold at most a couple of frames and can fail to export.
    private const val MIN_SEGMENT_MS = 100L

    /**
     * Parts of the video to keep, in playback order. [startMs]..[endMs] is the range picked on the
     * timeline: the part kept when trimming, the part dropped when removing a section.
     * Returns an empty list when the result would be shorter than [MIN_RESULT_MS].
     */
    fun keptSegments(mode: VideoEditMode, durationMs: Long, startMs: Long, endMs: Long): List<VideoSegment> {
        val start = startMs.coerceIn(0L, durationMs)
        val end = endMs.coerceIn(start, durationMs)
        val segments = when (mode) {
            VideoEditMode.TRIM -> listOf(VideoSegment(start, end))
            VideoEditMode.REMOVE_SECTION -> listOf(VideoSegment(0L, start), VideoSegment(end, durationMs))
        }.filter { it.durationMs >= MIN_SEGMENT_MS }
        return if (segments.sumOf { it.durationMs } >= MIN_RESULT_MS) segments else emptyList()
    }

    fun editedName(originalName: String): String {
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        return if (base.endsWith(EDITED_SUFFIX)) originalName else base + EDITED_SUFFIX + extension
    }

    private const val EDITED_SUFFIX = " (edited)"
}
