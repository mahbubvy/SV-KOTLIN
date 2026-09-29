package com.secretvault.app.camera

import com.secretvault.app.core.camera.supportsVideoFrameRate
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.ui.camera.CameraViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRateTest {
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
}
