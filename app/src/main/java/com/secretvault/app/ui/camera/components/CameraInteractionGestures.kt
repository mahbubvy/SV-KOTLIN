package com.secretvault.app.ui.camera.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import com.secretvault.app.core.camera.pinchedCameraZoom
import com.secretvault.app.core.stream.StreamInteractionState
import java.util.Locale

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.cameraInteractionGestures(controls: StreamInteractionState, enabled: Boolean,
    onZoom: (Float) -> Unit, onTap: (Offset) -> Unit): Modifier {
    var desired by remember(controls.cameraId) { mutableFloatStateOf(controls.zoom) }
    val latestControls by rememberUpdatedState(controls)
    val latestZoom by rememberUpdatedState(onZoom)
    val latestTap by rememberUpdatedState(onTap)
    val transform = rememberTransformableState { factor, _, _ ->
        val range = latestControls
        desired = pinchedCameraZoom(desired, factor, range.minZoom, range.maxZoom)
        latestZoom(desired)
    }
    LaunchedEffect(controls.zoom, transform.isTransformInProgress) {
        if (!transform.isTransformInProgress) desired = controls.zoom
    }
    return pointerInput(controls.cameraId, enabled) {
        detectTapGestures { if (enabled) latestTap(it) }
    }.transformable(transform, canPan = { false }, enabled = enabled && controls.maxZoom > controls.minZoom)
        .semantics {
            stateDescription = "Camera zoom %.1f×".format(Locale.US, controls.zoom)
            if (enabled && controls.maxZoom > controls.minZoom) customActions = listOf(
                CustomAccessibilityAction("Zoom in") { latestZoom(pinchedCameraZoom(latestControls.zoom, 1.5f, latestControls.minZoom, latestControls.maxZoom)); true },
                CustomAccessibilityAction("Zoom out") { latestZoom(pinchedCameraZoom(latestControls.zoom, 1 / 1.5f, latestControls.minZoom, latestControls.maxZoom)); true },
            )
        }
}
