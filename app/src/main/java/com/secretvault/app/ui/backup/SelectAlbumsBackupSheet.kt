package com.secretvault.app.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.EncryptedMediaUri
import com.secretvault.app.core.model.Album
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.theme.VaultSurfaceVariant

@Composable
fun SelectAlbumsBackupSheet(
    albums: List<Album>,
    onBack: () -> Unit,
    onContinue: (selectedAlbumIds: Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val allSelected = selectedIds.size == albums.size && albums.isNotEmpty()

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
                        text = "Select Albums",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                TextButton(
                    onClick = {
                        selectedIds = if (allSelected) emptySet() else albums.map { it.id }.toSet()
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

            // Albums List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(albums, key = { it.id }) { album ->
                    val isSelected = selectedIds.contains(album.id)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(VaultSurface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) VaultAccent else VaultSurfaceVariant,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable {
                                selectedIds = if (isSelected) {
                                    selectedIds - album.id
                                } else {
                                    selectedIds + album.id
                                }
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Album Thumbnail or Default Icon
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(VaultSurfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (album.coverThumbnailPath != null) {
                                AsyncImage(
                                    model = EncryptedMediaUri(album.coverThumbnailPath),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                val icon = when (album.id) {
                                    AlbumEntity.ALBUM_CAMERA_ID -> Icons.Default.CameraAlt
                                    AlbumEntity.ALBUM_IMPORTS_ID -> Icons.Default.Download
                                    else -> Icons.Default.Folder
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = VaultAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Album Title & Count
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = album.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${album.itemCount} items" + if (album.coverMediaId != null) " • Custom Cover" else "",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }

                        // Circular Checkbox
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) VaultAccent else Color.Transparent)
                                .border(
                                    width = 2.dp,
                                    color = if (isSelected) VaultAccent else TextMuted,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = VaultDarkBg,
                                    modifier = Modifier.size(16.dp)
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
                    text = if (selectedIds.isEmpty()) "Select Albums to Export" else "Continue to Export (${selectedIds.size} albums)",
                    color = if (selectedIds.isNotEmpty()) VaultDarkBg else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}
