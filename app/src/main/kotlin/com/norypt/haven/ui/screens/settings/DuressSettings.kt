package com.norypt.haven.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.norypt.haven.session.UnlockResult
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.PasswordWaitLine
import com.norypt.haven.ui.components.refusalText
import com.norypt.haven.ui.components.rememberPasswordWaitMs
import kotlinx.coroutines.launch

/**
 * Duress password (opt-in). Entering it on any Haven password prompt silently replaces the
 * whole vault so that no password opens it and reports "Wrong password"; the real password then also
 * reports "Wrong password" because nothing is left to open.
 */
@Composable
fun DuressPasswordSection(enabled: Boolean) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(container.session.hasDuressPassword()) }
    var showSet by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var removeError by remember { mutableStateOf<String?>(null) }

    SectionLabel("Duress password")
    SectionCard {
        Text(
            "A second password you can type under pressure. Haven then quietly deletes everything in the vault, retires the device keys, cancels all alarms and shows only \"Wrong password\". Afterwards your real password shows \"Wrong password\" too, because nothing is left. There is no undo; only a backup made earlier can bring data back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FactRow(if (configured) FactLevel.WARNING else FactLevel.INFO, if (configured) "Duress password is set" else "Duress password is off")
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showSet = true }, enabled = enabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (configured) "Change duress password" else "Set a duress password")
        }
        if (configured) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { showRemove = true }, enabled = enabled && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove duress password") }
        }
    }

    if (showSet) {
        var real by remember { mutableStateOf("") }
        var duress by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var confirmed by remember { mutableStateOf(false) }
        DisposableEffect(Unit) { onDispose { real = ""; duress = ""; confirm = "" } }
        val waitMs = rememberPasswordWaitMs(container.throttle)
        val submit: () -> Unit = {
            when {
                // The countdown is on screen; nothing is sent until it ends.
                busy || waitMs > 0 -> Unit
                real.isEmpty() -> error = "Enter your Haven password."
                duress.length < 4 -> error = "Use at least 4 characters."
                duress != confirm -> error = "Duress passwords do not match."
                duress == real -> error = "The duress password must differ from your real password."
                !confirmed -> confirmed = true
                else -> {
                    busy = true; error = null
                    scope.launch {
                        val outcome = runCatching { container.session.setDuressPassword(real.toCharArray(), duress.toCharArray()) }
                        busy = false
                        val result = outcome.getOrNull()
                        when {
                            result == UnlockResult.Success -> { configured = true; notice = "Duress password set."; showSet = false }
                            // Back to the form with the Haven password cleared, so the reason is visible
                            // and the same wrong password is not sent again by accident.
                            result != null -> { error = refusalText(result); confirmed = false; real = "" }
                            else -> { error = "Could not set the duress password (${outcome.exceptionOrNull()?.javaClass?.simpleName})."; confirmed = false }
                        }
                    }
                    Unit
                }
            }
        }
        if (confirmed) {
            ConfirmDialog(
                title = "Arm the duress password?",
                body = "Typing it on any Haven password prompt will silently erase the entire vault with no way back. Only a backup made earlier can restore your data.",
                confirmLabel = "Arm it",
                onConfirm = submit,
                onDismiss = { confirmed = false },
            )
        } else {
            AlertDialog(
                onDismissRequest = { if (!busy) showSet = false },
                title = { Text(if (configured) "Change duress password" else "Set a duress password") },
                text = {
                    Column {
                        PasswordField(real, { real = it }, label = "Your Haven password", imeAction = ImeAction.Next)
                        Spacer(Modifier.height(8.dp))
                        PasswordField(duress, { duress = it }, label = "Duress password", imeAction = ImeAction.Next)
                        Spacer(Modifier.height(8.dp))
                        PasswordField(confirm, { confirm = it }, label = "Confirm duress password", isError = error != null, supportingText = error, onDone = submit)
                        PasswordWaitLine(waitMs, Modifier.padding(top = 4.dp))
                    }
                },
                confirmButton = { Button(onClick = submit, enabled = !busy && waitMs == 0L && real.isNotEmpty() && duress.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Continue") } },
                dismissButton = { TextButton(onClick = { showSet = false }, enabled = !busy) { Text("Cancel") } },
            )
        }
    }

    if (showRemove) {
        PasswordPromptDialog(
            title = "Remove duress password",
            body = "Enter your Haven password to turn the duress password off.",
            busy = busy,
            error = removeError,
            confirmLabel = "Remove",
            onConfirm = { chars ->
                busy = true
                removeError = null
                scope.launch {
                    val outcome = runCatching { container.session.clearDuressPassword(chars) }
                    java.util.Arrays.fill(chars, '\u0000')
                    busy = false
                    val result = outcome.getOrNull()
                    when {
                        result == UnlockResult.Success -> { configured = false; notice = "Duress password removed."; showRemove = false }
                        result != null -> removeError = refusalText(result)
                        else -> removeError = "Could not remove the duress password (${outcome.exceptionOrNull()?.javaClass?.simpleName})."
                    }
                }
            },
            onDismiss = { showRemove = false; removeError = null },
        )
    }
}
