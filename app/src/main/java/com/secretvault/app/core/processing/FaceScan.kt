package com.secretvault.app.core.processing

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.player.DecryptingMediaDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A face found by [scanFaces]: its blur region (source time) and a small picture of it, kept in memory only. */
class FoundFace(val blur: Sticker, val preview: Bitmap?)

private const val SAMPLE_MS = 200L
private const val SCAN_SIDE = 720 // frames are scaled to fit this square for detection

/** What a scan found: faces without a region yet, and how many it skipped because one already covers them. */
class FaceScanResult(val faces: List<FoundFace>, val alreadyCovered: Int)

/**
 * Finds faces in [clip]'s trimmed part, five times a second, with the on-device detector. Frames are read straight
 * from the encrypted file and stay in memory. Faces the clip's regions already cover are left out; most-seen faces
 * first. [onProgress] gets 0..1.
 */
suspend fun scanFaces(cryptoEngine: VaultCryptoEngine, clip: Clip, onProgress: (Float) -> Unit): FaceScanResult =
    withContext(Dispatchers.Default) {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build())
        val retriever = MediaMetadataRetriever()
        try {
            DecryptingMediaDataSource(cryptoEngine, File(clip.media.encryptedPath)).use { source ->
                retriever.setDataSource(source)
                // Upright, like the editor's picture; slow, as each call seeks, so only for previews and as a fallback.
                fun frameAt(ms: Long): Bitmap? = if (Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(ms * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, SCAN_SIDE, SCAN_SIDE)
                else retriever.getFrameAtTime(ms * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)

                val samples = mutableListOf<FaceSample>()
                var aspect = 1f
                fun detect(at: Long, frame: Bitmap) {
                    val w = frame.width.toFloat()
                    val h = frame.height.toFloat()
                    aspect = w / h
                    val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                    samples += FaceSample(at, faces.map { f ->
                        // The detector counts a lean counter-clockwise; regions turn clockwise.
                        f.boundingBox.let { b -> FaceBox(b.left / w, b.top / h, b.right / w, b.bottom / h, -f.headEulerAngleZ) }
                    })
                    frame.recycle()
                }
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val span = (clip.endMs - clip.startMs).coerceAtLeast(1L).toFloat()
                val decoded = try {
                    DecryptingMediaDataSource(cryptoEngine, File(clip.media.encryptedPath)).use { stream ->
                        decodeSamples(stream, clip.startMs, clip.endMs, rotation, keepGoing = { isActive }) { at, frame ->
                            detect(at, frame)
                            onProgress((at - clip.startMs) / span)
                        }
                    }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false // this phone's decoder can't hand over frames: seek to each one instead
                }
                ensureActive()
                if (!decoded) {
                    samples.clear()
                    val times = (clip.startMs until clip.endMs step SAMPLE_MS).toList()
                    times.forEachIndexed { i, at ->
                        ensureActive()
                        frameAt(at)?.let { detect(at, it) }
                        onProgress((i + 1f) / times.size)
                    }
                }
                val (covered, fresh) = faceTracks(samples).partition { it.coveredBy(clip.regions) }
                FaceScanResult(fresh.sortedByDescending { it.points.size }.map { track ->
                    ensureActive()
                    val (at, box) = track.clearest
                    FoundFace(track.toBlur(clip.sourceDurationMs, aspect, SAMPLE_MS), frameAt(at)?.let { faceCrop(it, box) })
                }, covered.size)
            }
        } finally {
            retriever.release()
            detector.close()
        }
    }

/**
 * Decodes [startMs]..[endMs] of the video straight through and hands [onFrame] a small upright frame about every
 * [SAMPLE_MS]. Much faster than seeking to each sample, since a seek decodes from the previous key frame every time.
 * Throws if the decoder can't give its frames as images.
 */
private fun decodeSamples(source: MediaDataSource, startMs: Long, endMs: Long, rotation: Int, keepGoing: () -> Boolean,
                          onFrame: (Long, Bitmap) -> Unit) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
        extractor.seekTo(startMs * 1_000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var next = startMs
        while (keepGoing()) {
            if (!inputDone) codec.dequeueInputBuffer(10_000).takeIf { it >= 0 }?.let { i ->
                val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                // A second past the end, as frames can come out of the decoder in a different order than they go in.
                if (size < 0 || extractor.sampleTime > (endMs + 1_000) * 1_000) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o < 0) continue
            val ms = info.presentationTimeUs / 1_000
            if (info.size > 0 && ms >= next && ms < endMs) {
                codec.getOutputImage(o)?.use { onFrame(ms, smallUpright(it, rotation)) } ?: error("no image output")
                while (next <= ms) next += SAMPLE_MS
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || ms >= endMs) break
        }
    } finally {
        codec?.run { runCatching { stop() }; release() }
        extractor.release()
    }
}

// A decoded YUV frame as an RGB bitmap no bigger than SCAN_SIDE, every n-th pixel (plenty for finding faces), turned
// upright by the video's [rotation].
private fun smallUpright(image: Image, rotation: Int): Bitmap {
    val crop = image.cropRect
    val step = ceil(maxOf(crop.width(), crop.height()) / SCAN_SIDE.toFloat()).toInt().coerceAtLeast(1)
    val w = crop.width() / step
    val h = crop.height() / step
    val (yPlane, uPlane, vPlane) = image.planes
    val pixels = IntArray(w * h)
    for (row in 0 until h) {
        val sy = crop.top + row * step
        for (col in 0 until w) {
            val sx = crop.left + col * step
            val y = (yPlane.buffer.get(sy * yPlane.rowStride + sx * yPlane.pixelStride).toInt() and 255) - 16
            val uvAt = (sy / 2) * uPlane.rowStride + (sx / 2) * uPlane.pixelStride
            val u = (uPlane.buffer.get(uvAt).toInt() and 255) - 128
            val v = (vPlane.buffer.get((sy / 2) * vPlane.rowStride + (sx / 2) * vPlane.pixelStride).toInt() and 255) - 128
            // BT.601 limited range, as phone cameras record.
            val r = (1.164f * y + 1.596f * v).toInt().coerceIn(0, 255)
            val g = (1.164f * y - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
            val b = (1.164f * y + 2.017f * u).toInt().coerceIn(0, 255)
            pixels[row * w + col] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val frame = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    if (rotation % 360 == 0) return frame
    return Bitmap.createBitmap(frame, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, false).also { frame.recycle() }
}

// The face with a little room around it, at most 160 px, from a frame that's then let go.
private fun faceCrop(frame: Bitmap, box: FaceBox): Bitmap {
    val pad = 0.25f
    val left = ((box.left - box.width * pad) * frame.width).roundToInt().coerceIn(0, frame.width - 1)
    val top = ((box.top - box.height * pad) * frame.height).roundToInt().coerceIn(0, frame.height - 1)
    val right = ((box.right + box.width * pad) * frame.width).roundToInt().coerceIn(left + 1, frame.width)
    val bottom = ((box.bottom + box.height * pad) * frame.height).roundToInt().coerceIn(top + 1, frame.height)
    val crop = Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    val scale = 160f / maxOf(crop.width, crop.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(crop, (crop.width * scale).roundToInt().coerceAtLeast(1),
        (crop.height * scale).roundToInt().coerceAtLeast(1), true) else crop
    if (small !== crop) crop.recycle()
    if (small !== frame) frame.recycle()
    return small
}
