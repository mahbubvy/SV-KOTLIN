package com.secretvault.app.ui.viewer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.model.MediaType
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MediaInfoDialog(
    item: MediaItem,
    albumName: String,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(item.createdAt))

    val sizeStr = when {
        item.sizeBytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", item.sizeBytes.toDouble() / (1024 * 1024))
        else -> String.format(Locale.US, "%.1f KB", item.sizeBytes.toDouble() / 1024)
    }

    val dimensionStr = if (item.width > 0 && item.height > 0) {
        "${item.width} × ${item.height}"
    } else {
        "Unknown"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = VaultSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "File Details",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(16.dp))

                InfoRow(label = "Filename", value = item.filename)
                InfoRow(label = "Type", value = if (item.mediaType == MediaType.PHOTO) "Encrypted Photo" else "Encrypted Video")
                InfoRow(label = "Size", value = sizeStr)
                InfoRow(label = "Dimensions", value = dimensionStr)
                if (item.mediaType == MediaType.VIDEO && item.durationMs > 0) {
                    val sec = (item.durationMs / 1000L).toInt()
                    InfoRow(label = "Duration", value = String.format("%d:%02d", sec / 60, sec % 60))
                }
                InfoRow(label = "Album", value = albumName)
                InfoRow(label = "Created", value = dateStr)
                InfoRow(label = "Encryption", value = "AES-256-GCM Hardware-backed")

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = VaultAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextMuted,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(0.65f)
        )
    }
}
