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
import androidx.compose.material.icons.filled.Star
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
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.components.priorityColor
import com.norypt.haven.ui.components.priorityLabel
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
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
            HavenTopBar("Task", onBack = { nav.up() }, actions = {
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
        val colors = LocalHavenColors.current
        // Only trust the linked block once it refers to the reminder the task currently points at.
        val linked = ui.linked?.takeIf { it.reminderId == task.reminderId }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
            )
            if (task.completed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Completed" + (task.completedAt?.let { " on ${formatEpochMillis(it)}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val priority = task.priorityEnum()
            if (task.starred || priority != Priority.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.starred) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, contentDescription = null, tint = colors.warning, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Starred", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    PriorityChip(priority)
                }
            }

            SectionCard {
                DetailRow("List", ui.listName ?: "Unknown list")
                Spacer(Modifier.height(8.dp))
                DetailRow(
                    label = "Due",
                    value = when {
                        due == null -> "No due date"
                        overdue -> "${formatDateTime(due)} · Overdue"
                        else -> formatDateTime(due)
                    },
                    emphasise = overdue,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { DetailRow("Priority", priorityLabel(priority)) }
                    Box {
                        OutlinedButton(onClick = { priorityMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Filled.Flag, contentDescription = null, tint = priorityColor(priority), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Change")
                        }
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

            SectionCard {
                Text("If still not done", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Follow-up: ${followUpDescription(task.followUpMinutes)}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        OutlinedButton(onClick = { followUpMenu = true }, enabled = due != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change") }
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
                Spacer(Modifier.height(4.dp))
                Text(
                    if (due == null) "Set a due date to use a follow-up."
                    else "Haven rings again at the chosen time after the due time if the task is still incomplete. Completing the task cancels it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { vm.setCompleted(!task.completed) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = if (task.completed) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.buttonColors(),
            ) { Text(if (task.completed) "Mark incomplete" else "Mark complete") }

            if (task.notes.isNotBlank()) {
                SectionCard {
                    Text("Notes", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(task.notes, style = MaterialTheme.typography.bodyMedium)
                }
            }

            SectionCard {
                Text("Reminder", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                when {
                    task.reminderId == null -> {
                        Text("No reminder is linked to this task.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { nav.navigate(Routes.reminderEdit(taskId = id)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Add a reminder for this task")
                        }
                        OutlinedButton(onClick = { dialog = DetailDialog.LinkReminder }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text("Link existing reminder…")
                        }
                    }
                    linked == null -> Text("Loading reminder…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    linked.reminder == null -> {
                        Text("The linked reminder no longer exists.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.unlink() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Unlink") }
                    }
                    else -> {
                        val r = linked.reminder
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                linked.next != null -> "Next: ${formatOccurrence(linked.next)}"
                                !r.enabled -> "Reminder is turned off"
                                else -> "No upcoming occurrence"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { nav.navigate(Routes.reminderDetail(r.id)) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Open reminder") }
                            TextButton(onClick = { vm.unlink() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlink") }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("Unlinking only removes the connection; the reminder itself stays.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            OutlinedButton(
                onClick = { dialog = DetailDialog.Delete },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete task") }
            Spacer(Modifier.height(16.dp))
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

@Composable
private fun DetailRow(label: String, value: String, emphasise: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = if (emphasise) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
