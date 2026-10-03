package com.secretvault.app.stream

import android.content.ClipboardManager
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.ui.stream.CameraStreamScreen
import com.secretvault.app.ui.stream.StreamViewerScreen
import com.secretvault.app.ui.camera.CameraScreen
import com.secretvault.app.ui.camera.CameraViewModel
import com.secretvault.app.ui.settings.SettingsScreen
import com.secretvault.app.core.camera.CameraMode
import com.secretvault.app.core.camera.LensFacing
import com.secretvault.app.core.camera.VideoMode
import com.secretvault.app.core.camera.FlashMode
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.core.database.entity.AlbumEntity
import com.secretvault.app.core.model.MediaType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import com.secretvault.app.SecretVaultApp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.secretvault.app.ui.navigation.Screen
import com.secretvault.app.ui.navigation.VaultNavGraph
import com.secretvault.app.ui.gallery.GalleryViewModel
import android.graphics.Rect
import java.util.concurrent.atomic.AtomicReference
import com.secretvault.app.ui.theme.SecretVaultTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StreamLiveUiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return visit(automation.rootInActiveWindow) ?: automation.windows.firstNotNullOfOrNull { window ->
            val root = window.root
            if (root?.packageName == "com.secretvault.app") visit(root) else null
        }
    }

    private fun waitFor(text: String, timeout: Long = 10000): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            find { it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text }?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        val foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName
        val status = find { it.text?.toString()?.startsWith("Could not") == true || it.text?.toString()?.startsWith("Camera stream") == true || it.text?.toString() in listOf("Preparing camera…", "Connecting…", "Waiting for first frame…", "Choose Send or View",
            "Could not receive camera. Check Wi-Fi and the connection details, then try again.",
            "Video could not decode. Start a new session.") }?.text
        val visibleText = mutableListOf<String>()
        fun readVisible(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (!node.isPassword && !node.isEditable) node.text?.toString()?.let { visibleText.add(it.take(160)) }
            for (index in 0 until node.childCount) readVisible(node.getChild(index))
        }
        readVisible(instrumentation.uiAutomation.rootInActiveWindow)
        throw AssertionError("Stream UI did not show: $text; foreground package: $foreground; status: $status; visible=${visibleText.take(30)}")
    }

    private fun tap(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        do {
            var target = find { it.text?.toString() == text || it.contentDescription?.toString() == text }
            while (target != null && !target.isClickable) target = target.parent
            target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("No enabled clickable control for $text")
    }

    private fun zoom(action: String, expected: String) {
        val node = requireNotNull(find { it.stateDescription?.toString()?.startsWith("Camera zoom ") == true })
        val control = requireNotNull(node.actionList.firstOrNull { it.label?.toString() == action })
        assertTrue("Camera zoom action rejected", node.performAction(control.id))
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (find { it.stateDescription?.toString() == "Camera zoom $expected×" } == null) {
            assertTrue("Hardware zoom did not acknowledge $expected", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    private fun focusViewer() {
        val bounds = Rect().also { waitFor("Live camera").getBoundsInScreen(it) }
        val now = SystemClock.uptimeMillis()
        val x = bounds.exactCenterX()
        val y = bounds.top + bounds.height() * 0.4f
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            instrumentation.uiAutomation.injectInputEvent(down, true)
            instrumentation.uiAutomation.injectInputEvent(up, true)
        } finally { down.recycle(); up.recycle() }
        waitFor("Focus target", 2000)
        SystemClock.sleep(6000)
        assertTrue("Remote focus was rejected", find { it.text?.toString() == "Camera control could not be applied" } == null)
        waitFor("Live camera")
    }

    @Test fun streamsActualCameraThroughUi() {
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        assertTrue("Unlock the phone locally before the live camera check", !context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { it.setContent { SecretVaultTheme { CameraStreamScreen(onBack = {}) } } }
                if (role == "send") {
                    invitationFile.delete()
                    tap("Start camera")
                    waitFor("Waiting for a viewer")
                    tap("Copy connection details")
                    instrumentation.runOnMainSync {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val value = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        assertTrue("No private invitation", value?.startsWith("svstream://") == true)
                        invitationFile.writeText(requireNotNull(value))
                    }
                    waitFor("Sending live camera", 60000)
                } else {
                    tap("View camera")
                    waitFor("Connection details")
                    val field = requireNotNull(find { it.isEditable }) { "Connection field missing" }
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, invitationFile.readText().trim())
                    }
                    assertTrue("Connection field rejected input", field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
                    tap("Connect")
                    waitFor("Live camera")
                }
                val counterReadyBy = SystemClock.elapsedRealtime() + 5000
                val positiveFps = Regex("1280 × 720 · [1-9]\\d* FPS")
                while (find { it.text?.toString()?.matches(positiveFps) == true } == null && SystemClock.elapsedRealtime() < counterReadyBy) {
                    SystemClock.sleep(100)
                }
                assertTrue("Live FPS counter did not start", find { it.text?.toString()?.matches(positiveFps) == true } != null)
                var minimumFps = Int.MAX_VALUE
                val sampleSeconds = if (role == "send") 10 else 15
                repeat(sampleSeconds) {
                    SystemClock.sleep(1000)
                    waitFor(if (role == "send") "Sending live camera" else "Live camera", 500)
                    val status = find { it.text?.toString()?.matches(Regex("1280 × 720 · \\d+ FPS")) == true }
                    val fps = requireNotNull(status?.text?.toString()) { "Live FPS missing" }.substringAfter(" · ").substringBefore(" FPS").toInt()
                    assertTrue("Live camera stopped producing/displaying frames", fps > 0)
                    minimumFps = minOf(minimumFps, fps)
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: 720p live for ${sampleSeconds}s; minimum $minimumFps FPS") })
                if (role == "send") {
                    waitFor("Viewer disconnected or connection stalled. Start a new session.", 15000)
                } else {
                    tap("Disconnect")
                    waitFor("Stream stopped")
                }
            }
        } finally { invitationFile.delete() }
    }

    @Test fun discoversAndPairsTheNormalCameraThroughUi() {
        val role = InstrumentationRegistry.getArguments().getString("streamRole")
        val native = InstrumentationRegistry.getArguments().getString("cameraSource") == "native"
        val remotePhoto = InstrumentationRegistry.getArguments().getString("remotePhoto") == "true"
        val noStreamPin = InstrumentationRegistry.getArguments().getString("noStreamPin") == "true"
        val cameraChanges = InstrumentationRegistry.getArguments().getString("cameraChanges") == "true"
        val remoteVideo = InstrumentationRegistry.getArguments().getString("remoteVideo") == "true"
        val remoteSettings = InstrumentationRegistry.getArguments().getString("remoteSettings") == "true"
        val viewerScreenshot = InstrumentationRegistry.getArguments().getString("viewerScreenshot") == "true"
        val remoteInteractions = InstrumentationRegistry.getArguments().getString("remoteInteractions") == "true"
        val requestedMode = when (InstrumentationRegistry.getArguments().getString("videoMode")) {
            "1080p60" -> VideoMode.FHD_60
            "4k30" -> VideoMode.UHD_30
            "4k60" -> VideoMode.UHD_60
            "highest" -> if (native) VideoMode.UHD_30 else VideoMode.UHD_60
            else -> VideoMode.FHD_30
        }
        val videoExit = InstrumentationRegistry.getArguments().getString("videoExit") ?: "stop"
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        val app = context.applicationContext as SecretVaultApp
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val photoTestStartedAt = System.currentTimeMillis()
        val mediaBefore = if ((remotePhoto || remoteVideo) && role == "send") runBlocking { app.mediaRepository.getTotalCount() } else 0
        val unlockDeadline = SystemClock.elapsedRealtime() + 120000
        while (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            assertTrue("Unlock the phone locally before the live camera check", SystemClock.elapsedRealtime() < unlockDeadline)
            SystemClock.sleep(200)
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val prefs = context.getSharedPreferences("sv_stream_config", android.content.Context.MODE_PRIVATE)
        val saved = if (role == "send") {
            val previous = StreamPinManager(context).readPin()
            val fixtureLeftByInterruptedTest = previous?.concatToString() == FIXTURE_PIN
            previous?.fill('\u0000')
            if (fixtureLeftByInterruptedTest) prefs.all.filterKeys { it !in setOf("pin", "attempts", "blocked_until") } else prefs.all
        } else prefs.all
        val invitationFile = File(context.cacheDir, "stream-test-invitation")
        val cameraModel = CameraViewModel(if (native) VideoMode.FHD_60 else VideoMode.UHD_60).apply { setCameraMode(CameraMode.VIDEO) }
        val viewerTexture = AtomicReference<android.view.TextureView>()
        try {
            if ((remotePhoto || remoteVideo || cameraChanges || remoteSettings || viewerScreenshot || remoteInteractions) && role == "send") app.sessionManager.unlock()
            if (remoteVideo && !remoteInteractions) cameraModel.toggleRecordAudio()
            if (role == "send") {
                StreamPinManager(context).setPinRequired(!noStreamPin)
                if (!noStreamPin) {
                    val pin = FIXTURE_PIN.toCharArray()
                    try { StreamPinManager(context).setPin(pin) } finally { pin.fill('\u0000') }
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { screen -> screen.setContent { SecretVaultTheme {
                    if (role == "send") CameraScreen(context.applicationContext as SecretVaultApp, cameraModel, {}, {})
                    else StreamViewerScreen(onBack = {})
                } } }
                if (remoteInteractions) { instrumentation.waitForIdleSync(); app.sessionManager.unlock() }
                if (role == "send") {
                    if (remoteInteractions) {
                        waitFor("Flip Camera", 20000); SystemClock.sleep(2000)
                        zoom("Zoom in", "1.5"); zoom("Zoom out", "1.0")
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: local hardware zoom acknowledged 1.5x then 1x") })
                    }
                    invitationFile.delete(); tap("Stream"); waitFor("Waiting for a viewer", 20000)
                    invitationFile.writeText("svstream2://ready/" + java.net.URLEncoder.encode("SV ${Build.MODEL.take(48)}", "UTF-8"))
                    waitFor("Viewer connected", 120000)
                    if (remoteInteractions) {
                        runBlocking { kotlinx.coroutines.withTimeout(80000) { cameraModel.uiState.first { !it.recordAudio } } }
                        runBlocking { kotlinx.coroutines.withTimeout(30000) { cameraModel.uiState.first { it.recordAudio } } }
                        runBlocking { kotlinx.coroutines.withTimeout(30000) { cameraModel.uiState.first { !it.recordAudio } } }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: remote microphone OFF/ON/OFF synchronized to local camera") })
                    }
                    if (remoteSettings) {
                        runBlocking { kotlinx.coroutines.withTimeout(25000) { cameraModel.uiState.first { it.flashMode == FlashMode.ON } } }
                        if (!(native && requestedMode == VideoMode.FHD_60)) {
                            val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                            val info = LensFacing.BACK.selector.filter(provider.availableCameraInfos).first()
                            assertEquals("Remote flash did not enable CameraX torch", androidx.camera.core.TorchState.ON, info.torchState.value)
                        }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: remote flash ON applied to camera; native=${native && requestedMode == VideoMode.FHD_60}") })
                        runBlocking { kotlinx.coroutines.withTimeout(15000) { cameraModel.uiState.first { it.flashMode == FlashMode.OFF } } }
                    }
                    if (cameraChanges) {
                        val rear = cameraModel.uiState.value.rearLensOptions
                        val selections = listOf("front") + (listOfNotNull(rear.firstOrNull { it.id == null }) + rear.filter { it.id != null } +
                            listOfNotNull(rear.firstOrNull { it.id == null })).map { "back/${it.id ?: "default"}" }
                        for (target in selections) {
                            runBlocking { kotlinx.coroutines.withTimeout(25000) {
                                cameraModel.uiState.first { state ->
                                    if (target == "front") state.lensFacing == LensFacing.FRONT
                                    else state.lensFacing == LensFacing.BACK && "back/${state.selectedRearLensId ?: "default"}" == target
                                }
                            } }
                            waitFor("Camera changed", 15000)
                            if (target != "front") {
                                val lens = rear.first { "back/${it.id ?: "default"}" == target }
                                if (lens.physicalCameraId == null) {
                                    val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                                    val info = LensFacing.BACK.selector.filter(provider.availableCameraInfos).first()
                                    runBlocking { kotlinx.coroutines.withTimeout(10000) {
                                        while (info.zoomState.value?.zoomRatio?.let { kotlin.math.abs(it - lens.zoomRatio) < 0.01f } != true)
                                            kotlinx.coroutines.delay(50)
                                    } }
                                    instrumentation.sendStatus(0, Bundle().apply { putString("streamResult",
                                        "send: ${lens.label} selected remotely; CameraX applied zoom ${info.zoomState.value?.zoomRatio}") })
                                }
                            }
                        }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: requested front/rear and ${rear.size} rear lens choices matched camera model") })
                        SystemClock.sleep(7000); tap("Flip Camera"); SystemClock.sleep(2500); tap("Flip Camera")
                    }
                    if (remotePhoto) {
                        waitFor("Remote photo saved in this vault", 40000)
                        assertTrue("Remote shutter did not save exactly one media item", runBlocking { app.mediaRepository.getTotalCount() } == mediaBefore + 1)
                        val captured = runBlocking { app.mediaRepository.getMedia(AlbumEntity.ALBUM_CAMERA_ID).first() }
                            .filter { it.createdAt >= photoTestStartedAt && it.mediaType == MediaType.PHOTO }
                        assertTrue("Remote photo was not saved in Camera album", captured.size == 1)
                        assertTrue("Saved photo or thumbnail missing", captured.all {
                            File(it.encryptedPath).length() > 0 && File(requireNotNull(it.thumbnailPath)).length() > 0 && it.width > 0 && it.height > 0
                        })
                    }
                    if (remoteVideo) {
                        waitFor("Stop recording", 25000)
                        assertTrue("Camera did not enter recording state", cameraModel.uiState.value.isRecording)
                        if (videoExit == "background") { SystemClock.sleep(6000); activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED) }
                        if (videoExit == "stop") waitFor("Video saved in this vault", 70000)
                        else runBlocking { kotlinx.coroutines.withTimeout(70000) {
                            app.mediaRepository.getMedia(AlbumEntity.ALBUM_CAMERA_ID).first { items ->
                                items.any { it.createdAt >= photoTestStartedAt && it.mediaType == MediaType.VIDEO }
                            }
                        } }
                        assertTrue("Video still recording after save", !cameraModel.uiState.value.isRecording)
                        assertEquals(mediaBefore + 1 + if (remotePhoto) 1 else 0, runBlocking { app.mediaRepository.getTotalCount() })
                        val captured = runBlocking { app.mediaRepository.getMedia(AlbumEntity.ALBUM_CAMERA_ID).first() }
                            .filter { it.createdAt >= photoTestStartedAt && it.mediaType == MediaType.VIDEO }
                        assertEquals("Remote video not saved once in Camera album", 1, captured.size)
                        val item = captured.single()
                        assertTrue("Encrypted recording or thumbnail missing", File(item.encryptedPath).length() > 0 &&
                            File(requireNotNull(item.thumbnailPath)).length() > 0 && (item.durationMs ?: 0) >= 2500)
                        assertEquals(if (requestedMode.quality == androidx.camera.video.Quality.UHD) 3840 else 1920, item.width)
                        assertEquals(if (requestedMode.quality == androidx.camera.video.Quality.UHD) 2160 else 1080, item.height)
                        val plaintext = File(context.cacheDir, "remote-video-metadata-test.mp4")
                        try {
                            app.cryptoEngine.decryptFile(File(item.encryptedPath), plaintext)
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(plaintext.absolutePath)
                                val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                                val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                                val hasAudio = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                                assertEquals("Portrait recording metadata", "90", rotation)
                                assertTrue("Silent test recorded audio", hasAudio != "yes")
                                fps?.toFloatOrNull()?.let { assertTrue("Wrong capture FPS", kotlin.math.abs(it - requestedMode.fps) < 0.5f) }
                                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: decrypted test clip metadata rotation=$rotation captureFPS=$fps audio=$hasAudio; exit=$videoExit") })
                            } finally { retriever.release() }
                            val extractor = android.media.MediaExtractor()
                            try {
                                extractor.setDataSource(plaintext.absolutePath)
                                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                                extractor.selectTrack(track)
                                var count = 0
                                val first = extractor.sampleTime
                                var last = first
                                while (extractor.sampleTime >= 0) { last = extractor.sampleTime; count++; if (!extractor.advance()) break }
                                assertTrue("No valid recorded video samples", count > 30 && last > first)
                                val actualFps = (count - 1) * 1_000_000.0 / (last - first)
                                assertTrue("Wrong recorded FPS: $actualFps", kotlin.math.abs(actualFps - requestedMode.fps) <= 2.0)
                                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "send: test clip video samples=$count averagePTSfps=$actualFps") })
                            } finally { extractor.release() }
                        } finally { plaintext.delete() }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult",
                            "send: remote video encrypted with thumbnail and database record; ${item.width}x${item.height}; duration ${item.durationMs}ms") })
                    }
                    if (videoExit == "background") activity.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                    else {
                        if (videoExit != "disconnect") repeat(10) { SystemClock.sleep(1000); waitFor("Viewer connected", 500) }
                        waitFor("Viewer disconnected or connection stalled. Start a new session.", if (remotePhoto || remoteVideo) 60000 else 20000)
                    }
                    waitFor("Stream"); assertTrue("Camera mode changed", cameraModel.uiState.value.cameraMode == CameraMode.VIDEO)
                    assertTrue("Recording default changed", cameraModel.uiState.value.videoMode == if (native) VideoMode.FHD_60 else VideoMode.UHD_60)
                    tap("Stream"); waitFor("Waiting for a viewer", 20000); tap("Stop stream"); waitFor("Stream")
                } else {
                    val cameraName = java.net.URLDecoder.decode(invitationFile.readText().trim().substringAfter("/ready/"), "UTF-8")
                    val searchAt = SystemClock.elapsedRealtime()
                    waitFor(cameraName, 20000); val discoveryMs = SystemClock.elapsedRealtime() - searchAt
                    tap(cameraName)
                    if (!noStreamPin) {
                        waitFor("Enter streaming PIN")
                        var field = requireNotNull(find { it.isEditable })
                        assertTrue("PIN field is not masked", field.isPassword)
                        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0000")
                        }))
                        tap("Connect"); waitFor("Could not receive camera. Check Wi-Fi and the streaming PIN, then try again.")
                        tap("Back to cameras"); waitFor(cameraName, 20000); tap(cameraName); waitFor("Enter streaming PIN")
                        field = requireNotNull(find { it.isEditable })
                        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, FIXTURE_PIN)
                        }))
                        }
                    val connectAt = SystemClock.elapsedRealtime()
                    if (!noStreamPin) tap("Connect")
                    waitFor("Live camera", 20000)
                    if (noStreamPin) assertTrue("PIN-off viewer showed a PIN prompt", find { it.text?.toString() == "Enter streaming PIN" } == null)
                    activity.onActivity { screen ->
                        fun texture(view: android.view.View): android.view.TextureView? {
                            if (view is android.view.TextureView) return view
                            if (view is android.view.ViewGroup) for (index in 0 until view.childCount) texture(view.getChildAt(index))?.let { return it }
                            return null
                        }
                        val view = requireNotNull(texture(screen.window.decorView))
                        viewerTexture.set(view)
                        val matrix = FloatArray(9)
                        view.getTransform(android.graphics.Matrix()).getValues(matrix)
                        assertTrue("Portrait stream has no viewer quarter-turn: ${matrix.toList()}",
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSCALE_X]) < 0.001f &&
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSCALE_Y]) < 0.001f &&
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSKEW_X]) > 0.01f)
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: NSD discovery ${discoveryMs}ms; connection to first-frame ${SystemClock.elapsedRealtime() - connectAt}ms") })
                    waitFor("Video quality")
                    val qualityControl = waitFor("Video quality").let { node ->
                        var parent = node
                        while (!parent.isClickable && parent.parent != null) parent = parent.parent
                        parent
                    }
                    val qualityBounds = Rect().also(qualityControl::getBoundsInScreen)
                    assertTrue("Video quality touch target is too small", qualityBounds.height() >= 48 * context.resources.displayMetrics.density - 1)
                    assertTrue("Video quality control exceeds viewport", qualityBounds.left >= 0 && qualityBounds.right <= context.resources.displayMetrics.widthPixels)
                    val cameraBounds = Rect().also { waitFor("Choose camera").getBoundsInScreen(it) }
                    val recordBounds = Rect().also { waitFor("Record video").let { node ->
                        var button = node
                        while (!button.isClickable && button.parent != null) button = button.parent
                        button.getBoundsInScreen(it)
                    } }
                    val disconnectBounds = Rect().also { waitFor("Disconnect").let { node ->
                        var button = node
                        while (!button.isClickable && button.parent != null) button = button.parent
                        button.getBoundsInScreen(it)
                    } }
                    assertTrue("Camera menus must sit above recording", qualityBounds.bottom <= recordBounds.top && cameraBounds.bottom <= recordBounds.top)
                    assertTrue("Disconnect must remain below recording", recordBounds.bottom <= disconnectBounds.top)
                    if (context.resources.configuration.fontScale <= 1.3f && context.resources.configuration.screenWidthDp >= 360) {
                        assertTrue("Quality and lens must share the compact row", kotlin.math.abs(qualityBounds.bottom - cameraBounds.bottom) <= 2)
                    }
                    val highestMode = if (native) VideoMode.UHD_30 else VideoMode.UHD_60
                    waitFor(highestMode.label)
                    instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: highest default ${highestMode.label}") })
                    if (requestedMode != highestMode) {
                        tap("Video quality"); tap(requestedMode.label); waitFor("Camera setting applied", 20000); waitFor(requestedMode.label)
                    }
                    if (remoteInteractions) {
                        zoom("Zoom in", "1.5"); focusViewer(); zoom("Zoom out", "1.0")
                        tap("Microphone on"); waitFor("Microphone off"); SystemClock.sleep(2000)
                        tap("Microphone off"); waitFor("Microphone on"); SystemClock.sleep(2000)
                        tap("Microphone on"); waitFor("Microphone off"); SystemClock.sleep(2000)
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: remote hardware zoom, tap focus and microphone OFF/ON/OFF acknowledged") })
                    }
                    if (viewerScreenshot) {
                        val file = File(context.cacheDir, "stream-viewer-review.png")
                        SystemClock.sleep(4500)
                        try {
                            activity.onActivity { screen ->
                                viewerTexture.get().alpha = 0f
                                screen.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                            }
                            SystemClock.sleep(500)
                            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                            try { file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                            finally { bitmap.recycle() }
                            instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: review screenshot saved with live camera image hidden") })
                        } finally {
                            activity.onActivity { screen ->
                                screen.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                viewerTexture.get().alpha = 1f
                                assertTrue("Screenshot protection not restored", screen.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                            }
                        }
                    }
                    if (remoteSettings) {
                        fun flash(mode: String) {
                            val names = listOf("Flash auto", "Flash on", "Flash off")
                            val control = find { it.text?.toString() in names || it.contentDescription?.toString() in names } ?: throw AssertionError("Flash control missing")
                            val current = control.contentDescription?.toString()?.takeIf { it in names } ?: control.text.toString()
                            tap(current); tap("Flash $mode"); waitFor("Camera setting applied", 15000); waitFor("Flash $mode")
                        }
                        flash("on"); SystemClock.sleep(2000); flash("off"); SystemClock.sleep(500)
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: quality ${requestedMode.label}, remote flash ON/OFF acknowledged") })
                    }
                    if (cameraChanges) {
                        val cameraButton = waitFor("Choose camera").let { icon ->
                            var control = icon
                            while (!control.isClickable && control.parent != null) control = control.parent
                            control
                        }
                        val buttonBounds = Rect().also(cameraButton::getBoundsInScreen)
                        val density = context.resources.displayMetrics.density
                        assertTrue("Camera selector touch target is too small", buttonBounds.height() >= (48 * density - 1))
                        assertTrue("Camera selector exceeds viewport", buttonBounds.left >= 0 &&
                            buttonBounds.right <= context.resources.displayMetrics.widthPixels)
                        tap("Choose camera")
                        waitFor("Front")
                        val labels = mutableListOf<String>()
                        fun readOptions(node: AccessibilityNodeInfo?) {
                            if (node == null) return
                            node.text?.toString()?.takeIf { it == "Front" || it.startsWith("Rear ") }?.let { labels.add(it) }
                            for (index in 0 until node.childCount) readOptions(node.getChild(index))
                        }
                        instrumentation.uiAutomation.windows.forEach { readOptions(it.root) }
                        val rear = labels.distinct().filter { it.startsWith("Rear ") }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: available camera choices ${labels.distinct()}") })
                        assertTrue("Camera did not advertise front and rear", "Front" in labels && rear.isNotEmpty())
                        val default = rear.first { it == "Rear 1×" }
                        val order = listOf("Front", default) + rear.filter { it != default } + default
                        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        for (label in order) {
                            tap("Choose camera"); tap(label); waitFor("Camera changed", 15000)
                            waitFor(label); waitFor("Live camera"); SystemClock.sleep(1200)
                            if (label == "Front") {
                                val noFlash = waitFor("No flash").let { node ->
                                    var parent = node
                                    while (!parent.isClickable && parent.parent != null) parent = parent.parent
                                    parent
                                }
                                assertFalse("Front camera without flash enabled the flash control", noFlash.isEnabled)
                            }
                            assertTrue("Wrong viewer camera selection after $label", find { it.text?.toString() == label } != null)
                        }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: remote front/rear and ${rear.size} rear lens choices acknowledged; preview remained live") })
                        waitFor("Front", 15000); waitFor(default, 15000)
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: local camera changes synchronized without reconnecting") })
                    }
                    if (remotePhoto) {
                        tap("Take photo"); waitFor("Photo saved on camera device", 40000)
                        waitFor("Live camera")
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: remote photo saved acknowledgment; live preview remained active") })
                    }
                    if (remoteVideo) {
                        waitFor("Record video", 10000); tap("Record video"); waitFor("Recording started", 20000)
                        waitFor("Stop recording")
                        val selector = waitFor("Choose camera").let { node ->
                            var parent = node
                            while (!parent.isClickable && parent.parent != null) parent = parent.parent
                            parent
                        }
                        assertFalse("Lens controls remain enabled while recording", selector.isEnabled)
                        if (remoteInteractions) {
                            val microphone = waitFor("Microphone off").let { icon ->
                                var control = icon
                                while (!control.isClickable && control.parent != null) control = control.parent
                                control
                            }
                            assertFalse("Microphone can change audio tracks while recording", microphone.isEnabled)
                            zoom("Zoom in", "1.5"); focusViewer(); zoom("Zoom out", "1.0")
                            instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: zoom and focus accepted during recording; microphone disabled") })
                        }
                        if (remoteSettings) {
                            tap("Flash off"); tap("Flash on"); waitFor("Camera setting applied", 15000); waitFor("Flash on")
                            tap("Flash on"); tap("Flash off"); waitFor("Camera setting applied", 15000); waitFor("Flash off")
                            instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: flash ON/OFF while recording acknowledged") })
                        }
                        var previousTimestamp = 0L
                        repeat(4) {
                            SystemClock.sleep(1000); waitFor("Live camera", 500)
                            activity.onActivity {
                                val timestamp = requireNotNull(viewerTexture.get().surfaceTexture).timestamp
                                assertTrue("Viewer stopped rendering during recording", timestamp > previousTimestamp)
                                previousTimestamp = timestamp
                            }
                        }
                        if (videoExit == "disconnect") { tap("Disconnect"); waitFor("Refresh") }
                        else if (videoExit == "background") waitFor("Could not receive camera. Check Wi-Fi and the streaming PIN, then try again.", 25000)
                        else {
                            tap("Stop recording"); waitFor("Video saved on camera device", 70000)
                            waitFor("Record video"); waitFor("Live camera")
                            repeat(4) {
                                SystemClock.sleep(1000)
                                activity.onActivity {
                                    val timestamp = requireNotNull(viewerTexture.get().surfaceTexture).timestamp
                                    assertTrue("Viewer stopped rendering after recording", timestamp > previousTimestamp)
                                    previousTimestamp = timestamp
                                }
                            }
                        }
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult",
                            "view: remote start + four-second rendered preview; lens disabled; exit=$videoExit") })
                    }
                    if (videoExit == "stop") { repeat(15) { SystemClock.sleep(1000); waitFor("Live camera", 500) }; tap("Disconnect"); waitFor("Refresh") }
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: normal camera + discovery + selected PIN policy + live view + cleanup passed") })
            }
        } finally {
            invitationFile.delete()
            if ((remotePhoto || remoteVideo || cameraChanges || remoteSettings || viewerScreenshot || remoteInteractions) && role == "send" && !wasUnlocked) app.sessionManager.lock()
            if (role == "send") {
                val edit = prefs.edit().clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                } }
                assertTrue("Could not restore streaming settings", edit.commit())
            }
        }
    }

    @Test fun homeViewerPlacementAndLockedNavigationAreCorrect() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val context = instrumentation.targetContext
        val app = context.applicationContext as SecretVaultApp
        assertTrue("Unlock the phone locally before the Home check", !context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val keepOpen = app.sessionManager.keepUnlocked.value
        val navigation = AtomicReference<NavHostController>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                activity.onActivity { screen ->
                    app.sessionManager.unlock()
                    screen.setContent { SecretVaultTheme {
                        val nav = rememberNavController(); navigation.set(nav)
                        VaultNavGraph(app, navController = nav)
                    } }
                }
                val composeReadyBy = SystemClock.elapsedRealtime() + 5000
                while (navigation.get() == null && SystemClock.elapsedRealtime() < composeReadyBy) SystemClock.sleep(50)
                assertTrue("Home navigation composition did not start", navigation.get() != null)
                activity.onActivity { navigation.get().navigate(Screen.VaultHome.route) }
                val view = waitFor("View stream")
                val import = requireNotNull(find { it.contentDescription?.toString() == "Import Photos & Videos" })
                val viewBounds = Rect(); val importBounds = Rect()
                view.getBoundsInScreen(viewBounds); import.getBoundsInScreen(importBounds)
                assertTrue("View stream must be above Import", viewBounds.centerY() < importBounds.centerY())
                lateinit var gallery: GalleryViewModel
                activity.onActivity { gallery = ViewModelProvider(navigation.get().getBackStackEntry(Screen.VaultHome.route))[GalleryViewModel::class.java] }
                activity.onActivity { gallery.setAlbumFilter("stream-ui-nonexistent-album", "Stream UI fixture") }
                waitFor("Stream UI fixture"); assertTrue(find { it.text?.toString() == "View stream" || it.contentDescription?.toString() == "View stream" } == null)
                activity.onActivity { gallery.setAlbumFilter(null) }; waitFor("View stream")
                activity.onActivity { gallery.startSelectionWith("stream-ui-nonexistent-item") }
                waitFor("1 selected"); SystemClock.sleep(400)
                assertTrue(find { it.text?.toString() == "View stream" || it.contentDescription?.toString() == "View stream" } == null)
                activity.onActivity { gallery.clearSelection() }; waitFor("View stream")
                tap("View stream"); waitFor("Refresh"); tap("Back"); waitFor("View stream")
                tap("View stream"); waitFor("Refresh")
                activity.onActivity { app.sessionManager.lock() }
                val deadline = SystemClock.elapsedRealtime() + 5000
                var route: String? = null
                do { activity.onActivity { route = navigation.get().currentBackStackEntry?.destination?.route }; SystemClock.sleep(100) }
                while (route != Screen.DecoyWeather.route && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Locked viewer was not removed", route == Screen.DecoyWeather.route)
            }
        } finally {
            if (wasUnlocked || keepOpen) app.sessionManager.unlock()
            if (keepOpen) app.sessionManager.setKeepUnlocked(true)
        }
    }

    @Test fun settingsPinPersistsAndBackgroundDoesNotResumeBroadcasting() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("sv_stream_config", android.content.Context.MODE_PRIVATE)
        val saved = prefs.all
        val pins = StreamPinManager(context)
        val cameraModel = CameraViewModel()
        prefs.edit().remove("pin").putBoolean("require_pin", true).commit()
        fun fillPin(value: String) {
            val fields = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isEditable) fields.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue("Two masked PIN fields required", fields.size == 2 && fields.all { it.isPassword })
            fields.forEach { assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val app = context.applicationContext as SecretVaultApp
                fun openSettings() {
                    activity.onActivity { it.setContent { SecretVaultTheme { SettingsScreen(app.pinManager, {}) } } }
                    waitFor("Set streaming PIN")
                    assertTrue("Test stream still appears in Settings", find { it.text?.toString() == "Camera stream test" } == null)
                }
                fun openCamera() {
                    activity.onActivity { it.setContent { SecretVaultTheme { CameraScreen(app, cameraModel, {}, {}) } } }
                    waitFor("Stream")
                }
                fun saveSettingsPin(value: String) {
                    tap("Set streaming PIN"); waitFor("Confirm PIN"); fillPin(value); tap("Save PIN")
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    while (find { it.text?.toString() == "Confirm PIN" } != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                    assertTrue("Streaming PIN dialog did not close", find { it.text?.toString() == "Confirm PIN" } == null)
                    val stored = requireNotNull(pins.readPin())
                    try { assertTrue("Settings did not persist streaming PIN", stored.concatToString() == value) } finally { stored.fill('\u0000') }
                }
                openSettings()
                tap("Require streaming PIN")
                assertTrue("PIN-off preference did not persist", !StreamPinManager(context).isPinRequired())
                assertTrue("PIN-off unexpectedly required PIN setup", !pins.isConfigured())
                openCamera(); tap("Stream"); waitFor("Waiting for a viewer", 20000)
                assertTrue("PIN-off prompted for setup", find { it.text?.toString() == "Confirm PIN" } == null)
                tap("Stop stream"); waitFor("Stream")
                openSettings(); tap("Require streaming PIN")
                assertTrue("PIN-on preference did not persist", StreamPinManager(context).isPinRequired())
                saveSettingsPin(FIXTURE_PIN)
                openCamera()
                val streamBounds = Rect().also { requireNotNull(find { it.contentDescription?.toString() == "Stream" }).getBoundsInScreen(it) }
                val flashBounds = Rect().also { requireNotNull(find { it.contentDescription?.toString()?.startsWith("Flash ") == true }).getBoundsInScreen(it) }
                assertTrue("Stream icon is not above Flash", streamBounds.bottom <= flashBounds.top && streamBounds.centerX() == flashBounds.centerX())
                tap("Stream"); waitFor("Waiting for a viewer", 20000)
                tap("Stop stream"); waitFor("Stream")
                openSettings()
                saveSettingsPin("7426")
                openCamera()
                assertTrue("Changing PIN broadcast automatically", find { it.contentDescription?.toString() == "Stop stream" } == null)
                tap("Stream"); waitFor("Waiting for a viewer", 20000)
                activity.moveToState(Lifecycle.State.CREATED); SystemClock.sleep(1000)
                activity.moveToState(Lifecycle.State.RESUMED); waitFor("Stream", 20000)
                assertTrue("Returning resumed broadcast automatically", find { it.contentDescription?.toString() == "Stop stream" } == null)
                tap("Stream"); waitFor("Waiting for a viewer", 20000); tap("Stop stream"); waitFor("Stream")
            }
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
            } }
            assertTrue(edit.commit())
        }
    }

    companion object { private const val FIXTURE_PIN = "9164" }
}
