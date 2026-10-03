package com.secretvault.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.secretvault.app.core.security.PinManager
import com.secretvault.app.SecretVaultApp
import com.secretvault.app.core.stream.StreamBleDiscovery
import com.secretvault.app.core.stream.StreamPinManager
import com.secretvault.app.ui.stream.StreamPinDialog
import com.secretvault.app.ui.auth.BiometricAuthHelper
import com.secretvault.app.ui.theme.TextPrimary
import com.secretvault.app.ui.theme.TextSecondary
import com.secretvault.app.ui.theme.VaultAccent
import com.secretvault.app.ui.theme.VaultDarkBg
import com.secretvault.app.ui.theme.VaultError
import com.secretvault.app.ui.theme.VaultSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(pinManager: PinManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val streamPins = remember { StreamPinManager(context.applicationContext) }
    var streamPinRequired by remember { mutableStateOf(streamPins.isPinRequired()) }
    val streamPrefs = remember { context.getSharedPreferences("sv_stream_config", android.content.Context.MODE_PRIVATE) }
    var showStreamPin by remember { mutableStateOf(false) }
    var savingStreamPin by remember { mutableStateOf(false) }
    var streamPinError by remember { mutableStateOf<String?>(null) }
    var bluetoothEnabled by remember {
        mutableStateOf(streamPrefs.getBoolean("bluetooth_discovery", true) && StreamBleDiscovery.allowed(context, true))
    }
    val app = context.applicationContext as SecretVaultApp
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.sessionManager.setExternalPickerInProgress(false)
        bluetoothEnabled = StreamBleDiscovery.allowed(context, true)
        streamPrefs.edit().putBoolean("bluetooth_discovery", bluetoothEnabled).apply()
        if (!bluetoothEnabled) Toast.makeText(context, "Wi-Fi discovery still works without Bluetooth permission", Toast.LENGTH_SHORT).show()
    }
    val biometricAvailable = remember(context) { BiometricAuthHelper.isBiometricAvailable(context) }
    var fingerprintEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    var showChangePin by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Column(
        Modifier.fillMaxSize().background(VaultDarkBg).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Text("Settings", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(value = streamPinRequired, role = Role.Switch, onValueChange = { required ->
                        try {
                            streamPins.setPinRequired(required)
                            streamPinRequired = required
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not save streaming PIN preference", Toast.LENGTH_SHORT).show()
                        }
                    }).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Require streaming PIN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (streamPinRequired) "Viewers enter your four-digit streaming PIN"
                        else "Anyone on your Wi-Fi can view and control an active stream", color = TextSecondary, fontSize = 14.sp)
                }
                Switch(checked = streamPinRequired, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = VaultDarkBg, checkedTrackColor = VaultAccent,
                        uncheckedTrackColor = VaultSurface, uncheckedBorderColor = TextSecondary))
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(role = Role.Button) { streamPinError = null; showStreamPin = true }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Set streaming PIN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Save a four-digit PIN for camera streaming", color = TextSecondary, fontSize = 14.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(value = bluetoothEnabled, role = Role.Switch, onValueChange = { enabled ->
                        if (!enabled || StreamBleDiscovery.allowed(context, true)) {
                            bluetoothEnabled = enabled
                            streamPrefs.edit().putBoolean("bluetooth_discovery", enabled).apply()
                        } else {
                            app.sessionManager.setExternalPickerInProgress(true)
                            bluetoothPermission.launch(StreamBleDiscovery.permissions(true))
                        }
                    }).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bluetooth discovery", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Help nearby devices find this camera. Video uses Wi-Fi.", color = TextSecondary, fontSize = 14.sp)
                }
                Switch(checked = bluetoothEnabled, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = VaultDarkBg, checkedTrackColor = VaultAccent,
                        uncheckedTrackColor = VaultSurface, uncheckedBorderColor = TextSecondary))
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(role = Role.Button) { showChangePin = true }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Change PIN", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("Use your current PIN to set a new one", color = TextSecondary, fontSize = 14.sp)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextSecondary)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(
                        value = fingerprintEnabled,
                        enabled = biometricAvailable || fingerprintEnabled,
                        role = Role.Switch,
                        onValueChange = {
                            pinManager.setBiometricEnabled(it)
                            fingerprintEnabled = it
                        }
                    )
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fingerprint unlock", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (biometricAvailable) "Use your phone's fingerprint to open the vault"
                        else "Add a fingerprint in your phone settings first",
                        color = TextSecondary, fontSize = 14.sp
                    )
                }
                Switch(
                    checked = fingerprintEnabled,
                    onCheckedChange = null,
                    enabled = biometricAvailable || fingerprintEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VaultDarkBg,
                        checkedTrackColor = VaultAccent,
                        uncheckedTrackColor = VaultSurface,
                        uncheckedBorderColor = TextSecondary
                    )
                )
            }
        }
    }

    if (showStreamPin) StreamPinDialog(setup = true, saving = savingStreamPin, error = streamPinError,
        onDismiss = { showStreamPin = false }, onConfirm = { pin ->
            savingStreamPin = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { try { streamPins.setPin(pin) } finally { pin.fill('\u0000') } }
                    showStreamPin = false
                    Toast.makeText(context, "Streaming PIN saved", Toast.LENGTH_SHORT).show()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    streamPinError = "Could not save streaming PIN. Try again."
                } finally { pin.fill('\u0000'); savingStreamPin = false }
            }
        })

    if (showChangePin) {
        ChangePinDialog(
            pinManager = pinManager,
            onDismiss = { showChangePin = false },
            onChanged = {
                showChangePin = false
                Toast.makeText(context, "PIN changed", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun ChangePinDialog(pinManager: PinManager, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !saving, dismissOnClickOutside = !saving),
        containerColor = VaultSurface,
        title = { Text("Change PIN", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter your current PIN and confirm a new 4-digit PIN.", color = TextSecondary, fontSize = 14.sp)
                PinField("Current PIN", currentPin, !saving) { currentPin = it; error = null }
                PinField("New PIN", newPin, !saving) { newPin = it; error = null }
                PinField("Confirm new PIN", confirmation, !saving, ImeAction.Done) { confirmation = it; error = null }
                error?.let {
                    Text(it, color = VaultError, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && currentPin.length == 4 && newPin.length == 4 && confirmation.length == 4,
                colors = ButtonDefaults.textButtonColors(contentColor = VaultAccent, disabledContentColor = TextSecondary),
                onClick = {
                    if (newPin != confirmation) {
                        error = "New PINs do not match"
                    } else {
                        saving = true
                        scope.launch {
                            try {
                                val changed = withContext(Dispatchers.IO) { pinManager.changePin(currentPin, newPin) }
                                if (changed) onChanged()
                                else error = if (pinManager.isLockedOut()) {
                                    "Too many attempts. Try again in ${pinManager.getRemainingLockoutSeconds()} seconds."
                                } else "Current PIN is incorrect"
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = "Could not change PIN. Try again."
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel", color = TextSecondary) }
        }
    )
}

@Composable
private fun PinField(
    label: String, value: String, enabled: Boolean, imeAction: ImeAction = ImeAction.Next,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { digit -> digit in '0'..'9' }.take(4)) },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = VaultAccent,
            unfocusedLabelColor = TextSecondary,
            focusedBorderColor = VaultAccent,
            unfocusedBorderColor = TextSecondary,
            cursorColor = VaultAccent
        )
    )
}
