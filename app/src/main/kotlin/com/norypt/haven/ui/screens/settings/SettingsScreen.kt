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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.crypto.VaultIds
import com.norypt.haven.crypto.VaultKeyEnvelope
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var locking by remember { mutableStateOf(false) }
    val envelope by produceState<VaultKeyEnvelope?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { container.keyManager.envelope(VaultIds.CONTENT) }.getOrNull() }
    }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(title = "Settings")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            Button(
                onClick = { if (!locking) { locking = true; scope.launch { try { container.session.lock() } finally { locking = false } } } },
                enabled = !locking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(if (locking) "Locking…" else "Lock now") }
            Spacer(Modifier.height(16.dp))

            SectionCard {
                SettingsNavRow("Security", "Auto-lock, password, device screen lock, deletion") { nav.navigate(Routes.SETTINGS_SECURITY) }
                HorizontalDivider()
                SettingsNavRow("Appearance", "System, light or dark") { nav.navigate(Routes.SETTINGS_APPEARANCE) }
                HorizontalDivider()
                SettingsNavRow("Backup & restore", "Encrypted files in Downloads/Haven") { nav.navigate(Routes.BACKUP) }
                HorizontalDivider()
                SettingsNavRow("Alarm readiness", "Check that reminders can ring") { nav.navigate(Routes.ALARM_READINESS) }
                HorizontalDivider()
                SettingsNavRow("About", "Version, what Haven does and cannot do") { nav.navigate(Routes.ABOUT) }
            }
            Spacer(Modifier.height(16.dp))

            SectionCard {
                Text("Security facts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                FactRow(FactLevel.OK, "No account required")
                FactRow(FactLevel.OK, "No internet permission")
                FactRow(FactLevel.OK, "Private content is encrypted on this device")
                FactRow(FactLevel.INFO, "Alarm times are stored outside the password-protected vault so alarms work after a restart")
                FactRow(FactLevel.INFO, "Norypt cannot recover a lost password or backup key")
                val (lvl, label) = hardwareLevelFact(container.session.hardwareLevel ?: envelope?.hwLevel)
                FactRow(lvl, label)
                envelope?.let { FactRow(FactLevel.INFO, "Key protection: ${argon2Summary(it.kdf)}") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
