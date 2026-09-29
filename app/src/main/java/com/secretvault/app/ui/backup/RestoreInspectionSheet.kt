package com.secretvault.app.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.core.backup.SvBackupManifest
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.theme.VaultSurfaceVariant

@Composable
fun RestoreInspectionSheet(
    manifest: SvBackupManifest,
    archiveFileName: String,
    onDismiss: () -> Unit,
    onConfirmRestore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalBytes = manifest.totalBytes()
    val sizeFormatted = if (totalBytes > 1024 * 1024) {
        String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
    } else {
        "${totalBytes / 1024} KB"
    }

    val photoCount = manifest.items.count { it.mediaType == "photo" }
    val videoCount = manifest.items.count { it.mediaType == "video" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VaultDarkBg,
        shape = RoundedCornerShape(20.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = VaultAccent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Backup Archive Verified",
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
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "The backup was decrypted and authenticated successfully. Review the contents below before restoring:",
                    color = TextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                // Archive Metrics Card
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = VaultSurface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Media", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                "${manifest.items.size} items ($photoCount photos, $videoCount videos)",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Albums", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                "${manifest.albums.size} albums",
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Payload Size", color = TextSecondary, fontSize = 13.sp)
                            Text(
                                sizeFormatted,
                                color = VaultAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }

                        if (archiveFileName.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("File", color = TextSecondary, fontSize = 13.sp)
                                Text(
                                    archiveFileName,
                                    color = TextMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmRestore,
                colors = ButtonDefaults.buttonColors(containerColor = VaultAccent),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = "Restore ${manifest.items.size} Items",
                    color = VaultDarkBg,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        modifier = modifier
    )
}
