package com.secretvault.app.core.processing

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.player.DecryptingMediaDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** A face found by [scanFaces]: its blur region (source time) and a small picture of it, kept in memory only. */
class FoundFace(val blur: Sticker, val preview: Bitmap?)

private const val SAMPLE_MS = 200L
private const val SCAN_SIDE = 720 // frames are scaled to fit this square for detection

/**
 * Finds faces in [clip]'s trimmed part, five times a second, with the on-device detector. Frames are read straight
 * from the encrypted file and stay in memory. Most-seen faces first; [onProgress] gets 0..1.
 */
suspend fun scanFaces(cryptoEngine: VaultCryptoEngine, clip: Clip, onProgress: (Float) -> Unit): List<FoundFace> =
    withContext(Dispatchers.Default) {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build())
        val retriever = MediaMetadataRetriever()
        try {
            DecryptingMediaDataSource(cryptoEngine, File(clip.media.encryptedPath)).use { source ->
                retriever.setDataSource(source)
                // Upright, like the editor's picture. ponytail: seeks per sample; a sequential MediaCodec decode would be faster.
                fun frameAt(ms: Long): Bitmap? = if (Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(ms * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, SCAN_SIDE, SCAN_SIDE)
                else retriever.getFrameAtTime(ms * 1_000, MediaMetadataRetriever.OPTION_CLOSEST)

                val times = (clip.startMs until clip.endMs step SAMPLE_MS).toList()
                val samples = mutableListOf<FaceSample>()
                var aspect = 1f
                times.forEachIndexed { i, at ->
                    ensureActive()
                    val frame = frameAt(at)
                    if (frame != null) {
                        val w = frame.width.toFloat()
                        val h = frame.height.toFloat()
                        aspect = w / h
                        val faces = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                        samples += FaceSample(at, faces.map { f ->
                            f.boundingBox.let { b -> FaceBox(b.left / w, b.top / h, b.right / w, b.bottom / h) }
                        })
                        frame.recycle()
                    }
                    onProgress((i + 1f) / times.size)
                }
                faceTracks(samples).sortedByDescending { it.points.size }.map { track ->
                    ensureActive()
                    val (at, box) = track.clearest
                    FoundFace(track.toBlur(clip.sourceDurationMs, aspect, SAMPLE_MS), frameAt(at)?.let { faceCrop(it, box) })
                }
            }
        } finally {
            retriever.release()
            detector.close()
        }
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
