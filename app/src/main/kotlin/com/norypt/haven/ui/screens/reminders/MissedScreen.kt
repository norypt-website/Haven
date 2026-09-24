package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ListSpacing
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
import com.norypt.haven.ui.up

@Composable
fun MissedScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: MissedViewModel = viewModel { MissedViewModel(container) }
    val items by vm.items.collectAsState()
    val list = items ?: emptyList()
    val colors = LocalHavenColors.current

    Scaffold(
        topBar = {
            HavenTopBar("Missed reminders", onBack = { nav.up() }) {
                if (list.isNotEmpty()) TextButton(onClick = { vm.acknowledgeAll() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Acknowledge all") }
            }
        },
    ) { padding ->
        if (items != null && list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("No missed reminders", "Reminders that ring without an answer appear here until you acknowledge them.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = ListSpacing) {
                items(list, key = { it.occurrence.occurrenceId }) { item ->
                    SectionCard {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !item.isTest) {
                                val taskId = item.followUpTaskId
                                if (taskId != null) nav.navigate(Routes.taskDetail(taskId)) else nav.navigate(Routes.reminderDetail(item.occurrence.reminderId))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.AlarmOff, contentDescription = null, tint = colors.warning, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text("Missed · " + Fmt.epochMs(item.occurrence.triggerAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (!item.isTest) Text(if (item.followUpTaskId != null) "Tap to open the task" else "Tap to open the reminder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.acknowledge(item.occurrence.occurrenceId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Acknowledge") }
                        }
                    }
                }
            }
        }
    }
}
