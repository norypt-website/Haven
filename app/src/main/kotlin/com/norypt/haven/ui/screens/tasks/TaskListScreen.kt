package com.norypt.haven.ui.screens.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.norypt.haven.ui.components.ColorTile
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.GroupPosition
import com.norypt.haven.ui.components.ItemRow
import com.norypt.haven.ui.components.PillChip
import com.norypt.haven.ui.components.RoundCheck
import com.norypt.haven.ui.components.RowTextInset
import com.norypt.haven.ui.components.cardSegment
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.entryInitials
import com.norypt.haven.ui.components.priorityColor
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.theme.LocalHavenColors
import com.norypt.haven.ui.components.PriorityChip
import com.norypt.haven.ui.components.StarIcon
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
                    onValueChange = { query = it; selected = emptySet() },
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
                placeholder = { Text("Add a task") },
                singleLine = true,
                enabled = !selectionMode,
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "Add a task" },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitNew() }),
                trailingIcon = {
                    IconButton(onClick = { submitNew() }, enabled = newTitle.isNotBlank() && !selectionMode, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add task")
                    }
                },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(TaskFilter.ALL, TaskFilter.OPEN, TaskFilter.COMPLETED, TaskFilter.STARRED).forEach { option ->
                    PillChip(
                        selected = filter == option,
                        // The visible tasks change, so a selection made before is dropped (Delete must never reach hidden tasks).
                        onClick = { filter = option; selected = emptySet() },
                        label = option.label,
                        leading = if (option == TaskFilter.STARRED) { { StarIcon(size = 17.dp, contentDescription = null) } } else null,
                    )
                }
            }
            Text(
                "Sorted by: ${sort.label}" +
                    (if (activeQuery.isNotBlank()) " · ${visible.size} matching" else "") +
                    (if (selectionMode) " · Long-press or tap to select" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
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
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)) {
                    item { ListProgressCard(listName, open = all.size - completedCount, total = all.size) }
                    item { Spacer(Modifier.height(12.dp)) }
                    itemsIndexed(visible, key = { _, t -> t.id }) { i, task ->
                        TaskItemRow(
                            task = task,
                            position = GroupPosition.of(i, visible.size),
                            onOpen = { nav.navigate(Routes.taskDetail(task.id)) },
                            modifier = Modifier.animateItem(),
                            selectionMode = selectionMode,
                            selected = task.id in liveSelected,
                            onToggleComplete = { vm.setCompleted(task.id, it) },
                            onToggleSelect = { selected = if (task.id in selected) selected - task.id else selected + task.id },
                        )
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
 * One task as a row of a section card. Completion shows three ways (round check, strikethrough,
 * "Completed"); overdue is a word, not only a colour. Long press enters selection mode, where the
 * leading control selects instead. Without [onToggleComplete] (search results) the check only shows.
 */
@Composable
internal fun TaskItemRow(
    task: TaskEntity,
    position: GroupPosition,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    listName: String? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleComplete: ((Boolean) -> Unit)? = null,
    onToggleSelect: (() -> Unit)? = null,
) {
    val due = task.due()
    val overdue = task.isOverdue()
    val priority = task.priorityEnum()
    val scheme = MaterialTheme.colorScheme
    val ring = priorityColor(priority)
    val meta = buildList {
        if (listName != null) add(listName)
        if (task.completed) add("Completed")
        if (due != null) add("Due ${formatDateTime(due)}" + if (overdue) " · Overdue" else "")
    }
    ItemRow(
        title = task.title,
        modifier = modifier.cardSegment(position, fill = if (selected) scheme.primaryContainer else scheme.surface, line = scheme.outlineVariant, dividerInset = RowTextInset),
        detail = meta.joinToString(" · ").ifEmpty { null },
        detailColor = if (overdue) scheme.error else Color.Unspecified,
        starred = task.starred,
        done = task.completed,
        titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        onClick = { if (selectionMode && onToggleSelect != null) onToggleSelect() else onOpen() },
        onLongClick = onToggleSelect,
        leadingSize = 48.dp,
        leading = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    selectionMode && onToggleSelect != null -> Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = if (selected) "Selected, tap to deselect" else "Not selected, tap to select" },
                    )
                    onToggleComplete != null -> RoundCheck(
                        checked = task.completed,
                        onCheckedChange = onToggleComplete,
                        ring = ring,
                        description = if (task.completed) "Completed, tap to mark incomplete" else "Not completed, tap to mark complete",
                    )
                    else -> Icon(
                        if (task.completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (task.completed) "Completed" else "Open",
                        tint = if (task.completed) scheme.primary else ring,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        },
        below = if (priority != Priority.NONE) { { PriorityChip(priority) } } else null,
    ) {
        if (task.reminderId != null) {
            Icon(Icons.Filled.Alarm, contentDescription = "Reminder linked", tint = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp).size(20.dp))
        }
    }
}

/** The list's colour tile, how many tasks are open and a completion bar. */
@Composable
private fun ListProgressCard(name: String, open: Int, total: Int) {
    val colors = LocalHavenColors.current
    GroupCard {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ListTile(name, size = 48.dp, corner = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (open == 0) "All ${taskCount(total)} done" else "$open open of $total",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else (total - open).toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = if (open == 0) colors.success else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** A list's tile: its initials on the colour worked out from its name, like password entries. */
@Composable
internal fun ListTile(name: String, size: androidx.compose.ui.unit.Dp = 44.dp, corner: androidx.compose.ui.unit.Dp = 13.dp) {
    val initials = entryInitials(name)
    ColorTile(tileBrush(EntryColor.auto(name).color), size, corner) {
        if (initials.isEmpty()) {
            Icon(Icons.Filled.Checklist, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        } else {
            // Like password tiles: sized by the tile, not the font setting, so the letters always fit.
            val fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.36f).toSp() }
            Text(initials, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
