package com.secretvault.app.ui

import android.app.KeyguardManager
import android.graphics.*
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.*
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.*
import com.secretvault.app.core.model.*
import com.secretvault.app.ui.navigation.*
import com.secretvault.app.ui.theme.SecretVaultTheme
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GalleryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp

    @Test fun encryptedThumbnailKeepsOldPreviewDuringUpgrade(): Unit = runBlocking {
        waitUntil("Unlock Pixel for thumbnail UI check", 120000) { !app.getSystemService(KeyguardManager::class.java).isDeviceLocked }
        val directory = File(app.cacheDir, "thumbnail-continuity-${UUID.randomUUID()}").apply { mkdirs() }
        val old = File(directory, "old.thumb")
        val upgraded = File(directory, "new.thumb")
        fun encryptedColor(file: File, color: Int) {
            val bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            bitmap.recycle()
            try { file.writeBytes(app.cryptoEngine.encryptBytes(bytes)) } finally { bytes.fill(0) }
        }
        encryptedColor(old, Color.RED); encryptedColor(upgraded, Color.BLUE)
        val originalLoader = coil.Coil.imageLoader(app)
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val loader = coil.ImageLoader.Builder(app).components {
            add(EncryptedThumbnailKeyer())
            add(object : coil.fetch.Fetcher.Factory<EncryptedMediaUri> {
                override fun create(data: EncryptedMediaUri, options: coil.request.Options, imageLoader: coil.ImageLoader) = object : coil.fetch.Fetcher {
                    override suspend fun fetch(): coil.fetch.FetchResult? {
                        if (data.filePath == upgraded.absolutePath) { started.set(true); release.await() }
                        val result = EncryptedThumbnailFetcher(data, options, app.cryptoEngine).fetch()
                        val bitmap = ((result as coil.fetch.DrawableResult).drawable as android.graphics.drawable.BitmapDrawable).bitmap
                        assertTrue("Grid image decoded at full screen size", bitmap.width <= 768 && bitmap.height <= 768)
                        return result
                    }
                }
            })
        }.build()
        val path = mutableStateOf(old.absolutePath)
        try {
            coil.Coil.setImageLoader(loader)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { SecretVaultTheme {
                    Box(Modifier.fillMaxSize()) {
                        com.secretvault.app.ui.gallery.components.StableEncryptedThumbnail("synthetic", path.value, "Synthetic preview", Modifier.fillMaxSize())
                    }
                } } }
                wait("Synthetic preview"); SystemClock.sleep(1000)
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                try {
                    fun centerColor(): Int {
                        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try { return image.getPixel(image.width / 2, image.height / 2) } finally { image.recycle() }
                    }
                    waitUntil("Initial synthetic preview did not load") { Color.red(centerColor()) > 200 }
                    scenario.onActivity { path.value = upgraded.absolutePath }
                    waitUntil("Upgrade fetch did not start") { started.get() }
                    repeat(4) {
                        SystemClock.sleep(150)
                        assertTrue("Thumbnail cleared while replacement was loading", Color.red(centerColor()) > 200)
                    }
                    release.complete(Unit)
                    waitUntil("Upgraded synthetic preview did not load") { Color.blue(centerColor()) > 200 }
                } finally {
                    scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                }
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("galleryResult", "Synthetic encrypted preview remained visible throughout delayed upgrade; grid decode bounded to 768px") })
        } finally {
            release.complete(Unit); coil.Coil.setImageLoader(originalLoader); loader.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }

    @Test fun populatedCameraFolderDoesNotFlashEmptyOrRestartVisibleImages(): Unit = runBlocking {
        waitUntil("Unlock Pixel for camera-folder stress check", 120000) { !app.getSystemService(KeyguardManager::class.java).isDeviceLocked }
        val suffix = UUID.randomUUID().toString()
        val directory = File(app.cacheDir, "camera-grid-$suffix").apply { mkdirs() }
        val source = File(directory, "source.enc")
        val bitmap = Bitmap.createBitmap(640, 960, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(150, 45, 45)) }
        val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it); it.toByteArray() }
        bitmap.recycle()
        source.writeBytes(app.cryptoEngine.encryptBytes(bytes)); bytes.fill(0)
        val fixtures = (0 until 96).map { index ->
            val original = File(directory, "photo_${suffix}_$index.enc"); source.copyTo(original)
            val thumbnail = File(directory, "photo_${suffix}_$index.thumb"); source.copyTo(thumbnail)
            MediaItem("camera-grid-$suffix-$index", "Camera grid test $index.jpg", "Synthetic", MediaType.PHOTO, "image/jpeg",
                original.absolutePath, thumbnail.absolutePath, original.length(), width = 640, height = 960,
                albumId = AlbumEntity.ALBUM_CAMERA_ID, createdAt = System.currentTimeMillis() + 60000 + index)
        }
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val fetches = java.util.concurrent.atomic.AtomicInteger()
        val originalLoader = coil.Coil.imageLoader(app)
        val loader = coil.ImageLoader.Builder(app).components {
            add(EncryptedThumbnailKeyer())
            add(object : coil.fetch.Fetcher.Factory<EncryptedMediaUri> {
                override fun create(data: EncryptedMediaUri, options: coil.request.Options, imageLoader: coil.ImageLoader) = object : coil.fetch.Fetcher {
                    override suspend fun fetch(): coil.fetch.FetchResult? {
                        if (data.filePath.contains(suffix)) fetches.incrementAndGet()
                        return EncryptedThumbnailFetcher(data, options, app.cryptoEngine).fetch()
                    }
                }
            })
        }.build()
        val repository = object : com.secretvault.app.data.repository.MediaRepository by app.mediaRepository {
            override fun getMedia(albumId: String?, sortOrder: SortOrder) = kotlinx.coroutines.flow.flow {
                kotlinx.coroutines.delay(600)
                app.mediaRepository.getMedia(albumId, sortOrder).collect { emit(it) }
            }
        }
        val gallery = com.secretvault.app.ui.gallery.GalleryViewModel(repository)
        val albums = com.secretvault.app.ui.gallery.AlbumsViewModel(app.albumRepository)
        val settingsClicks = java.util.concurrent.atomic.AtomicInteger()
        val lockClicks = java.util.concurrent.atomic.AtomicInteger()
        try {
            app.mediaRepository.insertAll(fixtures); coil.Coil.setImageLoader(loader)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    app.sessionManager.unlock()
                    activity.setContent { SecretVaultTheme {
                        com.secretvault.app.ui.gallery.GalleryScreen(app, gallery, albums, {}, {}, { lockClicks.incrementAndGet() },
                            { settingsClicks.incrementAndGet() }, {})
                    } }
                }
                wait("Settings")
                val settingsBounds = Rect().also { wait("Settings").getBoundsInScreen(it) }
                val lockBounds = Rect().also { wait("Lock Vault").getBoundsInScreen(it) }
                assertTrue("Settings did not replace the rightmost lock position", settingsBounds.left > lockBounds.right)
                click("Settings"); click("Lock Vault")
                assertEquals(1, settingsClicks.get()); assertEquals(1, lockClicks.get())
                repeat(3) {
                    click("Camera")
                    wait("Loading media…")
                    assertTrue("Populated camera folder flashes an empty message", find { it.text?.toString() == "No media in Camera" } == null)
                    wait("Camera grid test 95.jpg")
                    if (it < 2) click("Back")
                }
                repeat(3) { find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(1500) }
                repeat(3) { find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(1500) }
                wait("Camera grid test 95.jpg")
                waitUntil("Visible preview upgrade did not finish") { gallery.uiState.value.mediaList.take(12).all { it.thumbnailPath?.endsWith(GALLERY_THUMBNAIL_SUFFIX) == true } }
                SystemClock.sleep(1500)
                val before = fetches.get()
                repeat(6) { scenario.onActivity { gallery.toggleItemSelection(fixtures.last().id) }; SystemClock.sleep(120) }
                assertEquals("Selection recomposition restarted unchanged thumbnails", before, fetches.get())
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("galleryResult", "96 synthetic Camera items; three folder reentries without false empty state; scrolling and live upgrades; stable visible requests across six selection updates; Settings/Lock positions and callbacks pass") })
            }
        } finally {
            coil.Coil.setImageLoader(originalLoader); loader.shutdown()
            app.mediaRepository.deleteByIds(fixtures.map { it.id })
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
            if (!wasUnlocked) app.sessionManager.lock()
        }
    }

    @Test fun thumbnailCropKeepsCirclesRoundAndDoesNotRecycleOriginal() {
        val source = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        Canvas(source).drawCircle(360f, 640f, 180f, Paint().apply { color = Color.WHITE })
        val thumbnail = createGalleryThumbnail(source)
        try {
            assertEquals(512, thumbnail.width); assertEquals(512, thumbnail.height)
            val horizontal = (0 until 512).count { Color.red(thumbnail.getPixel(it, 256)) > 128 }
            val vertical = (0 until 512).count { Color.red(thumbnail.getPixel(256, it)) > 128 }
            assertTrue("Thumbnail distorts a circle", kotlin.math.abs(horizontal - vertical) <= 2)
            assertFalse(source.isRecycled)
        } finally { source.recycle(); thumbnail.recycle() }
    }

    @Test fun galleryZoomFavoritesAndEncryptedPreviewUpgrade(): Unit = runBlocking {
        waitUntil("Unlock the phone locally for gallery UI tests", 120000) {
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
                app.getSystemService(android.os.PowerManager::class.java).isInteractive
        }
        val suffix = UUID.randomUUID().toString()
        val directory = File(app.cacheDir, "gallery-test-$suffix").apply { mkdirs() }
        val album = AlbumEntity("gallery-test-$suffix", "Gallery test", false)
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val fixtures = mutableListOf<MediaItem>()
        val existingFavoriteCount = app.mediaRepository.getMedia(null).first().count { it.isFavorite }
        try {
            app.database.albumDao().insert(album)
            val photoFile = File(directory, "photo_$suffix.enc")
            val jpeg = File(directory, "source.jpg")
            val bitmap = Bitmap.createBitmap(1200, 2000, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(30, 40, 50))
                drawCircle(600f, 1000f, 300f, Paint().apply { color = Color.WHITE })
            }
            jpeg.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
            app.cryptoEngine.encryptFile(jpeg, photoFile)
            val photo = MediaItem("photo_$suffix", "Gallery test photo.jpg", "Synthetic circle", MediaType.PHOTO, "image/jpeg",
                photoFile.absolutePath, null, photoFile.length(), width = 1200, height = 2000, albumId = album.id, createdAt = 2)
            val originalVideo = File(directory, "input.mp4")
            instrumentation.context.assets.open("video-edit-fixture.mp4").use { input -> originalVideo.outputStream().use { input.copyTo(it) } }
            val portraitVideo = File(directory, "portrait.mp4")
            rotateVideo(originalVideo, portraitVideo)
            val videoFile = File(directory, "video_$suffix.enc")
            app.cryptoEngine.encryptFile(portraitVideo, videoFile)
            val video = MediaItem("video_$suffix", "Gallery test video.mp4", "Synthetic video", MediaType.VIDEO, "video/mp4",
                videoFile.absolutePath, null, videoFile.length(), durationMs = 12000, width = 360, height = 640, albumId = album.id, createdAt = 1)
            fixtures += listOf(photo, video)
            fixtures.forEach { app.mediaRepository.insertMedia(it) }
            val originalBytes = fixtures.associate { it.id to File(it.encryptedPath).readBytes() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    app.sessionManager.unlock()
                    activity.setContent { SecretVaultTheme {
                    val nav = rememberNavController()
                    VaultNavGraph(app, navController = nav)
                    LaunchedEffect(Unit) { nav.navigate(Screen.VaultHome.route) }
                } } }
                openTestAlbum()
                waitUntil("Thumbnail refresh did not complete") {
                    fixtures.all { item -> runBlocking { app.mediaRepository.getMediaById(item.id) }?.thumbnailPath?.endsWith(".gallery2.thumb") == true }
                }
                fixtures.forEach { item ->
                    val updated = requireNotNull(app.mediaRepository.getMediaById(item.id))
                    val thumbnailBytes = app.cryptoEngine.decryptBytes(File(requireNotNull(updated.thumbnailPath)).readBytes())
                    val thumbnail = requireNotNull(BitmapFactory.decodeByteArray(thumbnailBytes, 0, thumbnailBytes.size))
                    try {
                        assertEquals(thumbnail.width, thumbnail.height)
                        assertTrue("Thumbnail is still undersized", thumbnail.width >= if (item.mediaType == MediaType.PHOTO) 512 else 360)
                    } finally { thumbnail.recycle(); thumbnailBytes.fill(0) }
                    assertArrayEquals(originalBytes[item.id], File(item.encryptedPath).readBytes())
                }
                capture(scenario)
                click(photo.filename)
                waitZoom(1f)
                pinch()
                waitUntil("Photo pinch did not zoom") { zoomScale() > 1.2f }
                resetZoom()
                doubleTap()
                waitZoom(2.5f)
                click("Rotate photo 90 degrees clockwise")
                waitZoom(1f)
                click("Add to favorites")
                wait("Remove from favorites")
                assertTrue(requireNotNull(app.mediaRepository.getMediaById(photo.id)).isFavorite)
                click("Back")
                wait("Gallery test")
                click("Back")
                click("Favorites")
                click(photo.filename)
                wait("Remove from favorites")
                click("Remove from favorites")
                wait("Add to favorites")
                click("Back")
                if (existingFavoriteCount == 0) wait("No favorites yet") else wait("Favorites")
                assertFalse(requireNotNull(app.mediaRepository.getMediaById(photo.id)).isFavorite)
                click("Back")
                openTestAlbum()
                click(video.filename)
                waitZoom(1f)
                tapMedia()
                pinch()
                waitUntil("Video pinch did not zoom") { zoomScale() > 1.2f }
                val before = playerPosition(scenario)
                SystemClock.sleep(2500)
                assertTrue("Video stopped playing while zoomed", playerPosition(scenario) > before + 1500)
                resetZoom()
                doubleTap()
                waitZoom(2.5f)
                resetZoom()
                tapMedia()
                click("Rotate video 90 degrees clockwise")
                waitZoom(1f)
                click("Back")
                wait("Gallery test")
            }
        } finally {
            fixtures.forEach { app.mediaRepository.getMediaById(it.id)?.let { updated -> app.mediaRepository.deleteMedia(listOf(updated)) } }
            app.database.albumDao().deleteCustomAlbum(album.id)
            directory.listFiles().orEmpty().forEach { it.delete() }; directory.delete()
            if (!wasUnlocked) app.sessionManager.lock()
        }
    }

    private fun rotateVideo(input: File, output: File) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val target = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.setOrientationHint(90); muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(target, buffer, info); extractor.advance()
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun waitUntil(message: String, timeoutMs: Long = 15000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        throw AssertionError("$message; vaultUnlocked=${app.sessionManager.isUnlocked.value}; window=${root?.packageName}; locked=${app.getSystemService(KeyguardManager::class.java).isDeviceLocked}; interactive=${app.getSystemService(android.os.PowerManager::class.java).isInteractive}")
    }
    private fun wait(text: String): AccessibilityNodeInfo {
        waitUntil("Missing UI: $text") { find { it.text?.toString() == text || it.contentDescription?.toString() == text } != null }
        return requireNotNull(find { it.text?.toString() == text || it.contentDescription?.toString() == text })
    }
    private fun click(text: String) {
        var node = wait(text)
        while (!node.isClickable) node = requireNotNull(node.parent)
        assertTrue("Cannot click $text", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun openTestAlbum() {
        wait("Favorites")
        repeat(20) {
            if (find { it.text?.toString() == "Gallery test" } != null) { click("Gallery test"); return }
            find { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(300)
        }
        throw AssertionError("Test album not found after scrolling")
    }
    private fun zoomNode() = find { it.stateDescription?.toString()?.startsWith("Zoom ") == true }
    private fun zoomScale() = zoomNode()?.stateDescription?.toString()?.removePrefix("Zoom ")?.removeSuffix("×")?.toFloatOrNull() ?: 0f
    private fun waitZoom(scale: Float) = waitUntil("Zoom did not become $scale") { kotlin.math.abs(zoomScale() - scale) < 0.05f }
    private fun resetZoom() {
        val node = requireNotNull(zoomNode())
        val action = node.actionList.first { it.label?.toString() == "Reset zoom" }
        assertTrue(node.performAction(action.id)); waitZoom(1f)
    }
    private fun mediaPoint(): Pair<Float, Float> {
        val bounds = Rect().also { requireNotNull(zoomNode()).getBoundsInScreen(it) }
        return bounds.exactCenterX() to (bounds.top + bounds.height() * 0.4f)
    }
    private fun tapMedia() { val (x, y) = mediaPoint(); touch(x, y); SystemClock.sleep(400) }
    private fun doubleTap() { val (x, y) = mediaPoint(); touch(x, y); SystemClock.sleep(80); touch(x, y) }
    private fun touch(x: Float, y: Float) {
        val start = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
    }
    private fun pinch() {
        val (x, y) = mediaPoint()
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, radius: Float, count: Int) {
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply { this.x = x + if (index == 0) -radius else radius; this.y = y; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 50f, 1)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 50f, 2)
        for (step in 1..12) { SystemClock.sleep(20); event(MotionEvent.ACTION_MOVE, 50f + step * 10f, 2) }
        event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 170f, 2)
        event(MotionEvent.ACTION_UP, 170f, 1)
    }
    private fun playerPosition(scenario: ActivityScenario<MainActivity>): Long {
        var position = 0L
        fun player(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) player(view.getChildAt(index))?.let { return it }
            return null
        }
        scenario.onActivity { position = requireNotNull(player(it.window.decorView)?.player).currentPosition }
        return position
    }
    private fun capture(scenario: ActivityScenario<MainActivity>) {
        try {
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            SystemClock.sleep(300)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(app.cacheDir, "gallery-review.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
        } finally { scenario.onActivity {
            it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        } }
    }
}
