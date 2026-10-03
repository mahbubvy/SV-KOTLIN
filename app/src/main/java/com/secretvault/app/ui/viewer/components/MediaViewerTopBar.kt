package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultAccent

@Composable
fun MediaViewerTopBar(
    item: MediaItem,
    currentIndex: Int,
    totalCount: Int,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    onRotateMedia: () -> Unit,
    modifier: Modifier = Modifier,
    onFavoriteClick: () -> Unit = {},
    favoriteEnabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }

            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = item.filename,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${currentIndex + 1} of $totalCount",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = item.isFavorite, enabled = favoriteEnabled, onCheckedChange = { onFavoriteClick() },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.8f), CircleShape)) {
                Icon(if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (item.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (item.isFavorite) VaultAccent else TextPrimary)
            }
            if (item.mediaType == MediaType.PHOTO || item.mediaType == MediaType.VIDEO) {
                IconButton(onClick = onRotateMedia) {
                    Icon(
                        imageVector = Icons.Default.Rotate90DegreesCw,
                        contentDescription = if (item.mediaType == MediaType.PHOTO) {
                            "Rotate photo 90 degrees clockwise"
                        } else {
                            "Rotate video 90 degrees clockwise"
                        },
                        tint = TextPrimary
                    )
                }
            }
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Details",
                    tint = TextPrimary
                )
            }
        }
    }
}
