package com.secretvault.app.ui.gallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultError
import com.secretvault.app.ui.theme.VaultSurface

@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    onMoveClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = selectedCount > 0

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(VaultSurface)
            .navigationBarsPadding()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionButton(
            icon = Icons.Default.DriveFileMove,
            label = "Move",
            enabled = enabled,
            onClick = onMoveClick
        )
        ActionButton(
            icon = Icons.Default.Delete,
            label = "Delete",
            enabled = enabled,
            isDestructive = true,
            onClick = onDeleteClick
        )
        ActionButton(
            icon = Icons.Default.Share,
            label = "Share",
            enabled = enabled,
            onClick = onShareClick
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = when {
        !enabled -> TextPrimary.copy(alpha = 0.3f)
        isDestructive -> VaultError
        else -> TextPrimary
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = color
        )
    }
}
