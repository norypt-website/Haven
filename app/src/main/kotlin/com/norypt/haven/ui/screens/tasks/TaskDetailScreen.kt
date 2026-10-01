package com.norypt.haven.ui.screens.tasks

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.data.TaskRepository
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.HavenTopBar
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.norypt.haven.ui.components.CardDivider
import com.norypt.haven.ui.components.EmptyRow
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.FieldRow
import com.norypt.haven.ui.components.FieldValue
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.ItemRow
import com.norypt.haven.ui.components.PriorityPill
import com.norypt.haven.ui.components.RowChevron
import com.norypt.haven.ui.components.RowTextInset
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.StarredPill
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.priorityTileBrush
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.screens.reminders.ReminderTile
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.components.priorityColor
import com.norypt.haven.ui.components.priorityLabel
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up

private sealed interface DetailDialog {
    data object Delete : DetailDialog
    data object LinkReminder : DetailDialog
}

/** Task detail. Completing, deleting and reminder linking are separate, clearly labelled actions. */
@Composable
fun TaskDetailScreen(nav: NavHostController, id: String) {
    val container = LocalAppContainer.current
    val vm: TaskDetailViewModel = viewModel(key = "task-detail:$id") { TaskDetailViewModel(container, id) }
    val ui by vm.ui.collectAsState()
    val allReminders by vm.allReminders.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var priorityMenu by remember { mutableStateOf(false) }
    var followUpMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DetailDialog?>(null) }
    var leaving by remember { mutableStateOf(false) }

    // The task disappeared (deleted here or elsewhere): leave once, without crashing.
    val gone = ui.loaded && ui.task == null
    LaunchedEffect(gone) { if (gone && !leaving) { leaving = true; nav.up() } }

    Scaffold(
        topBar = {
            // The header below names the task, so the bar carries only the actions.
            HavenTopBar("", onBack = { nav.up() }, actions = {
                val t = ui.task
                if (t != null) {
                    StarButton(starred = t.starred, onToggle = { vm.setStarred(!t.starred) })
                    IconButton(onClick = { nav.navigate(Routes.taskEdit(id = id)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Edit, contentDescription = "Edit task") }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Delete task") }, onClick = { menuOpen = false; dialog = DetailDialog.Delete })
                        }
                    }
                }
            })
        },
    ) { padding ->
        val task = ui.task
        if (task == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val due = task.due()
        val overdue = task.isOverdue()
        val priority = task.priorityEnum()
        // Only trust the linked block once it refers to the reminder the task currently points at.
        val linked = ui.linked?.takeIf { it.reminderId == task.reminderId }
        val dueText = when {
            due == null -> "No due date"
            overdue -> "${formatDateTime(due)} · Overdue"
            else -> formatDateTime(due)
        }

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp)) {
            // ---- Header ----
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                IconTile(
                    if (task.completed) Icons.Filled.CheckCircle else Icons.Filled.TaskAlt,
                    if (task.completed) tileBrush(EntryColor.GREEN.color) else priorityTileBrush(priority),
                    size = 76.dp,
                    corner = 22.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    task.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        task.completed -> "Completed" + (task.completedAt?.let { " on ${formatEpochMillis(it)}" } ?: "")
                        due == null -> "No due date"
                        overdue -> "Overdue · was due ${formatDateTime(due)}"
                        else -> "Due ${formatDateTime(due)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue && !task.completed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.starred) StarredPill()
                    PriorityPill(priority)
                    TagPill(
                        ui.listName ?: "Unknown list",
                        large = true,
                        icon = { Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.setCompleted(!task.completed) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = if (task.completed) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.buttonColors(),
                border = if (task.completed) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
            ) {
                Icon(if (task.completed) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (task.completed) "Mark incomplete" else "Mark complete",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }

            // ---- Details ----
            SectionLabel("Details")
            GroupCard {
                FieldRow(Icons.Filled.Checklist, "List", value = { FieldValue(ui.listName ?: "Unknown list") })
                CardDivider()
                FieldRow(Icons.Filled.Event, "Due", value = {
                    Text(dueText, style = MaterialTheme.typography.bodyLarge, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                })
                CardDivider()
                FieldRow(Icons.Filled.Flag, "Priority", value = { FieldValue(priorityLabel(priority)) }, iconTint = priorityColor(priority)) {
                    Box {
                        TextButton(onClick = { priorityMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
                        DropdownMenu(expanded = priorityMenu, onDismissRequest = { priorityMenu = false }) {
                            Priority.entries.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(priorityLabel(p)) },
                                    leadingIcon = {
                                        if (p == priority) Icon(Icons.Filled.Check, contentDescription = "Current")
                                        else Icon(Icons.Filled.Flag, contentDescription = null, tint = priorityColor(p))
                                    },
                                    onClick = { priorityMenu = false; if (p != priority) vm.setPriority(p) },
                                )
                            }
                        }
                    }
                }
            }

            // ---- Follow-up ----
            SectionLabel("If still not done")
            GroupCard {
                FieldRow(Icons.Filled.NotificationsActive, "Follow-up", value = { FieldValue(followUpDescription(task.followUpMinutes)) }) {
                    Box {
                        TextButton(onClick = { followUpMenu = true }, enabled = due != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
                        DropdownMenu(expanded = followUpMenu, onDismissRequest = { followUpMenu = false }) {
                            val choices: List<Int?> = listOf<Int?>(null) + TaskRepository.FOLLOW_UP_CHOICES.map { it.first }
                            choices.forEach { minutes ->
                                val current = (task.followUpMinutes ?: 0) == (minutes ?: 0)
                                DropdownMenuItem(
                                    text = { Text(followUpChoiceLabel(minutes)) },
                                    leadingIcon = { if (current) Icon(Icons.Filled.Check, contentDescription = "Current") else Spacer(Modifier.size(24.dp)) },
                                    onClick = { followUpMenu = false; if (!current) vm.setFollowUp(minutes) },
                                )
                            }
                        }
                    }
                }
                Text(
                    if (due == null) "Set a due date to use a follow-up."
                    else "Haven rings again at the chosen time after the due time if the task is still incomplete. Completing the task cancels it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }

            if (task.notes.isNotBlank()) {
                SectionLabel("Notes")
                GroupCard { Text(task.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) }
            }

            // ---- Reminder ----
            SectionLabel("Reminder")
            GroupCard {
                when {
                    task.reminderId == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("No reminder is linked to this task.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { nav.navigate(Routes.reminderEdit(taskId = id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Add a reminder for this task")
                        }
                        OutlinedButton(onClick = { dialog = DetailDialog.LinkReminder }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Link existing reminder…")
                        }
                    }
                    linked == null -> EmptyRow("Loading reminder…", Icons.Filled.Alarm)
                    linked.reminder == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The linked reminder no longer exists.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { vm.unlink() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Unlink") }
                    }
                    else -> {
                        val r = linked.reminder
                        ItemRow(
                            title = r.title,
                            detail = when {
                                linked.next != null -> "Next: ${formatOccurrence(linked.next)}"
                                !r.enabled -> "Reminder is turned off"
                                else -> "No upcoming occurrence"
                            },
                            onClick = { nav.navigate(Routes.reminderDetail(r.id)) },
                            leading = { ReminderTile(r.priority, r.enabled) },
                        ) { RowChevron() }
                        CardDivider(RowTextInset)
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Unlinking only removes the connection; the reminder itself stays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.unlink() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlink") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(
                onClick = { dialog = DetailDialog.Delete },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete task") }
        }
    }

    val task = ui.task
    when (val d = dialog) {
        null -> Unit
        DetailDialog.Delete -> if (task != null) ConfirmDialog(
            title = "Delete this task",
            body = "'${task.title}' is removed from '${ui.listName ?: "its list"}' permanently." + if (task.reminderId != null) " The linked reminder is kept." else "",
            confirmLabel = "Delete",
            onConfirm = { dialog = null; vm.delete() },
            onDismiss = { dialog = null },
        ) else dialog = null
        DetailDialog.LinkReminder -> ChooserDialog(
            title = "Link existing reminder",
            options = allReminders,
            label = { it.title },
            emptyText = "There are no reminders yet. Use 'Add a reminder for this task' instead.",
            confirmLabel = "Link",
            onChoose = { r -> vm.link(r.id); dialog = null },
            onDismiss = { dialog = null },
        )
        else -> Unit
    }
}
