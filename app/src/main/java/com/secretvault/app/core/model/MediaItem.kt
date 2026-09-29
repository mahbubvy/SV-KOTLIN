package com.secretvault.app.core.model

enum class MediaType {
    PHOTO,
    VIDEO
}

enum class SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
    NAME_A_Z,
    NAME_Z_A
}

data class MediaItem(
    val id: String,
    val filename: String,
    val originalName: String,
    val mediaType: MediaType,
    val mimeType: String,
    val encryptedPath: String,
    val thumbnailPath: String?,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val albumId: String,
    val isFavorite: Boolean = false
)

data class Album(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val coverMediaId: String?,
    val coverThumbnailPath: String? = null,
    val itemCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
