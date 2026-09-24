package com.norypt.haven.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
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
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val vm: BackupViewModel = viewModel { BackupViewModel(container) }
    var menuOpen by remember { mutableStateOf(false) }

    // The document picker is a system excursion: it must not lock the vault while it is open.
    var pickerOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (pickerOpen) { pickerOpen = false; container.lockController.endSystemInteraction() }
        vm.onFilePicked(uri)
    }
    DisposableEffect(Unit) { onDispose { if (pickerOpen) { pickerOpen = false; container.lockController.endSystemInteraction() } } }
    // After a restore every list, reminder and task on screen refers to replaced rows. Start over
    // from Today with an empty back stack so nothing shows the old data or an id that no longer exists.
    LaunchedEffect(vm.restoredJustNow) {
        if (vm.restoredJustNow) {
            kotlinx.coroutines.delay(1_200) // let the "Restored …" line be read
            vm.restoredJustNow = false
            nav.navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(
            title = "Backup & restore",
            onBack = { nav.up() },
            actions = {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Generate a new backup key") }, enabled = !vm.busy && vm.keyDisplay != null, onClick = { menuOpen = false; vm.showRotateConfirm = true })
                }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            BackupKeySection(vm)
            Spacer(Modifier.height(12.dp))
            CreateBackupSection(vm, nav)
            Spacer(Modifier.height(12.dp))
            RestoreSection(vm, nav) {
                if (!pickerOpen) {
                    pickerOpen = true
                    container.lockController.beginSystemInteraction()
                    runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                        pickerOpen = false
                        container.lockController.endSystemInteraction()
                        vm.restoreMessage = "No file picker is available on this device."
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (vm.showRotateConfirm) {
        ConfirmDialog(
            title = "Generate a new backup key?",
            body = "New backups will need the new key. Backups you already made still need the old key, so keep your record of it. You will be asked to confirm the new key before the next backup.",
            confirmLabel = "Generate new key",
            onConfirm = vm::rotateKey,
            onDismiss = { vm.showRotateConfirm = false },
        )
    }
    if (vm.showRestoreConfirm) {
        ConfirmDialog(
            title = "Replace everything in this vault with the backup?",
            body = "All current reminders, tasks and lists" + (if (vm.restorePasswords) ", passwords and folders" else "") + " on this device will be replaced by the contents of the backup file. Alarms are rebuilt from the restored reminders.",
            confirmLabel = "Replace and restore",
            onConfirm = vm::restore,
            onDismiss = { vm.showRestoreConfirm = false },
        )
    }
}

@Composable
private fun BackupKeySection(vm: BackupViewModel) {
    val container = LocalAppContainer.current
    SectionCard {
        Text("Backup key", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "A backup needs BOTH this key and the passphrase you choose. Keep the key somewhere other than the backup file. Norypt cannot recover either.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        val key = vm.keyDisplay
        when {
            vm.keyError != null -> FactRow(FactLevel.DANGER, vm.keyError!!)
            key == null -> BusyRow("Reading the backup key…")
            else -> {
                MonospaceBox(key)
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { container.clipboard.copy("Haven", key) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
                }
                Text(
                    "Copies are cleared from the clipboard after a short time, but clearing cannot retract what another app already read.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                when (vm.keyVerified) {
                    true -> FactRow(FactLevel.OK, "You confirmed that this key is recorded")
                    false -> {
                        FactRow(FactLevel.WARNING, "Not yet confirmed", "Backups stay disabled until you confirm you recorded the key.")
                        OutlinedTextField(
                            value = vm.verifyInput,
                            onValueChange = { vm.verifyInput = it; vm.verifyError = null },
                            label = { Text("Type or paste the backup key to confirm you recorded it") },
                            isError = vm.verifyError != null,
                            supportingText = vm.verifyError?.let { { Text(it) } },
                            singleLine = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::verifyKey, enabled = vm.verifyInput.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Confirm key") }
                    }
                    null -> BusyRow("Checking…")
                }
            }
        }
    }
}

@Composable
private fun CreateBackupSection(vm: BackupViewModel, nav: NavHostController) {
    SectionCard {
        Text("Create backup", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        PasswordField(vm.exportPassphrase, { if (!vm.exporting) vm.exportPassphrase = it }, label = "Backup passphrase", imeAction = ImeAction.Next)
        Spacer(Modifier.height(8.dp))
        PasswordField(
            vm.exportConfirm, { if (!vm.exporting) vm.exportConfirm = it }, label = "Confirm passphrase",
            isError = vm.exportConfirm.isNotEmpty() && vm.exportConfirm != vm.exportPassphrase,
            onDone = vm::export,
        )
        Spacer(Modifier.height(4.dp))
        SwitchRow("Include password keeper", vm.includePasswords, enabled = !vm.exporting) { vm.includePasswords = it }
        if (vm.includePasswords && !vm.keeperUnlocked) {
            Text("The password keeper is locked.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { nav.navigate(Routes.PASSWORD_UNLOCK) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock keeper") }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.exporting) BusyRow("Encrypting and writing the backup…")
        Button(
            onClick = vm::export,
            enabled = vm.keyVerified == true && !vm.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("Save encrypted backup to Downloads/Haven") }
        if (vm.keyVerified != true) {
            Text("Confirm the backup key above first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.exportMessage?.let { Spacer(Modifier.height(8.dp)); FactRow(if (vm.exportOk) FactLevel.OK else FactLevel.WARNING, it) }
        Spacer(Modifier.height(8.dp))
        FactRow(FactLevel.INFO, "The file is encrypted; Haven cannot stop you or other apps from copying it elsewhere later.")
        FactRow(FactLevel.INFO, "Old backups keep old data; changing the app password does not change existing backups.")
    }
}

@Composable
private fun RestoreSection(vm: BackupViewModel, nav: NavHostController, onChooseFile: () -> Unit) {
    SectionCard {
        Text("Restore", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Only files on this device's storage are accepted; cloud providers are refused. Restoring replaces what is in this vault.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onChooseFile, enabled = !vm.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose a backup file") }
        if (vm.inspecting) BusyRow("Reading the file header…")

        val ins = vm.inspection
        if (ins != null) {
            Spacer(Modifier.height(8.dp))
            val h = ins.header
            val created = runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.createdAtEpochMs)) }.getOrDefault("unknown")
            FactRow(FactLevel.INFO, "Created $created", "Written by Haven build ${h.appVersionCode}. Contains: ${h.contents.joinToString { if (it == "content") "reminders and tasks" else if (it == "passwords") "passwords" else it }}.")
            when (ins.keyIdMatchesStored) {
                true -> FactRow(FactLevel.OK, "Matches this vault's current backup key")
                false -> FactRow(FactLevel.WARNING, "Needs a different backup key — enter it below")
                null -> FactRow(FactLevel.INFO, "Could not compare with the stored backup key — enter the key below")
            }
            Spacer(Modifier.height(8.dp))
            PasswordField(vm.restorePassphrase, { if (!vm.restoring) vm.restorePassphrase = it }, label = "Backup passphrase", imeAction = ImeAction.Next)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.restoreKeyText,
                onValueChange = { if (!vm.restoring) vm.restoreKeyText = it },
                label = { Text("Backup key") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            val hasPasswords = "passwords" in h.contents
            SwitchRow("Also restore passwords", vm.restorePasswords && hasPasswords, enabled = hasPasswords && !vm.restoring, detail = if (hasPasswords) "Needs the password keeper unlocked." else "This backup contains no passwords.") { vm.restorePasswords = it }
            if (vm.restorePasswords && hasPasswords && !vm.keeperUnlocked) {
                Row { OutlinedButton(onClick = { nav.navigate(Routes.PASSWORD_UNLOCK) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock keeper") }; Spacer(Modifier.width(8.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            if (vm.restoring) BusyRow("Decrypting and restoring…")
            Button(onClick = vm::requestRestore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore from this file") }
        }
        vm.restoreMessage?.let { Spacer(Modifier.height(8.dp)); FactRow(if (vm.restoreOk) FactLevel.OK else FactLevel.WARNING, it) }
    }
}
