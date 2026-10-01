package com.secretvault.app.ui.editor

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.processing.VideoSegment
import com.secretvault.app.core.worker.VideoEditState
import com.secretvault.app.ui.theme.SecretVaultTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoEditorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp

    @Test
    fun unreadablePreviewShowsErrorAndAllowsRetryAndBack() {
        val invalid = File(app.cacheDir, "editor-invalid-${UUID.randomUUID()}.enc")
        invalid.writeText("Invalid encrypted video fixture")
        var returned = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        SecretVaultTheme {
                            VideoEditorScreen(app, fixtureItem(invalid)) { returned = true }
                        }
                    }
                }
                assertTrue(waitForText("Could not open this video."))
                assertNull(findNode("Loading video…"))
                clickNode("Retry")
                SystemClock.sleep(300)
                assertTrue(waitForText("Could not open this video."))
                clickNode("Back")
                instrumentation.waitForIdleSync()
                assertTrue(returned)
            }
        } finally {
            invalid.delete()
        }
    }

    @Test
    fun trimRemoveSectionsAndCancelPreserveTheOriginal() = runBlocking {
        val directory = File(app.cacheDir, "editor-device-test-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(directory, "input.mp4")
        val encrypted = File(directory, "source.enc")
        val outputs = mutableListOf<MediaItem>()
        try {
            instrumentation.context.assets.open("video-edit-fixture.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            app.cryptoEngine.encryptFile(input, encrypted)
            val originalBytes = encrypted.readBytes()
            val original = fixtureItem(encrypted)
            val manager = app.videoEditManager
            suspend fun export(segments: List<VideoSegment>, expectedMs: Long) {
                instrumentation.runOnMainSync { manager.clearResult(); manager.start(original, segments) }
                val result = withTimeout(60_000) {
                    manager.state.first { it is VideoEditState.Done || it is VideoEditState.Failed }
                }
                assertTrue("Export failed: $result", result is VideoEditState.Done)
                val saved = (result as VideoEditState.Done).item
                outputs += saved
                assertEquals(original.albumId, saved.albumId)
                val plaintext = File(directory, "result.mp4")
                app.cryptoEngine.decryptFile(File(saved.encryptedPath), plaintext)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(plaintext.absolutePath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertEquals(expectedMs.toDouble(), duration.toDouble(), 100.0)
                    assertNotNull(retriever.frameAtTime)
                    val shared = File(app.cacheDir, "vault_shared/editor-test-${UUID.randomUUID()}.mp4")
                    try {
                        shared.parentFile!!.mkdirs()
                        plaintext.copyTo(shared)
                        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", shared)
                        app.contentResolver.openInputStream(uri)!!.use {
                            assertArrayEquals(plaintext.readBytes(), it.readBytes())
                        }
                    } finally {
                        shared.delete()
                    }
                } finally {
                    retriever.release()
                    plaintext.delete()
                }
                assertArrayEquals(originalBytes, encrypted.readBytes())
            }
            export(listOf(VideoSegment(2_000, 8_000)), 6_000)
            export(listOf(VideoSegment(0, 2_000), VideoSegment(4_000, 12_000)), 10_000)
            export(listOf(VideoSegment(0, 2_000), VideoSegment(4_000, 7_000), VideoSegment(9_000, 12_000)), 8_000)
            assertTrue(outputs.first().filename.endsWith("_edited.mp4"))
            assertTrue(outputs[1].filename.endsWith("_edited_1.mp4"))
            assertTrue(outputs[2].filename.endsWith("_edited_2.mp4"))

            instrumentation.runOnMainSync {
                manager.clearResult()
                manager.start(original, listOf(VideoSegment(0, 2_000), VideoSegment(4_000, 12_000)))
            }
            delay(100)
            instrumentation.runOnMainSync { manager.cancel() }
            withTimeout(10_000) { manager.state.first { it !is VideoEditState.Running } }
            assertNull(manager.state.value)
            assertArrayEquals(originalBytes, encrypted.readBytes())
            assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("video_edit_") })
        } finally {
            app.mediaRepository.deleteMedia(outputs)
            app.videoEditManager.clearResult()
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    private fun fixtureItem(file: File) = MediaItem(
        id = "editor-device-test", filename = "device_test_${file.parentFile!!.name}.mp4",
        originalName = "Generated test video", mediaType = MediaType.VIDEO, mimeType = "video/mp4",
        encryptedPath = file.absolutePath, thumbnailPath = null, sizeBytes = file.length(),
        durationMs = 12_000, width = 640, height = 360, albumId = AlbumEntity.ALBUM_CAMERA_ID
    )

    private fun findNode(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun waitForText(text: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findNode(text) != null) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun clickNode(text: String) {
        var node = findNode(text) ?: error("Missing control: $text")
        while (!node.isClickable) node = node.parent ?: error("Control cannot be clicked: $text")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
