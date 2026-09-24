package com.norypt.haven.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.BuildConfig
import com.norypt.haven.R
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up

@Composable
fun AboutScreen(nav: NavHostController) {
    Column(Modifier.fillMaxSize()) {
        HavenTopBar(title = "About Haven", onBack = { nav.up() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.drawable.norypt_logo),
                    contentDescription = "Norypt",
                    modifier = Modifier.fillMaxWidth(0.6f).heightIn(max = 72.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text("Haven ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("A private place for what matters", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Text("Passwords, reminders, and tasks—encrypted.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("by Norypt — norypt.com", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }

            SectionCard {
                Text("What Haven does", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                FactRow(FactLevel.OK, "No account required")
                FactRow(FactLevel.OK, "No internet permission", "Haven never connects to anything. A build check fails if any dependency asks for network access.")
                FactRow(FactLevel.OK, "Private content is encrypted on this device", "Reminders, tasks and passwords are stored in encrypted databases whose keys need both your password and a key bound to this device.")
                FactRow(FactLevel.OK, "Alarm times are stored outside the password-protected vault so alarms work after a restart", "Notifications never contain reminder text.")
                FactRow(FactLevel.OK, "Manual, local, encrypted backups", "A backup needs both its passphrase and the backup key.")
                FactRow(FactLevel.INFO, "Norypt cannot recover a lost password or backup key")
            }
            Spacer(Modifier.height(12.dp))

            SectionCard {
                Text("What Haven cannot do", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                FactRow(FactLevel.WARNING, "Protect you from a compromised operating system", "Root, a modified bootloader or malware in the OS can see everything while the vault is open.")
                FactRow(FactLevel.WARNING, "Hide content while it is on screen", "Screenshots and screen recording are blocked, but a malicious keyboard or accessibility service can read what you type or view.")
                FactRow(FactLevel.WARNING, "Hide alarm timing from the operating system", "When reminders ring, and that Haven is installed, is visible to the OS.")
                FactRow(FactLevel.WARNING, "Make the phone anonymous", "Haven only avoids creating identity: no account, no email, no phone number, no network.")
                FactRow(FactLevel.WARNING, "Guarantee that deleted data is gone", "Deleting in Haven does not touch backups you exported, and flash storage may retain remnants.")
                FactRow(FactLevel.WARNING, "Claim an independent security audit", "Automated tests pass, but no independent audit has been performed yet.")
            }
            Spacer(Modifier.height(12.dp))

            SectionCard {
                Text("Open-source components", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "SQLCipher (encrypted SQLite)\nTink (AES-GCM and streaming AEAD)\nArgon2kt (Argon2id key derivation)\nAndroidX and Jetpack Compose (Material 3)\nKotlin coroutines and serialization",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
