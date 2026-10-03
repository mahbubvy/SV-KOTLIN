package com.secretvault.app.core.camera

internal fun pinchedCameraZoom(current: Float, factor: Float, minimum: Float, maximum: Float): Float {
    if (!factor.isFinite() || factor <= 0f) return current
    return (current * factor).coerceIn(minimum, maximum)
}

internal fun zoomCrop(left: Int, top: Int, right: Int, bottom: Int, ratio: Float): IntArray {
    require(ratio.isFinite() && ratio >= 1f && right > left && bottom > top)
    val width = ((right - left) / ratio).toInt().coerceAtLeast(1)
    val height = ((bottom - top) / ratio).toInt().coerceAtLeast(1)
    val x = left + (right - left - width) / 2
    val y = top + (bottom - top - height) / 2
    return intArrayOf(x, y, x + width, y + height)
}

internal fun streamFocusPoint(x: Float, y: Float, width: Float, height: Float, frameWidth: Int,
    frameHeight: Int, rotation: Int, previewAspect: Float): Pair<Float, Float>? {
    if (!x.isFinite() || !y.isFinite() || width <= 0f || height <= 0f || !previewAspect.isFinite() || previewAspect <= 0f) return null
    val rotatedWidth = if (rotation % 180 == 0) frameWidth.toFloat() else frameHeight.toFloat()
    val rotatedHeight = if (rotation % 180 == 0) frameHeight.toFloat() else frameWidth.toFloat()
    val outerScale = minOf(width / rotatedWidth, height / rotatedHeight)
    val outerWidth = rotatedWidth * outerScale
    val outerHeight = rotatedHeight * outerScale
    val innerWidth = minOf(outerWidth, outerHeight * previewAspect)
    val innerHeight = minOf(outerHeight, outerWidth / previewAspect)
    val nx = (x - (width - innerWidth) / 2f) / innerWidth
    val ny = (y - (height - innerHeight) / 2f) / innerHeight
    return if (nx in 0f..1f && ny in 0f..1f) nx to ny else null
}

internal fun sensorPointInFrame(x: Float, y: Float, viewportAspect: Float, frameAspect: Float, rotation: Int): Pair<Float, Float> {
    val orientedAspect = if (rotation % 180 == 0) frameAspect else 1f / frameAspect
    val nx = 0.5f + (x - 0.5f) * minOf(1f, viewportAspect / orientedAspect)
    val ny = 0.5f + (y - 0.5f) * minOf(1f, orientedAspect / viewportAspect)
    return when (rotation) { 90 -> ny to (1f - nx); 180 -> (1f - nx) to (1f - ny); 270 -> (1f - ny) to nx; else -> nx to ny }
}
