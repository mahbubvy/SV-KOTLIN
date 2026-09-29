package com.secretvault.app.core.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FaceBlurProcessor {

    private val detector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    /**
     * Detects faces and applies high-intensity true Gaussian blur over all detected face/head regions.
     */
    suspend fun processFaceBlur(sourceBitmap: Bitmap, blurRadius: Int = 30): Bitmap {
        val mutableBitmap = if (sourceBitmap.isMutable) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val faces = detectFaces(mutableBitmap)
        if (faces.isEmpty()) {
            return mutableBitmap
        }

        val canvas = Canvas(mutableBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (face in faces) {
            val rawBox = face.boundingBox

            // Limit blur strictly to face with 10px surrounding area
            val displayScale = (min(mutableBitmap.width, mutableBitmap.height) / 1080f).coerceAtLeast(1f)
            val margin = (10 * displayScale).toInt().coerceAtLeast(10)

            val left = max(0, rawBox.left - margin)
            val top = max(0, rawBox.top - margin)
            val right = min(mutableBitmap.width, rawBox.right + margin)
            val bottom = min(mutableBitmap.height, rawBox.bottom + margin)

            val width = right - left
            val height = bottom - top

            if (width > 8 && height > 8) {
                val faceCrop = Bitmap.createBitmap(mutableBitmap, left, top, width, height)

                // Scale down slightly for performance and high-radius dispersion (intense Gaussian blur)
                val scaleFactor = 0.35f
                val scaledW = (width * scaleFactor).toInt().coerceAtLeast(16)
                val scaledH = (height * scaleFactor).toInt().coerceAtLeast(16)

                val scaled = Bitmap.createScaledBitmap(faceCrop, scaledW, scaledH, true)
                val blurredScaled = fastGaussianBlur(scaled, radius = blurRadius.coerceIn(15, 50))
                val finalBlurred = Bitmap.createScaledBitmap(blurredScaled, width, height, true)

                if (scaled != faceCrop && scaled != blurredScaled) scaled.recycle()
                if (blurredScaled != scaled && blurredScaled != finalBlurred) blurredScaled.recycle()

                val destRect = Rect(left, top, right, bottom)
                val ovalPath = Path().apply {
                    addOval(
                        RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                        Path.Direction.CW
                    )
                }

                canvas.save()
                canvas.clipPath(ovalPath)
                canvas.drawBitmap(finalBlurred, null, destRect, paint)
                canvas.restore()

                if (finalBlurred != faceCrop) finalBlurred.recycle()
                faceCrop.recycle()
            }
        }

        return mutableBitmap
    }

    private suspend fun detectFaces(bitmap: Bitmap) = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                continuation.resume(faces)
            }
            .addOnFailureListener {
                continuation.resume(emptyList())
            }
    }

    /**
     * StackBlur implementation of true Gaussian blur approximation.
     * Operates in linear time O(W * H) without integer/float overflow.
     */
    private fun fastGaussianBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = if (sentBitmap.isMutable) sentBitmap else sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (radius < 1) return bitmap

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        val a = IntArray(wh)
        var rsum: Int; var gsum: Int; var bsum: Int; var asum: Int
        var x: Int; var y: Int; var i: Int; var p: Int; var yp: Int; var yi: Int; var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(4) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int; var goutsum: Int; var boutsum: Int; var aoutsum: Int
        var rinsum: Int; var ginsum: Int; var binsum: Int; var ainsum: Int

        for (curY in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
            routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
            rsum = 0; gsum = 0; bsum = 0; asum = 0
            for (idx in -radius..radius) {
                p = pix[yi + min(wm, max(idx, 0))]
                sir = stack[idx + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                sir[3] = (p ushr 24)
                rbs = r1 - abs(idx)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                asum += sir[3] * rbs
                if (idx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                a[yi] = dv[asum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                if (curY == 0) {
                    vmin[curX] = min(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                sir[3] = (p ushr 24)

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0; ainsum = 0
            routsum = 0; goutsum = 0; boutsum = 0; aoutsum = 0
            rsum = 0; gsum = 0; bsum = 0; asum = 0
            yp = -radius * w
            for (idx in -radius..radius) {
                yi = max(0, yp) + curX
                sir = stack[idx + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                sir[3] = a[yi]
                rbs = r1 - abs(idx)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                asum += a[yi] * rbs
                if (idx > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                    ainsum += sir[3]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                    aoutsum += sir[3]
                }
                if (idx < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (dv[asum] shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                asum -= aoutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                aoutsum -= sir[3]

                if (curX == 0) {
                    vmin[curY] = min(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                sir[3] = a[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                ainsum += sir[3]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                asum += ainsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                aoutsum += sir[3]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                ainsum -= sir[3]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}
