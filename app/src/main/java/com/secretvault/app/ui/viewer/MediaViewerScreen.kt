package com.secretvault.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.ui.gallery.components.MoveMediaDialog
import com.secretvault.app.ui.gallery.components.ShareProgressDialog
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.viewer.components.MediaInfoDialog
import com.secretvault.app.ui.viewer.components.MediaViewerBottomBar
import com.secretvault.app.ui.viewer.components.MediaViewerTopBar
import com.secretvault.app.ui.viewer.components.StreamingVideoPlayer
import com.secretvault.app.ui.viewer.components.ZoomablePhotoView

@Composable
fun MediaViewerScreen(
    app: SecretVaultApp,
    viewModel: MediaViewerViewModel,
    onBack: () -> Unit,
    onEditVideo: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val albums by app.albumRepository.getAllAlbums().collectAsState(initial = emptyList())
    val isSharing by app.ephemeralShareManager.isSharing.collectAsState()
    val shareProgress by app.ephemeralShareManager.shareProgress.collectAsState()
    val shareStatusText by app.ephemeralShareManager.shareStatusText.collectAsState()

    if (uiState.mediaList.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        )
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.currentIndex.coerceIn(0, (uiState.mediaList.size - 1).coerceAtLeast(0)),
        pageCount = { uiState.mediaList.size }
    )

    // Sync pager changes to ViewModel
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            viewModel.setCurrentIndex(page)
        }
    }

    // Scroll to page if updated externally
    LaunchedEffect(uiState.currentIndex) {
        if (pagerState.currentPage != uiState.currentIndex && uiState.currentIndex in uiState.mediaList.indices) {
            pagerState.scrollToPage(uiState.currentIndex)
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) break
            ctx = ctx.baseContext
        }
        ctx as? android.app.Activity
    }

    val currentItem = uiState.mediaList.getOrNull(uiState.currentIndex)
    val mediaRotationById = remember { mutableStateMapOf<String, Int>() }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSetCoverDialog by remember { mutableStateOf(false) }
    var zoomedMediaId by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Horizontal Pager for Fullscreen Media
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = zoomedMediaId != currentItem?.id
        ) { page ->
            val item = uiState.mediaList.getOrNull(page)
            if (item != null) {
                if (item.mediaType == MediaType.PHOTO) {
                    ZoomablePhotoView(
                        item = item,
                        rotationDegrees = mediaRotationById[item.id] ?: 0,
                        onTap = { viewModel.toggleControls() },
                        isActivePage = pagerState.currentPage == page,
                        onZoomChanged = { zoomed -> if (zoomed) zoomedMediaId = item.id else if (zoomedMediaId == item.id) zoomedMediaId = null }
                    )
                } else {
                    StreamingVideoPlayer(
                        item = item,
                        cryptoEngine = app.cryptoEngine,
                        controlsVisible = uiState.isControlsVisible,
                        isActivePage = pagerState.currentPage == page,
                        rotationDegrees = mediaRotationById[item.id] ?: 0,
                        onToggleControls = { viewModel.toggleControls() },
                        onZoomChanged = { zoomed -> if (zoomed) zoomedMediaId = item.id else if (zoomedMediaId == item.id) zoomedMediaId = null }
                    )
                }
            }
        }

        // Top Overlay Bar
        if (currentItem != null) {
            AnimatedVisibility(
                visible = uiState.isControlsVisible,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                MediaViewerTopBar(
                    item = currentItem,
                    currentIndex = uiState.currentIndex,
                    totalCount = uiState.mediaList.size,
                    onBack = onBack,
                    onInfoClick = { viewModel.setInfoDialogOpen(true) },
                    onRotateMedia = {
                        mediaRotationById[currentItem.id] = ((mediaRotationById[currentItem.id] ?: 0) + 90) % 360
                    }
                )
            }

            // Bottom Overlay Bar
            AnimatedVisibility(
                visible = uiState.isControlsVisible,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                MediaViewerBottomBar(
                    onShare = {
                        activity?.let { act ->
                            app.ephemeralShareManager.shareItem(act, currentItem)
                        }
                    },
                    onEdit = if (currentItem.mediaType == MediaType.VIDEO) {
                        { onEditVideo(currentItem.id) }
                    } else null,
                    onSetCover = { showSetCoverDialog = true },
                    onMove = { viewModel.setMoveDialogOpen(true) },
                    onDelete = { showDeleteDialog = true }
                )
            }
        }

        // Delete Confirmation Dialog
        if (showDeleteDialog && currentItem != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { androidx.compose.material3.Text("Delete Item?") },
                text = {
                    val mediaTypeName = if (currentItem.mediaType == MediaType.VIDEO) "video" else "photo"
                    androidx.compose.material3.Text("Are you sure you want to permanently delete this $mediaTypeName? This action cannot be undone.")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteCurrent(onEmpty = onBack)
                        }
                    ) {
                        androidx.compose.material3.Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeleteDialog = false }
                    ) {
                        androidx.compose.material3.Text("Cancel")
                    }
                }
            )
        }

        // Set Cover Confirmation Dialog
        if (showSetCoverDialog && currentItem != null) {
            if (currentItem.mediaType == MediaType.VIDEO) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSetCoverDialog = false },
                    title = { androidx.compose.material3.Text("Cannot Set Video as Cover", color = TextPrimary) },
                    text = { androidx.compose.material3.Text("Only photos can be used as album cover artwork.", color = TextSecondary) },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { showSetCoverDialog = false }) {
                            androidx.compose.material3.Text("OK", color = VaultAccent)
                        }
                    },
                    containerColor = VaultSurface
                )
            } else {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showSetCoverDialog = false },
                    title = { androidx.compose.material3.Text("Set as Album Cover?", color = TextPrimary) },
                    text = {
                        val albumLabel = if (uiState.albumName.isNotBlank()) " for '${uiState.albumName}'" else ""
                        androidx.compose.material3.Text("Do you want to set this photo as the cover image$albumLabel?", color = TextSecondary)
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showSetCoverDialog = false
                                viewModel.setAsAlbumCover()
                                android.widget.Toast.makeText(context, "Album cover updated", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            androidx.compose.material3.Text(
                                "Set as Cover",
                                color = VaultAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showSetCoverDialog = false }) {
                            androidx.compose.material3.Text("Cancel", color = TextMuted)
                        }
                    },
                    containerColor = VaultSurface
                )
            }
        }

        // Info Dialog
        if (uiState.isInfoDialogOpen && currentItem != null) {
            MediaInfoDialog(
                item = currentItem,
                albumName = uiState.albumName,
                onDismiss = { viewModel.setInfoDialogOpen(false) }
            )
        }

        // Move Media Dialog
        if (uiState.isMoveDialogOpen && currentItem != null) {
            MoveMediaDialog(
                albums = albums,
                itemCount = 1,
                onDismiss = { viewModel.setMoveDialogOpen(false) },
                onAlbumSelected = { album ->
                    viewModel.moveToAlbum(album.id)
                },
                onCreateNewAlbum = {
                    // Handled in dialog or albums tab
                }
            )
        }

        // Ephemeral Share Preparation Dialog
        if (isSharing) {
            ShareProgressDialog(
                statusText = shareStatusText,
                progress = shareProgress,
                onCancel = { app.ephemeralShareManager.cancelShare() }
            )
        }
    }
}
