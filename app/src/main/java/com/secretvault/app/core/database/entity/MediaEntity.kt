package com.secretvault.app.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType

@Entity(
    tableName = "media_items",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["createdAt"]),
        Index(value = ["filename"])
    ]
)
data class MediaEntity(
    @PrimaryKey
    val id: String,
    val filename: String,
    val originalName: String,
    val mediaType: String, // "PHOTO" or "VIDEO"
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
) {
    fun toDomain(): MediaItem {
        return MediaItem(
            id = id,
            filename = filename,
            originalName = originalName,
            mediaType = try { MediaType.valueOf(mediaType.uppercase()) } catch (e: Exception) { MediaType.PHOTO },
            mimeType = mimeType,
            encryptedPath = encryptedPath,
            thumbnailPath = thumbnailPath,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            width = width,
            height = height,
            createdAt = createdAt,
            albumId = albumId,
            isFavorite = isFavorite
        )
    }

    companion object {
        fun fromDomain(item: MediaItem): MediaEntity {
            return MediaEntity(
                id = item.id,
                filename = item.filename,
                originalName = item.originalName,
                mediaType = item.mediaType.name,
                mimeType = item.mimeType,
                encryptedPath = item.encryptedPath,
                thumbnailPath = item.thumbnailPath,
                sizeBytes = item.sizeBytes,
                durationMs = item.durationMs,
                width = item.width,
                height = item.height,
                createdAt = item.createdAt,
                albumId = item.albumId,
                isFavorite = item.isFavorite
            )
        }
    }
}
