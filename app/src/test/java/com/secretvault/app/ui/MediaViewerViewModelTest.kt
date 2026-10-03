package com.secretvault.app.ui

import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.data.repository.AlbumRepository
import com.secretvault.app.data.repository.MediaRepository
import com.secretvault.app.ui.viewer.MediaViewerViewModel
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerViewModelTest {

    @Test fun favoriteChangesAfterPersistenceAndCanBeRemoved() = runTest(testDispatcher) {
        viewModel = MediaViewerViewModel("album_camera", "photo_1", mockMediaRepo, mockAlbumRepo)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()
        coVerify { mockMediaRepo.setFavorite("photo_1", true) }
        assertEquals(true, viewModel.getCurrentItem()?.isFavorite)
        viewModel.toggleFavorite()
        advanceUntilIdle()
        coVerify { mockMediaRepo.setFavorite("photo_1", false) }
        assertEquals(false, viewModel.getCurrentItem()?.isFavorite)
    }

    @Test fun failedFavoriteWriteDoesNotChangeTheMark() = runTest(testDispatcher) {
        coEvery { mockMediaRepo.setFavorite(any(), any()) } throws IllegalStateException("Database unavailable")
        viewModel = MediaViewerViewModel("album_camera", "photo_1", mockMediaRepo, mockAlbumRepo)
        advanceUntilIdle()
        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertEquals(false, viewModel.getCurrentItem()?.isFavorite)
        org.junit.Assert.assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test fun favoritesViewerContainsOnlyMarkedItems() = runTest(testDispatcher) {
        coEvery { mockMediaRepo.getMedia(any(), any()) } returns flowOf(sampleItems.map { it.copy(isFavorite = it.id == "video_1") })
        viewModel = MediaViewerViewModel(null, "video_1", mockMediaRepo, mockAlbumRepo, favoritesOnly = true)
        advanceUntilIdle()
        assertEquals(listOf("video_1"), viewModel.uiState.value.mediaList.map { it.id })
        assertEquals("Favorites", viewModel.uiState.value.albumName)
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockMediaRepo: MediaRepository
    private lateinit var mockAlbumRepo: AlbumRepository
    private lateinit var viewModel: MediaViewerViewModel

    private val sampleItems = listOf(
        MediaItem(
            id = "photo_1",
            filename = "SV_01012026_1200.jpg",
            originalName = "SV_01012026_1200.jpg",
            mediaType = MediaType.PHOTO,
            mimeType = "image/jpeg",
            encryptedPath = "/path/p1.enc",
            thumbnailPath = "/path/p1.thumb",
            sizeBytes = 1024,
            createdAt = 1000L,
            albumId = "album_camera"
        ),
        MediaItem(
            id = "video_1",
            filename = "SV_01012026_1201.mp4",
            originalName = "SV_01012026_1201.mp4",
            mediaType = MediaType.VIDEO,
            mimeType = "video/mp4",
            encryptedPath = "/path/v1.enc",
            thumbnailPath = "/path/v1.thumb",
            sizeBytes = 2048,
            createdAt = 2000L,
            albumId = "album_camera"
        )
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockMediaRepo = mockk(relaxed = true)
        mockAlbumRepo = mockk(relaxed = true)
        coEvery { mockMediaRepo.getMedia(any(), any()) } returns flowOf(sampleItems)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialIndexResolvesToSelectedId() = runTest(testDispatcher) {
        viewModel = MediaViewerViewModel(
            albumId = "album_camera",
            initialMediaId = "video_1",
            mediaRepository = mockMediaRepo,
            albumRepository = mockAlbumRepo
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.mediaList.size)
        assertEquals(1, viewModel.uiState.value.currentIndex)
        assertEquals("video_1", viewModel.getCurrentItem()?.id)
    }

    @Test
    fun testSetAsAlbumCover() = runTest(testDispatcher) {
        viewModel = MediaViewerViewModel(
            albumId = "album_camera",
            initialMediaId = "photo_1",
            mediaRepository = mockMediaRepo,
            albumRepository = mockAlbumRepo
        )
        advanceUntilIdle()

        viewModel.setAsAlbumCover()
        advanceUntilIdle()

        coVerify { mockAlbumRepo.setAlbumCover("album_camera", "photo_1") }
    }

    @Test
    fun testMoveCurrentItem() = runTest(testDispatcher) {
        viewModel = MediaViewerViewModel(
            albumId = "album_camera",
            initialMediaId = "photo_1",
            mediaRepository = mockMediaRepo,
            albumRepository = mockAlbumRepo
        )
        advanceUntilIdle()

        viewModel.moveToAlbum("custom_vacation")
        advanceUntilIdle()

        coVerify { mockMediaRepo.moveMediaToAlbum(listOf("photo_1"), "custom_vacation") }
    }
}
