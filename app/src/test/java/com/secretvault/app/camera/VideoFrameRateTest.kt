package com.secretvault.app.camera

import com.secretvault.app.core.camera.CameraLensOption
import com.secretvault.app.core.camera.supportsVideoFrameRate
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.core.camera.VideoOrientation
import com.secretvault.app.core.camera.defaultVideoMode
import com.secretvault.app.ui.camera.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRateTest {
    @Test
    fun cameraStartsWithTheRequestedDeviceDefault() {
        val cmf = defaultVideoMode("Nothing", "A015", "Tetris")
        val pixel = defaultVideoMode("Google", "Pixel 5", "redfin")
        assertEquals(VideoMode.FHD_60, cmf)
        assertEquals(VideoMode.UHD_60, pixel)
        assertEquals(VideoMode.FHD_60, CameraViewModel(cmf).uiState.value.videoMode)
        assertEquals(VideoMode.UHD_60, CameraViewModel(pixel).uiState.value.videoMode)
        assertEquals(VideoMode.UHD_30, defaultVideoMode("Google", "Pixel 5a", "barbet"))
    }

    @Test
    fun resolutionLimitsFrameRateEvenWhenCameraAdvertises60() {
        val ranges = listOf(15..30, 30..30, 60..60)
        assertTrue(supportsVideoFrameRate(30, 16_666_667L, ranges))
        assertTrue(supportsVideoFrameRate(60, 16_666_667L, ranges))
        assertFalse(supportsVideoFrameRate(60, 33_333_333L, ranges))
        assertFalse(supportsVideoFrameRate(60, 0L, ranges))
        assertTrue(supportsVideoFrameRate(30, 0L, ranges))
        assertFalse(supportsVideoFrameRate(30, 50_000_000L, ranges))
        assertFalse(supportsVideoFrameRate(60, 16_666_667L, listOf(15..30)))
        assertFalse(supportsVideoFrameRate(30, 0L, listOf(24..24)))
    }

    @Test
    fun selectionRejectsUnsupportedModesAndChangesDuringRecording() {
        val viewModel = CameraViewModel()
        viewModel.setSupportedVideoModes(listOf(VideoMode.FHD_30, VideoMode.UHD_30, VideoMode.UHD_60), VideoMode.UHD_30)
        viewModel.setVideoMode(VideoMode.FHD_60)
        assertEquals(VideoMode.UHD_30, viewModel.uiState.value.videoMode)
        viewModel.setVideoMode(VideoMode.FHD_30)
        assertEquals(VideoMode.FHD_30, viewModel.uiState.value.videoMode)
        viewModel.setVideoMode(VideoMode.UHD_60)
        assertEquals(VideoMode.UHD_60, viewModel.uiState.value.videoMode)
        viewModel.setRecordingState(true)
        viewModel.setVideoMode(VideoMode.UHD_30)
        assertEquals(VideoMode.UHD_60, viewModel.uiState.value.videoMode)
    }

    @Test
    fun videoOrientationCanBeLockedButNotChangedDuringRecording() {
        val viewModel = CameraViewModel()
        assertEquals(VideoOrientation.PORTRAIT, viewModel.uiState.value.videoOrientation)

        viewModel.setVideoOrientation(VideoOrientation.LANDSCAPE)
        assertEquals(VideoOrientation.LANDSCAPE, viewModel.uiState.value.videoOrientation)

        viewModel.setRecordingState(true)
        viewModel.setVideoOrientation(VideoOrientation.PORTRAIT)
        assertEquals(VideoOrientation.LANDSCAPE, viewModel.uiState.value.videoOrientation)
    }

    @Test
    fun videoOrientationMapsToCameraXTargetRotation() {
        assertEquals(android.view.Surface.ROTATION_0, VideoOrientation.PORTRAIT.targetRotation)
        assertEquals(android.view.Surface.ROTATION_90, VideoOrientation.LANDSCAPE.targetRotation)
        assertEquals(90, VideoOrientation.PORTRAIT.recorderOrientationHint)
        assertEquals(0, VideoOrientation.LANDSCAPE.recorderOrientationHint)
    }

    @Test
    fun uhd60ModeKeepsIts4kResolutionLabel() {
        assertEquals("4K", VideoMode.UHD_60.resolutionLabel)
        assertEquals("1080p", VideoMode.FHD_60.resolutionLabel)
    }

    @Test
    fun onlyAvailableRearLensesCanBeSelected() {
        val viewModel = CameraViewModel()
        viewModel.setRearLensOptions(listOf(
            CameraLensOption(null, "1×"),
            CameraLensOption("3", "Ultra-wide")
        ))

        viewModel.selectRearLens("3")
        assertEquals("3", viewModel.uiState.value.selectedRearLensId)
        viewModel.selectRearLens("unknown")
        assertEquals("3", viewModel.uiState.value.selectedRearLensId)
        viewModel.setRearLensOptions(listOf(CameraLensOption(null, "1×")))
        assertEquals(null, viewModel.uiState.value.selectedRearLensId)
    }
}
