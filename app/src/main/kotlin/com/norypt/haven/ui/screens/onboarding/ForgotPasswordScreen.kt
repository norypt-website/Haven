package com.norypt.haven.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * The only way to leave a vault whose password is lost. Three deliberate steps stand between
 * the button and the deletion: a red button, the word ERASE typed by hand, and a final
 * confirmation, so a pocket touch or a stranger with the phone cannot wipe the vault by
 * accident. The screen looks the same whether the vault is real or a duress decoy.
 */
@Composable
fun ForgotPasswordScreen(onErased: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var askWord by remember { mutableStateOf(false) }
    var finalConfirm by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(title = "Forgot your password?", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionCard {
                Text("There is no reset", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your password never leaves this phone and Norypt has no copy of it. Nothing on this " +
                        "screen can recover it. If you still have it written down, go back and try again; " +
                        "spaces and capital letters count.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FactRow(FactLevel.INFO, "A backup made earlier can bring your data back", "After erasing, set up Haven again and restore the backup file with its passphrase and backup key.")
            }
            Spacer(Modifier.height(12.dp))
            SectionCard {
                Text("Erase everything and start over", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "All reminders, tasks, lists, passwords and folders on this phone are deleted, the " +
                        "device-bound keys are retired and every alarm is cancelled. Haven returns to " +
                        "first-run. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Spacer(Modifier.height(8.dp)); FactRow(FactLevel.DANGER, it) }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { typed = ""; askWord = true },
                    enabled = !erasing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text(if (erasing) "Erasing…" else "Erase everything") }
            }
        }
    }

    if (askWord) {
        val ok = typed.trim() == "ERASE"
        AlertDialog(
            onDismissRequest = { askWord = false },
            title = { Text("Type ERASE to continue") },
            text = {
                Column {
                    Text("To confirm that you want to delete everything in Haven on this phone, type the word ERASE in capital letters.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Type ERASE") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { askWord = false; finalConfirm = true },
                    enabled = ok,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { askWord = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
    if (finalConfirm) {
        ConfirmDialog(
            title = "Last chance: erase the whole vault?",
            body = "Everything in Haven on this phone is deleted now and cannot be recovered without a backup.",
            confirmLabel = "Erase everything",
            onConfirm = {
                finalConfirm = false
                erasing = true; error = null
                scope.launch {
                    val result = runCatching { container.session.eraseEverything { container.prefs.clearAll() } }
                    erasing = false
                    if (result.isSuccess) onErased() else error = "Erase failed (${result.exceptionOrNull()?.javaClass?.simpleName}). Try again."
                }
            },
            onDismiss = { finalConfirm = false },
        )
    }
}
