package com.secretvault.app.ui.gallery.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.model.MediaItem

@Composable
internal fun ThumbnailRefreshEffect(item: MediaItem?) {
    val app = LocalContext.current.applicationContext as SecretVaultApp
    LaunchedEffect(item?.id, item?.thumbnailPath) { item?.let { app.galleryThumbnailManager.refresh(it) } }
}
