package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
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
    rotationDegrees: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    isActivePage: Boolean = true,
    onZoomChanged: (Boolean) -> Unit = {}
) {
    val zoom = rememberMediaZoomState(item.id, rotationDegrees, isActivePage, onZoomChanged)
    var imageSize by remember(item.id) { mutableStateOf(Size.Zero) }
    LaunchedEffect(imageSize, rotationDegrees) {
        zoom.content = if (rotationDegrees % 180 != 0) Size(imageSize.height, imageSize.width) else imageSize
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .mediaZoomGestures(zoom, onTap),
        contentAlignment = Alignment.Center
    ) {
        val quarterTurn = rotationDegrees % 180 != 0
        val imageWidth = if (quarterTurn) maxHeight else maxWidth
        val imageHeight = if (quarterTurn) maxWidth else maxHeight
        SubcomposeAsyncImage(
            model = EncryptedMediaUri(item.encryptedPath),
            contentDescription = item.filename,
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = Size(it.result.drawable.intrinsicWidth.toFloat(), it.result.drawable.intrinsicHeight.toFloat()) },
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
                .width(imageWidth)
                .height(imageHeight)
                .graphicsLayer(
                    rotationZ = rotationDegrees.toFloat(),
                    scaleX = zoom.scale,
                    scaleY = zoom.scale,
                    translationX = zoom.offset.x,
                    translationY = zoom.offset.y
                )
        )
    }
}
