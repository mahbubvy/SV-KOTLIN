package com.secretvault.app.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.secretvault.app.core.database.entity.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mediaList: List<MediaEntity>)

    @Update
    suspend fun update(media: MediaEntity)

    @Delete
    suspend fun delete(media: MediaEntity)

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM media_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaEntity?

    @Query("SELECT * FROM media_items ORDER BY createdAt DESC")
    fun getAllNewest(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items ORDER BY createdAt ASC")
    fun getAllOldest(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items ORDER BY filename ASC")
    fun getAllNameAsc(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items ORDER BY filename DESC")
    fun getAllNameDesc(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE albumId = :albumId ORDER BY createdAt DESC")
    fun getByAlbumNewest(albumId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE albumId = :albumId ORDER BY createdAt ASC")
    fun getByAlbumOldest(albumId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE albumId = :albumId ORDER BY filename ASC")
    fun getByAlbumNameAsc(albumId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE albumId = :albumId ORDER BY filename DESC")
    fun getByAlbumNameDesc(albumId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media_items WHERE albumId = :albumId")
    suspend fun getByAlbumList(albumId: String): List<MediaEntity>

    @Query("SELECT * FROM media_items ORDER BY createdAt DESC")
    suspend fun getAllList(): List<MediaEntity>

    @Query("UPDATE media_items SET albumId = :targetAlbumId WHERE id IN (:ids)")
    suspend fun moveItemsToAlbum(ids: List<String>, targetAlbumId: String)

    @Query("SELECT COUNT(*) FROM media_items WHERE albumId = :albumId")
    suspend fun getItemCountForAlbum(albumId: String): Int

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun getTotalMediaCount(): Int
}
