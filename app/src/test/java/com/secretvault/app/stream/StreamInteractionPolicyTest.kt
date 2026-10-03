package com.secretvault.app.stream

import com.secretvault.app.core.stream.*
import org.junit.Assert.*
import org.junit.Test

class StreamInteractionPolicyTest {
    private val controls = StreamInteractionState("back/default", 1f, 8f, 1f, 0.5f, true, true)
    @Test fun zoomAndFocusRemainAvailableDuringRecordingButMicrophoneDoesNot() {
        val recording = StreamRecordingState(available = true, recording = true)
        assertTrue(interactionAllowed(StreamInteractionRequest(1, "back/default", 0, 2f), controls, controls.cameraId, recording))
        assertTrue(interactionAllowed(StreamInteractionRequest(1, "back/default", 1, 0.3f, 0.7f), controls, controls.cameraId, recording))
        assertFalse(interactionAllowed(StreamInteractionRequest(1, "back/default", 2, 0f), controls, controls.cameraId, recording))
    }
    @Test fun rejectsStaleCameraSavingUnsupportedControlsAndInvalidCoordinates() {
        val zoom = StreamInteractionRequest(1, "back/default", 0, 2f)
        assertFalse(interactionAllowed(zoom, controls, "front", null))
        assertFalse(interactionAllowed(zoom, controls, controls.cameraId, StreamRecordingState(saving = true)))
        assertFalse(interactionAllowed(zoom.copy(x = 9f), controls, controls.cameraId, null))
        assertFalse(interactionAllowed(zoom.copy(x = Float.NaN), controls, controls.cameraId, null))
        assertFalse(interactionAllowed(zoom.copy(kind = 1, x = 0.5f), controls.copy(focusAvailable = false), controls.cameraId, null))
        assertFalse(interactionAllowed(zoom.copy(kind = 1, x = 0.5f, y = 1.1f), controls, controls.cameraId, null))
    }
}
