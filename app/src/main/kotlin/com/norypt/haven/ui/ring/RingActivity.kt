package com.norypt.haven.ui.ring

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.norypt.haven.MainActivity
import com.norypt.haven.havenApp
import com.norypt.haven.R
import com.norypt.haven.alarm.AlarmIntents
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.security.SecureWindow
import com.norypt.haven.ui.theme.HavenTheme
import com.norypt.haven.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map

/**
 * Full-screen ringing UI. Direct-boot aware and shown over the lock screen, so it must NEVER
 * show private content and must not touch credential-encrypted storage (no AppPreferences).
 * Snooze/dismiss are offered only when the "locked-screen actions" setting allows.
 */
class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug builds only: `adb shell am start ... --ez com.norypt.haven.debug.ALLOW_CAPTURE true` lets us take design screenshots.
        if (com.norypt.haven.BuildConfig.DEBUG && intent?.getBooleanExtra("com.norypt.haven.debug.ALLOW_CAPTURE", false) == true) havenApp.debugAllowCapture = true
        SecureWindow.apply(this, allowCapture = com.norypt.haven.BuildConfig.DEBUG && havenApp.debugAllowCapture)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val runtime = AlarmRuntime.get(this)
        val occurrenceId = intent.getStringExtra(AlarmIntents.EXTRA_OCCURRENCE_ID)?.takeIf { it.length <= 256 }
        val allowActions = runtime.prefs.lockedScreenActionsAllowed
        setContent {
            HavenTheme(ThemeMode.SYSTEM) {
                val ringingFlow = androidx.compose.runtime.remember { runtime.store.occurrences().observeRinging().map { it.map { o -> o.occurrenceId } } }
                val ringing by ringingFlow.collectAsState(initial = listOfNotNull(occurrenceId))
                androidx.compose.runtime.LaunchedEffect(ringing) { if (ringing.isEmpty()) finish() }
                RingScreen(
                    count = ringing.size,
                    allowActions = allowActions,
                    snoozeMinutes = runtime.prefs.defaultSnoozeMinutes,
                    presets = runtime.prefs.snoozePresetsMinutes,
                    onSnooze = { minutes -> ringing.forEach { id -> runtime.submit { runtime.actions.snooze(id, minutes) } }; finish() },
                    onDismiss = { ringing.forEach { id -> runtime.submit { runtime.actions.dismiss(id) } }; finish() },
                    onOpen = {
                        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(AlarmIntents.EXTRA_OCCURRENCE_ID, ringing.firstOrNull())
                        val km = getSystemService(KeyguardManager::class.java)
                        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() { startActivity(i); finish() }
                        }) else { startActivity(i); finish() }
                    },
                )
            }
        }
    }
}

@Composable
internal fun RingScreen(
    count: Int,
    allowActions: Boolean,
    snoozeMinutes: Int,
    presets: List<Int>,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    openLabel: String = "Open Haven",
    showOpen: Boolean = true,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            androidx.compose.material3.Icon(painterResource(R.drawable.ic_haven_mark), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(24.dp))
            Text(if (count > 1) "$count Haven reminders" else "Haven reminder", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Unlock Haven to see what this is about.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(40.dp))
            if (allowActions) {
                Button(onClick = { onSnooze(snoozeMinutes) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Snooze $snoozeMinutes min") }
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.filter { it != snoozeMinutes }.take(3).forEach { m -> TextButton(onClick = { onSnooze(m) }) { Text("$m min") } }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Dismiss") }
            } else {
                Text("Snooze and dismiss require unlocking Haven (your setting).", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            if (showOpen) TextButton(onClick = onOpen, modifier = Modifier.height(48.dp)) { Text(openLabel) }
        }
    }
}
