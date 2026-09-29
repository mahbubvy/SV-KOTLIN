package com.secretvault.app.ui.gallery.components

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.secretvault.app.core.model.SortOrder
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultSurface

@Composable
fun SortMenuDropdown(
    currentSort: SortOrder,
    onSortSelected: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.Sort,
                contentDescription = "Sort media",
                tint = TextSecondary
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(VaultSurface)
        ) {
            SortMenuItem(
                title = "Newest First",
                isSelected = currentSort == SortOrder.NEWEST_FIRST,
                onClick = {
                    onSortSelected(SortOrder.NEWEST_FIRST)
                    expanded = false
                }
            )
            SortMenuItem(
                title = "Oldest First",
                isSelected = currentSort == SortOrder.OLDEST_FIRST,
                onClick = {
                    onSortSelected(SortOrder.OLDEST_FIRST)
                    expanded = false
                }
            )
            SortMenuItem(
                title = "Name (A – Z)",
                isSelected = currentSort == SortOrder.NAME_A_Z,
                onClick = {
                    onSortSelected(SortOrder.NAME_A_Z)
                    expanded = false
                }
            )
            SortMenuItem(
                title = "Name (Z – A)",
                isSelected = currentSort == SortOrder.NAME_Z_A,
                onClick = {
                    onSortSelected(SortOrder.NAME_Z_A)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun SortMenuItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text = title,
                color = if (isSelected) VaultAccent else TextPrimary
            )
        },
        trailingIcon = {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = VaultAccent
                )
            }
        },
        onClick = onClick
    )
}
