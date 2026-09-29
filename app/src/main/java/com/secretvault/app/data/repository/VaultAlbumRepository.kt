package com.secretvault.app.data.repository

import com.secretvault.app.core.database.dao.AlbumDao
import com.secretvault.app.core.database.dao.MediaDao
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

interface AlbumRepository {
    fun getAllAlbums(): Flow<List<Album>>
    suspend fun getAlbumById(id: String): Album?
    suspend fun createAlbum(name: String): String
    suspend fun renameAlbum(id: String, newName: String)
    suspend fun deleteAlbum(id: String, deleteContents: Boolean = false)
    suspend fun setAlbumCover(albumId: String, mediaId: String?)
    suspend fun removeAlbumCover(albumId: String)
}

class VaultAlbumRepository(
    private val albumDao: AlbumDao,
    private val mediaDao: MediaDao
) : AlbumRepository {

    override fun getAllAlbums(): Flow<List<Album>> {
        return albumDao.getAllAlbumsWithCount().map { list ->
            list.map { item ->
                Album(
                    id = item.id,
                    name = item.name,
                    isSystem = item.isSystem,
                    coverMediaId = item.coverMediaId,
                    coverThumbnailPath = item.coverThumbnailPath,
                    itemCount = item.mediaCount,
                    createdAt = item.createdAt
                )
            }
        }
    }

    override suspend fun getAlbumById(id: String): Album? = withContext(Dispatchers.IO) {
        val entity = albumDao.getById(id) ?: return@withContext null
        val count = mediaDao.getItemCountForAlbum(id)
        entity.toDomain(itemCount = count)
    }

    override suspend fun createAlbum(name: String): String = withContext(Dispatchers.IO) {
        val id = "album_" + UUID.randomUUID().toString().take(8)
        val entity = AlbumEntity(
            id = id,
            name = name.trim(),
            isSystem = false
        )
        albumDao.insert(entity)
        id
    }

    override suspend fun renameAlbum(id: String, newName: String) = withContext(Dispatchers.IO) {
        albumDao.renameAlbum(id, newName.trim())
    }

    override suspend fun deleteAlbum(id: String, deleteContents: Boolean) = withContext(Dispatchers.IO) {
        val items = mediaDao.getByAlbumList(id)
        if (deleteContents) {
            for (item in items) {
                val encFile = File(item.encryptedPath)
                if (encFile.exists()) encFile.delete()
                item.thumbnailPath?.let {
                    val thumbFile = File(it)
                    if (thumbFile.exists()) thumbFile.delete()
                }
            }
            if (items.isNotEmpty()) {
                mediaDao.deleteByIds(items.map { it.id })
            }
        } else {
            if (items.isNotEmpty()) {
                mediaDao.moveItemsToAlbum(items.map { it.id }, AlbumEntity.ALBUM_UNSORTED_ID)
            }
        }
        albumDao.deleteCustomAlbum(id)
    }

    override suspend fun setAlbumCover(albumId: String, mediaId: String?) = withContext(Dispatchers.IO) {
        albumDao.updateCover(albumId, mediaId)
    }

    override suspend fun removeAlbumCover(albumId: String) = withContext(Dispatchers.IO) {
        albumDao.updateCover(albumId, null)
    }
}
