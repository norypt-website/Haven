package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.HorizontalDivider
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.components.priorityColor
import com.norypt.haven.ui.components.priorityLabel
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.data.Reminder
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.recurrence.OccurrenceKey
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ListSpacing
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun ReminderDetailScreen(nav: NavHostController, id: String) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: ReminderDetailViewModel = viewModel(key = "reminder-detail-$id") { ReminderDetailViewModel(container, id) }
    val reminder by vm.reminder.collectAsState()
    val occurrences by vm.occurrences.collectAsState()
    val tasks by vm.linkedTasks.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var editFuture: Occurrence? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            HavenTopBar("Reminder", onBack = { nav.up() }) {
                val current = reminder
                if (current != null) {
                    StarButton(starred = current.starred, onToggle = { vm.setStarred(!current.starred) })
                    SmallIconButton(onClick = { menuOpen = true }, contentDescription = "Series options", icon = Icons.Filled.MoreVert)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Edit series") }, onClick = { menuOpen = false; nav.navigate(Routes.reminderEdit(id)) })
                        DropdownMenuItem(
                            text = { Text(if (reminder?.enabled == true) "Disable" else "Enable") },
                            onClick = { menuOpen = false; if (reminder?.enabled == true) confirmDisable = true else vm.setEnabled(true) },
                        )
                        DropdownMenuItem(text = { Text("Delete entire series…") }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                }
            }
        },
    ) { padding ->
        val r = reminder
        if (r == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState("Reminder not found", "It may have been deleted.", action = { Button(onClick = { nav.up() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } })
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = ListSpacing) {
            item {
                SectionCard {
                    Text(r.title, style = MaterialTheme.typography.headlineSmall)
                    if (r.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(r.notes, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(Fmt.scheduleSummary(r.schedule), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Fmt.earlyOffsets(r.schedule)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    PriorityMenuRow(r.priority, onChange = { vm.setPriority(it) })
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.enabled) Icons.Filled.Alarm else Icons.Filled.AlarmOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(if (r.enabled) "On" else "Off", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = { if (it) vm.setEnabled(true) else confirmDisable = true },
                            modifier = Modifier.semantics { contentDescription = if (r.enabled) "Reminder on" else "Reminder off" },
                        )
                    }
                }
            }

            if (tasks.isNotEmpty()) item {
                SectionCard {
                    Text("Linked tasks", style = MaterialTheme.typography.titleMedium)
                    Text("Dismissing this reminder never completes a task.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    tasks.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { nav.navigate(Routes.taskDetail(t.id)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (t.completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, contentDescription = if (t.completed) "Completed" else "Open", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("Open task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("Next occurrences", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (occurrences.isEmpty()) item {
                Text(
                    if (r.enabled) "No future occurrences." else "This reminder is off. Turn it on to see upcoming occurrences.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            items(occurrences, key = { it.key.value }) { o ->
                OccurrenceRow(o, r, vm, onEditFuture = { editFuture = o })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmDisable) {
        ConfirmDialog(
            title = "Disable this reminder?",
            body = "It will stop ringing. Nothing is deleted; you can turn it back on at any time.",
            confirmLabel = "Disable",
            destructive = false,
            onConfirm = { confirmDisable = false; vm.setEnabled(false) },
            onDismiss = { confirmDisable = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete the entire series?",
            body = "This deletes the reminder with every past and future occurrence. Linked tasks are kept.",
            confirmLabel = "Delete the entire series",
            onConfirm = { confirmDelete = false; vm.deleteSeries { nav.up() } },
            onDismiss = { confirmDelete = false },
        )
    }
    val editing = editFuture
    val current = reminder
    if (editing != null && current != null) {
        EditFutureSheet(
            reminder = current,
            from = editing,
            vm = vm,
            onDismiss = { editFuture = null },
            onSaved = { newId -> editFuture = null; nav.navigate(Routes.reminderDetail(newId)) { popUpTo(Routes.REMINDERS) } },
        )
    }
}

@Composable
private fun OccurrenceRow(o: Occurrence, r: Reminder, vm: ReminderDetailViewModel, onEditFuture: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var moveDate: LocalDate? by remember { mutableStateOf(null) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val moved = o.key in r.schedule.overrides

    SectionCard {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.occurrence(o, r.schedule), style = MaterialTheme.typography.bodyLarge)
                if (moved) Text("Moved from ${Fmt.dateTime(com.norypt.haven.recurrence.OccurrenceKeys.toLocal(o.key))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SmallIconButton(onClick = { menuOpen = true }, contentDescription = "Options for ${Fmt.relative(o.instant)}", icon = Icons.Filled.MoreVert)
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                QuickMove.entries.forEach { m ->
                    DropdownMenuItem(text = { Text(m.label) }, onClick = { menuOpen = false; vm.quickMove(o, m) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Move to a chosen date and time…") }, onClick = { menuOpen = false; pickDate = true })
                DropdownMenuItem(text = { Text("Delete this occurrence…") }, onClick = { menuOpen = false; confirmSkip = true })
                DropdownMenuItem(text = { Text("Edit this and future") }, onClick = { menuOpen = false; onEditFuture() })
                DropdownMenuItem(text = { Text("Delete this and future…") }, onClick = { menuOpen = false; confirmEnd = true })
            }
        }
    }

    if (pickDate) {
        HavenDatePickerDialog(o.nominalLocal.toLocalDate(), onPick = { moveDate = it; pickDate = false }, onDismiss = { pickDate = false })
    }
    val d = moveDate
    if (d != null) {
        HavenTimePickerDialog(o.nominalLocal.toLocalTime(), onPick = { t: LocalTime -> vm.moveOccurrence(o.key, d.atTime(t)); moveDate = null }, onDismiss = { moveDate = null })
    }
    if (confirmSkip) {
        ConfirmDialog(
            title = "Delete this occurrence?",
            body = "Only ${Fmt.relative(o.instant)} is removed. The rest of the series is unchanged.",
            confirmLabel = "Delete this occurrence",
            onConfirm = { confirmSkip = false; vm.deleteOccurrence(o.key) },
            onDismiss = { confirmSkip = false },
        )
    }
    if (confirmEnd) {
        ConfirmDialog(
            title = "Delete this and future occurrences?",
            body = "The series ends before ${Fmt.relative(o.instant)}. Earlier occurrences are kept.",
            confirmLabel = "Delete this and future occurrences",
            onConfirm = { confirmEnd = false; vm.deleteThisAndFuture(o.key) },
            onDismiss = { confirmEnd = false },
        )
    }
}

/** Priority shown with icon + label, changed through a small menu. */
@Composable
private fun PriorityMenuRow(value: Priority, onChange: (Priority) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = priorityColor(value), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text("Priority", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Priority: ${priorityLabel(value)}. Tap to change" }) {
                Text(priorityLabel(value))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Priority.entries.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(priorityLabel(p)) },
                        leadingIcon = { Icon(Icons.Filled.Flag, contentDescription = null, tint = priorityColor(p)) },
                        trailingIcon = if (p == value) { { Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", modifier = Modifier.size(18.dp)) } } else null,
                        onClick = { open = false; if (p != value) onChange(p) },
                    )
                }
            }
        }
    }
}

/** "Edit this and future": the series is split and the tail becomes a new reminder with these values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditFutureSheet(reminder: Reminder, from: Occurrence, vm: ReminderDetailViewModel, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tail: Schedule? = remember(reminder, from.key) { vm.tailScheduleFrom(from.key) }
    val draft = remember(tail) { ScheduleDraft(tail ?: reminder.schedule.copy(startDate = from.nominalLocal.toLocalDate().toString())) }
    var title by remember { mutableStateOf(reminder.title) }
    var notes by remember { mutableStateOf(reminder.notes) }
    var titleError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val defaultMinute = vm.defaultMinuteOfDay
    val schedule = draft.toSchedule(defaultMinute)
    val preview = remember(schedule) { schedule?.let { vm.preview(it) } ?: emptyList() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("Edit this and future occurrences", style = MaterialTheme.typography.titleLarge)
            Text(
                "Occurrences before ${Fmt.relative(from.instant)} stay as they are. From here on the reminder uses these values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it; if (it.isNotBlank()) titleError = false },
                label = { Text("Title") },
                singleLine = true,
                isError = titleError,
                supportingText = if (titleError) { { Text("A title is required.") } } else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            ScheduleEditor(draft, defaultMinute, preview, schedule)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (title.isBlank()) { titleError = true; return@Button }
                        val s = schedule ?: return@Button
                        saving = true
                        vm.editThisAndFuture(from.key, title, notes, s, onSaved)
                    },
                    enabled = !saving && schedule != null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("Save") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
