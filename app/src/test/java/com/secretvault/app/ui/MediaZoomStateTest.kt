package com.secretvault.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.secretvault.app.ui.viewer.components.MediaZoomState
import com.secretvault.app.ui.viewer.components.boundedMediaPan
import org.junit.Assert.*
import org.junit.Test

class MediaZoomStateTest {
    @Test fun pinchStartsAtNormalSizeAndCannotShrinkBelowFit() {
        val zoom = MediaZoomState().apply { viewport = Size(400f, 800f); content = Size(400f, 800f) }
        zoom.transform(2f, Offset.Zero)
        assertEquals(2f, zoom.scale, 0f)
        zoom.transform(0.1f, Offset(100f, 100f))
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }
    @Test fun letterboxedMediaCannotPanIntoEmptySpace() {
        assertEquals(Offset(200f, 0f), boundedMediaPan(Offset(1000f, 1000f), 2f, Size(400f, 800f), Size(1920f, 1080f)))
    }
    @Test fun doubleTapZoomsAroundTapAndResets() {
        val zoom = MediaZoomState().apply { viewport = Size(400f, 800f); content = viewport }
        zoom.doubleTap(Offset(100f, 200f))
        assertEquals(Offset(150f, 300f), zoom.offset)
        zoom.doubleTap(Offset.Zero)
        assertEquals(1f, zoom.scale, 0f)
        assertEquals(Offset.Zero, zoom.offset)
    }
}
