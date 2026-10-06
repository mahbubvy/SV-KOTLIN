package com.secretvault.app.ui.editor

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.image.decodeEncryptedImage
import com.secretvault.app.core.image.renderPhoto
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.processing.Adjustment
import com.secretvault.app.core.processing.PhotoEdit
import com.secretvault.app.core.processing.VideoEditPlan
import com.secretvault.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The preview works on a copy this big (long side); the save uses the full photo. */
private const val PREVIEW_SIDE = 1600
/** Smaller still while a slider or handle is moving, so the preview keeps up. */
private const val QUICK_SIDE = 900
/** Bigger photos are saved scaled down to this long side, to stay within memory. */
private const val SAVE_SIDE = 6000

private enum class PhotoTab(val label: String) { ADJUST("Adjust"), CROP("Crop"), FILTERS("Filters"), DRAW("Draw") }

/**
 * Edits a vault photo: adjustments and grain, crop and straighten, filters and drawing, then saves a new copy beside
 * it. The photo is decrypted in memory only.
 */
@Composable
fun PhotoEditorScreen(app: SecretVaultApp, item: MediaItem, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(PhotoEdit()) }
    var tab by remember { mutableStateOf(PhotoTab.ADJUST) }
    // A slider or handle is moving: the preview renders smaller to keep up.
    var moving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // A smaller copy for the preview; null while loading, and a failed decode shows an error.
    var failed by remember { mutableStateOf(false) }
    val source by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { runCatching { decodeEncryptedImage(app.cryptoEngine, File(item.encryptedPath), PREVIEW_SIDE) }.getOrNull() }
        if (value == null) failed = true
    }
    // The edited preview, redrawn whenever the edit changes; a newer edit stops an older render part-way.
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source) {
        val src = source ?: return@LaunchedEffect
        snapshotFlow { Triple(edit, tab == PhotoTab.CROP, moving) }.collectLatest { (e, wholeFrame, quick) ->
            withContext(Dispatchers.Default) {
                renderPhoto(src, e, cropped = !wholeFrame, maxSide = if (quick) QUICK_SIDE else PREVIEW_SIDE) { isActive }
            }?.let { preview = it }
        }
    }

    fun leave() { if (edit == PhotoEdit()) onBack() else confirmDiscard = true }
    BackHandler(enabled = !saving) { leave() }

    fun save() {
        saving = true
        scope.launch {
            // Finishes even if the screen goes away mid-save, so a copy is never half made.
            val saved = withContext(NonCancellable + Dispatchers.Default) {
                runCatching {
                    val full = decodeEncryptedImage(app.cryptoEngine, File(item.encryptedPath), SAVE_SIDE) ?: return@runCatching null
                    val edited = renderPhoto(full, edit).also { full.recycle() } ?: return@runCatching null
                    val names = app.mediaRepository.getMedia().first().mapTo(HashSet()) { it.filename }
                    app.mediaSaveQueue.savePhoto(edited, item.albumId, VideoEditPlan.editedFileName(item.filename, names))
                }.getOrNull()
            }
            saving = false
            if (saved != null) {
                Toast.makeText(context, "Saved as a new photo", Toast.LENGTH_SHORT).show()
                onBack()
            } else Toast.makeText(context, "Couldn't save the edited photo", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::leave, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
            Text("Edit photo", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val canSave = source != null && !saving && edit != PhotoEdit()
            TextButton(onClick = ::save, enabled = canSave) {
                Text("Save", color = if (canSave) VaultAccent else TextMuted, fontWeight = FontWeight.SemiBold)
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            val shown = preview
            when {
                failed -> Text("This photo couldn't be opened.", color = TextSecondary)
                shown == null -> CircularProgressIndicator(color = VaultAccent)
                else -> Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = source != null && !saving
            when (tab) {
                PhotoTab.ADJUST -> PhotoAdjustPanel(edit, ready, onMoving = { moving = it }) { edit = it }
                else -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                PhotoTab.entries.forEach { t ->
                    val icon = when (t) {
                        PhotoTab.ADJUST -> Icons.Default.Tune
                        PhotoTab.CROP -> Icons.Default.Crop
                        PhotoTab.FILTERS -> Icons.Default.FilterVintage
                        PhotoTab.DRAW -> Icons.Default.Brush
                    }
                    TabTool(icon, t.label, selected = tab == t, enabled = ready) { tab = t }
                }
            }
        }
    }

    if (saving) AlertDialog(onDismissRequest = {}, containerColor = VaultSurface,
        title = { Text("Saving", color = TextPrimary) },
        text = { LinearProgressIndicator(color = VaultAccent, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {})

    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, containerColor = VaultSurface,
        title = { Text("Discard changes?", color = TextPrimary) },
        text = { Text("Your edit hasn't been saved. The original photo is not affected.", color = TextSecondary) },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard", color = VaultError) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = VaultAccent) } })
}

/** A bottom tab: like a [Tool], in the accent colour while selected. */
@Composable
private fun TabTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when { !enabled -> TextMuted; selected -> VaultAccent; else -> TextSecondary }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.widthIn(min = 64.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}

/** The video editor's adjustments plus Grain: chips pick one, the slider sets it. */
@Composable
private fun PhotoAdjustPanel(edit: PhotoEdit, enabled: Boolean, onMoving: (Boolean) -> Unit, onChange: (PhotoEdit) -> Unit) {
    // null is Grain, which isn't a colour adjustment.
    var current by remember { mutableStateOf<Adjustment?>(Adjustment.BRIGHTNESS) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (Adjustment.entries + listOf(null)).forEach { a ->
            val value = if (a == null) edit.grain else edit.adjustments[a]
            FilterChip(selected = current == a, onClick = { current = a },
                label = { Text((a?.label ?: "Grain") + if (value != 0) " •" else "") },
                colors = FilterChipDefaults.filterChipColors(labelColor = TextSecondary, selectedLabelColor = VaultDarkBg,
                    selectedContainerColor = VaultAccent))
        }
    }
    val a = current
    val value = if (a == null) edit.grain else edit.adjustments[a]
    LabeledSlider(a?.label ?: "Grain", value.toString(), value.toFloat(), if (a == null) 0f..100f else -100f..100f, enabled,
        onDone = { onMoving(false) }) { v ->
        onMoving(true)
        val n = kotlin.math.round(v).toInt()
        onChange(if (a == null) edit.copy(grain = n) else edit.copy(adjustments = edit.adjustments.with(a, n)))
    }
    Row {
        PanelButton("Reset", enabled && value != 0) { onChange(if (a == null) edit.copy(grain = 0) else edit.copy(adjustments = edit.adjustments.with(a, 0))) }
        PanelButton("Reset all", enabled && (!edit.adjustments.isNone || edit.grain != 0)) {
            onChange(edit.copy(adjustments = com.secretvault.app.core.processing.Adjustments(), grain = 0))
        }
    }
}
