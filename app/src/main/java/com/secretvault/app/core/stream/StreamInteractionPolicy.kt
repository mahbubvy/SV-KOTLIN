package com.secretvault.app.core.stream

internal fun interactionAllowed(request: StreamInteractionRequest, controls: StreamInteractionState?, selected: String?, recording: StreamRecordingState?): Boolean {
    if (controls == null || controls.cameraId == null || request.cameraId != controls.cameraId || request.cameraId != selected || recording?.saving == true ||
        !request.x.isFinite() || !request.y.isFinite()) return false
    return when (request.kind) {
        0 -> request.x in controls.minZoom..controls.maxZoom && request.y == 0f
        1 -> controls.focusAvailable && request.x in 0f..1f && request.y in 0f..1f
        2 -> controls.microphoneAvailable && recording?.recording != true && request.x in listOf(0f, 1f) && request.y == 0f
        else -> false
    }
}
