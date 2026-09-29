package com.secretvault.app.ui.gallery.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.secretvault.app.core.model.SortOrder
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurface

import androidx.compose.material.icons.filled.Deselect

@Composable
fun GalleryTopBar(
    title: String,
    isSelectionMode: Boolean,
    selectedCount: Int,
    isAllSelected: Boolean = false,
    currentSort: SortOrder,
    showBackButton: Boolean,
    onBackClick: () -> Unit,
    onSortSelected: (SortOrder) -> Unit,
    onSelectAllClick: () -> Unit,
    onCloseSelectionClick: () -> Unit,
    onLockClick: () -> Unit,
    onBackupRestoreClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onCloseSelectionClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel selection",
                        tint = TextPrimary
                    )
                }
                Text(
                    text = "$selectedCount selected",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VaultAccent
                )
            }

            IconButton(onClick = onSelectAllClick) {
                Icon(
                    imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                    contentDescription = if (isAllSelected) "Deselect All" else "Select All",
                    tint = TextPrimary
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showBackButton) {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                }
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(start = if (showBackButton) 0.dp else 12.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onBackupRestoreClick != null) {
                    IconButton(onClick = onBackupRestoreClick) {
                        Icon(
                            imageVector = Icons.Default.SettingsBackupRestore,
                            contentDescription = "Backup & Restore",
                            tint = TextSecondary
                        )
                    }
                }

                SortMenuDropdown(
                    currentSort = currentSort,
                    onSortSelected = onSortSelected
                )

                IconButton(onClick = onLockClick) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Vault",
                        tint = VaultAccent
                    )
                }
            }
        }
    }
}
