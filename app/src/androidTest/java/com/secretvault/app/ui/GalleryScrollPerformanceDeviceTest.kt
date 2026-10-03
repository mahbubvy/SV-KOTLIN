package com.secretvault.app.ui

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.*
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.*
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.GALLERY_THUMBNAIL_SUFFIX
import com.secretvault.app.core.model.*
import com.secretvault.app.data.repository.MediaRepository
import com.secretvault.app.ui.gallery.*
import com.secretvault.app.ui.theme.SecretVaultTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class GalleryScrollPerformanceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow) ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { window ->
            window.root?.takeIf { it.packageName == "com.secretvault.app" }?.let(::visit)
        }
    }
    private fun until(message: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!predicate()) { assertTrue(message, SystemClock.elapsedRealtime() < end); SystemClock.sleep(50) }
    }
    private fun swipe(up: Boolean) {
        val metrics = app.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val from = metrics.heightPixels * if (up) 0.72f else 0.3f
        val to = metrics.heightPixels * if (up) 0.3f else 0.72f
        val start = SystemClock.uptimeMillis()
        fun event(action: Int, y: Float) {
            val input = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(input, true)) } finally { input.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, from)
        repeat(24) { SystemClock.sleep(12); event(MotionEvent.ACTION_MOVE, from + (to - from) * (it + 1) / 24f) }
        event(MotionEvent.ACTION_UP, to)
        SystemClock.sleep(400)
    }
    @Test fun profileMixedCameraFolderScroll(): Unit = runBlocking {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        assertFalse("Unlock the test phone before profiling", app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val mode = InstrumentationRegistry.getArguments().getString("previewMode", "legacy")
        val label = InstrumentationRegistry.getArguments().getString("profileLabel", "baseline")
        val root = File(app.cacheDir, "gallery-profile-${UUID.randomUUID()}").apply { mkdirs() }
        fun jpeg(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            for (y in 0 until height step 24) for (x in 0 until width step 24) {
                paint.color = Color.rgb((x * 17 + y) % 256, (y * 13 + x) % 256, (x * 7 + y * 3) % 256)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + 24).toFloat(), (y + 24).toFloat(), paint)
            }
            return java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it); bitmap.recycle(); it.toByteArray() }
        }
        val photo = File(root, "source-photo.enc")
        val thumbnail = File(root, "source-preview.enc")
        val video = File(root, "source-video.enc")
        val inputVideo = File(root, "input.mp4")
        jpeg(1600, 2400).let { try { photo.writeBytes(app.cryptoEngine.encryptBytes(it)) } finally { it.fill(0) } }
        jpeg(512, 512).let { try { thumbnail.writeBytes(app.cryptoEngine.encryptBytes(it)) } finally { it.fill(0) } }
        instrumentation.context.assets.open("video-edit-fixture.mp4").use { source -> inputVideo.outputStream().use { source.copyTo(it) } }
        app.cryptoEngine.encryptFile(inputVideo, video); inputVideo.delete()
        val originalLoader = coil.Coil.imageLoader(app)
        val wasUnlocked = app.sessionManager.isUnlocked.value
        try {
            repeat(3) { trial ->
                val suffix = "${root.name}-$trial"
                val fixtures = (0 until 96).map { index ->
                    val isVideo = index % 3 == 0
                    val original = File(root, "${if (isVideo) "video" else "photo"}_${suffix}_$index.enc")
                    (if (isVideo) video else photo).copyTo(original)
                    val preview = File(root, "preview_${suffix}_$index${if (mode == "current") GALLERY_THUMBNAIL_SUFFIX else ".thumb"}")
                    thumbnail.copyTo(preview)
                    MediaItem("$suffix-$index", "Scroll fixture $index", "Generated", if (isVideo) MediaType.VIDEO else MediaType.PHOTO,
                        if (isVideo) "video/mp4" else "image/jpeg", original.absolutePath, preview.absolutePath, original.length(),
                        durationMs = if (isVideo) 12000 else 0, albumId = AlbumEntity.ALBUM_CAMERA_ID, createdAt = System.currentTimeMillis() + 60000 + index)
                }
                val emissions = AtomicInteger()
                val repository = object : MediaRepository by app.mediaRepository {
                    override fun getMedia(albumId: String?, sortOrder: SortOrder) = app.mediaRepository.getMedia(albumId, sortOrder).map {
                        emissions.incrementAndGet(); it.filter { item -> item.id.startsWith(suffix) }
                    }
                }
                val gallery = GalleryViewModel(repository).apply { setAlbumFilter(AlbumEntity.ALBUM_CAMERA_ID, "Camera") }
                val albums = AlbumsViewModel(app.albumRepository)
                val loader = app.newImageLoader()
                val frames = ConcurrentLinkedQueue<Long>()
                val layouts = ConcurrentLinkedQueue<Long>()
                val draws = ConcurrentLinkedQueue<Long>()
                val syncs = ConcurrentLinkedQueue<Long>()
                val gpus = ConcurrentLinkedQueue<Long>()
                val dropped = AtomicInteger()
                val thread = HandlerThread("GalleryFrames").apply { start() }
                val listener = Window.OnFrameMetricsAvailableListener { _, metrics, skipped ->
                    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) != 1L) {
                        frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                        layouts.add(metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                        draws.add(metrics.getMetric(FrameMetrics.DRAW_DURATION))
                        syncs.add(metrics.getMetric(FrameMetrics.SYNC_DURATION))
                        if (Build.VERSION.SDK_INT >= 31) gpus.add(metrics.getMetric(FrameMetrics.GPU_DURATION))
                    }
                    dropped.addAndGet(skipped)
                }
                try {
                    app.mediaRepository.insertAll(fixtures); coil.Coil.setImageLoader(loader)
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        scenario.onActivity { activity ->
                            app.sessionManager.unlock()
                            activity.setContent { SecretVaultTheme { GalleryScreen(app, gallery, albums, {}, {}, {}, {}, {}) } }
                        }
                        try {
                            until("Gallery did not load fixtures") { find { it.contentDescription?.toString() == "Scroll fixture 95" } != null }
                        } catch (failure: AssertionError) {
                            throw AssertionError("${failure.message}; media=${gallery.uiState.value.mediaList.size} loading=${gallery.uiState.value.isLoading} error=${gallery.uiState.value.loadError} foreground=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}", failure)
                        }
                        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
                        repeat(4) { swipe(true) }; repeat(4) { swipe(false) }
                        scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }
                        val times = frames.filter { it > 0 }.sorted()
                        assertTrue("Not enough measured frames", times.size > 100)
                        val p95 = times[(times.size * 0.95).toInt().coerceAtMost(times.lastIndex)] / 1e6
                        val slow = times.count { it > 16666667L } * 100.0 / times.size
                        fun p95Of(values: Collection<Long>): Double { val sorted = values.filter { it >= 0 }.sorted(); return sorted.getOrElse((sorted.size * 0.95).toInt()) { 0L } / 1e6 }
                        val upgrades = gallery.uiState.value.mediaList.count { it.thumbnailPath?.endsWith(GALLERY_THUMBNAIL_SUFFIX) == true }
                        instrumentation.sendStatus(0, Bundle().apply { putString("profile", "$label mode=$mode trial=$trial frames=${times.size} p95ms=$p95 over16msPct=$slow layout95=${p95Of(layouts)} draw95=${p95Of(draws)} sync95=${p95Of(syncs)} gpu95=${p95Of(gpus)} emissions=${emissions.get()} modern=$upgrades cacheMax=${loader.memoryCache?.maxSize} dropped=${dropped.get()}") })
                    }
                } finally {
                    thread.quitSafely(); coil.Coil.setImageLoader(originalLoader); loader.shutdown()
                    app.mediaRepository.deleteByIds(fixtures.map { it.id })
                    fixtures.forEach { File(requireNotNull(it.thumbnailPath)).delete() }
                }
            }
        } finally {
            coil.Coil.setImageLoader(originalLoader)
            root.listFiles()?.forEach { it.delete() }; root.delete()
            if (!wasUnlocked) app.sessionManager.lock()
        }
    }
}
