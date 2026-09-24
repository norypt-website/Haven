package com.norypt.haven.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.security.LockPolicy
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up

private val CLIPBOARD_CHOICES = listOf(15, 30, 45, 60, 120)

@Composable
fun SecuritySettingsScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val vm: SecurityViewModel = viewModel { SecurityViewModel(container) }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(title = "Security", onBack = { nav.up() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            vm.notice?.let { FactRow(FactLevel.INFO, it); Spacer(Modifier.height(8.dp)) }

            // (a) Auto-lock
            SectionCard {
                Text("Auto-lock", style = MaterialTheme.typography.titleMedium)
                Text("Lock after this long without interaction", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LockPolicy.TIMEOUT_CHOICES_MS.forEach { ms ->
                    RadioRow(label = timeoutLabel(ms), selected = vm.policy.inactivityTimeoutMs == ms) { vm.setTimeout(ms) }
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Lock when the app goes to the background", vm.policy.lockOnBackground, detail = "After a short grace period, so a quick app switch does not force re-entry.") { vm.setLockOnBackground(it) }
                SwitchRow("Lock when the screen turns off", vm.policy.lockOnScreenOff) { vm.setLockOnScreenOff(it) }
            }
            Spacer(Modifier.height(12.dp))

            // (b) Locked-screen alarm actions
            SectionCard {
                Text("Ringing reminders", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    "Allow snooze and dismiss without unlocking Haven",
                    vm.lockedScreenActions,
                    detail = "When on, anyone holding the phone can silence a ringing reminder from the lock screen. They still cannot read it.",
                ) { vm.updateLockedScreenActions(it) }
            }
            Spacer(Modifier.height(12.dp))

            // (c) Clipboard
            SectionCard {
                Text("Clear copied secrets after", style = MaterialTheme.typography.titleMedium)
                Text("Clearing cannot retract what another app or a clipboard history already read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                CLIPBOARD_CHOICES.forEach { s ->
                    RadioRow(label = if (s < 60) "$s seconds" else if (s == 60) "1 minute" else "${s / 60} minutes", selected = vm.clipboardSeconds == s) { vm.updateClipboardSeconds(s) }
                }
            }
            Spacer(Modifier.height(12.dp))

            // (d) Change password, (e) device screen lock
            SectionCard {
                Text("Password", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::openChangePassword, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change password") }
                Spacer(Modifier.height(12.dp))
                SwitchRow(
                    "Require device screen lock to open vaults",
                    vm.requireDeviceAuth,
                    enabled = !vm.busy,
                    detail = "An optional extra step, not a replacement for the password. Changing it re-wraps the vault keys and asks for your Haven password.",
                ) { vm.beginDeviceAuthChange(it) }
            }
            Spacer(Modifier.height(12.dp))

            // (e2) Duress password (opt-in)
            DuressPasswordSection(enabled = !vm.busy)
            Spacer(Modifier.height(12.dp))

            // (f) Facts
            SectionCard {
                Text("Key protection", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val (lvl, label) = hardwareLevelFact(vm.hardwareLevel)
                FactRow(lvl, label)
                vm.envelope?.let { FactRow(FactLevel.INFO, argon2Summary(it.kdf), "Chosen for this device at setup and never lowered automatically.") }
                FactRow(FactLevel.INFO, "Private content is encrypted on this device")
                FactRow(FactLevel.INFO, "Norypt cannot recover a lost password or backup key")
            }
            Spacer(Modifier.height(12.dp))

            // (g) Danger zone
            SectionCard {
                Text("Danger zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("Each action asks for your Haven password and then a confirmation that names exactly what is deleted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                DangerButton("Delete all reminders", enabled = !vm.busy) { vm.begin(SecurityViewModel.Action.DELETE_REMINDERS) }
                Spacer(Modifier.height(8.dp))
                DangerButton("Delete all tasks and lists", enabled = !vm.busy) { vm.begin(SecurityViewModel.Action.DELETE_TASKS) }
                Spacer(Modifier.height(8.dp))
                if (vm.keeperUnlocked) {
                    DangerButton("Delete all passwords and folders", enabled = !vm.busy) { vm.begin(SecurityViewModel.Action.DELETE_PASSWORDS) }
                } else {
                    Text("Unlock the password keeper first to delete passwords and folders.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { nav.navigate(Routes.PASSWORD_UNLOCK) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock the password keeper") }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Erasing the entire vault retires the device-bound keys, deletes both encrypted databases, cancels all alarms and returns Haven to first-run. Backups you exported are not affected and remain readable with their passphrase and backup key. Flash storage may retain remnants; this is not a guaranteed wipe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DangerButton("Erase the entire Haven vault", enabled = !vm.busy) { vm.begin(SecurityViewModel.Action.ERASE_ALL) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (vm.showChangePassword) ChangePasswordDialog(vm)
    when (vm.step) {
        SecurityViewModel.Step.PASSWORD -> PasswordPromptDialog(
            title = when (vm.action) {
                SecurityViewModel.Action.DEVICE_AUTH -> "Confirm with your password"
                else -> "Confirm it is you"
            },
            body = when (vm.action) {
                SecurityViewModel.Action.DELETE_REMINDERS -> "Deleting all reminders requires your Haven password."
                SecurityViewModel.Action.DELETE_TASKS -> "Deleting all tasks and lists requires your Haven password."
                SecurityViewModel.Action.DELETE_PASSWORDS -> "Deleting all passwords and folders requires your Haven password."
                SecurityViewModel.Action.ERASE_ALL -> "Erasing the entire vault requires your Haven password."
                SecurityViewModel.Action.DEVICE_AUTH -> "Changing the device screen lock requirement re-wraps the vault keys and requires your Haven password."
                null -> ""
            },
            busy = vm.busy,
            error = vm.dialogError,
            onConfirm = vm::submitPassword,
            onDismiss = vm::cancel,
        )
        SecurityViewModel.Step.CONFIRM -> when (vm.action) {
            SecurityViewModel.Action.DELETE_REMINDERS -> ConfirmDialog(
                title = "Delete all reminders?",
                body = "Every reminder and its scheduled alarms will be deleted. Tasks, lists and passwords are not affected.",
                confirmLabel = "Delete all reminders", onConfirm = vm::confirm, onDismiss = vm::cancel,
            )
            SecurityViewModel.Action.DELETE_TASKS -> ConfirmDialog(
                title = "Delete all tasks and lists?",
                body = "Every task and every list will be deleted. Reminders and passwords are not affected.",
                confirmLabel = "Delete all tasks and lists", onConfirm = vm::confirm, onDismiss = vm::cancel,
            )
            SecurityViewModel.Action.DELETE_PASSWORDS -> ConfirmDialog(
                title = "Delete all passwords and folders?",
                body = "Every saved login and every folder in the password keeper will be deleted. Reminders and tasks are not affected.",
                confirmLabel = "Delete all passwords", onConfirm = vm::confirm, onDismiss = vm::cancel,
            )
            SecurityViewModel.Action.ERASE_ALL -> EraseDialog(vm)
            else -> Unit
        }
        SecurityViewModel.Step.NONE -> Unit
    }
}

@Composable
private fun DangerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(label) }
}

@Composable
private fun ChangePasswordDialog(vm: SecurityViewModel) {
    AlertDialog(
        onDismissRequest = { if (!vm.busy) vm.closeChangePassword() },
        title = { Text("Change password") },
        text = {
            Column {
                Text("The vault keys are re-wrapped under the new password. Existing backups keep the passphrase they were made with.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PasswordField(vm.currentPassword, { if (!vm.busy) vm.currentPassword = it }, label = "Current password", imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                PasswordField(vm.newPassword, { if (!vm.busy) vm.newPassword = it }, label = "New password", imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    vm.confirmPassword, { if (!vm.busy) vm.confirmPassword = it }, label = "Confirm new password",
                    isError = vm.confirmPassword.isNotEmpty() && vm.confirmPassword != vm.newPassword,
                    onDone = vm::changePassword,
                )
                vm.changeError?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (vm.busy) BusyRow("Re-wrapping vault keys…")
            }
        },
        confirmButton = { Button(onClick = vm::changePassword, enabled = !vm.busy) { Text("Change") } },
        dismissButton = { TextButton(onClick = vm::closeChangePassword, enabled = !vm.busy) { Text("Cancel") } },
    )
}

@Composable
private fun EraseDialog(vm: SecurityViewModel) {
    val ready = vm.eraseTyped.trim() == "ERASE"
    AlertDialog(
        onDismissRequest = { if (!vm.busy) vm.cancel() },
        title = { Text("Erase the entire Haven vault?") },
        text = {
            Column {
                Text(
                    "This retires the device-bound keys, deletes both encrypted databases, cancels all alarms and returns Haven to first-run. Backups you exported are not affected and remain readable with their passphrase and backup key. Flash storage may retain remnants; this is not a guaranteed wipe.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = vm.eraseTyped,
                    onValueChange = { if (!vm.busy) vm.eraseTyped = it },
                    label = { Text("Type ERASE to confirm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (vm.busy) BusyRow("Erasing…")
            }
        },
        confirmButton = {
            Button(
                onClick = vm::confirm,
                enabled = ready && !vm.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Erase everything") }
        },
        dismissButton = { TextButton(onClick = vm::cancel, enabled = !vm.busy) { Text("Cancel") } },
    )
}
