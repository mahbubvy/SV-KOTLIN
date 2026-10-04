package com.secretvault.app.ui.transfer

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.model.MediaItem
import com.secretvault.app.core.stream.StreamEndpoint
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.core.transfer.TransferReceiver
import com.secretvault.app.core.transfer.TransferSender
import com.secretvault.app.core.transfer.TransferState
import com.secretvault.app.ui.stream.StreamPinDialog
import com.secretvault.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receive photos and videos from another SV phone on the same Wi-Fi. See SPEC-file-share.md. */
@Composable
fun ReceiveFilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SecretVaultApp
    val pins = remember { StreamPinManager(context.applicationContext) }
    val receiver = remember { TransferReceiver(context.applicationContext, app.cryptoEngine, app.mediaRepository, pins) }
    val state by receiver.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pinReady by remember { mutableStateOf(pins.isConfigured()) }
    var savingPin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    KeepScreenOn()
    StopWhenHidden { receiver.stop() }
    DisposableEffect(Unit) { onDispose { receiver.close() } }
    LaunchedEffect(pinReady) { if (pinReady) receiver.start() }
    BackHandler { receiver.stop(); onBack() }

    TransferPage("Receive files", onBack = { receiver.stop(); onBack() }) {
        when (val current = state) {
            TransferState.Idle -> Body(if (pinReady) "Starting…" else "Set a streaming PIN so only people you tell can send to this phone.")
            is TransferState.Waiting -> {
                Body("Waiting for a sender…")
                Body("On the other phone, select photos or videos, tap Send to SV, then choose \"${current.name}\" and enter this phone's streaming PIN.")
                Body("Both phones must be on the same Wi-Fi. A phone hotspot works too.")
            }
            is TransferState.AwaitingAccept -> {
                Body("${current.peerName} wants to send files.")
                AlertDialog(onDismissRequest = {}, containerColor = VaultSurface, titleContentColor = TextPrimary, textContentColor = TextSecondary,
                    title = { Text("Receive files?") },
                    text = { Text("${current.peerName} wants to send ${items(current.count)} (${size(context, current.bytes)}). They will be saved to Imports.") },
                    confirmButton = { TextButton(onClick = receiver::accept, modifier = Modifier.heightIn(min = 48.dp)) { Text("Accept", color = VaultAccent) } },
                    dismissButton = { TextButton(onClick = receiver::decline, modifier = Modifier.heightIn(min = 48.dp)) { Text("Decline", color = TextSecondary) } })
            }
            is TransferState.Transferring -> Progress("Receiving", current, context) { receiver.stop() }
            is TransferState.Done -> {
                Body("${items(current.count)} saved to Imports.")
                PrimaryButton("Receive more") { receiver.start() }
            }
            is TransferState.Failed -> {
                Body(current.message)
                if (current.completed > 0) Body("${items(current.completed)} received before that were saved to Imports.")
                PrimaryButton("Receive again") { receiver.start() }
            }
        }
    }

    if (!pinReady) StreamPinDialog(setup = true, saving = savingPin, error = pinError,
        message = "Set a four-digit streaming PIN. People sending files to this phone will need it.",
        onDismiss = onBack, onConfirm = { pin ->
            savingPin = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { pins.setPin(pin) }
                    pinReady = true
                } catch (_: Exception) { pinError = "Could not save the PIN. Try again." }
                finally { pin.fill('\u0000'); savingPin = false }
            }
        })
}

/** Send [mediaIds] from this vault to another SV phone that has Receive files open. */
@Composable
fun SendFilesScreen(mediaIds: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SecretVaultApp
    val sender = remember { TransferSender(context.applicationContext, app.cryptoEngine) }
    val state by sender.state.collectAsState()
    val receivers by sender.discovery.state.collectAsState()
    val items by produceState<List<MediaItem>?>(null, mediaIds) {
        value = withContext(Dispatchers.IO) { mediaIds.mapNotNull { app.mediaRepository.getMediaById(it) } }
    }
    var prompt by remember { mutableStateOf<StreamEndpoint?>(null) }
    KeepScreenOn()
    StopWhenHidden { sender.cancel() }
    DisposableEffect(Unit) { onDispose { sender.close() } }
    LaunchedEffect(Unit) { sender.discovery.search() }
    BackHandler { sender.cancel(); onBack() }

    TransferPage("Send to SV", onBack = { sender.cancel(); onBack() }) {
        val selected = items
        when (val current = state) {
            TransferState.Idle -> when {
                selected == null -> Body("Loading…")
                selected.isEmpty() -> Body("Nothing selected. Go back and select photos or videos.")
                else -> {
                    Body("Send ${items(selected.size)} to:")
                    Body(receivers.message)
                    receivers.cameras.forEach { receiver ->
                        OutlinedButton(onClick = { prompt = receiver }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)) { Text(receiver.name) }
                    }
                    TextButton(onClick = { sender.discovery.search() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh", color = VaultAccent) }
                }
            }
            is TransferState.Waiting -> {
                Body("Connecting to ${current.name}…")
                Body("The other phone needs to tap Accept.")
                OutlinedButton(onClick = sender::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = TextPrimary) }
            }
            is TransferState.AwaitingAccept -> Unit
            is TransferState.Transferring -> Progress("Sending", current, context) { sender.cancel() }
            is TransferState.Done -> {
                Body("Sent ${items(current.count)}.")
                PrimaryButton("Done", onBack)
            }
            is TransferState.Failed -> {
                Body(current.message)
                if (current.completed > 0) Body("${items(current.completed)} were sent and saved on the other phone before that.")
                PrimaryButton("Try again") { sender.reset(); sender.discovery.search() }
            }
        }
    }

    prompt?.let { receiver ->
        StreamPinDialog(setup = false, message = "Enter the streaming PIN set on ${receiver.name}.",
            onDismiss = { prompt = null }, onConfirm = { pin ->
                prompt = null
                val selected = items.orEmpty()
                if (selected.isEmpty()) pin.fill('\u0000')
                else { sender.discovery.stopSearching(); sender.send(receiver, pin, selected) }
            })
    }
}

@Composable
private fun TransferPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(VaultDarkBg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 56.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable private fun Body(text: String) = Text(text, color = TextSecondary, fontSize = 16.sp)

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) =
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = VaultAccent, contentColor = VaultDarkBg),
        modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }

@Composable
private fun Progress(verb: String, state: TransferState.Transferring, context: android.content.Context, onCancel: () -> Unit) {
    Body("$verb item ${state.index} of ${state.count}")
    LinearProgressIndicator(progress = { if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else 0f },
        color = VaultAccent, trackColor = VaultSurface, modifier = Modifier.fillMaxWidth())
    Body("${size(context, state.bytesDone)} of ${size(context, state.bytesTotal)}")
    OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", color = TextPrimary) }
}

/** Keeps the display on while a transfer screen is open, so the phone doesn't sleep mid-transfer. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
}

/** Calls [onStop] when the app goes to the background; transfers never continue unseen. */
@Composable
private fun StopWhenHidden(onStop: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onStop)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
}

private fun items(count: Int) = if (count == 1) "1 item" else "$count items"
private fun size(context: android.content.Context, bytes: Long) = Formatter.formatShortFileSize(context, bytes)
