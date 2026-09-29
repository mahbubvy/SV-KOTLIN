package com.secretvault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = WeatherBlue,
    secondary = VaultAccent,
    tertiary = WeatherSky,
    background = WeatherBackground,
    surface = WeatherSurface,
    onPrimary = TextPrimary,
    onSecondary = VaultDarkBg,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = VaultError
)

@Composable
fun SecretVaultTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
