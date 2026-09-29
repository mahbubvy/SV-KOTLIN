package com.secretvault.app.ui.gallery

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.model.Album
import com.secretvault.app.ui.gallery.components.AlbumGridItem
import com.secretvault.app.ui.gallery.components.CreateAlbumDialog
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultError
import com.secretvault.app.ui.theme.VaultSurface

@Composable
fun AlbumsTab(
    viewModel: AlbumsViewModel,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val albums by viewModel.albums.collectAsState()

    var isCreateDialogOpen by remember { mutableStateOf(false) }
    var albumToRename by remember { mutableStateOf<Album?>(null) }
    var albumToDelete by remember { mutableStateOf<Album?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // New Album Header Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Albums (${albums.size})",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Button(
                    onClick = { isCreateDialogOpen = true },
                    colors = ButtonDefaults.buttonColors(containerColor = VaultAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = VaultDarkBg
                    )
                    Spacer(modifier = Modifier.padding(2.dp))
                    Text("New Album", color = VaultDarkBg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumGridItem(
                        album = album,
                        onClick = { onAlbumClick(album) },
                        onRename = { albumToRename = album },
                        onDelete = { albumToDelete = album },
                        onRemoveCover = { viewModel.removeCover(album.id) }
                    )
                }
            }
        }

        // Create Album Dialog
        if (isCreateDialogOpen) {
            CreateAlbumDialog(
                title = "Create New Album",
                confirmButtonText = "Create",
                onDismiss = { isCreateDialogOpen = false },
                onConfirm = { name ->
                    viewModel.createAlbum(name)
                    isCreateDialogOpen = false
                }
            )
        }

        // Rename Album Dialog
        albumToRename?.let { album ->
            CreateAlbumDialog(
                initialName = album.name,
                title = "Rename Album",
                confirmButtonText = "Save",
                onDismiss = { albumToRename = null },
                onConfirm = { newName ->
                    viewModel.renameAlbum(album.id, newName)
                    albumToRename = null
                }
            )
        }

        // Delete Album Confirmation Dialog
        albumToDelete?.let { album ->
            Dialog(onDismissRequest = { albumToDelete = null }) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = VaultSurface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Delete Album",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        if (album.itemCount > 0) {
                            Text(
                                text = "The album \"${album.name}\" contains ${album.itemCount} item(s). What would you like to do with the items inside?",
                                fontSize = 14.sp,
                                color = TextSecondary,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            // Option 1: Move items to Unsorted
                            Button(
                                onClick = {
                                    viewModel.deleteAlbum(album.id, deleteContents = false)
                                    albumToDelete = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = VaultAccent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Keep Items (Move to Unsorted)", color = VaultDarkBg, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            // Option 2: Delete album and all its items
                            Button(
                                onClick = {
                                    viewModel.deleteAlbum(album.id, deleteContents = true)
                                    albumToDelete = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = VaultError),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Delete Album & All Items", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { albumToDelete = null },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Text("Cancel", color = TextMuted)
                            }
                        } else {
                            Text(
                                text = "Are you sure you want to delete the empty album \"${album.name}\"?",
                                fontSize = 14.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { albumToDelete = null }) {
                                    Text("Cancel", color = TextMuted)
                                }
                                Spacer(modifier = Modifier.padding(4.dp))
                                Button(
                                    onClick = {
                                        viewModel.deleteAlbum(album.id, deleteContents = false)
                                        albumToDelete = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = VaultError)
                                ) {
                                    Text("Delete", color = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
