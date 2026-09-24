package com.norypt.haven.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.backup.BackupInspection
import com.norypt.haven.backup.BackupKey
import com.norypt.haven.backup.ExportResult
import com.norypt.haven.backup.RestoreResult
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.SecurityLevel
import com.norypt.haven.crypto.VaultIds
import com.norypt.haven.crypto.VaultKeyEnvelope
import com.norypt.haven.di.AppContainer
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.PasswordField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/* ---------- Plain-language security facts (shared by several screens) ---------- */

fun argon2Summary(p: Argon2Params): String = "Argon2id, ${p.memoryKib / 1024} MiB, ${p.iterations} ${if (p.iterations == 1) "pass" else "passes"}"

/** Level + label for the hardware key fact. Software keys are only possible in debug builds. */
fun hardwareLevelFact(level: SecurityLevel?): Pair<FactLevel, String> = when (level) {
    SecurityLevel.STRONGBOX -> FactLevel.OK to "Hardware key: StrongBox secure element"
    SecurityLevel.TRUSTED_ENVIRONMENT -> FactLevel.OK to "Hardware key: Trusted execution environment"
    SecurityLevel.SOFTWARE, SecurityLevel.UNKNOWN -> FactLevel.WARNING to "Software-backed key (debug build only)"
    null -> FactLevel.INFO to "Hardware key level: not read yet"
}

fun timeoutLabel(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s seconds" else { val m = s / 60; if (m == 1L) "1 minute" else "$m minutes" }
}

/* ---------- Shared settings widgets ---------- */

@Composable
fun SettingsNavRow(title: String, detail: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick, role = Role.Button).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, detail: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Monospace, selectable box for keys and passphrases. */
@Composable
fun MonospaceBox(text: String) {
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp),
        )
    }
}

@Composable
fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Asks for the Haven password (fresh authentication). The text is held in plain `remember`
 * state, handed over as a CharArray that the caller wipes, and cleared on dispose.
 */
