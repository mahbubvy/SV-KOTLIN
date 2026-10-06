package com.secretvault.app.core.processing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** A face the detector found, in fractions of the upright picture, leaning [roll] degrees clockwise. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val roll: Float = 0f) {
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val width get() = right - left
    val height get() = bottom - top
}

/** The faces found in one sampled frame, [atMs] into the source. */
class FaceSample(val atMs: Long, val faces: List<FaceBox>)

/** One person's face over time: where it was in each sample it was found in. */
class FaceTrack(val points: List<Pair<Long, FaceBox>>) {
    /** The sample where the face is biggest, for a preview of whose face it is. */
    val clearest: Pair<Long, FaceBox> get() = points.maxBy { it.second.width * it.second.height }
}

/**
 * Links faces from one sample to the next into tracks: each face joins the nearest open track whose face, carried on at
 * its last speed, would be less than a face's width away, biggest moves last. A track not seen for [maxGapMs] ends; a
 * face seen again later starts a new one.
 */
fun faceTracks(samples: List<FaceSample>, maxGapMs: Long = 1_000): List<FaceTrack> {
    val open = mutableListOf<MutableList<Pair<Long, FaceBox>>>()
    val done = mutableListOf<List<Pair<Long, FaceBox>>>()
    for (sample in samples.sortedBy { it.atMs }) {
        open.removeAll { track -> (sample.atMs - track.last().first > maxGapMs).also { if (it) done += track } }
        val pairs = buildList {
            open.forEachIndexed { t, track ->
                val (lastAt, last) = track.last()
                // Where the face should be by now if it kept its last speed, so one missed while moving is found again.
                var x = last.centerX
                var y = last.centerY
                if (track.size >= 2) {
                    val (prevAt, prev) = track[track.size - 2]
                    val f = (sample.atMs - lastAt).toFloat() / (lastAt - prevAt).coerceAtLeast(1)
                    x += (last.centerX - prev.centerX) * f
                    y += (last.centerY - prev.centerY) * f
                }
                sample.faces.forEachIndexed { f, face ->
                    val moved = hypot(face.centerX - x, face.centerY - y) / max(last.width, face.width)
                    if (moved < 1f) add(Triple(moved, t, f))
                }
            }
        }.sortedBy { it.first }
        val usedTracks = HashSet<Int>()
        val usedFaces = HashSet<Int>()
        for ((_, t, f) in pairs) {
            if (t in usedTracks || f in usedFaces) continue
            open[t] += sample.atMs to sample.faces[f]
            usedTracks += t
            usedFaces += f
        }
        sample.faces.forEachIndexed { f, face -> if (f !in usedFaces) open += mutableListOf(sample.atMs to face) }
    }
    return (done + open).map(::FaceTrack)
}

/** Whether one of [regions] (source time) already sits on this face for at least half the samples it was seen in. */
fun FaceTrack.coveredBy(regions: List<Sticker>): Boolean = regions.any { r ->
    points.count { (at, face) ->
        at in r.startMs until r.endMs && r.placementAt(at).let { p ->
            abs(p.centerX - face.centerX) <= face.width && abs(p.centerY - face.centerY) <= face.height
        }
    } * 2 >= points.size
}

/**
 * A track as a keyframed blur region in source time, covering the face with room for hair and chin. Only the
 * keyframes needed to stay within [tolerance] (fraction of the picture) of every detection are kept; between them the
 * region glides, which also carries it across samples where the face was missed. [aspect] is the picture's width /
 * height, [sampleMs] the time between samples, which pads each end.
 */
fun FaceTrack.toBlur(sourceDurationMs: Long, aspect: Float, sampleMs: Long, tolerance: Float = 0.02f): Sticker {
    fun placement(face: FaceBox): Placement {
        val w = face.width * 1.5f
        val h = face.height * 1.7f
        // Height in the picture's pixels over width in them, as Placement measures it.
        return Placement(face.centerX, face.centerY - face.height * 0.1f, w, face.roll, h / w / aspect).fitted()
    }
    val placed = points.map { (at, face) -> at to placement(face) }
    val start = (points.first().first - sampleMs / 2).coerceAtLeast(0L)
    val end = (points.last().first + sampleMs).coerceAtMost(sourceDurationMs)
    val kept = simplify(placed, tolerance)
    // Keys only if it actually moves; a face that stays put is one placement.
    val still = kept.all { (_, p) -> off(kept.first().second, p) <= tolerance }
    return if (still) Sticker(StickerSource.Blur(), start, end, kept.first().second, placedAtMs = kept.first().first - start)
    else Sticker(StickerSource.Blur(), start, end, kept.first().second, kept.map { (at, p) -> Keyframe(at - start, p) })
}

// Ramer–Douglas–Peucker over time: keep the ends, and the point furthest from the straight line between them when
// it's off by more than the tolerance, then repeat on each side.
private fun simplify(points: List<Pair<Long, Placement>>, tolerance: Float): List<Pair<Long, Placement>> {
    if (points.size <= 2) return points.distinctBy { it.first }
    val (t0, a) = points.first()
    val (t1, b) = points.last()
    var worst = 0
    var worstOff = 0f
    for (i in 1 until points.lastIndex) {
        val (t, p) = points[i]
        val f = if (t1 == t0) 0f else (t - t0).toFloat() / (t1 - t0)
        fun mix(x: Float, y: Float) = x + (y - x) * f
        val d = off(Placement(mix(a.centerX, b.centerX), mix(a.centerY, b.centerY), mix(a.widthFraction, b.widthFraction), mix(a.rotation, b.rotation),
            mix(a.stretch, b.stretch)), p)
        if (d > worstOff) { worstOff = d; worst = i }
    }
    if (worstOff <= tolerance) return listOf(points.first(), points.last())
    return simplify(points.subList(0, worst + 1), tolerance).dropLast(1) + simplify(points.subList(worst, points.size), tolerance)
}

// How far apart two placements are: the biggest difference in centre, width, height or turn, as a fraction of the picture.
private fun off(a: Placement, b: Placement) = maxOf(abs(a.centerX - b.centerX), abs(a.centerY - b.centerY),
    abs(a.widthFraction - b.widthFraction), abs(a.widthFraction * a.stretch - b.widthFraction * b.stretch),
    abs(a.rotation - b.rotation) * DEGREE)

// A turn as a distance for [off]: 5° counts like the default tolerance, 2% of the picture.
private const val DEGREE = 0.004f
