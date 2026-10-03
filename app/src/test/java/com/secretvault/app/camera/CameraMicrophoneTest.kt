package com.secretvault.app.camera

import com.secretvault.app.ui.camera.CameraViewModel
import org.junit.Assert.*
import org.junit.Test

class CameraMicrophoneTest {
    @Test fun audioSelectionIsExplicitAndCannotChangeDuringAClip() {
        val model = CameraViewModel()
        assertTrue(model.setRecordAudio(false))
        assertFalse(model.uiState.value.recordAudio)
        assertTrue(model.setRecordAudio(true))
        model.setRecordingState(true)
        assertFalse(model.setRecordAudio(false))
        model.toggleRecordAudio()
        assertTrue(model.uiState.value.recordAudio)
        model.setRecordingState(false)
        assertTrue(model.setRecordAudio(false))
    }
}
