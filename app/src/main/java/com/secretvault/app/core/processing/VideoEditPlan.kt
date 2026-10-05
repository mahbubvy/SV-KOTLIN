package com.secretvault.app.core.processing

data class VideoSegment(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    operator fun contains(ms: Long): Boolean = ms in startMs..endMs
}

enum class VideoEditMode { TRIM, REMOVE_SECTION }

object VideoEditPlan {
    const val MIN_RESULT_MS = 1_000L
    const val MAX_SECTIONS = 10
    const val NEW_SECTION_MS = 2_000L

    // Slivers this short hold at most a couple of frames and can fail to export.
    private const val MIN_SEGMENT_MS = 100L

    /** The part to keep when trimming; empty when it would be shorter than [MIN_RESULT_MS]. */
    fun trimmed(durationMs: Long, keep: VideoSegment): List<VideoSegment> =
        withSectionsRemoved(durationMs, listOf(VideoSegment(0L, keep.startMs), VideoSegment(keep.endMs, durationMs)))

    /**
     * Parts of the video left after cutting out [removed] (any order, may overlap), in playback
     * order. Empty when the result would be shorter than [MIN_RESULT_MS].
     */
    fun withSectionsRemoved(durationMs: Long, removed: List<VideoSegment>): List<VideoSegment> {
        val kept = mutableListOf<VideoSegment>()
        var cursor = 0L
        for (section in removed.sortedBy { it.startMs }) {
            val start = section.startMs.coerceIn(0L, durationMs)
            if (start > cursor) kept += VideoSegment(cursor, start)
            cursor = maxOf(cursor, section.endMs.coerceIn(0L, durationMs))
        }
        if (cursor < durationMs) kept += VideoSegment(cursor, durationMs)
        val segments = kept.filter { it.durationMs >= MIN_SEGMENT_MS }
        return if (segments.sumOf { it.durationMs } >= MIN_RESULT_MS) segments else emptyList()
    }

    /**
     * A [NEW_SECTION_MS] section centred on [tapMs], squeezed so it stays inside the video and
     * doesn't overlap [existing]. Null when the tap lands in an existing section or there's no room.
     */
    fun newSectionAt(tapMs: Long, durationMs: Long, existing: List<VideoSegment>): VideoSegment? {
        if (existing.any { tapMs in it }) return null
        val lower = existing.filter { it.endMs <= tapMs }.maxOfOrNull { it.endMs } ?: 0L
        val upper = existing.filter { it.startMs >= tapMs }.minOfOrNull { it.startMs } ?: durationMs
        val centredStart = (tapMs - NEW_SECTION_MS / 2).coerceAtLeast(lower)
        val end = (centredStart + NEW_SECTION_MS).coerceAtMost(upper)
        // Near an edge or neighbour, grow back the other way so it keeps its full length where it fits.
        val start = (end - NEW_SECTION_MS).coerceAtLeast(lower)
        return if (end - start >= MIN_SEGMENT_MS) VideoSegment(start, end) else null
    }

    /** "SV_x.mp4" -> "SV_x_edited.mp4", then "_edited_1", "_edited_2"... for names already taken. */
    /**
     * Maps [ranges] picked on the original video onto the exported video made of [segments]:
     * each kept segment contributes its overlap with a range, shifted to where that segment lands in the output.
     */
    fun toOutput(ranges: List<VideoSegment>, segments: List<VideoSegment>): List<VideoSegment> {
        val mapped = mutableListOf<VideoSegment>()
        var outputStart = 0L
        for (segment in segments) {
            for (range in ranges) {
                val start = maxOf(range.startMs, segment.startMs)
                val end = minOf(range.endMs, segment.endMs)
                if (end > start) mapped += VideoSegment(outputStart + start - segment.startMs, outputStart + end - segment.startMs)
            }
            outputStart += segment.durationMs
        }
        return mapped.sortedBy { it.startMs }
    }

    fun editedFileName(fileName: String, existingNames: Set<String>): String {
        val dot = fileName.lastIndexOf('.')
        val extension = if (dot > 0) fileName.substring(dot) else ""
        val base = (if (dot > 0) fileName.substring(0, dot) else fileName).replace(EDITED_SUFFIX, "")
        var candidate = "${base}_edited$extension"
        var counter = 1
        while (candidate in existingNames) {
            candidate = "${base}_edited_$counter$extension"
            counter++
        }
        return candidate
    }

    private val EDITED_SUFFIX = Regex("_edited(_\\d+)?$")
}
