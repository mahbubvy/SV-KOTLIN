package com.secretvault.app.ui.camera.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.secretvault.app.ui.theme.VaultAccent

@Composable
fun FocusIndicator(
    focusPoint: Offset?,
    modifier: Modifier = Modifier
) {
    if (focusPoint == null) return

    val scale = remember(focusPoint) { Animatable(1.5f) }
    val alpha = remember(focusPoint) { Animatable(1.0f) }

    LaunchedEffect(focusPoint) {
        scale.animateTo(1.0f, animationSpec = tween(200))
        alpha.animateTo(0.0f, animationSpec = tween(600, delayMillis = 400))
    }

    if (alpha.value > 0f) {
        val sizeDp = 70.dp
        val radius = with(LocalDensity.current) { sizeDp.toPx() / 2f }
        Box(
            modifier = modifier
                .offset {
                    IntOffset(
                        (focusPoint.x - radius).toInt(),
                        (focusPoint.y - radius).toInt()
                    )
                }
                .size(sizeDp)
                .border(
                    width = 2.dp,
                    color = VaultAccent.copy(alpha = alpha.value),
                    shape = CircleShape
                )
        )
    }
}
