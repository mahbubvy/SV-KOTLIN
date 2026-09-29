package com.secretvault.app.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.secretvault.app.core.image.EncryptedMediaUri
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.theme.VaultSurfaceVariant

private enum class MediaFilterType { ALL, PHOTOS, VIDEOS }

@Composable
fun SelectMediaBackupSheet(
    mediaItems: List<MediaItem>,
    onBack: () -> Unit,
    onContinue: (selectedMediaIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var currentFilter by remember { mutableStateOf(MediaFilterType.ALL) }

    val filteredItems = remember(mediaItems, currentFilter) {
        when (currentFilter) {
            MediaFilterType.ALL -> mediaItems
            MediaFilterType.PHOTOS -> mediaItems.filter { it.mediaType == MediaType.PHOTO }
            MediaFilterType.VIDEOS -> mediaItems.filter { it.mediaType == MediaType.VIDEO }
        }
    }

    val allSelected = selectedIds.size == filteredItems.size && filteredItems.isNotEmpty()

    // Calculate approximate size of selected items
    val selectedBytes = remember(selectedIds, mediaItems) {
        mediaItems.filter { it.id in selectedIds }.sumOf { it.sizeBytes }
    }
    val selectedSizeFormatted = if (selectedBytes > 1024 * 1024) {
        String.format(java.util.Locale.US, "%.1f MB", selectedBytes / (1024.0 * 1024.0))
    } else {
        "${selectedBytes / 1024} KB"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultDarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                    Text(
                        text = "Select Media",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                TextButton(
                    onClick = {
                        selectedIds = if (allSelected) {
                            emptySet()
                        } else {
                            filteredItems.map { it.id }.toSet()
                        }
                    }
                ) {
                    Text(
                        text = if (allSelected) "Deselect All" else "Select All",
                        color = VaultAccent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }

            // Segmented Filter Pill Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MediaFilterType.values().forEach { filterType ->
                    val isSelected = currentFilter == filterType
                    val label = when (filterType) {
                        MediaFilterType.ALL -> "All"
                        MediaFilterType.PHOTOS -> "Photos"
                        MediaFilterType.VIDEOS -> "Videos"
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { currentFilter = filterType },
                        label = { Text(label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = VaultSurface,
                            labelColor = TextPrimary,
                            selectedContainerColor = VaultAccent,
                            selectedLabelColor = VaultDarkBg
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (isSelected) VaultAccent else VaultSurfaceVariant,
                            enabled = true,
                            selected = isSelected
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            // 3-Column Media Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    val isSelected = selectedIds.contains(item.id)
                    val thumbPath = item.thumbnailPath ?: item.encryptedPath

                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(VaultSurface)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) VaultAccent else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selectedIds = if (isSelected) {
                                    selectedIds - item.id
                                } else {
                                    selectedIds + item.id
                                }
                            }
                    ) {
                        AsyncImage(
                            model = EncryptedMediaUri(thumbPath),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )

                        // Video Duration Badge
                        if (item.mediaType == MediaType.VIDEO && item.durationMs > 0) {
                            val durationSec = (item.durationMs / 1000)
                            val min = durationSec / 60
                            val sec = durationSec % 60
                            val timeStr = String.format(java.util.Locale.US, "%d:%02d", min, sec)

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.Black.copy(alpha = 0.7f))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = timeStr,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Selection Checkmark Badge
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) VaultAccent else Color.Black.copy(alpha = 0.5f))
                                .border(
                                    width = 1.5.dp,
                                    color = if (isSelected) VaultAccent else Color.White.copy(alpha = 0.8f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = VaultDarkBg,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Sticky Bottom Action Bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(VaultDarkBg.copy(alpha = 0.95f))
                .padding(16.dp)
        ) {
            Button(
                onClick = { onContinue(selectedIds) },
                enabled = selectedIds.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VaultAccent,
                    disabledContainerColor = VaultSurface
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (selectedIds.isEmpty()) {
                        "Select Media to Export"
                    } else {
                        "Export ${selectedIds.size} Items ($selectedSizeFormatted)"
                    },
                    color = if (selectedIds.isNotEmpty()) VaultDarkBg else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
