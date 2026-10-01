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

data class CameraLensOption(val physicalCameraId: String?, val label: String, val zoomRatio: Float = 1f) {
    val id: String? get() = physicalCameraId ?: if (zoomRatio < 1f) "logical-ultrawide" else null
}

internal fun logicalRearLensOptions(minZoomRatio: Float): List<CameraLensOption> =
    if (minZoomRatio.isFinite() && minZoomRatio > 0f && minZoomRatio < 1f) {
        listOf(CameraLensOption(null, String.format(java.util.Locale.US, "%.1f\u00D7", minZoomRatio), minZoomRatio),
            CameraLensOption(null, "1\u00D7"))
    } else listOf(CameraLensOption(null, "1\u00D7"))

enum class VideoMode(val fps: Int, val label: String) {
    FHD_30(30, "1080p \u00B7 30 FPS"),
    FHD_60(60, "1080p \u00B7 60 FPS"),
    UHD_30(30, "4K \u00B7 30 FPS"),
    UHD_60(60, "4K \u00B7 60 FPS");

    val quality: Quality get() = if (this == UHD_30 || this == UHD_60) Quality.UHD else Quality.FHD
    val resolutionLabel: String get() = if (this == UHD_30 || this == UHD_60) "4K" else "1080p"
}

internal fun isCmfPhone1(manufacturer: String, model: String, device: String): Boolean =
    manufacturer.equals("Nothing", true) && model.equals("A015", true) && device.equals("Tetris", true)

internal fun defaultVideoMode(manufacturer: String, model: String, device: String): VideoMode = when {
    isCmfPhone1(manufacturer, model, device) -> VideoMode.FHD_60
    manufacturer.equals("Google", true) && device.equals("redfin", true) -> VideoMode.UHD_60
    else -> VideoMode.UHD_30
}

enum class VideoOrientation(val label: String, val targetRotation: Int, val recorderOrientationHint: Int) {
    PORTRAIT("Portrait", android.view.Surface.ROTATION_0, 90),
    LANDSCAPE("Landscape", android.view.Surface.ROTATION_90, 0)
}

internal fun supportsVideoFrameRate(
    fps: Int,
    minFrameDurationNs: Long,
    supportedRanges: List<IntRange>
): Boolean = supportedRanges.any { fps in it } &&
    if (minFrameDurationNs > 0L) minFrameDurationNs <= (1_000_000_000L + fps - 1) / fps
    else fps == 30
