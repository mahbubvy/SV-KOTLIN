package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.secretvault.app.core.image.EncryptedMediaUri
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.VaultAccent

@Composable
fun ZoomablePhotoView(
    item: MediaItem,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = newScale
        if (newScale > 1f) {
            val maxOffsetX = (newScale - 1f) * 500f
            val maxOffsetY = (newScale - 1f) * 800f
            offset = Offset(
                x = (offset.x + offsetChange.x * newScale).coerceIn(-maxOffsetX, maxOffsetX),
                y = (offset.y + offsetChange.y * newScale).coerceIn(-maxOffsetY, maxOffsetY)
            )
        } else {
            offset = Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .transformable(state = transformState, enabled = scale > 1.05f),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = EncryptedMediaUri(item.encryptedPath),
            contentDescription = item.filename,
            contentScale = ContentScale.Fit,
            loading = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Fast thumbnail placeholder if available
                    if (item.thumbnailPath != null) {
                        AsyncImage(
                            model = EncryptedMediaUri(item.thumbnailPath),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    CircularProgressIndicator(
                        color = VaultAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(36.dp)
                    )
                }
            },
            error = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Unable to load image",
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}
