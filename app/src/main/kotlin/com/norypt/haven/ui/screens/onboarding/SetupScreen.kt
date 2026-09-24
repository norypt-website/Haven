package com.norypt.haven.ui.screens.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.norypt.haven.crypto.SecurityLevel
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.SectionCard

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: SetupViewModel = viewModel { SetupViewModel(container) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        HavenTopBar(title = "Choose a password")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (val phase = vm.phase) {
                is SetupViewModel.Phase.Created -> CreatedSummary(phase, onDone)
                else -> SetupForm(vm)
            }
        }
    }
}

@Composable
private fun SetupForm(vm: SetupViewModel) {
    val enabled = !vm.busy && !vm.fatal
    Text(
        "This password protects everything in Haven. Use a long passphrase. Spaces are fine. Haven never trims or changes it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    PasswordField(
        value = vm.password,
        onValueChange = { if (enabled) vm.password = it },
        label = "Password",
        imeAction = ImeAction.Next,
        isError = vm.error != null && vm.password.length < SetupViewModel.MIN_LENGTH,
    )
    Spacer(Modifier.height(8.dp))
    PasswordField(
        value = vm.confirm,
        onValueChange = { if (enabled) vm.confirm = it },
        label = "Confirm password",
        isError = vm.confirm.isNotEmpty() && vm.confirm != vm.password,
        supportingText = if (vm.confirm.isNotEmpty() && vm.confirm != vm.password) "Does not match" else null,
        onDone = { if (enabled) vm.create() },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = vm::suggest, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Suggest a passphrase")
    }
    vm.suggestion?.let { suggestion ->
        Spacer(Modifier.height(8.dp))
        SectionCard {
            SelectionContainer {
                Text(
                    suggestion,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small).padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("Write it down somewhere safe. There is no reset.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = vm::useSuggestion, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use it") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = vm::suggest, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Another") }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = vm.requireDeviceAuth, enabled = enabled, role = Role.Checkbox, onValueChange = { vm.requireDeviceAuth = it })
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = vm.requireDeviceAuth, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(
            "Also require my device screen lock to open vaults (optional extra step; not a replacement for the password)",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    Spacer(Modifier.height(16.dp))
    vm.error?.let { err ->
        FactRow(if (vm.fatal) FactLevel.DANGER else FactLevel.WARNING, err)
        Spacer(Modifier.height(8.dp))
    }
    if (vm.busy) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                when (vm.phase) {
                    SetupViewModel.Phase.Measuring -> "Measuring this device… (≈1 s)"
                    else -> "Creating your encrypted vaults…"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Button(onClick = vm::create, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text("Create vault")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Norypt cannot recover a lost password.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun CreatedSummary(phase: SetupViewModel.Phase.Created, onDone: () -> Unit) {
    val p = phase.params
    SectionCard {
        Text("Your vault is ready", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        FactRow(FactLevel.OK, "Key protection: Argon2id, ${p.memoryKib / 1024} MiB, ${p.iterations} passes")
        when (phase.level) {
            SecurityLevel.STRONGBOX -> FactRow(FactLevel.OK, "Hardware key: StrongBox secure element")
            SecurityLevel.TRUSTED_ENVIRONMENT -> FactRow(FactLevel.OK, "Hardware key: Trusted execution environment")
            SecurityLevel.SOFTWARE, SecurityLevel.UNKNOWN, null -> FactRow(FactLevel.WARNING, "Software-backed key (debug build only)")
        }
        FactRow(FactLevel.INFO, "Private content is encrypted on this device")
        FactRow(FactLevel.INFO, "Norypt cannot recover a lost password or backup key")
    }
    Spacer(Modifier.height(16.dp))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Continue") }
}
