package com.secretvault.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.SortOrder
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class GalleryUiState(
    val activeAlbumId: String? = null,
    val activeAlbumName: String = "All Media",
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    val mediaList: List<MediaItem> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val isMoveDialogOpen: Boolean = false,
    val isCreateAlbumDialogOpen: Boolean = false
)

class GalleryViewModel(
    private val mediaRepository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var mediaSubscriptionJob: Job? = null

    init {
        loadMedia()
    }

    fun setAlbumFilter(albumId: String?, albumName: String = "All Media") {
        _uiState.value = _uiState.value.copy(
            activeAlbumId = albumId,
            activeAlbumName = albumName,
            mediaList = emptyList(),
            selectedIds = emptySet(),
            isSelectionMode = false
        )
        loadMedia()
    }

    fun setSortOrder(sortOrder: SortOrder) {
        _uiState.value = _uiState.value.copy(sortOrder = sortOrder)
        loadMedia()
    }

    fun toggleItemSelection(id: String) {
        val current = _uiState.value.selectedIds.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _uiState.value = _uiState.value.copy(
            selectedIds = current,
            isSelectionMode = current.isNotEmpty()
        )
    }

    fun startSelectionWith(id: String) {
        _uiState.value = _uiState.value.copy(
            selectedIds = setOf(id),
            isSelectionMode = true
        )
    }

    fun selectAll() {
        val allIds = _uiState.value.mediaList.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(
            selectedIds = allIds,
            isSelectionMode = true
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedIds = emptySet(),
            isSelectionMode = false
        )
    }

    fun setMoveDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isMoveDialogOpen = open)
    }

    fun setCreateAlbumDialogOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(isCreateAlbumDialogOpen = open)
    }

    fun deleteSelected() {
        val idsToDelete = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            mediaRepository.deleteByIds(idsToDelete)
            clearSelection()
        }
    }

    fun moveSelectedToAlbum(targetAlbumId: String) {
        val idsToMove = _uiState.value.selectedIds.toList()
        viewModelScope.launch {
            mediaRepository.moveMediaToAlbum(idsToMove, targetAlbumId)
            clearSelection()
            setMoveDialogOpen(false)
        }
    }

    private fun loadMedia() {
        mediaSubscriptionJob?.cancel()
        mediaSubscriptionJob = viewModelScope.launch {
            mediaRepository.getMedia(
                albumId = _uiState.value.activeAlbumId,
                sortOrder = _uiState.value.sortOrder
            ).collectLatest { list ->
                _uiState.value = _uiState.value.copy(mediaList = list)
            }
        }
    }
}