@Composable
fun PasswordPromptDialog(
    title: String,
    body: String,
    busy: Boolean,
    error: String?,
    confirmLabel: String = "Continue",
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { text = "" } }
    val submit = {
        if (!busy && text.isNotEmpty()) {
            val chars = text.toCharArray()
            text = ""
            onConfirm(chars)
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                PasswordField(value = text, onValueChange = { if (!busy) text = it }, label = "Haven password", isError = error != null, supportingText = error, onDone = submit)
                if (busy) BusyRow("Checking password…")
            }
        },
        confirmButton = { Button(onClick = submit, enabled = !busy && text.isNotEmpty()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

/* ---------- Security settings ---------- */

class SecurityViewModel(private val container: AppContainer) : ViewModel() {
    enum class Action { DELETE_REMINDERS, DELETE_TASKS, DELETE_PASSWORDS, ERASE_ALL, DEVICE_AUTH }
    enum class Step { NONE, PASSWORD, CONFIRM }

    var policy by mutableStateOf(container.prefs.lockPolicy)
    var lockedScreenActions by mutableStateOf(container.alarmRuntime.prefs.lockedScreenActionsAllowed)
    var clipboardSeconds by mutableStateOf(container.prefs.clipboardClearSeconds)
    var requireDeviceAuth by mutableStateOf(container.prefs.requireDeviceAuth)
    var envelope by mutableStateOf<VaultKeyEnvelope?>(null)
    var busy by mutableStateOf(false)
    /** Short status line shown after an action completes. */
    var notice by mutableStateOf<String?>(null)

    // Change password
    var showChangePassword by mutableStateOf(false)
    var currentPassword by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirmPassword by mutableStateOf("")
    var changeError by mutableStateOf<String?>(null)

    // Password-gated actions
    var action by mutableStateOf<Action?>(null)
    var step by mutableStateOf(Step.NONE)
    var dialogError by mutableStateOf<String?>(null)
    var eraseTyped by mutableStateOf("")
    private var pendingDeviceAuthValue = false

    val hardwareLevel: SecurityLevel? get() = container.session.hardwareLevel ?: envelope?.hwLevel
    val keeperUnlocked: Boolean get() = container.session.passwordsOrNull() != null

    init { loadEnvelope() }

    private fun loadEnvelope() {
        viewModelScope.launch {
            envelope = withContext(Dispatchers.IO) { runCatching { container.keyManager.envelope(VaultIds.CONTENT) }.getOrNull() }
        }
    }

    fun setTimeout(ms: Long) { policy = policy.copy(inactivityTimeoutMs = ms); container.prefs.lockPolicy = policy }
    fun setLockOnBackground(v: Boolean) { policy = policy.copy(lockOnBackground = v); container.prefs.lockPolicy = policy }
    fun setLockOnScreenOff(v: Boolean) { policy = policy.copy(lockOnScreenOff = v); container.prefs.lockPolicy = policy }
    fun updateLockedScreenActions(v: Boolean) { lockedScreenActions = v; container.alarmRuntime.prefs.lockedScreenActionsAllowed = v }
    fun updateClipboardSeconds(s: Int) { clipboardSeconds = s; container.prefs.clipboardClearSeconds = s }

    fun openChangePassword() { changeError = null; showChangePassword = true }
    fun closeChangePassword() { showChangePassword = false; currentPassword = ""; newPassword = ""; confirmPassword = ""; changeError = null }

    fun changePassword() {
        if (busy) return
        changeError = null
        when {
            currentPassword.isEmpty() -> { changeError = "Enter your current password."; return }
            newPassword.length < 8 -> { changeError = "Use at least 8 characters for the new password."; return }
            newPassword != confirmPassword -> { changeError = "The new password entries do not match."; return }
        }
        val params = envelope?.kdf
        if (params == null) { changeError = "Could not read the vault key parameters."; return }
        busy = true
        viewModelScope.launch {
            val old = currentPassword.toCharArray()
            val new = newPassword.toCharArray()
            try {
                val ok = container.session.changePassword(old, new, params)
                if (ok) {
                    closeChangePassword()
                    notice = "Password changed. Existing backups still open with the passphrase they were made with."
                    loadEnvelope()
                } else {
                    changeError = "Current password is wrong"
                }
            } catch (e: IllegalArgumentException) {
                changeError = "The new password must differ from your duress password."
            } catch (e: Exception) {
                changeError = "Could not change the password (${e.javaClass.simpleName})."
            } finally {
                old.fill('\u0000'); new.fill('\u0000')
                currentPassword = ""
                busy = false
            }
        }
    }

    fun beginDeviceAuthChange(newValue: Boolean) {
        pendingDeviceAuthValue = newValue
        begin(Action.DEVICE_AUTH)
    }

    fun begin(a: Action) {
        if (busy) return
        notice = null
        dialogError = null
        eraseTyped = ""
        action = a
        step = Step.PASSWORD
    }

    fun cancel() {
        if (busy) return
        action = null; step = Step.NONE; dialogError = null; eraseTyped = ""
    }

    /** Step 1: fresh authentication with the Haven password. */
    fun submitPassword(chars: CharArray) {
        val a = action ?: run { chars.fill('\u0000'); return }
        busy = true
        dialogError = null
        viewModelScope.launch {
            try {
                if (a == Action.DEVICE_AUTH) {
                    val ok = container.session.setDeviceAuthRequirement(chars, pendingDeviceAuthValue)
                    if (ok) {
                        container.prefs.requireDeviceAuth = pendingDeviceAuthValue
                        requireDeviceAuth = pendingDeviceAuthValue
                        notice = if (pendingDeviceAuthValue) "Device screen lock is now also required to open vaults." else "Device screen lock is no longer required to open vaults."
                        loadEnvelope()
                        action = null; step = Step.NONE
                    } else {
                        dialogError = "Wrong password."
                    }
                } else {
                    if (container.session.verifyPassword(chars)) step = Step.CONFIRM else dialogError = "Wrong password."
                }
            } catch (e: Exception) {
                dialogError = "Could not verify (${e.javaClass.simpleName})."
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    /** Step 2: the scoped confirmation. */
    fun confirm() {
        val a = action ?: return
        if (busy) return
        if (a == Action.ERASE_ALL && eraseTyped.trim() != "ERASE") return
        busy = true
        viewModelScope.launch {
            try {
                when (a) {
                    Action.DELETE_REMINDERS -> { container.reminders.deleteAll(); notice = "All reminders deleted." }
                    Action.DELETE_TASKS -> {
                        container.tasks.deleteAllTasks()
                        container.tasks.observeLists().first().forEach { container.tasks.deleteList(it.id) }
                        notice = "All tasks and lists deleted."
                    }
                    Action.DELETE_PASSWORDS -> {
                        if (container.session.passwordsOrNull() == null) { notice = "Unlock the password keeper first."; return@launch }
                        container.passwords.deleteAll()
                        notice = "All passwords and folders deleted."
                    }
                    Action.ERASE_ALL -> container.session.eraseEverything { container.prefs.clearAll() } // root guard returns to Welcome
                    Action.DEVICE_AUTH -> Unit
                }
            } catch (e: Exception) {
                notice = "The action failed (${e.javaClass.simpleName})."
            } finally {
                action = null; step = Step.NONE; eraseTyped = ""
                busy = false
            }
        }
    }

    override fun onCleared() {
        currentPassword = ""; newPassword = ""; confirmPassword = ""; eraseTyped = ""
    }
}

/* ---------- Backup & restore ---------- */

class BackupViewModel(private val container: AppContainer) : ViewModel() {
    // Backup key
    var keyDisplay by mutableStateOf<String?>(null)
    private var storedKey: BackupKey? = null
    /** null while loading. */
    var keyVerified by mutableStateOf<Boolean?>(null)
    var keyError by mutableStateOf<String?>(null)
    var verifyInput by mutableStateOf("")
    var verifyError by mutableStateOf<String?>(null)
    var showRotateConfirm by mutableStateOf(false)

    // Create backup
    var exportPassphrase by mutableStateOf("")
    var exportConfirm by mutableStateOf("")
    var includePasswords by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var exportMessage by mutableStateOf<String?>(null)
    var exportOk by mutableStateOf(false)

    // Restore
    var restoreUri by mutableStateOf<Uri?>(null)
    var inspection by mutableStateOf<BackupInspection?>(null)
    /** Set once after a successful restore; the screen then restarts navigation so no screen keeps state from the replaced data. */
    var restoredJustNow by mutableStateOf(false)
    var inspecting by mutableStateOf(false)
    var restoreMessage by mutableStateOf<String?>(null)
    var restoreOk by mutableStateOf(false)
    var restorePassphrase by mutableStateOf("")
    var restoreKeyText by mutableStateOf("")
    var restorePasswords by mutableStateOf(false)
    var restoring by mutableStateOf(false)
    var showRestoreConfirm by mutableStateOf(false)

    val keeperUnlocked: Boolean get() = container.session.passwordsOrNull() != null
    val busy: Boolean get() = exporting || restoring || inspecting

    init { loadKey() }

    private fun loadKey() {
        viewModelScope.launch {
            try {
                val k = container.backups.backupKey()
                storedKey?.destroy()
                storedKey = k
                keyDisplay = k.toDisplayString()
                keyVerified = container.backups.backupKeyVerified()
                keyError = null
            } catch (e: Exception) {
                keyError = "Could not read the backup key (${e.javaClass.simpleName})."
            }
        }
    }

    fun verifyKey() {
        val stored = storedKey ?: return
        verifyError = null
        val parsed = try {
            BackupKey.parse(verifyInput)
        } catch (e: IllegalArgumentException) {
            verifyError = "That is not a valid backup key. Check for typos."
            return
        }
        val a = parsed.bytes()
        val b = stored.bytes()
        val same = MessageDigest.isEqual(a, b)
        a.fill(0); b.fill(0); parsed.destroy()
        if (!same) { verifyError = "That key does not match. Compare it character by character."; return }
        viewModelScope.launch {
            try {
                container.backups.markBackupKeyVerified()
                keyVerified = true
                verifyInput = ""
            } catch (e: Exception) {
                verifyError = "Could not save the confirmation (${e.javaClass.simpleName})."
            }
        }
    }

    fun rotateKey() {
        showRotateConfirm = false
        viewModelScope.launch {
            try {
                val k = container.backups.rotateBackupKey()
                storedKey?.destroy()
                storedKey = k
                keyDisplay = k.toDisplayString()
                keyVerified = false
                verifyInput = ""
                verifyError = null
                restoreKeyText = ""
                inspection = inspection?.copy(keyIdMatchesStored = false)
            } catch (e: Exception) {
                keyError = "Could not generate a new key (${e.javaClass.simpleName})."
            }
        }
    }

    fun export() {
        if (busy || keyVerified != true) return
        exportMessage = null
        exportOk = false
        when {
            exportPassphrase.length < 8 -> { exportMessage = "Use a backup passphrase of at least 8 characters."; return }
            exportPassphrase != exportConfirm -> { exportMessage = "The passphrase entries do not match."; return }
            includePasswords && !keeperUnlocked -> { exportMessage = "Unlock the password keeper first to include it."; return }
        }
        exporting = true
        viewModelScope.launch {
            val chars = exportPassphrase.toCharArray()
            try {
                val params = withContext(Dispatchers.IO) { container.keyManager.envelope(VaultIds.CONTENT).kdf }
                when (val r = container.backups.export(chars, includePasswords, params)) {
                    is ExportResult.Success -> { exportOk = true; exportMessage = "Saved ${r.displayName} (${r.bytes} bytes)." }
                    is ExportResult.Failure -> exportMessage = r.message
                }
            } catch (e: Exception) {
                exportMessage = "Backup failed (${e.javaClass.simpleName})."
            } finally {
                chars.fill('\u0000')
                exportPassphrase = ""; exportConfirm = ""
                exporting = false
            }
        }
    }

    fun onFilePicked(uri: Uri?) {
        restoreMessage = null
        restoreOk = false
        inspection = null
        restoreUri = null
        if (uri == null) return
        if (!container.backups.isLocalUri(uri)) {
            restoreMessage = "Only files on this device's storage are accepted (cloud providers are refused)."
            return
        }
        inspecting = true
        viewModelScope.launch {
            try {
                container.backups.inspect(uri).fold(
                    onSuccess = { ins ->
                        restoreUri = uri
                        inspection = ins
                        restoreKeyText = if (ins.keyIdMatchesStored == true) keyDisplay.orEmpty() else ""
                    },
                    onFailure = { e -> restoreMessage = "Cannot read this file: ${e.message ?: e.javaClass.simpleName}" },
                )
            } finally {
                inspecting = false
            }
        }
    }

    fun requestRestore() {
        restoreMessage = null
        if (restoreUri == null || inspection == null) return
        if (restorePassphrase.isEmpty()) { restoreMessage = "Enter the backup passphrase."; return }
        if (restorePasswords && !keeperUnlocked) { restoreMessage = "Unlock the password keeper first to restore passwords."; return }
        val key = try { BackupKey.parse(restoreKeyText) } catch (e: IllegalArgumentException) { restoreMessage = "The backup key is not valid. Check for typos."; return }
        key.destroy()
        showRestoreConfirm = true
    }

    fun restore() {
        showRestoreConfirm = false
        val uri = restoreUri ?: return
        if (busy) return
        restoring = true
        restoreOk = false
        viewModelScope.launch {
            val chars = restorePassphrase.toCharArray()
            var key: BackupKey? = null
            try {
                key = BackupKey.parse(restoreKeyText)
                when (val r = container.backups.restore(uri, chars, key, restorePasswords)) {
                    is RestoreResult.Success -> {
                        restoreOk = true
                        restoreMessage = "Restored ${r.reminders} reminders, ${r.tasks} tasks and ${r.passwords} passwords."
                        restoreUri = null; inspection = null; restoreKeyText = ""
                        restoredJustNow = true
                    }
                    is RestoreResult.Failure -> restoreMessage = r.message
                }
            } catch (e: IllegalArgumentException) {
                restoreMessage = "The backup key is not valid."
            } catch (e: Exception) {
                restoreMessage = "Restore failed (${e.javaClass.simpleName})."
            } finally {
                chars.fill('\u0000')
                key?.destroy()
                restorePassphrase = ""
                restoring = false
            }
        }
    }

    override fun onCleared() {
        storedKey?.destroy(); storedKey = null
        keyDisplay = null; verifyInput = ""; exportPassphrase = ""; exportConfirm = ""; restorePassphrase = ""; restoreKeyText = ""
    }
}

/* ---------- Alarm readiness ---------- */

class AlarmReadinessViewModel(private val container: AppContainer) : ViewModel() {
    var report by mutableStateOf<AlarmReadiness.Report?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var testMessage by mutableStateOf<String?>(null)
    var scheduling by mutableStateOf(false)

    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { container.readiness.check(container.alarmRuntime) } }
            result.fold(
                onSuccess = { report = it; error = null },
                onFailure = { error = "Could not read the system alarm settings (${it.javaClass.simpleName})." },
            )
            loading = false
        }
    }

    fun testAlarm() {
        if (scheduling) return
        scheduling = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { container.alarmRuntime.actions.scheduleTestAlarm(10) }
                testMessage = "A test alarm will ring in 10 seconds. Lock the phone to see the lock-screen alert."
                refresh()
            } catch (e: Exception) {
                testMessage = "Could not schedule the test alarm (${e.javaClass.simpleName})."
            } finally {
                scheduling = false
            }
        }
    }
}
