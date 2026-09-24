package com.norypt.haven.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.PriorityStripe
import com.norypt.haven.ui.theme.LocalHavenColors
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up
import java.time.LocalDateTime

internal enum class TaskSort(val label: String) { MANUAL("Manual"), DUE("Due date"), PRIORITY("Priority"), TITLE("Title"), CREATED("Created") }
internal enum class TaskFilter(val label: String) { OPEN("Open"), COMPLETED("Completed"), STARRED("Starred"), ALL("All") }

/** Applies the filter, an optional text query (title/notes) and the sort. */
internal fun List<TaskEntity>.sortedAndFiltered(sort: TaskSort, filter: TaskFilter, query: String = ""): List<TaskEntity> {
    val filtered = when (filter) {
        TaskFilter.OPEN -> filter { !it.completed }
        TaskFilter.COMPLETED -> filter { it.completed }
        TaskFilter.STARRED -> filter { it.starred }
        TaskFilter.ALL -> this
    }.let { list -> if (query.isBlank()) list else list.filter { it.matches(query) } }
    return when (sort) {
        TaskSort.MANUAL -> filtered.sortedWith(compareBy<TaskEntity> { it.position }.thenBy { it.createdAt })
        TaskSort.DUE -> filtered.sortedWith(compareBy<TaskEntity, LocalDateTime?>(nullsLast()) { it.due() }.thenBy { it.position })
        // HIGH first, NONE last; ties by due date (soonest first, none last), then manual order.
        TaskSort.PRIORITY -> filtered.sortedWith(
            compareByDescending<TaskEntity> { it.priority }.thenBy<TaskEntity, LocalDateTime?>(nullsLast()) { it.due() }.thenBy { it.position },
        )
        TaskSort.TITLE -> filtered.sortedWith(compareBy<TaskEntity, String>(String.CASE_INSENSITIVE_ORDER) { it.title }.thenBy { it.position })
        TaskSort.CREATED -> filtered.sortedWith(compareByDescending<TaskEntity> { it.createdAt }.thenBy { it.position })
    }
}

private sealed interface ListDialog {
    data class MoveSelected(val ids: Set<String>) : ListDialog
    data class DeleteSelected(val ids: Set<String>) : ListDialog
    data class DeleteCompleted(val count: Int) : ListDialog
}

