package com.secretvault.app.ui.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.stream.StreamInteractionState
import com.secretvault.app.ui.theme.*
import java.util.Locale

@Composable
internal fun CameraZoomSlider(controls: StreamInteractionState, enabled: Boolean, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    if (controls.maxZoom <= controls.minZoom) return
    var target by remember(controls.cameraId, controls.minZoom, controls.maxZoom) { mutableFloatStateOf(controls.zoom) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(controls.zoom, enabled) { if (!dragging) target = controls.zoom }
    Column(modifier.width(48.dp).heightIn(max = 216.dp)
        .background(VaultDarkBg.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("%.1f×".format(Locale.US, controls.zoom), color = TextPrimary, fontSize = 12.sp)
        Layout(modifier = Modifier.width(48.dp).height(160.dp), content = {
            Slider(value = target.coerceIn(controls.minZoom, controls.maxZoom), onValueChange = { target = it; dragging = true; onZoom(it) },
                onValueChangeFinished = { dragging = false }, enabled = enabled,
                valueRange = controls.minZoom..controls.maxZoom,
                colors = SliderDefaults.colors(thumbColor = VaultAccent, activeTrackColor = VaultAccent, inactiveTrackColor = TextSecondary),
                modifier = Modifier.semantics {
                    contentDescription = "Camera zoom slider"
                    stateDescription = "%.1f×".format(Locale.US, controls.zoom)
                })
        }) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val slider = measurables.single().measure(Constraints.fixed(height, width))
            layout(width, height) {
                slider.placeWithLayer((width - height) / 2, (height - width) / 2) { rotationZ = 270f }
            }
        }
    }
}
