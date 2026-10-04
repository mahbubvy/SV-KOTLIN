package com.secretvault.app.transfer

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.image.MediaPreview
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.core.transfer.TransferReceiver
import com.secretvault.app.core.transfer.TransferSender
import com.secretvault.app.core.transfer.TransferState
import com.secretvault.app.data.repository.MediaRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TransferDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp
    private val pin get() = charArrayOf('7', '4', '2', '6')

    private suspend fun waitFor(predicate: () -> Boolean) = withTimeout(30_000) {
        while (!predicate()) delay(20)
    }

    private fun scenario(): ActivityScenario<MainActivity>? {
        if (InstrumentationRegistry.getArguments().getString("transferBackendOnly") == "true") {
            app.sessionManager.unlock()
            return null
        }
        assertFalse("Unlock the phone before testing", app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                app.sessionManager.unlock()
                activity.setContentView(android.widget.TextView(activity).apply { text = "Generated file-sharing test" })
            }
        }
    }

    private fun photo(): ByteArray {
        val bitmap = Bitmap.createBitmap(720, 1080, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().use { output ->
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output); output.toByteArray() }
            finally { bitmap.recycle() }
        }
    }

    private fun fixtures(root: File, includeVideo: Boolean): List<MediaItem> {
        val source = File(root, "photo.enc")
        val bytes = photo()
        try { source.writeBytes(app.cryptoEngine.encryptBytes(bytes)) } finally { bytes.fill(0) }
        val items = (0 until if (includeVideo) 20 else 1).map { index ->
            val encrypted = File(root, "photo-$index.enc")
            source.copyTo(encrypted)
            MediaItem("${root.name}-$index", "generated-$index.jpg", "generated-$index.jpg", MediaType.PHOTO,
                "image/jpeg", encrypted.absolutePath, null, encrypted.length(), width = 720, height = 1080, albumId = AlbumEntity.ALBUM_IMPORTS_ID)
        }.toMutableList()
        if (includeVideo) {
            val encrypted = File(root, "video.enc")
            app.cryptoEngine.createEncryptingOutputStream(encrypted.outputStream()).use { vault ->
                instrumentation.context.assets.open("video-edit-fixture.mp4").use { it.copyTo(vault) }
                val padding = ByteArray(1024 * 1024)
                repeat(32) { vault.write(padding) }
            }
            items += MediaItem("${root.name}-video", "generated-video.mp4", "generated-video.mp4", MediaType.VIDEO,
                "video/mp4", encrypted.absolutePath, null, encrypted.length(), albumId = AlbumEntity.ALBUM_IMPORTS_ID)
        }
        return items
    }

    @Test fun requiredPinWithoutSavedPinFailsClosed(): Unit = runBlocking {
        val name = "transfer-test-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val pins = StreamPinManager(app, prefs)
        try {
            pins.setSharePinRequired(true)
            TransferReceiver(app, app.cryptoEngine, app.mediaRepository, pins).use { receiver ->
                receiver.start()
                waitFor { receiver.state.value is TransferState.Failed }
                assertTrue(pins.isSharePinRequired())
                assertNull(pins.readPin())
                assertEquals(0, (receiver.state.value as TransferState.Failed).completed)
            }
        } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun cancellingDuringPreviewDoesNotPublishCurrentItem(): Unit = runBlocking {
        val name = "transfer-test-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val inserted = Collections.synchronizedList(mutableListOf<MediaItem>())
        val repository = object : MediaRepository by app.mediaRepository {
            override suspend fun insertMedia(item: MediaItem) { inserted.add(item) }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var currentFile: File? = null
        var currentThumb: File? = null
        val pins = StreamPinManager(app, prefs).apply { setSharePinRequired(false) }
        val wasUnlocked = app.sessionManager.isUnlocked.value
        try {
            scenario().use {
                TransferReceiver(app, app.cryptoEngine, repository, pins, previewWriter = { _, file, _, thumb, _ ->
                    currentFile = file; currentThumb = thumb
                    thumb.parentFile?.mkdirs(); thumb.writeBytes(byteArrayOf(1))
                    entered.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                    MediaPreview(720, 1080, 0)
                }).use { receiver ->
                    TransferSender(app, app.cryptoEngine).use { sender ->
                        receiver.start()
                        waitFor { receiver.state.value is TransferState.Waiting }
                        sender.discovery.search()
                        val ownName = "SV ${android.os.Build.MODEL.take(48)}"
                        val endpoint = withTimeout(20_000) {
                            sender.discovery.state.first { it.cameras.any { camera -> camera.name == ownName } }.cameras.first { it.name == ownName }
                        }
                        sender.send(endpoint, null, fixtures(root, false))
                        waitFor { receiver.state.value is TransferState.AwaitingAccept }
                        receiver.accept()
                        waitFor { entered.count == 0L }
                        receiver.stop(); release.countDown()
                        waitFor { currentFile?.exists() == false && currentThumb?.exists() == false }
                        assertTrue("Cancelled preview was inserted", inserted.isEmpty())
                        assertEquals(0, (receiver.state.value as TransferState.Failed).completed)
                    }
                }
            }
        } finally {
            release.countDown()
            inserted.forEach { File(it.encryptedPath).delete(); it.thumbnailPath?.let { path -> File(path).delete() } }
            currentFile?.delete(); currentThumb?.delete()
            root.listFiles()?.forEach { it.delete() }; root.delete(); app.deleteSharedPreferences(name)
            if (!wasUnlocked) app.sessionManager.lock()
        }
    }

    @Test fun transfersGeneratedFilesAcrossWifi(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("transferRole") ?: throw AssertionError("Provide sender/receiver role")
        val peer = args.getString("transferPeerBase64")?.let {
            String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8)
        } ?: args.getString("transferPeer") ?: throw AssertionError("Provide peer model name")
        val testCase = args.getString("transferCase", "success")
        val protected = args.getString("transferPin", "false") == "true"
        val name = "transfer-test-${UUID.randomUUID()}"
        val root = File(app.cacheDir, name).apply { mkdirs() }
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val pins = StreamPinManager(app, prefs).apply {
            setSharePinRequired(protected)
            if (protected) setPin(pin)
        }
        val received = Collections.synchronizedList(mutableListOf<MediaItem>())
        val repository = object : MediaRepository by app.mediaRepository {
            override suspend fun insertMedia(item: MediaItem) {
                app.mediaRepository.insertMedia(item)
                received.add(item)
            }
        }
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val start = android.os.SystemClock.elapsedRealtime()
        try {
            scenario().use {
                if (role == "receiver") {
                    TransferReceiver(app, app.cryptoEngine, repository, pins).use { receiver ->
                        receiver.start()
                        waitFor { receiver.state.value is TransferState.Waiting }
                        instrumentation.sendStatus(0, Bundle().apply { putString("transfer", "receiver-ready") })
                        withTimeout(120_000) { receiver.state.first { it is TransferState.AwaitingAccept } }
                        if (testCase == "decline") receiver.decline() else receiver.accept()
                        if (testCase == "receiver-cancel") {
                            withTimeout(45_000) { receiver.state.first { it is TransferState.Transferring && it.index == 21 } }
                            receiver.stop()
                        }
                        withTimeout(90_000) { receiver.state.first { it is TransferState.Done || it is TransferState.Failed } }
                        if (testCase == "success") {
                            assertEquals(21, (receiver.state.value as TransferState.Done).count)
                            assertEquals(21, received.size)
                            received.forEach { item ->
                                assertEquals(item, app.mediaRepository.getMediaById(item.id))
                                assertEquals(AlbumEntity.ALBUM_IMPORTS_ID, item.albumId)
                                assertTrue(item.width > 0 && item.height > 0)
                                assertNotNull(item.thumbnailPath)
                                var count = 0L
                                val sink = object : OutputStream() {
                                    override fun write(b: Int) { count++ }
                                    override fun write(b: ByteArray, off: Int, len: Int) { count += len }
                                }
                                File(item.encryptedPath).inputStream().use { source -> app.cryptoEngine.decryptStream(source, sink) }
                                assertTrue(count > 0)
                            }
                            assertTrue(received.single { it.mediaType == MediaType.VIDEO }.durationMs > 0)
                        } else {
                            assertTrue(receiver.state.value is TransferState.Failed)
                            assertTrue(received.size < 21)
                            assertEquals(received.size, (receiver.state.value as TransferState.Failed).completed)
                        }
                    }
                } else {
                    TransferSender(app, app.cryptoEngine).use { sender ->
                        val media = fixtures(root, true)
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("transfer", "fixtures-ready elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
                        })
                        sender.discovery.search()
                        val endpoint = try {
                            withTimeout(25_000) {
                                sender.discovery.state.first { it.cameras.any { camera -> camera.name == peer } }.cameras.first { it.name == peer }
                            }
                        } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                            throw AssertionError("Receiver not discovered: ${sender.discovery.state.value.message}; names=${sender.discovery.state.value.cameras.map { it.name }}", failure)
                        }
                        assertEquals(protected, endpoint.requiresPin)
                        sender.send(endpoint, if (protected) pin else null, media)
                        if (testCase == "sender-cancel") {
                            withTimeout(45_000) { sender.state.first { it is TransferState.Transferring && it.index == 21 } }
                            sender.cancel()
                        }
                        withTimeout(90_000) { sender.state.first { it is TransferState.Done || it is TransferState.Failed } }
                        if (testCase == "success") assertEquals(21, (sender.state.value as TransferState.Done).count)
                        else assertTrue(sender.state.value is TransferState.Failed)
                    }
                }
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("transfer", "role=$role case=$testCase pin=$protected received=${received.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
                })
            }
        } finally {
            app.mediaRepository.deleteByIds(received.map { it.id })
            received.forEach { File(it.encryptedPath).delete(); it.thumbnailPath?.let { path -> File(path).delete() } }
            root.listFiles()?.forEach { it.delete() }; root.delete(); app.deleteSharedPreferences(name)
            if (!wasUnlocked) app.sessionManager.lock()
        }
    }
}
