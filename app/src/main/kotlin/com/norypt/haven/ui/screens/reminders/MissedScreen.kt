package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.GroupPosition
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.ItemRow
import com.norypt.haven.ui.components.RowTextInset
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.cardSegment
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
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
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp)) {
                if (list.isNotEmpty()) item { SectionLabel("Missed", count = list.size) }
                itemsIndexed(list, key = { _, it -> it.occurrence.occurrenceId }) { i, item ->
                    ItemRow(
                        title = item.title,
                        modifier = Modifier.cardSegment(GroupPosition.of(i, list.size), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.outlineVariant, RowTextInset),
                        detail = "Missed · " + Fmt.epochMs(item.occurrence.triggerAt),
                        onClick = if (item.isTest) null else {
                            {
                                val taskId = item.followUpTaskId
                                if (taskId != null) nav.navigate(Routes.taskDetail(taskId)) else nav.navigate(Routes.reminderDetail(item.occurrence.reminderId))
                            }
                        },
                        leading = { IconTile(Icons.Filled.AlarmOff, tileBrush(EntryColor.ORANGE.color)) },
                        below = if (item.isTest) null else {
                            {
                                Text(
                                    if (item.followUpTaskId != null) "Tap to open the task" else "Tap to open the reminder",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    ) {
                        TextButton(onClick = { vm.acknowledge(item.occurrence.occurrenceId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Acknowledge") }
                    }
                }
            }
        }
    }
}
