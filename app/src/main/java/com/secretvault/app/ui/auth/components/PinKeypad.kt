package com.secretvault.app.ui.auth.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurfaceVariant

@Composable
fun PinKeypad(
    onDigitClick: (Char) -> Unit,
    onBackspaceClick: () -> Unit,
    onBiometricClick: () -> Unit,
    isBiometricAvailable: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("BIO", "0", "DEL")
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { key ->
                    when (key) {
                        "BIO" -> {
                            if (isBiometricAvailable) {
                                KeypadActionButton(
                                    onClick = onBiometricClick,
                                    enabled = enabled
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Fingerprint,
                                        contentDescription = "Biometric Unlock",
                                        tint = VaultAccent,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.size(72.dp))
                            }
                        }
                        "DEL" -> {
                            KeypadActionButton(
                                onClick = onBackspaceClick,
                                enabled = enabled
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                                    contentDescription = "Backspace",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        else -> {
                            KeypadNumberButton(
                                digit = key.first(),
                                onClick = { onDigitClick(key.first()) },
                                enabled = enabled
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadNumberButton(
    digit: Char,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(VaultSurfaceVariant.copy(alpha = 0.7f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = digit.toString(),
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) TextPrimary else TextSecondary.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun KeypadActionButton(
    onClick: () -> Unit,
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
