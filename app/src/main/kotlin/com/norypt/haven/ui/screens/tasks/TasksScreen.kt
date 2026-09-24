package com.norypt.haven.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.storage.content.TaskListEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ListSpacing
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.navigation.Routes
import kotlinx.coroutines.delay

private sealed interface OverviewDialog {
    data object NewList : OverviewDialog
    data class Rename(val list: TaskListEntity) : OverviewDialog
    data class DeleteList(val summary: ListSummary) : OverviewDialog
    data class DeleteCompletedEverywhere(val count: Int) : OverviewDialog
    data class DeleteAll(val count: Int) : OverviewDialog
}

/** Overview of every task list with open/total counts. */
@Composable
fun TasksScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    val vm: TasksOverviewViewModel = viewModel(key = "tasks-overview") { TasksOverviewViewModel(container) }
    val summaries by vm.summaries.collectAsState()
    val results by vm.results.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<OverviewDialog?>(null) }
    // Search text is plain remember state: never saved, never logged. Typing is debounced before it reaches the repository.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen, query) {
        if (!searchOpen) { vm.setQuery(""); return@LaunchedEffect }
        delay(200)
        vm.setQuery(query)
    }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    val totalTasks = summaries?.sumOf { it.total } ?: 0
    val completedTasks = summaries?.sumOf { it.completed } ?: 0
    val firstList = summaries?.firstOrNull()?.list

    Scaffold(
        topBar = {
            HavenTopBar("Tasks", actions = {
                IconButton(
                    onClick = { if (searchOpen) { searchOpen = false; query = "" } else searchOpen = true },
                    modifier = Modifier.size(48.dp),
                ) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searchOpen) "Close search" else "Search tasks") }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete completed tasks (all lists)") },
                            enabled = completedTasks > 0,
                            onClick = { menuOpen = false; dialog = OverviewDialog.DeleteCompletedEverywhere(completedTasks) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete ALL tasks…") },
                            enabled = totalTasks > 0,
                            onClick = { menuOpen = false; dialog = OverviewDialog.DeleteAll(totalTasks) },
                        )
                    }
                }
            })
        },
        floatingActionButton = {
            if (firstList != null && !searchOpen) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.taskEdit(listId = firstList.id)) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New task") },
                )
            }
        },
    ) { padding ->
        val lists = summaries
        when {
            searchOpen -> Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search all lists") },
                    placeholder = { Text("Title or notes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(searchFocus),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                val hits = results
                when {
                    query.isBlank() -> Text(
                        "Type to search titles and notes in every list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    hits == null -> Box(Modifier.fillMaxSize())
                    hits.isEmpty() -> EmptyState(title = "No matching tasks", body = "No task title or notes contain that text.")
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text(
                                "${taskCount(hits.size)} found",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(hits, key = { it.task.id }) { hit ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { nav.navigate(Routes.taskDetail(hit.task.id)) }
                                    .heightIn(min = 56.dp)
                                    .height(IntrinsicSize.Min)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) { TaskRowContent(hit.task, modifier = Modifier.weight(1f), listName = hit.listName) }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            lists == null -> Box(Modifier.fillMaxSize().padding(padding))
            lists.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "No lists yet",
                    body = "Tasks live in lists. Create one to start adding tasks.",
                    action = { Button(onClick = { dialog = OverviewDialog.NewList }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Create a list") } },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = ScreenPadding, verticalArrangement = ListSpacing) {
                item {
                    OutlinedButton(onClick = { dialog = OverviewDialog.NewList }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New list")
                    }
                }
                items(lists, key = { it.list.id }) { summary ->
                    ListSummaryRow(
                        summary = summary,
                        onOpen = { nav.navigate(Routes.taskList(summary.list.id)) },
                        onRename = { dialog = OverviewDialog.Rename(summary.list) },
                        onDelete = { dialog = OverviewDialog.DeleteList(summary) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) } // keep the last row clear of the FAB
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        OverviewDialog.NewList -> NameDialog(
            title = "New list",
            label = "List name",
            confirmLabel = "Create",
            onConfirm = { vm.createList(it); dialog = null },
            onDismiss = { dialog = null },
        )
        is OverviewDialog.Rename -> NameDialog(
            title = "Rename list",
            label = "List name",
            initial = d.list.name,
            confirmLabel = "Rename",
            onConfirm = { vm.renameList(d.list.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is OverviewDialog.DeleteList -> ConfirmDialog(
            title = if (d.summary.total == 0) "Delete list '${d.summary.list.name}'" else "Delete list '${d.summary.list.name}' and its ${taskCount(d.summary.total)}",
            body = if (d.summary.total == 0) "This list has no tasks. It is removed permanently."
            else "The list and every task in it (${d.summary.open} open, ${d.summary.completed} completed) are removed permanently. Reminders linked to those tasks are kept.",
            confirmLabel = "Delete list",
            onConfirm = { vm.deleteList(d.summary.list.id); dialog = null },
            onDismiss = { dialog = null },
        )
        is OverviewDialog.DeleteCompletedEverywhere -> ConfirmDialog(
            title = "Delete completed tasks in every list",
            body = "${taskCount(d.count)} marked as completed will be removed from all lists. Open tasks and reminders are kept.",
            confirmLabel = "Delete completed",
            onConfirm = { vm.deleteCompletedEverywhere(); dialog = null },
            onDismiss = { dialog = null },
        )
        is OverviewDialog.DeleteAll -> DeleteAllTasksDialog(
            count = d.count,
            onConfirm = { vm.deleteAllTasks(); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun ListSummaryRow(summary: ListSummary, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(summary.list.name, style = MaterialTheme.typography.titleMedium)
                // Overdue is named with the word "overdue" as well as the error colour.
                val errorColor = MaterialTheme.colorScheme.error
                Text(
                    buildAnnotatedString {
                        if (summary.total == 0) {
                            append("No tasks")
                        } else {
                            append("${summary.open} open · ${summary.total} total · ${summary.starred} starred")
                            if (summary.overdue > 0) {
                                append(" · ")
                                withStyle(SpanStyle(color = errorColor, fontWeight = FontWeight.Medium)) { append("${summary.overdue} overdue") }
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Options for list ${summary.list.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete list") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

/** "Delete ALL tasks" needs an explicit acknowledgement before the destructive button enables. */
@Composable
private fun DeleteAllTasksDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete ALL tasks in every list") },
        text = {
            Column {
                Text("All ${taskCount(count)} in every list will be removed permanently. The lists themselves and all reminders are kept.")
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("I understand this removes every task", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = understood,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete all tasks") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Single-line name entry (new list / rename). Text stays in plain `remember` state. */
@Composable
internal fun NameDialog(title: String, label: String, confirmLabel: String, initial: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name.trim()) }),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Radio-style single choice used for "Move to list", list picker and reminder picker. */
@Composable
internal fun <T> ChooserDialog(
    title: String,
    options: List<T>,
    label: (T) -> String,
    isSelected: (T) -> Boolean = { false },
    emptyText: String,
    confirmLabel: String = "Choose",
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(options.firstOrNull { isSelected(it) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (options.isEmpty()) {
                Text(emptyText)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(options) { option ->
                        val selected = picked == option
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = { picked = option }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label(option), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (options.isNotEmpty()) Button(onClick = { picked?.let(onChoose) }, enabled = picked != null) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (options.isEmpty()) "Close" else "Cancel") } },
    )
}
