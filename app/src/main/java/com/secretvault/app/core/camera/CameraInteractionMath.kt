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
