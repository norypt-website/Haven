package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.AssignmentLate
import androidx.compose.material.icons.filled.Star
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.theme.LocalHavenColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ListSpacing
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RingingScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: RingingViewModel = viewModel { RingingViewModel(container) }
    val items by vm.items.collectAsState()

    // Once the store has reported and nothing rings any more, leave.
    LaunchedEffect(items) { if (items != null && items!!.isEmpty()) nav.up() }

    Scaffold(topBar = { HavenTopBar("Ringing", onBack = { nav.up() }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = ListSpacing) {
            items(items ?: emptyList(), key = { it.occurrence.occurrenceId }) { item -> RingingCard(item, vm) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RingingCard(item: RingingViewModel.Item, vm: RingingViewModel) {
    val occId = item.occurrence.occurrenceId
    var menuOpen by remember { mutableStateOf(false) }
    var customSnooze by remember { mutableStateOf(false) }
    var untilTime by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val isEarly = item.isEarly
    val isFollowUp = item.isFollowUp
    val headline = when {
        item.isTest -> "Test alarm"
        isEarly -> "Coming up in ${Fmt.minutesShort(item.occurrence.earlyMinutes)}"
        isFollowUp -> "Unfinished task"
        else -> null
    }
    val (icon, tint) = when {
        isEarly -> Icons.Filled.Schedule to MaterialTheme.colorScheme.primary
        isFollowUp -> Icons.Filled.AssignmentLate to LocalHavenColors.current.warning
        else -> Icons.Filled.NotificationsActive to MaterialTheme.colorScheme.primary
    }

    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp).padding(top = 2.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (headline != null) Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!item.isTest) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val starred = item.reminder?.starred == true || item.followUpTask?.starred == true
                        if (starred) {
                            Icon(Icons.Filled.Star, contentDescription = "Starred", tint = LocalHavenColors.current.warning, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(item.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f, fill = false))
                    }
                    val priority = item.reminder?.priority ?: item.followUpTask?.let { com.norypt.haven.storage.content.Priority.of(it.priority) }
                    if (priority != null) { Spacer(Modifier.height(4.dp)); PriorityChip(priority) }
                }
                Spacer(Modifier.height(4.dp))
                when {
                    isEarly -> {
                        val mainAt = item.occurrence.triggerAt + item.occurrence.earlyMinutes * 60_000L
                        Text("The reminder itself rings at " + Fmt.epochMs(mainAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    isFollowUp -> {
                        val due = item.followUpTask?.dueLocal?.let { runCatching { java.time.LocalDateTime.parse(it) }.getOrNull() }
                        Text(
                            if (due != null) "Was due " + Fmt.dateTime(due) + " and is still open." else "This task is still open.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Text("Scheduled for " + Fmt.epochMs(item.occurrence.triggerAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.occurrence.snoozeCount > 0) Text("Snoozed ${item.occurrence.snoozeCount} ${if (item.occurrence.snoozeCount == 1) "time" else "times"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.notes, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (!item.isTest && !isFollowUp) {
                SmallIconButton(onClick = { menuOpen = true }, contentDescription = "More options for ${item.title}", icon = Icons.Filled.MoreVert)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Disable this reminder") }, onClick = { menuOpen = false; confirmDisable = true })
                    DropdownMenuItem(text = { Text("Delete series…") }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        }

        if (isFollowUp) {
            Spacer(Modifier.height(16.dp))
            val task = item.followUpTask
            Button(
                onClick = { vm.completeFollowUp(item.followUpTaskId, occId) },
                enabled = task != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Mark complete") }
            Text("Completing the task also ends this alert.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text(if (isFollowUp) "Remind again" else "Snooze", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.snoozePresets.forEach { m ->
                OutlinedButton(onClick = { vm.snooze(occId, m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(snoozeLabel(m)) }
            }
            OutlinedButton(onClick = { customSnooze = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Custom…") }
            OutlinedButton(onClick = { untilTime = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Until a time…") }
        }

        if (!item.isTest && !isFollowUp && !isEarly && item.reminder != null) {
            Spacer(Modifier.height(16.dp))
            Text("Reschedule", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Moves this occurrence and ends the current ring.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickMove.entries.forEach { m ->
                    OutlinedButton(onClick = { vm.quickReschedule(item, m) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(quickMoveShort(m)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (isFollowUp) {
            OutlinedButton(onClick = { vm.dismiss(occId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Dismiss, keep task open") }
        } else {
            Button(onClick = { vm.dismiss(occId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(if (isEarly) "Got it" else "Dismiss") }
        }

        if (item.tasks.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Linked tasks", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Completing a task does not dismiss the alarm; dismissing does not complete the task.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            item.tasks.forEach { t ->
                OutlinedButton(onClick = { vm.completeTask(t.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mark '${t.title}' complete") }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    if (customSnooze) {
        var text by remember { mutableStateOf(vm.defaultSnooze.toString()) }
        val minutes = text.toIntOrNull()?.takeIf { it in 1..(24 * 60) }
        AlertDialog(
            onDismissRequest = { customSnooze = false },
            title = { Text("Snooze for how long?") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text("Minutes (1 to 1440)") },
                    singleLine = true,
                    isError = minutes == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { minutes?.let { vm.snooze(occId, it) }; customSnooze = false }, enabled = minutes != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Snooze") } },
            dismissButton = { TextButton(onClick = { customSnooze = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
    }
    if (untilTime) {
        HavenTimePickerDialog(
            initial = LocalTime.now().plusMinutes(30).withSecond(0).withNano(0),
            onPick = { t ->
                val zone = ZoneId.systemDefault()
                var at = LocalDate.now().atTime(t).atZone(zone)
                if (!at.toInstant().isAfter(java.time.Instant.now())) at = at.plusDays(1)
                vm.snoozeUntil(occId, at.toInstant().toEpochMilli())
                untilTime = false
            },
            onDismiss = { untilTime = false },
        )
    }
    if (confirmDisable) {
        ConfirmDialog(
            title = "Disable this reminder?",
            body = "'${item.title}' will stop ringing in future. It stays in your list so you can turn it back on. This alarm keeps ringing until you dismiss or snooze it.",
            confirmLabel = "Disable reminder",
            destructive = false,
            onConfirm = { confirmDisable = false; vm.disableReminder(item.occurrence.reminderId) },
            onDismiss = { confirmDisable = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete the entire series?",
            body = "This deletes '${item.title}' with every past and future occurrence. Linked tasks are kept.",
            confirmLabel = "Delete the entire series",
            onConfirm = { confirmDelete = false; vm.deleteSeries(item.occurrence.reminderId) },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Short button labels for the quick-move buttons. */
private fun quickMoveShort(m: QuickMove): String = when (m) {
    QuickMove.PLUS_1H -> "+1 hour"
    QuickMove.PLUS_3H -> "+3 hours"
    QuickMove.TOMORROW -> "Tomorrow, same time"
    QuickMove.NEXT_WEEK -> "Next week"
}

private fun snoozeLabel(minutes: Int): String = when {
    minutes % 60 == 0 && minutes >= 60 -> "${minutes / 60} h"
    else -> "$minutes min"
}
