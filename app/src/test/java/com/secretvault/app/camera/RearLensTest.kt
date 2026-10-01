package com.secretvault.app.camera

import com.secretvault.app.core.camera.logicalRearLensOptions
import com.secretvault.app.ui.camera.CameraViewModel
import org.junit.Assert.*
import org.junit.Test

class RearLensTest {
    @Test fun pixelMinimumIsPreservedAndSelectionHasDistinctIdentity() {
        val options = logicalRearLensOptions(0.615f)
        assertEquals("0.6\u00D7", options.first().label)
        assertEquals(0.615f, options.first().zoomRatio, 0f)
        assertNull(options.first().physicalCameraId)
        assertNotEquals(options.first().id, options.last().id)
        val model = CameraViewModel()
        model.setRearLensOptions(options)
        model.selectRearLens(options.first().id)
        model.setRearLensOptions(options)
        assertEquals(options.first().id, model.uiState.value.selectedRearLensId)
        model.setRecordingState(true)
        model.selectRearLens(null)
        assertEquals(options.first().id, model.uiState.value.selectedRearLensId)
        model.setRecordingState(false)
        model.setRearLensOptions(logicalRearLensOptions(1f))
        assertNull(model.uiState.value.selectedRearLensId)
    }

    @Test fun unsupportedOrInvalidMinimumDoesNotOfferUltrawide() {
        for (minimum in listOf(1f, 2f, 0f, -1f, Float.NaN)) {
            assertEquals(listOf(1f), logicalRearLensOptions(minimum).map { it.zoomRatio })
        }
    }
}
