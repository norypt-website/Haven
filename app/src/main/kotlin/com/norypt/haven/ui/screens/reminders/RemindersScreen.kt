package com.norypt.haven.ui.screens.reminders

import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.StarIcon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import com.norypt.haven.ui.components.GroupPosition
import com.norypt.haven.ui.components.ItemRow
import com.norypt.haven.ui.components.RowTextInset
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.SelectedTile
import com.norypt.haven.ui.components.cardSegment
import com.norypt.haven.ui.navigation.Routes

@Composable
fun RemindersScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: ReminderListViewModel = viewModel { ReminderListViewModel(container) }
    val rows by vm.rows.collectAsState()
    val selected = vm.selected
    val selecting = selected.isNotEmpty()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSelected by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    // Rows arrive starred first; the list shows them as their own section.
    val (starred, others) = remember(rows) { rows.partition { it.reminder.starred } }

    Scaffold(
        topBar = {
            if (selecting) {
                HavenTopBar("${selected.size} selected", onBack = { vm.clearSelection() }) {
                    SmallIconButton(onClick = { confirmSelected = true }, contentDescription = "Delete selected", icon = Icons.Filled.Delete)
                    SmallIconButton(onClick = { vm.clearSelection() }, contentDescription = "Cancel selection", icon = Icons.Filled.Close)
                }
            } else {
                HavenTopBar("Reminders") {
                    SmallIconButton(onClick = { menuOpen = true }, contentDescription = "More options", icon = Icons.Filled.MoreVert)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Delete all reminders…") }, enabled = rows.isNotEmpty(), onClick = { menuOpen = false; confirmAll = true })
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selecting) ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.reminderEdit()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New reminder") },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No reminders yet",
                    body = "Reminders ring as alarms at the times you choose, even while Haven is locked. Their titles stay inside the vault.",
                    action = { Button(onClick = { nav.navigate(Routes.reminderEdit()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("New reminder") } },
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 96.dp)) {
                fun group(list: List<ReminderListViewModel.Row>) = itemsIndexed(list, key = { _, row -> row.reminder.id }) { i, row ->
                    val id = row.reminder.id
                    ReminderRow(
                        row = row,
                        position = GroupPosition.of(i, list.size),
                        selecting = selecting,
                        isSelected = id in selected,
                        onClick = { if (selecting) vm.toggleSelected(id) else nav.navigate(Routes.reminderDetail(id)) },
                        onLongClick = { vm.toggleSelected(id) },
                        onEnabledChange = { vm.setEnabled(id, it) },
                        modifier = Modifier.animateItem(),
                    )
                }
                // Headers keep position-based keys so a newly starred section is not pushed out of view.
                if (starred.isNotEmpty()) {
                    item { SectionLabel("Starred", count = starred.size, leading = { StarIcon(size = 15.dp, contentDescription = null) }) }
                    group(starred)
                }
                if (others.isNotEmpty()) {
                    item { SectionLabel(if (starred.isEmpty()) "Reminders" else "Other reminders", count = others.size) }
                    group(others)
                }
            }
        }
    }

    if (confirmSelected) {
        val n = selected.size
        ConfirmDialog(
            title = "Delete $n selected ${if (n == 1) "reminder" else "reminders"}?",
            body = "This removes ${if (n == 1) "this reminder and all its occurrences" else "these $n reminders and all their occurrences"}. Linked tasks are kept.",
            confirmLabel = "Delete $n selected ${if (n == 1) "reminder" else "reminders"}",
            onConfirm = { confirmSelected = false; vm.deleteSelected() },
            onDismiss = { confirmSelected = false },
        )
    }
    if (confirmAll) {
        DeleteAllRemindersDialog(count = rows.size, onConfirm = { confirmAll = false; vm.deleteAll() }, onDismiss = { confirmAll = false })
    }
}

@Composable
private fun ReminderRow(
    row: ReminderListViewModel.Row,
    position: GroupPosition,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val r = row.reminder
    val scheme = MaterialTheme.colorScheme
    ItemRow(
        title = r.title,
        modifier = modifier
            .cardSegment(position, fill = if (isSelected) scheme.primaryContainer else scheme.surface, line = scheme.outlineVariant, dividerInset = RowTextInset)
            .semantics { if (selecting) stateDescription = if (isSelected) "Selected" else "Not selected" },
        detail = when {
            !r.enabled -> "Off"
            row.next != null -> Fmt.occurrence(row.next, r.schedule)
            else -> "No future occurrences"
        },
        detailColor = if (r.enabled && row.next != null) scheme.onSurface else Color.Unspecified,
        starred = r.starred,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = { if (selecting && isSelected) SelectedTile(contentDescription = null) else ReminderTile(r.priority, r.enabled) },
        below = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Fmt.repeatShort(r.schedule), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                PriorityChip(r.priority)
            }
        },
    ) {
        if (!selecting) {
            Switch(
                checked = r.enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = if (r.enabled) "Reminder on" else "Reminder off" },
            )
        }
    }
}

/** "Delete ALL reminders" needs an explicit acknowledgement before the confirm button enables. */
@Composable
fun DeleteAllRemindersDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ALL reminders?") },
        text = {
            Column {
                Text("This removes every reminder ($count) and all their occurrences. Tasks linked to them are kept but lose their reminder. This cannot be undone.")
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = understood, onValueChange = { understood = it }, role = androidx.compose.ui.semantics.Role.Checkbox),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("I understand this removes every reminder", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = understood,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Delete ALL reminders") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
