package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale
import kotlin.math.min

internal fun fittedMediaSize(viewport: Size, content: Size): Size {
    if (content.width <= 0f || content.height <= 0f) return viewport
    val fit = min(viewport.width / content.width, viewport.height / content.height)
    return Size(content.width * fit, content.height * fit)
}

internal fun boundedMediaPan(offset: Offset, scale: Float, viewport: Size, content: Size): Offset {
    val fitted = fittedMediaSize(viewport, content)
    val maxX = ((fitted.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((fitted.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

internal class MediaZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
    var content by mutableStateOf(Size.Zero)

    fun transform(factor: Float, pan: Offset) {
        val nextScale = (scale * factor).coerceIn(1f, 5f)
        offset = boundedMediaPan(offset * (nextScale / scale) + pan, nextScale, viewport, content)
        scale = nextScale
    }

    fun doubleTap(point: Offset) {
        if (scale > 1f) reset() else {
            scale = 2.5f
            offset = boundedMediaPan((Offset(viewport.width / 2, viewport.height / 2) - point) * (scale - 1), scale, viewport, content)
        }
    }

    fun reset() { scale = 1f; offset = Offset.Zero }
}

@Composable
internal fun rememberMediaZoomState(mediaId: String, rotation: Int, active: Boolean, onZoomChanged: (Boolean) -> Unit): MediaZoomState {
    val zoom = remember(mediaId) { MediaZoomState() }
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    LaunchedEffect(rotation, active) { zoom.reset() }
    LaunchedEffect(zoom.scale > 1f) { latestOnZoomChanged(zoom.scale > 1f) }
    return zoom
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.mediaZoomGestures(zoom: MediaZoomState, onTap: () -> Unit): Modifier {
    val transform = rememberTransformableState { factor, pan, _ -> zoom.transform(factor, pan) }
    val latestTap by rememberUpdatedState(onTap)
    return onSizeChanged { zoom.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
        .pointerInput(zoom) { detectTapGestures(onTap = { latestTap() }, onDoubleTap = zoom::doubleTap) }
        .transformable(transform, canPan = { zoom.scale > 1f })
        .semantics {
            stateDescription = "Zoom %.1f×".format(Locale.US, zoom.scale)
            customActions = listOf(
                CustomAccessibilityAction("Zoom in") { zoom.transform(1.5f, Offset.Zero); true },
                CustomAccessibilityAction("Zoom out") { zoom.transform(1 / 1.5f, Offset.Zero); true },
                CustomAccessibilityAction("Reset zoom") { zoom.reset(); true },
            )
        }
}
