package com.secretvault.app.ui.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.secretvault.app.ui.auth.components.PinDotsIndicator
import com.secretvault.app.ui.auth.components.PinKeypad
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    LaunchedEffect(Unit) {
        val isBiometricAvailable = BiometricAuthHelper.isBiometricAvailable(context)

        launch {
            viewModel.events.collect { event ->
                when (event) {
                    is AuthEvent.UnlockSuccess, is AuthEvent.SetupSuccess -> {
                        onAuthenticated()
                    }
                    is AuthEvent.TriggerBiometric -> {
                        // Allow screen transition animation to finish and activity window focus to settle
                        delay(250L)
                        val act = activity ?: context.findFragmentActivity()
                        act?.let {
                            BiometricAuthHelper.promptBiometric(
                                activity = it,
                                onSuccess = { viewModel.onBiometricSuccess() },
                                onError = { err -> Log.w("AuthScreen", "Auto-biometric error: $err") },
                                onFailed = { Log.d("AuthScreen", "Auto-biometric failed attempt") }
                            )
                        }
                    }
                }
            }
        }

        viewModel.initialize(isBiometricAvailable)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultDarkBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar with Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            // Header Icon & Title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = VaultAccent,
                    modifier = Modifier.padding(8.dp)
                )

                Text(
                    text = state.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Text(
                    text = state.subtitle,
                    fontSize = 14.sp,
                    color = if (state.hasError) VaultError else TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // PIN Indicator Dots
                if (state.mode != AuthMode.LOCKED_OUT) {
                    PinDotsIndicator(
                        pinLength = state.pinLength,
                        hasError = state.hasError
                    )
                } else {
                    Text(
                        text = "Try again in ${state.remainingLockoutSeconds}s",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = VaultError
                    )
                }
            }

            // Keypad
            PinKeypad(
                onDigitClick = { digit: Char -> viewModel.onDigit(digit) },
                onBackspaceClick = { viewModel.onBackspace() },
                onBiometricClick = {
                    val act = activity ?: context.findFragmentActivity()
                    act?.let {
                        BiometricAuthHelper.promptBiometric(
                            activity = it,
                            onSuccess = { viewModel.onBiometricSuccess() },
                            onError = { /* fallback */ },
                            onFailed = { /* failed */ }
                        )
                    }
                },
                isBiometricAvailable = state.isBiometricAvailable,
                enabled = state.mode != AuthMode.LOCKED_OUT,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}
