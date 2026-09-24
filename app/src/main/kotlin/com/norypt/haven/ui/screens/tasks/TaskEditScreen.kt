package com.norypt.haven.ui.screens.tasks

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.data.TaskRepository
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PrioritySelector
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.up
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

private enum class EditPicker { DATE, TIME, LIST, REMINDER }

/** Create (id == null) or edit a task. Form text lives in the ViewModel, never in saved state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(nav: NavHostController, id: String?, listId: String?) {
    val container = LocalAppContainer.current
    val vm: TaskEditViewModel = viewModel(key = "task-edit:${id ?: "new"}:${listId ?: ""}") { TaskEditViewModel(container, id, listId) }
    val lists by vm.lists.collectAsState()
    val reminders by vm.reminders.collectAsState()
    var picker by remember { mutableStateOf<EditPicker?>(null) }

    val effectiveListId = vm.effectiveListId(lists)
    val listName = lists.find { it.id == effectiveListId }?.name
    val linkedReminder = vm.reminderId?.let { rid -> reminders.find { it.id == rid } }
    val canSave = vm.loaded && !vm.saving && effectiveListId != null

    LaunchedEffect(vm.notFound) { if (vm.notFound) nav.up() }

    fun save() {
        val target = effectiveListId ?: return
        vm.save(target) { nav.up() }
    }

    Scaffold(
        topBar = {
            HavenTopBar(if (id == null) "New task" else "Edit task", onBack = { nav.up() }, actions = {
                StarButton(starred = vm.starred, onToggle = { vm.starred = !vm.starred })
                TextButton(onClick = { save() }, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
            })
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val titleError = vm.showTitleError && vm.title.isBlank()
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.title = it },
                label = { Text("Title") },
                singleLine = true,
                enabled = vm.loaded,
                isError = titleError,
                supportingText = if (titleError) ({ Text("A title is required.") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.notes,
                onValueChange = { vm.notes = it },
                label = { Text("Notes (optional)") },
                minLines = 3,
                enabled = vm.loaded,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionCard {
                Text("Priority", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                // The four chips may not fit a narrow screen side by side; let them scroll instead of clipping.
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    PrioritySelector(value = vm.priority, onChange = { vm.priority = it }, enabled = vm.loaded)
                }
            }

            SectionCard {
                Text("List", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picker = EditPicker.LIST }, enabled = lists.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(listName ?: if (lists.isEmpty()) "No lists available" else "Choose a list")
                }
            }

            SectionCard {
                Text("Due", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker = EditPicker.DATE }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(vm.dueDate?.let { formatDate(it) } ?: "Set date")
                    }
                    OutlinedButton(onClick = { picker = EditPicker.TIME }, enabled = vm.dueDate != null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(vm.dueTime?.let { formatTime(it) } ?: "Set time")
                    }
                }
                when {
                    vm.dueDate == null -> {
                        Spacer(Modifier.height(4.dp))
                        Text("No due date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    vm.dueTime == null -> {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Due time not set — using ${formatTime(DefaultDueTime)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (vm.dueDate != null) {
                    Row {
                        if (vm.dueTime != null) TextButton(onClick = { vm.dueTime = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear time") }
                        TextButton(onClick = { vm.clearDue() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear due") }
                    }
                }
            }

            SectionCard {
                val hasDue = vm.dueDate != null
                Text("If still not done", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hasDue) "Haven rings again at the chosen time after the due time if the task is still incomplete. Completing the task cancels it."
                    else "Set a due date to use a follow-up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val choices: List<Int?> = listOf<Int?>(null) + TaskRepository.FOLLOW_UP_CHOICES.map { it.first }
                choices.forEach { minutes ->
                    val selected = (vm.followUpMinutes ?: 0) == (minutes ?: 0)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, enabled = hasDue && vm.loaded, role = Role.RadioButton, onClick = { vm.followUpMinutes = minutes }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = hasDue && vm.loaded)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            followUpChoiceLabel(minutes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (hasDue) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SectionCard {
                Text("Linked reminder (optional)", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { picker = EditPicker.REMINDER }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            vm.reminderId == null -> "None"
                            linkedReminder != null -> linkedReminder.title
                            else -> "Reminder not found"
                        },
                    )
                }
                if (vm.reminderId != null) {
                    TextButton(onClick = { vm.reminderId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove link") }
                }
                Text(
                    "Linking only connects the two. Completing or deleting the task never changes the reminder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.up() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = { save() }, enabled = canSave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Save") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    when (picker) {
        null -> Unit
        EditPicker.DATE -> {
            // Material's picker works in UTC-midnight millis; convert both ways with UTC so the calendar day is exact.
            val state = rememberDatePickerState(initialSelectedDateMillis = vm.dueDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picker = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { vm.dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picker = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
            ) { DatePicker(state = state) }
        }
        EditPicker.TIME -> {
            val initial = vm.dueTime ?: DefaultDueTime
            val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
            AlertDialog(
                onDismissRequest = { picker = null },
                title = { Text("Due time") },
                text = { TimePicker(state = state) },
                confirmButton = { TextButton(onClick = { vm.dueTime = LocalTime.of(state.hour, state.minute); picker = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } },
            )
        }
        EditPicker.LIST -> ChooserDialog(
            title = "List",
            options = lists,
            label = { it.name },
            isSelected = { it.id == effectiveListId },
            emptyText = "There are no lists. Create one from the Tasks overview first.",
            onChoose = { vm.listId = it.id; picker = null },
            onDismiss = { picker = null },
        )
        EditPicker.REMINDER -> ChooserDialog(
            title = "Link existing reminder",
            options = reminders,
            label = { it.title },
            isSelected = { it.id == vm.reminderId },
            emptyText = "There are no reminders yet. Save the task, then use 'Add a reminder for this task' on its detail page.",
            confirmLabel = "Link",
            onChoose = { vm.reminderId = it.id; picker = null },
            onDismiss = { picker = null },
        )
    }
}
