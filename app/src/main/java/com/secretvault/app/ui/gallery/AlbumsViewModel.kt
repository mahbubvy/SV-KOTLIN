package com.secretvault.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secretvault.app.core.model.Album
import com.secretvault.app.data.repository.AlbumRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlbumsViewModel(
    private val albumRepository: AlbumRepository
) : ViewModel() {

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    init {
        viewModelScope.launch {
            albumRepository.getAllAlbums().collectLatest { list ->
                _albums.value = list
            }
        }
    }

    fun createAlbum(name: String, onCreated: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val id = albumRepository.createAlbum(name)
            onCreated?.invoke(id)
        }
    }

    fun renameAlbum(id: String, newName: String) {
        viewModelScope.launch {
            albumRepository.renameAlbum(id, newName)
        }
    }

    fun deleteAlbum(id: String, deleteContents: Boolean = false) {
        viewModelScope.launch {
            albumRepository.deleteAlbum(id, deleteContents)
        }
    }

    fun removeCover(albumId: String) {
        viewModelScope.launch {
            albumRepository.removeAlbumCover(albumId)
        }
    }
}
