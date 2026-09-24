package com.norypt.haven.ui.screens.settings

import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up
import java.text.DateFormat
import java.util.Date

@Composable
fun AlarmReadinessScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: AlarmReadinessViewModel = viewModel { AlarmReadinessViewModel(container) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // A "Fix" excursion into system settings: begun before launch, ended when this screen resumes.
    var excursion by remember { mutableStateOf(false) }

    // Recheck on every entry and every return from system settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (excursion) { excursion = false; container.lockController.endSystemInteraction() }
                vm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (excursion) { excursion = false; container.lockController.endSystemInteraction() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        HavenTopBar(
            title = "Alarm readiness",
            onBack = { nav.up() },
            actions = {
                IconButton(onClick = vm::refresh, enabled = !vm.loading, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding)) {
            if (vm.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)) }
            Text(
                "Haven checks these facts every time you open this screen. Alarms cannot ring while the phone is off, or after you force-stop or disable Haven.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            vm.error?.let { FactRow(FactLevel.DANGER, it); Spacer(Modifier.height(8.dp)) }
            val report = vm.report
            if (report != null) {
                SectionCard {
                    report.items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                FactRow(
                                    when (item.status) {
                                        AlarmReadiness.Status.OK -> FactLevel.OK
                                        AlarmReadiness.Status.WARNING -> FactLevel.WARNING
                                        AlarmReadiness.Status.BLOCKED -> FactLevel.DANGER
                                    },
                                    item.title,
                                    item.detail,
                                )
                            }
                            val fix = item.fixIntent
                            if (fix != null && item.status != AlarmReadiness.Status.OK) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (!excursion) { excursion = true; container.lockController.beginSystemInteraction() }
                                        try {
                                            context.startActivity(fix)
                                        } catch (e: ActivityNotFoundException) {
                                            excursion = false
                                            container.lockController.endSystemInteraction()
                                            vm.testMessage = "This device has no settings page for ${item.title.lowercase()}."
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Fix") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionCard {
                    val next = report.nextArmedEpochMs
                    Text("Next scheduled Haven alarm: " + (next?.let { formatTime(it) } ?: "None"), style = MaterialTheme.typography.bodyLarge)
                    report.systemNextAlarmEpochMs?.let {
                        Text("Next alarm the system reports: ${formatTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SectionCard {
                Text("Test alarm", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("A test alarm will ring in 10 seconds. Lock the phone to see the lock-screen alert.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::testAlarm, enabled = !vm.scheduling, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (vm.scheduling) "Scheduling…" else "Test alarm")
                }
                vm.testMessage?.let { Spacer(Modifier.height(8.dp)); FactRow(FactLevel.INFO, it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun formatTime(epochMs: Long): String =
    runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs)) }.getOrDefault("unknown")
