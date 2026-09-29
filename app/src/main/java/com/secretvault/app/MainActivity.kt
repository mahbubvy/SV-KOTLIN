package com.secretvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.secretvault.app.ui.navigation.VaultNavGraph
import com.secretvault.app.ui.theme.SecretVaultTheme

class MainActivity : FragmentActivity() {

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
                VaultNavGraph(app = app)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as? SecretVaultApp
        app?.sessionManager?.onAppBackgrounded()
    }
}
