package com.secretvault.app.ui

import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.model.SortOrder
import com.secretvault.app.data.repository.MediaRepository
import com.secretvault.app.ui.gallery.GalleryViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockMediaRepo: MediaRepository
    private lateinit var viewModel: GalleryViewModel

    private val sampleItems = listOf(
        MediaItem(
            id = "m1",
            filename = "SV_01012026_1200.jpg",
            originalName = "SV_01012026_1200.jpg",
            mediaType = MediaType.PHOTO,
            mimeType = "image/jpeg",
            encryptedPath = "/path/m1.enc",
            thumbnailPath = "/path/m1.thumb",
            sizeBytes = 1024,
            createdAt = 1000L,
            albumId = "album_camera"
        ),
        MediaItem(
            id = "m2",
            filename = "SV_01012026_1201.mp4",
            originalName = "SV_01012026_1201.mp4",
            mediaType = MediaType.VIDEO,
            mimeType = "video/mp4",
            encryptedPath = "/path/m2.enc",
            thumbnailPath = "/path/m2.thumb",
            sizeBytes = 2048,
            createdAt = 2000L,
            albumId = "album_camera"
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockMediaRepo = mockk(relaxed = true)
        coEvery { mockMediaRepo.getMedia(any(), any()) } returns flowOf(sampleItems)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialMediaLoad() = runTest(testDispatcher) {
        viewModel = GalleryViewModel(mockMediaRepo)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.mediaList.size)
        assertEquals(SortOrder.NEWEST_FIRST, viewModel.uiState.value.sortOrder)
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun testSelectionFlow() = runTest(testDispatcher) {
        viewModel = GalleryViewModel(mockMediaRepo)
        advanceUntilIdle()

        // Start selection
        viewModel.startSelectionWith("m1")
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(setOf("m1"), viewModel.uiState.value.selectedIds)

        // Toggle another item
        viewModel.toggleItemSelection("m2")
        assertEquals(setOf("m1", "m2"), viewModel.uiState.value.selectedIds)

        // Select all
        viewModel.selectAll()
        assertEquals(setOf("m1", "m2"), viewModel.uiState.value.selectedIds)

        // Clear selection
        viewModel.clearSelection()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun testBatchDelete() = runTest(testDispatcher) {
        viewModel = GalleryViewModel(mockMediaRepo)
        advanceUntilIdle()

        viewModel.startSelectionWith("m1")
        viewModel.deleteSelected()
        advanceUntilIdle()

        coVerify { mockMediaRepo.deleteByIds(listOf("m1")) }
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun testBatchMove() = runTest(testDispatcher) {
        viewModel = GalleryViewModel(mockMediaRepo)
        advanceUntilIdle()

        viewModel.startSelectionWith("m1")
        viewModel.moveSelectedToAlbum("custom_album_1")
        advanceUntilIdle()

        coVerify { mockMediaRepo.moveMediaToAlbum(listOf("m1"), "custom_album_1") }
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }
}
