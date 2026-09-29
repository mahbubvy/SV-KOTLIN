package com.secretvault.app.ui.gallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.backup.BackupExportProgress
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultError

@Composable
fun ExportProgressDialog(
    progress: BackupExportProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = { /* Modal while exporting */ },
        containerColor = VaultDarkBg,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = VaultAccent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Exporting .svbackup",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = progress.stage,
                    color = TextMuted,
                    fontSize = 14.sp
                )

                if (progress.totalItems > 0) {
                    LinearProgressIndicator(
                        progress = { progress.progressPercentage },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = VaultAccent,
                        trackColor = VaultDarkBg
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Item ${progress.currentItem} of ${progress.totalItems}",
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${(progress.progressPercentage * 100).toInt()}%",
                            color = VaultAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = VaultAccent,
                        trackColor = VaultDarkBg
                    )
                }

                if (progress.activeFileName.isNotBlank()) {
                    Text(
                        text = progress.activeFileName,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = VaultError)
            }
        },
        modifier = modifier
    )
}