/** One task list: inline add, sort/filter, multi-select via long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(nav: NavHostController, listId: String) {
    val container = LocalAppContainer.current
    val vm: TaskListViewModel = viewModel(key = "task-list:$listId") { TaskListViewModel(container, listId) }
    val list by vm.list.collectAsState()
    val lists by vm.lists.collectAsState()
    val items by vm.items.collectAsState()

    // View preferences and selection are plain remember state: nothing here is persisted.
    var sort by remember { mutableStateOf(TaskSort.MANUAL) }
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var newTitle by remember { mutableStateOf("") }
    // Search text is plain remember state: it is never saved or logged.
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ListDialog?>(null) }

    val selectionMode = selected.isNotEmpty()
    BackHandler(enabled = selectionMode) { selected = emptySet() }
    BackHandler(enabled = !selectionMode && searchOpen) { searchOpen = false; query = "" }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    val all = items.orEmpty()
    val completedCount = all.count { it.completed }
    val activeQuery = if (searchOpen) query else ""
    val visible = remember(all, sort, filter, activeQuery) { all.sortedAndFiltered(sort, filter, activeQuery) }
    // Selection only ever refers to tasks that still exist.
    val liveSelected = remember(selected, all) { selected.filterTo(mutableSetOf()) { id -> all.any { it.id == id } } }
    val listName = list?.name ?: "List"

    fun submitNew() {
        val t = newTitle.trim()
        if (t.isNotEmpty()) { vm.createTask(t); newTitle = "" }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${liveSelected.size} selected", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = { dialog = ListDialog.MoveSelected(liveSelected) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to list")
                        }
                        IconButton(onClick = { dialog = ListDialog.DeleteSelected(liveSelected) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else {
                HavenTopBar(listName, onBack = { nav.up() }, actions = {
                    IconButton(
                        onClick = { if (searchOpen) { searchOpen = false; query = "" } else searchOpen = true },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searchOpen) "Close search" else "Search tasks") }
                    Box {
                        IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort: ${sort.label}") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            TaskSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = "Current") else Spacer(Modifier.size(24.dp)) },
                                    onClick = { sort = option; sortMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { filterMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.FilterList, contentDescription = "Filter: ${filter.label}") }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            TaskFilter.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = { if (option == filter) Icon(Icons.Filled.Check, contentDescription = "Current") else Spacer(Modifier.size(24.dp)) },
                                    onClick = { filter = option; filterMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete completed in this list") },
                                enabled = completedCount > 0,
                                onClick = { moreMenu = false; dialog = ListDialog.DeleteCompleted(completedCount) },
                            )
                        }
                    }
                })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search in this list") },
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
            }
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Add a task") },
                singleLine = true,
                enabled = !selectionMode,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitNew() }),
                trailingIcon = {
                    IconButton(onClick = { submitNew() }, enabled = newTitle.isNotBlank() && !selectionMode, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Add, contentDescription = "Add task")
                    }
                },
            )
            Text(
                "Showing: ${filter.label} · Sorted by: ${sort.label}" +
                    (if (activeQuery.isNotBlank()) " · ${visible.size} matching" else "") +
                    (if (selectionMode) " · Long-press or tap to select" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when {
                items == null -> Box(Modifier.fillMaxSize())
                visible.isEmpty() && activeQuery.isNotBlank() -> EmptyState(
                    title = "No matching tasks",
                    body = "Nothing in '$listName' matches that text with the current filter.",
                )
                visible.isEmpty() -> EmptyState(
                    title = when (filter) {
                        TaskFilter.OPEN -> if (all.isEmpty()) "No tasks yet" else "No open tasks"
                        TaskFilter.COMPLETED -> "No completed tasks"
                        TaskFilter.STARRED -> "No starred tasks"
                        TaskFilter.ALL -> "No tasks yet"
                    },
                    body = if (all.isEmpty()) "Type above to add the first task to '$listName'." else "Change the filter to see other tasks in this list.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { task ->
                        TaskRow(
                            task = task,
                            selectionMode = selectionMode,
                            selected = task.id in liveSelected,
                            onToggleComplete = { vm.setCompleted(task.id, it) },
                            onOpen = { nav.navigate(Routes.taskDetail(task.id)) },
                            onToggleSelect = { selected = if (task.id in selected) selected - task.id else selected + task.id },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ListDialog.MoveSelected -> ChooserDialog(
            title = "Move ${taskCount(d.ids.size)} to",
            options = lists.filter { it.id != listId },
            label = { it.name },
            emptyText = "There is no other list. Create one from the Tasks overview first.",
            confirmLabel = "Move",
            onChoose = { target -> vm.move(d.ids, target.id); selected = emptySet(); dialog = null },
            onDismiss = { dialog = null },
        )
        is ListDialog.DeleteSelected -> ConfirmDialog(
            title = if (d.ids.size == 1) "Delete 1 selected task" else "Delete ${d.ids.size} selected tasks",
            body = "The selected ${if (d.ids.size == 1) "task is" else "tasks are"} removed from '$listName' permanently. Linked reminders are kept.",
            confirmLabel = "Delete",
            onConfirm = { vm.deleteTasks(d.ids); selected = emptySet(); dialog = null },
            onDismiss = { dialog = null },
        )
        is ListDialog.DeleteCompleted -> ConfirmDialog(
            title = "Delete completed tasks in '$listName'",
            body = "${taskCount(d.count)} marked as completed will be removed from this list. Open tasks and reminders are kept.",
            confirmLabel = "Delete completed",
            onConfirm = { vm.deleteCompletedHere(); dialog = null },
            onDismiss = { dialog = null },
        )
    }
}

/**
 * Completion is shown three ways (checkbox, strikethrough, "Completed" text); overdue is a word,
 * not only a colour. Long press enters selection mode; in that mode the leading control selects.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(
    task: TaskEntity,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleComplete: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = { if (selectionMode) onToggleSelect() else onOpen() }, onLongClick = onToggleSelect)
            .heightIn(min = 56.dp)
            .height(IntrinsicSize.Min)
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (selected) "Selected, tap to deselect" else "Not selected, tap to select" },
            )
        } else {
            Checkbox(
                checked = task.completed,
                onCheckedChange = onToggleComplete,
                modifier = Modifier.size(48.dp).semantics { contentDescription = if (task.completed) "Completed, tap to mark incomplete" else "Not completed, tap to mark complete" },
            )
        }
        TaskRowContent(task, modifier = Modifier.weight(1f).padding(start = 4.dp))
    }
}

/**
 * Body of a task row shared by list rows and search results: priority stripe, title, meta line
 * (list name, completion, due/overdue), priority chip, star and reminder icons. The caller sets
 * `Modifier.height(IntrinsicSize.Min)` on the enclosing row so the stripe spans the row.
 */
@Composable
internal fun TaskRowContent(task: TaskEntity, modifier: Modifier = Modifier, listName: String? = null) {
    val due = task.due()
    val overdue = task.isOverdue()
    val priority = task.priorityEnum()
    val meta = buildList {
        if (listName != null) add(listName)
        if (task.completed) add("Completed")
        if (due != null) add("Due ${formatDateTime(due)}" + if (overdue) " · Overdue" else "")
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (priority != Priority.NONE) {
            PriorityStripe(priority)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty() || priority != Priority.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (priority != Priority.NONE) {
                        PriorityChip(priority)
                        if (meta.isNotEmpty()) Spacer(Modifier.width(8.dp))
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            meta.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (task.starred) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Star, contentDescription = "Starred", tint = LocalHavenColors.current.warning, modifier = Modifier.size(20.dp))
        }
        if (task.reminderId != null) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Alarm, contentDescription = "Reminder linked", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
