package com.secretvault.app.ui.stream

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.secretvault.app.ui.theme.*
import com.secretvault.app.core.stream.StreamPinManager.Companion.PIN_DIGITS

@Composable
fun StreamPinDialog(setup: Boolean, saving: Boolean = false, error: String? = null,
                    onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, containerColor = VaultSurface,
        titleContentColor = TextPrimary, textContentColor = TextSecondary,
        title = { Text(if (setup) "Set streaming PIN" else "Enter streaming PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (setup) "Use a separate four-digit PIN. This camera device remembers it." else "Enter the four-digit PIN set on the camera device.")
                val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    focusedLabelColor = VaultAccent, unfocusedLabelColor = TextSecondary,
                    focusedBorderColor = VaultAccent, unfocusedBorderColor = TextSecondary, cursorColor = VaultAccent)
                OutlinedTextField(value = pin, onValueChange = { if (it.length <= PIN_DIGITS && it.all { char -> char in '0'..'9' }) pin = it },
                    enabled = !saving, label = { Text("Streaming PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = colors, modifier = Modifier.fillMaxWidth())
                if (setup) OutlinedTextField(value = confirmation,
                    onValueChange = { if (it.length <= PIN_DIGITS && it.all { char -> char in '0'..'9' }) confirmation = it },
                    enabled = !saving, label = { Text("Confirm PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = colors, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = VaultError) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && pin.length == PIN_DIGITS && (!setup || pin == confirmation), onClick = {
                val value = pin.toCharArray(); pin = ""; confirmation = ""; onConfirm(value)
            }) { Text(if (saving) "Saving…" else if (setup) "Save PIN" else "Connect", color = VaultAccent) }
        }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel", color = TextSecondary) } })
}
