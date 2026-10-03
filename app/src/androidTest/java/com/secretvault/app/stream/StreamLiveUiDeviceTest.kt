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
import com.secretvault.app.core.camera.VideoMode
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
        throw AssertionError("Stream UI did not show: $text; foreground package: $foreground; status: $status")
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
        assumeTrue(role == "send" || role == "view")
        val context = instrumentation.targetContext
        val app = context.applicationContext as SecretVaultApp
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val photoTestStartedAt = System.currentTimeMillis()
        val mediaBefore = if (remotePhoto && role == "send") runBlocking { app.mediaRepository.getTotalCount() } else 0
        assertTrue("Unlock the phone locally before the live camera check", !context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
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
        try {
            if (remotePhoto && role == "send") app.sessionManager.unlock()
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
                if (role == "send") {
                    invitationFile.delete(); tap("Stream"); waitFor("Waiting for a viewer", 20000)
                    invitationFile.writeText("svstream2://ready/" + java.net.URLEncoder.encode("SV ${Build.MODEL.take(48)}", "UTF-8"))
                    waitFor("Viewer connected · Stop stream to record", 60000)
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
                    repeat(10) { SystemClock.sleep(1000); waitFor("Viewer connected · Stop stream to record", 500) }
                    waitFor("Viewer disconnected or connection stalled. Start a new session.", if (remotePhoto) 60000 else 20000)
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
                        val matrix = FloatArray(9)
                        view.getTransform(android.graphics.Matrix()).getValues(matrix)
                        assertTrue("Portrait stream has no viewer quarter-turn: ${matrix.toList()}",
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSCALE_X]) < 0.001f &&
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSCALE_Y]) < 0.001f &&
                            kotlin.math.abs(matrix[android.graphics.Matrix.MSKEW_X]) > 0.01f)
                    }
                    instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: NSD discovery ${discoveryMs}ms; connection to first-frame ${SystemClock.elapsedRealtime() - connectAt}ms") })
                    if (remotePhoto) {
                        tap("Take photo"); waitFor("Photo saved on camera device", 40000)
                        waitFor("Live camera")
                        instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "view: remote photo saved acknowledgment; live preview remained active") })
                    }
                    repeat(15) { SystemClock.sleep(1000); waitFor("Live camera", 500) }
                    tap("Disconnect"); waitFor("Refresh")
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("streamResult", "$role: normal camera + discovery + selected PIN policy + live view + cleanup passed") })
            }
        } finally {
            invitationFile.delete()
            if (remotePhoto && role == "send" && !wasUnlocked) app.sessionManager.lock()
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
