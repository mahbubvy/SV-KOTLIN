package com.secretvault.app.ui.auth.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultError
import kotlin.math.sin

@Composable
fun PinDotsIndicator(
    pinLength: Int,
    maxDigits: Int = 4,
    hasError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(hasError) {
        if (hasError) {
            // Shake animation
            for (i in 0..5) {
                shakeOffset.animateTo(
                    targetValue = if (i % 2 == 0) 15f else -15f,
                    animationSpec = tween(durationMillis = 50)
                )
            }
            shakeOffset.animateTo(0f, animationSpec = tween(durationMillis = 50))
        }
    }

    Row(
        modifier = modifier.offset { IntOffset(shakeOffset.value.toInt(), 0) },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until maxDigits) {
            val isFilled = i < pinLength
            val dotColor by animateColorAsState(
                targetValue = when {
                    hasError -> VaultError
                    isFilled -> VaultAccent
                    else -> TextMuted.copy(alpha = 0.3f)
                },
                label = "dot_color"
            )

            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(
                        width = 1.5.dp,
                        color = if (hasError) VaultError else if (isFilled) VaultAccent else TextMuted.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            )
        }
    }
}
