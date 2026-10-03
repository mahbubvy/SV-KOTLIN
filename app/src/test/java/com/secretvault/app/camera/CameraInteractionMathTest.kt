package com.secretvault.app.camera

import com.secretvault.app.core.camera.*
import org.junit.Assert.*
import org.junit.Test

class CameraInteractionMathTest {
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
