package com.norypt.haven.ui.screens.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.norypt.haven.R
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.screens.settings.confirmDeviceCredential
import com.norypt.haven.ui.screens.settings.currentActivity
import com.norypt.haven.ui.screens.settings.duringSystemInteraction

@Composable
fun UnlockScreen(onUnlocked: () -> Unit, onAbout: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: UnlockViewModel = viewModel { UnlockViewModel(container) }
    val activity = currentActivity()
    val focus = remember { FocusRequester() }
    var showEraseConfirm by remember { mutableStateOf(false) }

    val deviceAuth: suspend () -> Boolean = {
        val a = activity
        if (a == null) false else container.lockController.duringSystemInteraction { confirmDeviceCredential(a, "Confirm device screen lock") }
    }
    val submit = { vm.unlock(deviceAuth, onUnlocked) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_haven_mark),
            contentDescription = "Haven mark",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("Haven", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        val unrecoverable = vm.unrecoverable
        if (unrecoverable != null) {
            SectionCard {
                FactRow(FactLevel.DANGER, "This vault cannot be opened", unrecoverable)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Erasing retires the device-bound keys, deletes both encrypted databases, cancels all alarms and returns Haven to first-run. Backups you exported are not affected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showEraseConfirm = true },
                    enabled = !vm.erasing,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (vm.erasing) "Erasing…" else "Erase vault and start over") }
            }
        } else {
            PasswordField(
                value = vm.password,
                onValueChange = { if (!vm.busy) vm.password = it },
                label = "Password",
                modifier = Modifier.focusRequester(focus),
                isError = vm.message != null,
                supportingText = vm.message,
                onDone = submit,
            )
            if (vm.waitSeconds > 0) {
                Spacer(Modifier.height(4.dp))
                Text("Try again in ${vm.waitSeconds} s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            if (vm.busy) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Deriving key…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = submit, enabled = vm.canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Unlock")
            }
            Spacer(Modifier.height(8.dp))
            // Always offered, so it reveals nothing about the vault's state. Without it the only way
            // out of a forgotten password (or a duress-replaced decoy) is Android's "clear storage".
            TextButton(onClick = { showEraseConfirm = true }, enabled = !vm.busy && !vm.erasing, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Forgot your password? Erase and start over")
            }
        }

        Spacer(Modifier.height(32.dp))
        TextButton(onClick = onAbout, modifier = Modifier.heightIn(min = 48.dp)) { Text("About Haven") }
    }

    if (showEraseConfirm) {
        ConfirmDialog(
            title = "Erase the entire Haven vault?",
            body = "All reminders, tasks, lists, passwords and folders on this device will be deleted and cannot be recovered without a backup. Haven returns to first-run; you can then restore a backup with its passphrase and backup key. Flash storage may retain remnants; this is not a guaranteed wipe.",
            confirmLabel = "Erase everything",
            onConfirm = { showEraseConfirm = false; vm.eraseAndStartOver() },
            onDismiss = { showEraseConfirm = false },
        )
    }
}
