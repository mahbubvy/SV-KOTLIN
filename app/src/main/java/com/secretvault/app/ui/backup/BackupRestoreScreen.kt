package com.secretvault.app.ui.backup

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.backup.BackupScope
import com.secretvault.app.core.backup.SvBackupManifest
import com.secretvault.app.core.backup.VaultSummary
import com.secretvault.app.core.model.Album
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.ui.gallery.components.BackupPasswordDialog
import com.secretvault.app.ui.gallery.components.BackupProgressDialog
import com.secretvault.app.ui.gallery.components.ExportBackupDialog
import com.secretvault.app.ui.gallery.components.ExportProgressDialog
import com.secretvault.app.ui.theme.TextMuted
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultSurface
import com.secretvault.app.ui.theme.VaultSurfaceVariant
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupSubScreen {
    MAIN,
    SELECT_ALBUMS,
    SELECT_MEDIA
}

@Composable
fun BackupRestoreScreen(
    albums: List<Album>,
    mediaItems: List<MediaItem>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as SecretVaultApp
    val coroutineScope = rememberCoroutineScope()

    var currentSubScreen by remember { mutableStateOf(BackupSubScreen.MAIN) }

    // Return to the backup menu before leaving the backup screen.
    BackHandler {
        if (currentSubScreen != BackupSubScreen.MAIN) {
            currentSubScreen = BackupSubScreen.MAIN
        } else {
            onBack()
        }
    }

    // Vault Summary state
    var vaultSummary by remember { mutableStateOf<VaultSummary?>(null) }
    LaunchedEffect(Unit) {
        vaultSummary = app.backupExportManager.getVaultSummary()
    }

    // Export state
    val exportProgress by app.backupExportManager.progress.collectAsState()
    var pendingExportScope by remember { mutableStateOf<BackupScope>(BackupScope.FullVault) }
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPasswordToUse by remember { mutableStateOf<String?>(null) }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        app.sessionManager.setExternalPickerInProgress(false)
        if (uri != null && exportPasswordToUse != null) {
            val password = exportPasswordToUse!!
            val scope = pendingExportScope
            coroutineScope.launch {
                val result = app.backupExportManager.exportBackup(uri, password, scope)
                if (result.success) {
                    Toast.makeText(
                        context,
                        "Exported ${result.exportedItemsCount} items across ${result.exportedAlbumsCount} albums",
                        Toast.LENGTH_LONG
                    ).show()
                    currentSubScreen = BackupSubScreen.MAIN
                } else {
                    Toast.makeText(
                        context,
                        result.errorMessage ?: "Export failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
                exportPasswordToUse = null
            }
        } else {
            exportPasswordToUse = null
        }
    }

    // Restore state
    val importProgress by app.backupImportManager.progress.collectAsState()
    var selectedRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var selectedRestoreFileName by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var inspectedManifest by remember { mutableStateOf<SvBackupManifest?>(null) }
    var restorePasswordToUse by remember { mutableStateOf<String?>(null) }

    val backupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        app.sessionManager.setExternalPickerInProgress(false)
        if (uri != null) {
            selectedRestoreUri = uri
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val name = cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (it.moveToFirst() && nameIndex != -1) it.getString(nameIndex) else null
            } ?: uri.lastPathSegment ?: "backup.svbackup"
            selectedRestoreFileName = name
            showRestorePasswordDialog = true
        }
    }

    when (currentSubScreen) {
        BackupSubScreen.SELECT_ALBUMS -> {
            SelectAlbumsBackupSheet(
                albums = albums,
                onBack = { currentSubScreen = BackupSubScreen.MAIN },
                onContinue = { selectedAlbumIds ->
                    pendingExportScope = BackupScope.SelectedAlbums(selectedAlbumIds)
                    showExportPasswordDialog = true
                }
            )
        }
        BackupSubScreen.SELECT_MEDIA -> {
            SelectMediaBackupSheet(
                mediaItems = mediaItems,
                onBack = { currentSubScreen = BackupSubScreen.MAIN },
                onContinue = { selectedMediaIds ->
                    pendingExportScope = BackupScope.SelectedMedia(selectedMediaIds)
                    showExportPasswordDialog = true
                }
            )
        }
        BackupSubScreen.MAIN -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(VaultDarkBg)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    // Top Navigation Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                        Text(
                            text = "Backup & Restore",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    // Grouped Inset Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Section 1: CREATE BACKUP
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "CREATE BACKUP",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(start = 4.dp)
                            )

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = VaultSurface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    // Row 1: Full Vault
                                    val summarySubtitle = if (vaultSummary != null) {
                                        val s = vaultSummary!!
                                        val sizeMb = if (s.estimatedSizeBytes > 1024 * 1024) {
                                            String.format(Locale.US, "%.1f MB", s.estimatedSizeBytes / (1024.0 * 1024.0))
                                        } else {
                                            "${s.estimatedSizeBytes / 1024} KB"
                                        }
                                        "${s.totalItems} items (${s.totalPhotos} photos, ${s.totalVideos} videos) • $sizeMb"
                                    } else {
                                        "Export all albums, photos and videos"
                                    }

                                    BackupActionRow(
                                        icon = Icons.Default.Security,
                                        title = "Full Vault",
                                        subtitle = summarySubtitle,
                                        onClick = {
                                            pendingExportScope = BackupScope.FullVault
                                            showExportPasswordDialog = true
                                        }
                                    )

                                    HorizontalDivider(color = VaultSurfaceVariant, thickness = 0.8.dp)

                                    // Row 2: Album Backup
                                    BackupActionRow(
                                        icon = Icons.Default.FolderCopy,
                                        title = "Album Backup",
                                        subtitle = "Export specific albums with cover artwork",
                                        onClick = { currentSubScreen = BackupSubScreen.SELECT_ALBUMS }
                                    )

                                    HorizontalDivider(color = VaultSurfaceVariant, thickness = 0.8.dp)

                                    // Row 3: Select Photos & Videos
                                    BackupActionRow(
                                        icon = Icons.Default.PhotoLibrary,
                                        title = "Select Photos & Videos",
                                        subtitle = "Export individual media items",
                                        onClick = { currentSubScreen = BackupSubScreen.SELECT_MEDIA }
                                    )
                                }
                            }
                        }

                        // Section 2: RESTORE VAULT
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "RESTORE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                                modifier = Modifier.padding(start = 4.dp)
                            )

                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = VaultSurface),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SettingsBackupRestore,
                                        contentDescription = null,
                                        tint = VaultAccent,
                                        modifier = Modifier.size(36.dp)
                                    )

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Restore from .svbackup",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Select an encrypted backup file to restore into your vault. Content will be verified before importing.",
                                            fontSize = 13.sp,
                                            color = TextSecondary,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            lineHeight = 18.sp
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            app.sessionManager.setExternalPickerInProgress(true)
                                            backupPickerLauncher.launch(arrayOf("*/*"))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = VaultAccent),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                    ) {
                                        Text(
                                            text = "Browse Backup File...",
                                            color = VaultDarkBg,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Export Password Dialog
    if (showExportPasswordDialog) {
        ExportBackupDialog(
            onDismiss = { showExportPasswordDialog = false },
            onConfirm = { password ->
                showExportPasswordDialog = false
                exportPasswordToUse = password
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val filename = when (pendingExportScope) {
                    is BackupScope.FullVault -> "SecretVault_FullBackup_$timestamp.svbackup"
                    is BackupScope.SelectedAlbums -> "SecretVault_AlbumsBackup_$timestamp.svbackup"
                    is BackupScope.SelectedMedia -> "SecretVault_MediaBackup_$timestamp.svbackup"
                }
                app.sessionManager.setExternalPickerInProgress(true)
                backupExportLauncher.launch(filename)
            }
        )
    }

    // Export Live Progress Dialog
    if (exportProgress.isExporting) {
        ExportProgressDialog(
            progress = exportProgress,
            onCancel = { app.backupExportManager.cancelExport() }
        )
    }

    // Restore Password Dialog
    if (showRestorePasswordDialog && selectedRestoreUri != null) {
        BackupPasswordDialog(
            archiveFileName = selectedRestoreFileName,
            onDismiss = {
                showRestorePasswordDialog = false
                selectedRestoreUri = null
            },
            onConfirm = { password ->
                showRestorePasswordDialog = false
                val uri = selectedRestoreUri
                if (uri != null) {
                    restorePasswordToUse = password
                    // Step 1: Pre-inspect manifest for confirmation
                    coroutineScope.launch {
                        try {
                            val manifest = app.backupImportManager.inspectArchive(uri, password)
                            inspectedManifest = manifest
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Authentication failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            selectedRestoreUri = null
                            restorePasswordToUse = null
                        }
                    }
                }
            }
        )
    }

    // Pre-Restore Manifest Inspection Sheet
    inspectedManifest?.let { manifest ->
        RestoreInspectionSheet(
            manifest = manifest,
            archiveFileName = selectedRestoreFileName,
            onDismiss = {
                inspectedManifest = null
                selectedRestoreUri = null
                restorePasswordToUse = null
            },
            onConfirmRestore = {
                val uri = selectedRestoreUri
                val password = restorePasswordToUse
                inspectedManifest = null
                if (uri != null && password != null) {
                    coroutineScope.launch {
                        val result = app.backupImportManager.importBackup(uri, password)
                        if (result.success) {
                            Toast.makeText(
                                context,
                                "Restored ${result.importedItemsCount} items across ${result.importedAlbumsCount} albums",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                result.errorMessage ?: "Restore failed",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        selectedRestoreUri = null
                        restorePasswordToUse = null
                    }
                }
            }
        )
    }

    // Restore Live Progress Dialog
    if (importProgress.isImporting) {
        BackupProgressDialog(
            progress = importProgress,
            onCancel = { app.backupImportManager.cancelImport() }
        )
    }
}

@Composable
private fun BackupActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(VaultSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = VaultAccent,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}
