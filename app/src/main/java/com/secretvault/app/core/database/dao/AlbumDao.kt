package com.secretvault.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.secretvault.app.core.database.entity.AlbumEntity
import kotlinx.coroutines.flow.Flow

data class AlbumWithMediaCount(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    val coverMediaId: String?,
    val createdAt: Long,
    val mediaCount: Int,
    val coverThumbnailPath: String?
)

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(albums: List<AlbumEntity>)

    @Update
    suspend fun update(album: AlbumEntity)

    @Delete
    suspend fun delete(album: AlbumEntity)

    @Query("DELETE FROM albums WHERE id = :id AND isSystem = 0")
    suspend fun deleteCustomAlbum(id: String)

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AlbumEntity?

    @Query("SELECT * FROM albums")
    suspend fun getAllAlbumsList(): List<AlbumEntity>

    @Query("""
        SELECT 
            a.id, 
            a.name, 
            a.isSystem, 
            a.coverMediaId, 
            a.createdAt,
            COUNT(m.id) as mediaCount,
            COALESCE(
                (SELECT thumbnailPath FROM media_items WHERE id = a.coverMediaId LIMIT 1),
                (SELECT thumbnailPath FROM media_items WHERE albumId = a.id ORDER BY createdAt DESC LIMIT 1)
            ) as coverThumbnailPath
        FROM albums a
        LEFT JOIN media_items m ON a.id = m.albumId
        GROUP BY a.id
        ORDER BY a.isSystem DESC, a.createdAt ASC
    """)
    fun getAllAlbumsWithCount(): Flow<List<AlbumWithMediaCount>>

    @Query("UPDATE albums SET name = :newName WHERE id = :id AND isSystem = 0")
    suspend fun renameAlbum(id: String, newName: String)

    @Query("UPDATE albums SET coverMediaId = :coverMediaId WHERE id = :albumId")
    suspend fun updateCover(albumId: String, coverMediaId: String?)
}
