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
            find { it.text?.toString() == text }?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        val foreground = instrumentation.uiAutomation.rootInActiveWindow?.packageName
        val status = find { it.text?.toString() in listOf("Connecting…", "Waiting for first frame…", "Choose Send or View",
            "Could not receive camera. Check Wi-Fi and the connection details, then try again.",
            "Video could not decode. Start a new session.") }?.text
        throw AssertionError("Stream UI did not show: $text; foreground package: $foreground; status: $status")
    }

    private fun tap(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        do {
            var target = find { it.text?.toString() == text }
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
}
