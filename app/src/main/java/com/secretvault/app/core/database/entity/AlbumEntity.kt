package com.secretvault.app.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.secretvault.app.core.model.Album

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val isSystem: Boolean = false,
    val coverMediaId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(itemCount: Int = 0, coverThumbnailPath: String? = null): Album {
        return Album(
            id = id,
            name = name,
            isSystem = isSystem,
            coverMediaId = coverMediaId,
            coverThumbnailPath = coverThumbnailPath,
            itemCount = itemCount,
            createdAt = createdAt
        )
    }

    companion object {
        const val ALBUM_CAMERA_ID = "album_camera"
        const val ALBUM_IMPORTS_ID = "album_imports"
        const val ALBUM_UNSORTED_ID = "album_unsorted"

        fun fromDomain(album: Album): AlbumEntity {
            return AlbumEntity(
                id = album.id,
                name = album.name,
                isSystem = album.isSystem,
                coverMediaId = album.coverMediaId,
                createdAt = album.createdAt
            )
        }
    }
}
