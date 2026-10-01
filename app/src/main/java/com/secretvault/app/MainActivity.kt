package com.secretvault.app

import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.secretvault.app.ui.navigation.VaultNavGraph
import com.secretvault.app.ui.theme.SecretVaultTheme

class MainActivity : FragmentActivity() {
    private var cameraEntryRequest by mutableIntStateOf(0)
    private var awaitingVaultEntry = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Strict privacy: Block screenshots and recents thumbnail preview
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val app = application as SecretVaultApp

        setContent {
            SecretVaultTheme {
                VaultNavGraph(app = app, cameraEntryRequest = cameraEntryRequest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterVaultIfDeviceActive()
    }

    override fun onPause() {
        val session = (application as SecretVaultApp).sessionManager
        if (session.keepUnlocked.value) {
            awaitingVaultEntry = true
            session.onAppBackgrounded()
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && awaitingVaultEntry) enterVaultIfDeviceActive()
    }

    private fun enterVaultIfDeviceActive() {
        if (!awaitingVaultEntry) return
        val app = application as SecretVaultApp
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val unlocked = getSystemService(KeyguardManager::class.java)?.isDeviceLocked == false
        if (!interactive || !unlocked) {
            app.sessionManager.onScreenOff()
            return
        }
        awaitingVaultEntry = false
        if (app.sessionManager.onAppForegrounded()) cameraEntryRequest++
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as SecretVaultApp).sessionManager.updateActivity()
    }

    override fun onStop() {
        awaitingVaultEntry = true
        super.onStop()
        val app = application as? SecretVaultApp
        app?.sessionManager?.onAppBackgrounded()
    }
}
