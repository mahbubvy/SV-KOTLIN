package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.graphics.Movie
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.StickerImage
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.processing.Clip
import com.secretvault.app.core.processing.Sticker
import com.secretvault.app.core.processing.StickerSource
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.worker.VideoEditState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoMaskDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp

    @Test
    fun missingFaceCoveringFailsExportWithoutSavingAnUnmaskedVideo() = runBlocking {
        val manager = app.videoEditManager
        assertNull("Finish any existing export before this test", manager.state.value)
        val previousDraft = manager.draft
        val previousPosition = manager.draftPositionMs
        val previousHistory = manager.draftHistory
        val beforeIds = app.mediaRepository.getMedia().first().mapTo(HashSet()) { it.id }
        val beforeTemps = app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("video_edit_") }.mapTo(HashSet()) { it.name }
        val directory = File(app.cacheDir, "video-mask-test-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(directory, "input.mp4")
        val encrypted = File(directory, "source.enc")
        var started = false
        try {
            instrumentation.context.assets.open("video-edit-fixture.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            app.cryptoEngine.encryptFile(input, encrypted)
            val originalBytes = encrypted.readBytes()
            val video = MediaItem(directory.name, "${directory.name}.mp4", "Generated mask test video",
                MediaType.VIDEO, "video/mp4", encrypted.absolutePath, null, encrypted.length(),
                durationMs = 12_000, width = 640, height = 360, albumId = AlbumEntity.ALBUM_CAMERA_ID)
            val missingPhoto = video.copy(id = "${directory.name}-mask", mediaType = MediaType.PHOTO,
                mimeType = "image/png", encryptedPath = File(directory, "missing-mask.enc").absolutePath)
            val project = VideoProject(listOf(Clip(video, 12_000,
                regions = listOf(Sticker(StickerSource.Photo(missingPhoto), 0, 12_000)))))
            instrumentation.runOnMainSync {
                manager.keepDraft(project, 1_000)
                started = true
                manager.start(project)
            }
            val result = withTimeout(60_000) {
                manager.state.first { it is VideoEditState.Done || it is VideoEditState.Failed }
            }
            instrumentation.runOnMainSync { }
            assertTrue("Missing face covering produced an export: $result", result is VideoEditState.Failed)
            assertEquals(beforeIds, app.mediaRepository.getMedia().first().mapTo(HashSet()) { it.id })
            assertEquals(beforeTemps, app.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("video_edit_") }.mapTo(HashSet()) { it.name })
            assertArrayEquals(originalBytes, encrypted.readBytes())
            assertEquals(project, manager.draft)
        } finally {
            if (started) {
                instrumentation.runOnMainSync { manager.cancel() }
                withTimeout(60_000) { manager.state.first { it !is VideoEditState.Running } }
                (manager.state.value as? VideoEditState.Done)?.item?.let {
                    app.mediaRepository.deleteMedia(listOf(it))
                }
                instrumentation.runOnMainSync {
                    manager.clearResult()
                    manager.keepDraft(previousDraft ?: VideoProject(), previousPosition, previousHistory)
                }
            }
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun bundledGifChangesFramesAndLoopsWithoutChangingItsPoster() {
        val asset = "stickers/grid-pixel-01.gif"
        val duration = app.assets.open(asset).use { requireNotNull(Movie.decodeStream(it)).duration() }
        assertTrue(duration > 0)
        val image = requireNotNull(StickerImage.load(app, app.cryptoEngine, StickerSource.Pack(asset)))
        fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
        val bitmap = image.frameAt(0)
        val first = bitmap.pixels()
        val poster = image.poster.pixels()
        try {
            assertTrue("Animated pack sticker stayed on its first frame", (1..40).any {
                !first.contentEquals(image.frameAt(duration.toLong() * it / 41).pixels())
            })
            assertSame(bitmap, image.frameAt(duration.toLong()))
            assertArrayEquals(first, bitmap.pixels())
            assertArrayEquals(poster, image.poster.pixels())
        } finally {
            bitmap.recycle()
            image.poster.recycle()
        }
    }
}
