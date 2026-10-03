package com.secretvault.app.camera

import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.secretvault.app.MainActivity
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.camera.*
import com.secretvault.app.ui.camera.CameraScreen
import com.secretvault.app.ui.camera.CameraViewModel
import com.secretvault.app.ui.camera.components.CameraZoomSlider
import com.secretvault.app.core.stream.StreamInteractionState
import com.secretvault.app.ui.theme.SecretVaultTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraSliderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SecretVaultApp
    @org.junit.Before fun retrieveAppWindows() {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        until("Unlock Pixel for the camera slider test", 120000) {
            !app.getSystemService(KeyguardManager::class.java).isDeviceLocked &&
                app.getSystemService(android.os.PowerManager::class.java).isInteractive
        }
    }
    private fun findSlider(): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "Camera zoom slider") return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return visit(instrumentation.uiAutomation.rootInActiveWindow) ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { visit(it.root) }
    }
    private fun until(message: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { assertTrue(message, SystemClock.elapsedRealtime() < deadline); SystemClock.sleep(100) }
    }
    @Test fun syntheticSliderLayoutAndDisabledState() {
        val zoom = mutableFloatStateOf(2f)
        val enabled = androidx.compose.runtime.mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { SecretVaultTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CameraZoomSlider(StreamInteractionState(cameraId = "synthetic", minZoom = 1f, maxZoom = 7f, zoom = zoom.floatValue),
                        enabled.value, { zoom.floatValue = it }, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp))
                }
            } } }
            until("Synthetic slider is missing") { findSlider()?.isEnabled == true }
            scenario.onActivity { enabled.value = false }
            until("Busy slider remains enabled") { findSlider()?.isEnabled == false }
            assertFalse(requireNotNull(findSlider()).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 4f) }))
            assertEquals(2f, zoom.floatValue, 0.01f)
        }
    }
    @Test fun rightSliderDragAndAccessibleProgressChangeHardwareZoom() {
        val wasUnlocked = app.sessionManager.isUnlocked.value
        val model = CameraViewModel(VideoMode.UHD_60).apply { setCameraMode(CameraMode.VIDEO) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    app.sessionManager.unlock()
                    activity.setContent { SecretVaultTheme { CameraScreen(app, model, {}, {}) } }
                }
                instrumentation.waitForIdleSync(); app.sessionManager.unlock()
                try {
                    until("Zoom slider did not become ready") { findSlider()?.isEnabled == true }
                } catch (error: AssertionError) {
                    val slider = findSlider()
                    throw AssertionError("${error.message}; slider=$slider; root=${instrumentation.uiAutomation.rootInActiveWindow?.packageName}; windows=${instrumentation.uiAutomation.windows.map { it.root?.packageName }}", error)
                }
                val provider = ProcessCameraProvider.getInstance(app).get()
                val info = LensFacing.BACK.selector.filter(provider.availableCameraInfos).first()
                val bounds = Rect().also { requireNotNull(findSlider()).getBoundsInScreen(it) }
                assertTrue("Slider is not vertical", bounds.height() > bounds.width())
                assertTrue("Slider is not on the right", bounds.centerX() > app.resources.displayMetrics.widthPixels * 0.8f)
                assertTrue("Slider exceeds screen", bounds.right <= app.resources.displayMetrics.widthPixels && bounds.top >= 0)
                fun setZoom(value: Float) {
                    assertTrue(requireNotNull(findSlider()).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
                    try {
                        until("Hardware did not apply slider zoom $value") { kotlin.math.abs((info.zoomState.value?.zoomRatio ?: 0f) - value) < 0.05f }
                    } catch (error: AssertionError) {
                        throw AssertionError("${error.message}; observed=${info.zoomState.value?.zoomRatio}, slider=${findSlider()?.stateDescription}, cameras=${provider.availableCameraInfos.map { it.zoomState.value?.zoomRatio }}", error)
                    }
                }
                setZoom(2f)
                val range = requireNotNull(info.zoomState.value)
                val initialY = bounds.bottom - bounds.height() * (2f - range.minZoomRatio) / (range.maxZoomRatio - range.minZoomRatio)
                val targetY = bounds.top + bounds.height() * 0.2f
                val start = SystemClock.uptimeMillis()
                fun touch(action: Int, y: Float) {
                    val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), y, 0)
                    try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN, initialY)
                repeat(12) { index -> SystemClock.sleep(30); touch(MotionEvent.ACTION_MOVE, initialY + (targetY - initialY) * (index + 1) / 12f) }
                touch(MotionEvent.ACTION_UP, targetY)
                until("Slider drag did not increase real camera zoom") { (info.zoomState.value?.zoomRatio ?: 0f) > 2.5f }
                setZoom(1f)
                instrumentation.sendStatus(0, Bundle().apply { putString("cameraResult", "Right-side vertical slider drag changes hardware zoom; accessible 2x and 1x controls pass; range ${range.minZoomRatio}..${range.maxZoomRatio}") })
            }
        } finally { if (!wasUnlocked) app.sessionManager.lock() }
    }
}
