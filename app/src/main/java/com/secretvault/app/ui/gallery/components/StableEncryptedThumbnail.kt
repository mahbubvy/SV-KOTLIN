package com.secretvault.app.ui.gallery.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.secretvault.app.core.image.EncryptedMediaUri

@Composable
internal fun StableEncryptedThumbnail(identity: String, path: String, description: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var displayedKey by remember(identity) { mutableStateOf<MemoryCache.Key?>(null) }
    val request = remember(context, identity, path) {
        ImageRequest.Builder(context)
            .data(EncryptedMediaUri(path))
            .size(512)
            .crossfade(false)
            .placeholderMemoryCacheKey(displayedKey)
            .build()
    }
    AsyncImage(model = request, contentDescription = description, contentScale = ContentScale.Crop,
        onSuccess = { displayedKey = it.result.memoryCacheKey }, modifier = modifier)
}
