package com.secretvault.app.data.repository

import com.secretvault.app.core.crypto.SecureMemory
import com.secretvault.app.core.crypto.VaultCryptoEngine
import com.secretvault.app.core.database.dao.MediaDao
import com.secretvault.app.core.database.entity.MediaEntity
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

interface MediaRepository {
    fun getMedia(albumId: String? = null, sortOrder: SortOrder = SortOrder.NEWEST_FIRST): Flow<List<MediaItem>>
    suspend fun getMediaById(id: String): MediaItem?
    suspend fun insertMedia(item: MediaItem)
    suspend fun insertAll(items: List<MediaItem>)
    suspend fun deleteMedia(items: List<MediaItem>)
    suspend fun deleteByIds(ids: List<String>)
    suspend fun moveMediaToAlbum(itemIds: List<String>, targetAlbumId: String)
    suspend fun getTotalCount(): Int
    suspend fun setFavorite(id: String, favorite: Boolean)
}

class VaultMediaRepository(
    private val mediaDao: MediaDao,
    private val cryptoEngine: VaultCryptoEngine
) : MediaRepository {

    override fun getMedia(albumId: String?, sortOrder: SortOrder): Flow<List<MediaItem>> {
        val flow = if (albumId != null) {
            when (sortOrder) {
                SortOrder.NEWEST_FIRST -> mediaDao.getByAlbumNewest(albumId)
                SortOrder.OLDEST_FIRST -> mediaDao.getByAlbumOldest(albumId)
                SortOrder.NAME_A_Z -> mediaDao.getByAlbumNameAsc(albumId)
                SortOrder.NAME_Z_A -> mediaDao.getByAlbumNameDesc(albumId)
            }
        } else {
            when (sortOrder) {
                SortOrder.NEWEST_FIRST -> mediaDao.getAllNewest()
                SortOrder.OLDEST_FIRST -> mediaDao.getAllOldest()
                SortOrder.NAME_A_Z -> mediaDao.getAllNameAsc()
                SortOrder.NAME_Z_A -> mediaDao.getAllNameDesc()
            }
        }
        return flow.map { list -> list.map { it.toDomain() } }
    }

    override suspend fun getMediaById(id: String): MediaItem? = withContext(Dispatchers.IO) {
        mediaDao.getById(id)?.toDomain()
    }

    override suspend fun insertMedia(item: MediaItem) = withContext(Dispatchers.IO) {
        mediaDao.insert(MediaEntity.fromDomain(item))
    }

    override suspend fun insertAll(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        mediaDao.insertAll(items.map { MediaEntity.fromDomain(it) })
    }

    override suspend fun deleteMedia(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        for (item in items) {
            // Secure delete the physical encrypted file and thumbnail
            val encFile = File(item.encryptedPath)
            if (encFile.exists()) {
                encFile.delete()
            }
            item.thumbnailPath?.let {
                val thumbFile = File(it)
                if (thumbFile.exists()) {
                    thumbFile.delete()
                }
            }
        }
        mediaDao.deleteByIds(items.map { it.id })
    }

    override suspend fun deleteByIds(ids: List<String>) = withContext(Dispatchers.IO) {
        for (id in ids) {
            val item = mediaDao.getById(id)
            if (item != null) {
                val encFile = File(item.encryptedPath)
                if (encFile.exists()) encFile.delete()
                item.thumbnailPath?.let {
                    val thumbFile = File(it)
                    if (thumbFile.exists()) thumbFile.delete()
                }
            }
        }
        mediaDao.deleteByIds(ids)
    }

    override suspend fun moveMediaToAlbum(itemIds: List<String>, targetAlbumId: String) = withContext(Dispatchers.IO) {
        // Move without duplicating encrypted physical files on disk
        mediaDao.moveItemsToAlbum(itemIds, targetAlbumId)
    }

    override suspend fun getTotalCount(): Int = withContext(Dispatchers.IO) {
        mediaDao.getTotalMediaCount()
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        mediaDao.setFavorite(id, favorite)
    }
}
