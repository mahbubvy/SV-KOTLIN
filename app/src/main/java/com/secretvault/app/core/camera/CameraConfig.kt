package com.secretvault.app.core.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.video.Quality

enum class CameraMode {
    PHOTO,
    VIDEO
}

enum class FlashMode(val imageCaptureMode: Int) {
    AUTO(ImageCapture.FLASH_MODE_AUTO),
    ON(ImageCapture.FLASH_MODE_ON),
    OFF(ImageCapture.FLASH_MODE_OFF)
}

enum class LensFacing(val selector: CameraSelector) {
    BACK(CameraSelector.DEFAULT_BACK_CAMERA),
    FRONT(CameraSelector.DEFAULT_FRONT_CAMERA)
}

enum class VideoMode(val fps: Int, val label: String) {
    FHD_30(30, "1080p · 30 FPS"),
    FHD_60(60, "1080p · 60 FPS"),
    UHD_30(30, "4K · 30 FPS"),
    UHD_60(60, "4K · 60 FPS");

    val quality: Quality get() = if (this == UHD_30 || this == UHD_60) Quality.UHD else Quality.FHD
}

internal fun supportsVideoFrameRate(
    fps: Int,
    minFrameDurationNs: Long,
    supportedRanges: List<IntRange>
): Boolean = supportedRanges.any { fps in it } &&
    if (minFrameDurationNs > 0L) minFrameDurationNs <= (1_000_000_000L + fps - 1) / fps
    else fps == 30
