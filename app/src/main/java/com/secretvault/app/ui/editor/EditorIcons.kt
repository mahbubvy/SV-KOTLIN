package com.secretvault.app.ui.editor

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg

/** The icon shown with each colour adjustment, in both editors. */
internal val Adjustment.icon: ImageVector
    get() = when (this) {
        Adjustment.BRIGHTNESS -> Icons.Default.Brightness6
        Adjustment.EXPOSURE -> Icons.Default.Exposure
        Adjustment.CONTRAST -> Icons.Default.Contrast
        Adjustment.SATURATION -> Icons.Default.WaterDrop
        Adjustment.VIBRANCE -> Icons.Default.Palette
        Adjustment.SHADOWS -> Icons.Default.DarkMode
        Adjustment.HIGHLIGHTS -> Icons.Default.WbSunny
        Adjustment.WARMTH -> Icons.Default.Thermostat
        Adjustment.TINT -> Icons.Default.Colorize
        Adjustment.FADE -> Icons.Default.Opacity
    }

/** A chip's leading icon, turned [rotation] degrees (for a 9:16 from a 16:9 icon). */
@Composable
internal fun ChipIcon(icon: ImageVector, rotation: Float = 0f) =
    Icon(icon, null, Modifier.size(18.dp).rotate(rotation))

/** The editors' chip colours: grey, or the accent with dark text and icon when picked. */
@Composable
internal fun editorChipColors() = FilterChipDefaults.filterChipColors(labelColor = TextSecondary, iconColor = TextSecondary,
    selectedContainerColor = VaultAccent, selectedLabelColor = VaultDarkBg, selectedLeadingIconColor = VaultDarkBg)
