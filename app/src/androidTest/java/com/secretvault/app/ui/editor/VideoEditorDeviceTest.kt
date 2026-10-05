package com.secretvault.app.ui.editor

import android.media.MediaMetadataRetriever
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.SystemClock
import android.graphics.Rect
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
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
import com.secretvault.app.core.processing.VideoProject
import com.secretvault.app.core.processing.StickerSource
import com.secretvault.app.core.processing.Adjustment
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
    fun projectTimelineScrollZoomAndUndoUseGeneratedMedia() = runBlocking {
        withTimeout(120_000) {
            while (app.getSystemService(KeyguardManager::class.java).isDeviceLocked) delay(250)
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val pin = requireNotNull(InstrumentationRegistry.getArguments().getString("vaultPin")) { "Pass the locally authorized vault PIN as a test argument" }
        val directory = File(app.cacheDir, "project-layout-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(directory, "input.mp4")
        val encrypted = File(directory, "source.enc")
        val manager = app.videoEditManager
        val previousDraft = manager.draft
        val previousPosition = manager.draftPositionMs
        val previousHistory = manager.draftHistory
        val biometric = app.pinManager.isBiometricEnabled()
        val keepOpen = app.sessionManager.keepUnlocked.value
        val wasUnlocked = app.sessionManager.isUnlocked.value
        try {
            instrumentation.context.assets.open("video-edit-fixture.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            app.cryptoEngine.encryptFile(input, encrypted)
            val item = fixtureItem(encrypted).copy(id = directory.name, originalName = directory.name)
            app.mediaRepository.insertAll(listOf(item))
            val project = VideoProject().add(item, 12_000).trim(0, 0, 6_000).add(item, 12_000).trim(1, 6_000, 12_000)
                .addSticker(StickerSource.Emoji("😀"), 2_000).addSticker(StickerSource.Emoji("❤️"), 3_000)
            instrumentation.runOnMainSync {
                app.pinManager.setBiometricEnabled(false)
                app.sessionManager.setKeepUnlocked(false)
                manager.keepDraft(project, 0)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue("Decoy unavailable: window=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}, " +
                    "locked=${app.getSystemService(KeyguardManager::class.java).isDeviceLocked}, " +
                    "interactive=${app.getSystemService(android.os.PowerManager::class.java).isInteractive}", waitForText("San Francisco"))
                repeat(8) {
                    if (findNode("Visibility") == null) {
                        val metrics = app.resources.displayMetrics
                        swipe(metrics.widthPixels / 2f, metrics.heightPixels * 0.8f, metrics.widthPixels / 2f, metrics.heightPixels * 0.3f)
                        SystemClock.sleep(200)
                    }
                }
                clickNode("Visibility")
                assertTrue(waitForText("Enter PIN"))
                pin.forEach { clickNode(it.toString()); SystemClock.sleep(120) }
                assertTrue(waitForText("Export"))
                assertNotNull(findNode("Video timeline"))
                fun waitForProject(message: String, check: (VideoProject) -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < deadline && manager.draft?.let(check) != true) SystemClock.sleep(50)
                    assertTrue(message, manager.draft?.let(check) == true)
                }
                fun bounds(label: String) = Rect().also { requireNotNull(findNode(label)).getBoundsInScreen(it) }
                val addBefore = bounds("Add videos")
                val muteBefore = bounds("Mute clip 1")
                clickNode("Mute clip 1")
                waitForProject("Mute did not affect its clip") { it.clips[0].muted }
                clickNode("Undo")
                waitForProject("Undo did not restore sound") { !it.clips[0].muted }
                clickNode("Redo")
                waitForProject("Redo did not restore mute") { it.clips[0].muted }
                clickNode("Undo")
                waitForProject("Undo did not restore sound") { !it.clips[0].muted }
                SystemClock.sleep(500)
                fun seek(ms: Float) {
                    assertTrue(requireNotNull(findNode("Video timeline")).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ms) }))
                    SystemClock.sleep(500)
                    assertEquals("Preview did not seek", ms.toDouble(), manager.draftPositionMs.toDouble(), 50.0)
                }
                seek(500f)
                val muteScrolled = bounds("Mute clip 1")
                assertTrue("Mute stayed fixed instead of following its clip", muteScrolled.left < muteBefore.left)
                assertEquals("Add moved with the timeline", addBefore, bounds("Add videos"))
                val timeline = bounds("Video timeline")
                val dp = app.resources.displayMetrics.density
                val y = timeline.top + 96 * dp
                val center = timeline.centerX().toFloat()
                pinch(center, y, 24 * dp, 48 * dp)
                SystemClock.sleep(500)
                assertTrue("Timeline pinch did not change scale", bounds("Mute clip 1").left < muteScrolled.left - (8 * dp).toInt())
                assertEquals("Pinching the ruler edited the project", project, manager.draft)
                seek(0f)
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                try {
                    SystemClock.sleep(500)
                    fun screenshot(name: String) {
                        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try { File(app.getExternalFilesDir(null), name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                        finally { image.recycle() }
                    }
                    screenshot("video-editor-layout.png")
                    seek(3_000f)
                    screenshot("video-editor-scrolled.png")
                } finally { scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
                val start = manager.draftPositionMs
                swipe(center + 60 * dp, y, center + 24 * dp, y)
                SystemClock.sleep(500)
                assertTrue("Timeline swipe did not scrub", manager.draftPositionMs > start)
                assertEquals(addBefore, bounds("Add videos"))
                seek(0f)
                swipe(center + 2 * dp, timeline.top + 60 * dp, center + 48 * dp, timeline.top + 60 * dp)
                waitForProject("Clip edge did not trim") { it.clips[0].startMs > 0 }
                clickNode("Undo")
                waitForProject("One undo did not restore the whole trim gesture") { it.clips[0].startMs == 0L }
                seek(3_000f)
                val stickerAction = requireNotNull(findNode("Video timeline"))
                assertTrue(stickerAction.performAction(stickerAction.actionList.first { it.label?.toString() == "Select sticker 1" }.id))
                SystemClock.sleep(150)
                swipe(center, y, center + 24 * dp, y)
                waitForProject("Sticker track did not move") { it.stickers[0].startMs > 2_000 }
                clickNode("Undo")
                waitForProject("One undo did not restore the whole sticker gesture") { it.stickers[0].startMs == 2_000L }
                seek(3_000f)
                clickNode("Split")
                waitForProject("Split did not create a clip") { it.clips.size == 3 }
                clickNode("Undo")
                waitForProject("Undo did not join the split") { it.clips.size == 2 }
                clickNode("Redo")
                waitForProject("Redo did not restore the split") { it.clips.size == 3 }
                clickNode("Undo")
                waitForProject("Undo did not join the split") { it.clips.size == 2 }
                fun selectFirst() {
                    val node = requireNotNull(findNode("Video timeline"))
                    assertTrue(node.performAction(node.actionList.first { it.label?.toString() == "Select clip 1" }.id))
                    SystemClock.sleep(150)
                }
                selectFirst()
                clickNode("Right")
                waitForProject("Right did not reorder clips") { it.clips[1].id == project.clips[0].id }
                clickNode("Left")
                waitForProject("Left did not restore clip order") { it.clips[0].id == project.clips[0].id }
                clickNode("Delete")
                waitForProject("Delete did not remove the clip") { it.clips.size == 1 }
                clickNode("Delete")
                waitForProject("Deleting the last clip lost the undo draft") { it.clips.isEmpty() }
                clickNode("Undo")
                waitForProject("Undo did not restore the last clip") { it.clips.size == 1 }
                clickNode("Undo")
                waitForProject("Undo did not restore the clip") { it.clips.size == 2 }
                clickNode("Add videos")
                assertTrue(waitForText(item.originalName))
                clickNode(item.originalName)
                clickNode("Add (1)")
                waitForProject("Add did not append the selected video") { it.clips.size == 3 }
                clickNode("Undo")
                waitForProject("Undo did not remove the added clip") { it.clips.size == 2 }
                selectFirst()
                clickNode("Size")
                val zoom = bounds("Zoom")
                swipe(zoom.left + zoom.width() * 0.3f, zoom.exactCenterY(), zoom.left + zoom.width() * 0.6f, zoom.exactCenterY())
                waitForProject("Zoom slider did not change framing; bounds=$zoom") { it.clips[0].framing.zoom > 1.2f }
                clickNode("Undo")
                waitForProject("One undo did not restore the whole slider gesture") { it.clips[0].framing.zoom == 1f }
                val rotate = bounds("Rotate")
                swipe(rotate.left + rotate.width() * 0.5f, rotate.exactCenterY(), rotate.left + rotate.width() * 0.6f, rotate.exactCenterY())
                waitForProject("Rotate slider did not change framing") { it.clips[0].framing.angle != 0f }
                clickNode("Undo")
                waitForProject("One undo did not restore the rotate gesture") { it.clips[0].framing.angle == 0f }
                clickNode("Rotate 90°")
                waitForProject("Rotation did not change framing") { it.clips[0].framing.angle == 90f }
                clickNode("Fill")
                waitForProject("Fill did not cover the frame") { it.clips[0].framing.zoom > 1f }
                clickNode("Fit")
                waitForProject("Fit did not reset zoom") { it.clips[0].framing.zoom == 1f }
                clickNode("Reset")
                waitForProject("Reset did not clear framing") { it.clips[0].framing.angle == 0f }
                clickNode("Done")
                clickNode("Undo")
                waitForProject("Undo did not restore framing") { it.clips[0].framing.angle == 90f }
                clickNode("Size"); clickNode("Reset"); clickNode("Done")
                clickNode("Adjust")
                val brightness = bounds("Brightness")
                swipe(brightness.left + brightness.width() * 0.5f, brightness.exactCenterY(), brightness.left + brightness.width() * 0.7f, brightness.exactCenterY())
                waitForProject("Adjustment slider did not change the clip") { !it.clips[0].adjustments.isNone }
                clickNode("Apply to all clips")
                waitForProject("Apply to all did not copy adjustments") { it.clips[0].adjustments == it.clips[1].adjustments }
                clickNode("Reset")
                waitForProject("Adjustment Reset did not clear brightness") { it.clips[0].adjustments.isNone }
                clickNode("Contrast")
                val contrast = bounds("Contrast")
                swipe(contrast.left + contrast.width() * 0.5f, contrast.exactCenterY(), contrast.left + contrast.width() * 0.7f, contrast.exactCenterY())
                waitForProject("Contrast did not change the clip") { !it.clips[0].adjustments.isNone }
                clickNode("Reset all")
                waitForProject("Reset all did not clear adjustments") { it.clips[0].adjustments.isNone }
                Adjustment.entries.forEach { adjustment ->
                    repeat(4) {
                        if (findNode(adjustment.label) == null) {
                            swipe(app.resources.displayMetrics.widthPixels * 0.85f, contrast.top - 24 * dp,
                                app.resources.displayMetrics.widthPixels * 0.2f, contrast.top - 24 * dp)
                            SystemClock.sleep(150)
                        }
                    }
                    clickNode(adjustment.label)
                }
                clickNode("Done")
                val toolsY = bounds("Split").exactCenterY()
                swipe(app.resources.displayMetrics.widthPixels * 0.85f, toolsY, app.resources.displayMetrics.widthPixels * 0.2f, toolsY)
                clickNode("Sticker")
                assertTrue(waitForText("Add sticker"))
                clickNode("😀")
                waitForProject("Sticker picker did not add a track") { it.stickers.size == 3 }
                clickNode("Add key")
                waitForProject("Add key did not create a keyframe") { it.stickers.last().keys.size == 1 }
                clickNode("Remove key")
                waitForProject("Remove key did not remove the keyframe") { it.stickers.last().keys.isEmpty() }
                clickNode("Delete")
                waitForProject("Sticker Delete did not remove its track") { it.stickers.size == 2 }
                clickNode("Undo")
                waitForProject("Undo did not restore the sticker") { it.stickers.size == 3 }
                clickNode("Back")
                assertTrue(waitForText("Discard changes?"))
                clickNode("Keep editing")
                assertTrue(waitForText("Export"))
                clickNode("Play")
                assertTrue(waitForText("Pause"))
                clickNode("Pause")
                clickNode("Next clip"); clickNode("Previous clip")
                clickNode("Export")
                assertTrue(waitForText("Cutting video"))
                clickNode("Cancel")
                assertTrue(waitForText("Export"))
                instrumentation.sendStatus(0, Bundle().apply { putString("editorLayoutResult", "Fixed Add; scrolling Mute; timeline seek/swipe/pinch; split, mute, framing and sticker undo/redo; discard recovery; generated-media screenshots") })
            }
        } finally {
            instrumentation.runOnMainSync {
                manager.keepDraft(previousDraft ?: VideoProject(), previousPosition, previousHistory)
                app.pinManager.setBiometricEnabled(biometric)
                app.sessionManager.unlock()
                app.sessionManager.setKeepUnlocked(keepOpen)
                if (!wasUnlocked) app.sessionManager.lock()
            }
            app.mediaRepository.getMedia().first().firstOrNull { it.id == directory.name }?.let {
                app.mediaRepository.deleteMedia(listOf(it))
            }
            directory.listFiles().orEmpty().forEach { it.delete() }
            directory.delete()
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, x1, y1)
        repeat(10) { val fraction = (it + 1) / 10f; SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction) }
        send(MotionEvent.ACTION_UP, x2, y2)
    }

    private fun pinch(x: Float, y: Float, from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        fun send(action: Int, distance: Float, count: Int = 2) {
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = x + if (index == 0) -distance else distance; this.y = y; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, from, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), from)
        repeat(10) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, from + (to - from) * (it + 1) / 10f) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), to)
        send(MotionEvent.ACTION_UP, to, 1)
    }

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
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (matches(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it, matches)?.let { found -> return found } }
            return null
        }
        fun search(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            return find(root) { it.contentDescription?.toString() == text }
                ?: find(root) { it.text?.toString()?.equals(text, ignoreCase = true) == true }
                ?: find(root) { it.text?.toString()?.contains(text, ignoreCase = true) == true }
                ?: find(root) { it.contentDescription?.toString()?.contains(text) == true }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
            ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { window ->
                window.root?.takeIf { it.packageName == app.packageName }?.let(::search)
            }
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
        assertTrue("Missing control: $text", waitForText(text))
        var node = findNode(text) ?: error("Missing control: $text")
        while (!node.isClickable) node = node.parent ?: error("Control cannot be clicked: $text")
        node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(100)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }
}
