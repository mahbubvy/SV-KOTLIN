package com.secretvault.app.camera

import com.secretvault.app.core.camera.*
import org.junit.Assert.*
import org.junit.Test

class CameraInteractionMathTest {
    @Test fun viewerFocusIgnoresFitAndEncoderMarginsAndMapsActualImage() {
        assertEquals(0.5f to 0.5f, streamFocusPoint(500f, 500f, 1000f, 1000f, 1280, 720, 90, 0.45f))
        assertNull(streamFocusPoint(10f, 500f, 1000f, 1000f, 1280, 720, 90, 0.45f))
        assertNull(streamFocusPoint(760f, 500f, 1000f, 1000f, 1280, 720, 90, 0.45f))
        assertNull(streamFocusPoint(500f, 20f, 1000f, 1000f, 1280, 720, 0, 16f / 9))
        val point = requireNotNull(streamFocusPoint(387.5f, 750f, 1000f, 1000f, 1280, 720, 90, 0.45f))
        assertEquals(0.25f, point.first, 0.001f); assertEquals(0.75f, point.second, 0.001f)
    }
    @Test fun nativeFocusAccountsForSensorRotationAndViewportCrop() {
        val portrait = sensorPointInFrame(0f, 0.25f, 0.45f, 16f / 9, 90)
        assertEquals(0.25f, portrait.first, 0.001f); assertEquals(0.9f, portrait.second, 0.001f)
        assertEquals(0.25f to 0.75f, sensorPointInFrame(0.25f, 0.75f, 16f / 9, 16f / 9, 0))
        assertEquals(0.75f to 0.25f, sensorPointInFrame(0.25f, 0.75f, 16f / 9, 16f / 9, 180))
        assertEquals(0.25f to 0.25f, sensorPointInFrame(0.25f, 0.75f, 9f / 16, 16f / 9, 270))
    }
    @Test fun pinchUsesCameraLimitsAndRejectsNonfiniteFactors() {
        assertEquals(2f, pinchedCameraZoom(1f, 2f, 0.6f, 8f))
        assertEquals(0.6f, pinchedCameraZoom(1f, 0.1f, 0.6f, 8f))
        assertEquals(8f, pinchedCameraZoom(3f, 5f, 1f, 8f))
        assertEquals(2f, pinchedCameraZoom(2f, Float.NaN, 1f, 8f))
    }
    @Test fun nativeZoomCropPreservesCenterAndSensorOrigin() {
        assertArrayEquals(intArrayOf(1010, 770, 3010, 2270), zoomCrop(10, 20, 4010, 3020, 2f))
        assertArrayEquals(intArrayOf(10, 20, 4010, 3020), zoomCrop(10, 20, 4010, 3020, 1f))
        assertThrows(IllegalArgumentException::class.java) { zoomCrop(0, 0, 4, 4, Float.NaN) }
    }
}
