package com.secretvault.app.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.SortOrder
import com.secretvault.app.data.repository.AlbumRepository
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class MediaViewerUiState(
    val mediaList: List<MediaItem> = emptyList(),
    val currentIndex: Int = 0,
    val isControlsVisible: Boolean = true,
    val isInfoDialogOpen: Boolean = false,
    val isMoveDialogOpen: Boolean = false,
    val albumName: String = "Vault",
    val favoriteBusy: Boolean = false,
    val errorMessage: String? = null
)

class MediaViewerViewModel(
    private val albumId: String?,
    private val initialMediaId: String,
    private val mediaRepository: MediaRepository,
    private val albumRepository: AlbumRepository,
    private val favoritesOnly: Boolean = false
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaViewerUiState())
    val uiState: StateFlow<MediaViewerUiState> = _uiState.asStateFlow()

    init {
        loadMedia()
    }

    fun toggleControls() {
        _uiState.value = _uiState.value.copy(isControlsVisible = !_uiState.value.isControlsVisible)
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(isControlsVisible = visible)
    }

    fun setCurrentIndex(index: Int) {
        if (index in _uiState.value.mediaList.indices) {
            _uiState.value = _uiState.value.copy(currentIndex = index)
        }
    }

    fun setInfoDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isInfoDialogOpen = open)
    }

    fun setMoveDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isMoveDialogOpen = open)
    }

    fun setAsAlbumCover() {
        val currentItem = getCurrentItem() ?: return
        viewModelScope.launch {
            albumRepository.setAlbumCover(currentItem.albumId, currentItem.id)
        }
    }

    fun deleteCurrent(onEmpty: () -> Unit) {
        val currentItem = getCurrentItem() ?: return
        val currentIdx = _uiState.value.currentIndex
        viewModelScope.launch {
            mediaRepository.deleteByIds(listOf(currentItem.id))
            val updatedList = _uiState.value.mediaList.filter { it.id != currentItem.id }
            if (updatedList.isEmpty()) {
                onEmpty()
            } else {
                val nextIndex = currentIdx.coerceAtMost(updatedList.size - 1)
                _uiState.value = _uiState.value.copy(
                    mediaList = updatedList,
                    currentIndex = nextIndex
                )
            }
        }
    }

    fun moveToAlbum(targetAlbumId: String) {
        val currentItem = getCurrentItem() ?: return
        viewModelScope.launch {
            mediaRepository.moveMediaToAlbum(listOf(currentItem.id), targetAlbumId)
            setMoveDialogOpen(false)
        }
    }

    fun getCurrentItem(): MediaItem? {
        val state = _uiState.value
        return state.mediaList.getOrNull(state.currentIndex)
    }

    fun toggleFavorite() {
        if (_uiState.value.favoriteBusy) return
        val item = getCurrentItem() ?: return
        val favorite = !item.isFavorite
        _uiState.value = _uiState.value.copy(favoriteBusy = true, errorMessage = null)
        viewModelScope.launch {
            try {
                mediaRepository.setFavorite(item.id, favorite)
                _uiState.value = _uiState.value.copy(favoriteBusy = false,
                    mediaList = _uiState.value.mediaList.map { if (it.id == item.id) it.copy(isFavorite = favorite) else it })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.value = _uiState.value.copy(favoriteBusy = false, errorMessage = "Could not update favorite. Try again.")
            }
        }
    }

    private fun loadMedia() {
        viewModelScope.launch {
            val all = mediaRepository.getMedia(albumId, SortOrder.NEWEST_FIRST).first()
            val list = if (favoritesOnly) all.filter { it.isFavorite } else all
            val initialIndex = list.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)
            val albumName = if (albumId != null) {
                val album = albumRepository.getAlbumById(albumId)
                album?.name ?: "Album"
            } else if (favoritesOnly) {
                "Favorites"
            } else {
                "All Media"
            }

            _uiState.value = MediaViewerUiState(
                mediaList = list,
                currentIndex = initialIndex,
                albumName = albumName
            )
        }
    }
}
