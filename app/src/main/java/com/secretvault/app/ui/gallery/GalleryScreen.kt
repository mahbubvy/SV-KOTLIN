package com.secretvault.app.ui.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.model.MediaItem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.secretvault.app.SecretVaultApp
import android.net.Uri
import com.secretvault.app.ui.gallery.components.CreateAlbumDialog
import com.secretvault.app.ui.gallery.components.GalleryTopBar
import com.secretvault.app.ui.gallery.components.ImportProgressDialog
import com.secretvault.app.ui.gallery.components.MediaGridItem
import com.secretvault.app.ui.gallery.components.MoveMediaDialog
import com.secretvault.app.ui.gallery.components.SelectionBottomBar
import com.secretvault.app.ui.gallery.components.ShareProgressDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultBorder
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.theme.VaultSurfaceVariant
import kotlinx.coroutines.launch

@Composable
fun GalleryScreen(
    app: SecretVaultApp,
    galleryViewModel: GalleryViewModel,
    albumsViewModel: AlbumsViewModel,
    onMediaClick: (MediaItem) -> Unit,
    onCameraClick: () -> Unit,
    onLockClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onViewStreamClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by galleryViewModel.uiState.collectAsState()
    val albums by albumsViewModel.albums.collectAsState()
    val importProgress by app.batchImportManager.progress.collectAsState()
    val isSharing by app.ephemeralShareManager.isSharing.collectAsState()
    val shareProgress by app.ephemeralShareManager.shareProgress.collectAsState()
    val shareStatusText by app.ephemeralShareManager.shareStatusText.collectAsState()
    val keepOpen by app.sessionManager.keepUnlocked.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) break
            ctx = ctx.baseContext
        }
        ctx as? android.app.Activity
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        app.sessionManager.setExternalPickerInProgress(false)
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                val targetAlbum = uiState.activeAlbumId ?: com.secretvault.app.core.database.entity.AlbumEntity.ALBUM_IMPORTS_ID
                val result = app.batchImportManager.importUris(uris, targetAlbum)
                android.widget.Toast.makeText(
                    context,
                    "Imported ${result.successfulCount} items securely",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val allMedia by app.mediaRepository.getMedia(null).collectAsState(initial = emptyList())
    var showBackupRestoreScreen by remember { mutableStateOf(false) }

    if (showBackupRestoreScreen) {
        com.secretvault.app.ui.backup.BackupRestoreScreen(
            albums = albums,
            mediaItems = allMedia,
            onBack = { showBackupRestoreScreen = false }
        )
        return
    }

    val isAlbumsHome = uiState.activeAlbumId == null && !uiState.favoritesOnly
    val currentTitle = if (isAlbumsHome) "Albums" else uiState.activeAlbumName
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Intercept hardware/gesture Back: return to root Albums or exit selection mode instead of exiting app
    androidx.activity.compose.BackHandler(enabled = !isAlbumsHome || uiState.isSelectionMode) {
        if (uiState.isSelectionMode) {
            galleryViewModel.clearSelection()
        } else if (!isAlbumsHome) {
            galleryViewModel.setAlbumFilter(null, "Albums")
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultDarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top Bar
            GalleryTopBar(
                title = currentTitle,
                isSelectionMode = uiState.isSelectionMode,
                selectedCount = uiState.selectedIds.size,
                isAllSelected = uiState.mediaList.isNotEmpty() && uiState.selectedIds.size == uiState.mediaList.size,
                currentSort = uiState.sortOrder,
                showBackButton = !isAlbumsHome,
                onBackClick = { galleryViewModel.setAlbumFilter(null, "Albums") },
                onSortSelected = { galleryViewModel.setSortOrder(it) },
                onSelectAllClick = {
                    if (uiState.selectedIds.size == uiState.mediaList.size) {
                        galleryViewModel.clearSelection()
                    } else {
                        galleryViewModel.selectAll()
                    }
                },
                onCloseSelectionClick = { galleryViewModel.clearSelection() },
                onLockClick = onLockClick,
                onSettingsClick = if (isAlbumsHome) onSettingsClick else null,
                onFavoritesClick = if (isAlbumsHome) galleryViewModel::showFavorites else null,
                onBackupRestoreClick = if (isAlbumsHome && !uiState.isSelectionMode) {
                    { showBackupRestoreScreen = true }
                } else null
            )

            // Main Content Area: Albums View (Root) vs Album Media Grid
            if (isAlbumsHome) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .toggleable(keepOpen, role = Role.Switch, onValueChange = app.sessionManager::setKeepUnlocked)
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Keep vault open", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Open straight to camera. Turns off after 10 minutes outside the vault.", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = keepOpen,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = VaultDarkBg,
                            checkedTrackColor = VaultAccent,
                            uncheckedTrackColor = VaultSurface,
                            uncheckedBorderColor = TextSecondary
                        )
                    )
                }
                AlbumsTab(
                    viewModel = albumsViewModel,
                    mediaItems = allMedia,
                    onAlbumClick = { album ->
                        galleryViewModel.setAlbumFilter(album.id, album.name)
                    }
                )
            } else {
                // Media Grid for Selected Album
                if (uiState.mediaList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.5.dp, VaultBorder, RoundedCornerShape(16.dp))
                                .background(VaultSurface, RoundedCornerShape(16.dp))
                                .padding(horizontal = 24.dp, vertical = 32.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(VaultSurfaceVariant, RoundedCornerShape(28.dp))
                                    .border(0.5.dp, VaultBorder, RoundedCornerShape(28.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Collections,
                                    contentDescription = "No photos or videos",
                                    tint = VaultAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = if (uiState.favoritesOnly) "No favorites yet" else "No media in ${uiState.activeAlbumName}",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Text(
                                text = if (uiState.favoritesOnly) "Tap the heart while viewing a photo or video." else "Photos and videos added or imported into this album will appear securely here.",
                                fontSize = 13.sp,
                                color = TextMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(uiState.mediaList, key = { it.id }) { item ->
                            val isSelected = uiState.selectedIds.contains(item.id)
                            MediaGridItem(
                                item = item,
                                isSelected = isSelected,
                                isSelectionMode = uiState.isSelectionMode,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        galleryViewModel.toggleItemSelection(item.id)
                                    } else {
                                        onMediaClick(item)
                                    }
                                },
                                onLongClick = {
                                    galleryViewModel.startSelectionWith(item.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Floating Action Buttons (Visible when not in multi-selection mode)
        AnimatedVisibility(
            visible = !uiState.isSelectionMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isAlbumsHome) {
                    FloatingActionButton(onClick = onViewStreamClick,
                        containerColor = VaultSurface, contentColor = VaultAccent) {
                        Icon(Icons.Default.Cast, contentDescription = "View stream")
                    }
                }
                // Import from Device Button
                FloatingActionButton(
                    onClick = {
                        app.sessionManager.setExternalPickerInProgress(true)
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    containerColor = VaultSurface,
                    contentColor = VaultAccent
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = "Import Photos & Videos"
                    )
                }

                // Open Camera Button
                FloatingActionButton(
                    onClick = onCameraClick,
                    containerColor = VaultAccent,
                    contentColor = VaultDarkBg
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Open Camera"
                    )
                }
            }
        }

        // Multi-Selection Bottom Bar
        AnimatedVisibility(
            visible = uiState.isSelectionMode,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionBottomBar(
                selectedCount = uiState.selectedIds.size,
                onMoveClick = { galleryViewModel.setMoveDialogOpen(true) },
                onDeleteClick = { showDeleteConfirmDialog = true },
                onShareClick = {
                    val selectedItems = uiState.mediaList.filter { uiState.selectedIds.contains(it.id) }
                    activity?.let { act ->
                        app.ephemeralShareManager.shareMultiple(act, selectedItems)
                    }
                    galleryViewModel.clearSelection()
                }
            )
        }

        // Delete Confirmation Dialog
        if (showDeleteConfirmDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirmDialog = false },
                title = { androidx.compose.material3.Text("Delete Selected Items?") },
                text = { androidx.compose.material3.Text("Are you sure you want to permanently delete ${uiState.selectedIds.size} item(s)? This action cannot be undone.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            galleryViewModel.deleteSelected()
                            showDeleteConfirmDialog = false
                        }
                    ) {
                        androidx.compose.material3.Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeleteConfirmDialog = false }
                    ) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            )
        }

        // Batch Import Progress Dialog
        if (importProgress.isImporting) {
            ImportProgressDialog(
                progress = importProgress,
                onCancel = { app.batchImportManager.cancelImport() }
            )
        }

        // Move Media Dialog
        if (uiState.isMoveDialogOpen) {
            MoveMediaDialog(
                albums = albums,
                itemCount = uiState.selectedIds.size,
                onDismiss = { galleryViewModel.setMoveDialogOpen(false) },
                onAlbumSelected = { album ->
                    galleryViewModel.moveSelectedToAlbum(album.id)
                },
                onCreateNewAlbum = {
                    galleryViewModel.setCreateAlbumDialogOpen(true)
                }
            )
        }

        // Inline Create Album Dialog
        if (uiState.isCreateAlbumDialogOpen) {
            CreateAlbumDialog(
                title = "New Album for Moved Items",
                confirmButtonText = "Create & Move",
                onDismiss = { galleryViewModel.setCreateAlbumDialogOpen(false) },
                onConfirm = { albumName ->
                    albumsViewModel.createAlbum(albumName) { newAlbumId ->
                        galleryViewModel.moveSelectedToAlbum(newAlbumId)
                        galleryViewModel.setCreateAlbumDialogOpen(false)
                    }
                }
            )
        }

        // Ephemeral Share Progress Dialog
        if (isSharing) {
            ShareProgressDialog(
                statusText = shareStatusText,
                progress = shareProgress,
                onCancel = { app.ephemeralShareManager.cancelShare() }
            )
        }
    }
}
