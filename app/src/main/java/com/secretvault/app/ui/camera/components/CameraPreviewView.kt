package com.secretvault.app.ui.camera.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.secretvault.app.core.camera.CmfHighFpsCameraView
import com.secretvault.app.core.stream.StreamInteractionState

private class CameraPreviewHost(context: android.content.Context) : FrameLayout(context) {
    val cameraXPreview = PreviewView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    val cmfPreview = CmfHighFpsCameraView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        visibility = android.view.View.GONE
    }

    init {
        addView(cameraXPreview)
        addView(cmfPreview)
    }
}

@Composable
fun CameraPreviewView(
    useCmfHighFps: Boolean,
    onPreviewViewsCreated: (PreviewView, CmfHighFpsCameraView) -> Unit,
    onTapToFocus: (Float, Float, PreviewView, CmfHighFpsCameraView) -> Unit,
    modifier: Modifier = Modifier,
    interactions: StreamInteractionState = StreamInteractionState(),
    interactionEnabled: Boolean = true,
    onZoom: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val previewHost = remember { CameraPreviewHost(context) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .cameraInteractionGestures(interactions, interactionEnabled, onZoom) { offset ->
                    onTapToFocus(offset.x, offset.y, previewHost.cameraXPreview, previewHost.cmfPreview)
            }
    ) {
        AndroidView(
            factory = { previewHost },
            update = { host ->
                host.cameraXPreview.visibility = if (useCmfHighFps) android.view.View.GONE else android.view.View.VISIBLE
                host.cmfPreview.visibility = if (useCmfHighFps) android.view.View.VISIBLE else android.view.View.GONE
                onPreviewViewsCreated(host.cameraXPreview, host.cmfPreview)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
