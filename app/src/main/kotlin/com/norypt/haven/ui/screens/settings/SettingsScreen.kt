package com.norypt.haven.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import com.norypt.haven.BuildConfig
import com.norypt.haven.R
import com.norypt.haven.ui.components.CardDivider
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.HeroCard
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.theme.NoryptColors
import com.norypt.haven.ui.components.HavenTopBar
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp)) {
            HeroCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_haven_mark), contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Haven", style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text("Version ${BuildConfig.VERSION_NAME} · Vault open", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (!locking) { locking = true; scope.launch { try { container.session.lock() } finally { locking = false } } } },
                    enabled = !locking,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = NoryptColors.Navy),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (locking) "Locking…" else "Lock now", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                }
            }

            SectionLabel("General")
            GroupCard {
                SettingsNavRow("Security", "Auto-lock, password, device screen lock, deletion", Icons.Filled.Shield, EntryColor.INDIGO.color) { nav.navigate(Routes.SETTINGS_SECURITY) }
                CardDivider(68.dp)
                SettingsNavRow("Appearance", "System, light or dark", Icons.Filled.Palette, EntryColor.PURPLE.color) { nav.navigate(Routes.SETTINGS_APPEARANCE) }
                CardDivider(68.dp)
                SettingsNavRow("Backup & restore", "Encrypted files in Downloads/Haven", Icons.Filled.SettingsBackupRestore, EntryColor.TEAL.color) { nav.navigate(Routes.BACKUP) }
                CardDivider(68.dp)
                SettingsNavRow("Alarm readiness", "Check that reminders can ring", Icons.Filled.AlarmOn, EntryColor.ORANGE.color) { nav.navigate(Routes.ALARM_READINESS) }
                CardDivider(68.dp)
                SettingsNavRow("About", "Version, what Haven does and cannot do", Icons.Filled.Info, EntryColor.GRAPHITE.color) { nav.navigate(Routes.ABOUT) }
            }

            SectionLabel("Security facts")
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    FactRow(FactLevel.OK, "No account required")
                    FactRow(FactLevel.OK, "No internet permission")
                    FactRow(FactLevel.OK, "Private content is encrypted on this device")
                    FactRow(FactLevel.INFO, "Alarm times are stored outside the password-protected vault so alarms work after a restart")
                    FactRow(FactLevel.INFO, "Norypt cannot recover a lost password or backup key")
                    val (lvl, label) = hardwareLevelFact(container.session.hardwareLevel ?: envelope?.hwLevel)
                    FactRow(lvl, label)
                    envelope?.let { FactRow(FactLevel.INFO, "Key protection: ${argon2Summary(it.kdf)}") }
                }
            }
        }
    }
}
