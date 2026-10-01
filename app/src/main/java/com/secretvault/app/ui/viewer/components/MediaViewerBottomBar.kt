package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultError

@Composable
fun MediaViewerBottomBar(
    onShare: () -> Unit,
    onEdit: (() -> Unit)?,
    onSetCover: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ViewerActionItem(
            icon = Icons.Default.Share,
            label = "Share",
            onClick = onShare
        )
        if (onEdit != null) {
            ViewerActionItem(
                icon = Icons.Default.ContentCut,
                label = "Edit",
                onClick = onEdit
            )
        }
        ViewerActionItem(
            icon = Icons.Default.Image,
            label = "Set Cover",
            onClick = onSetCover
        )
        ViewerActionItem(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            label = "Move",
            onClick = onMove
        )
        ViewerActionItem(
            icon = Icons.Default.Delete,
            label = "Delete",
            isDestructive = true,
            onClick = onDelete
        )
    }
}

@Composable
private fun ViewerActionItem(
    icon: ImageVector,
    label: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (isDestructive) VaultError else TextPrimary

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = color,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
