package com.norypt.haven.ui.screens.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.GroupPosition
import com.norypt.haven.ui.components.PillChip
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.SelectedTile
import com.norypt.haven.ui.components.cardSegment
import com.norypt.haven.ui.components.StarIcon
import com.norypt.haven.ui.navigation.Routes
import kotlinx.coroutines.launch

@Composable
fun PasswordsScreen(nav: NavHostController) {
    PasswordVaultGate(nav) { PasswordsContent(nav) }
}

private sealed interface ListDialog {
    data object None : ListDialog
    data object NewFolder : ListDialog
    data class RenameFolder(val folder: FolderEntity) : ListDialog
    data class DeleteFolder(val folder: FolderEntity) : ListDialog
    data object DeleteSelected : ListDialog
    data object MoveSelected : ListDialog
    data object MoveToNewFolder : ListDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordsContent(nav: NavHostController) {
    val container = LocalAppContainer.current
    val vm = rememberPasswordsListViewModel(container)
    val folders by vm.folders.collectAsState()
    val entries by vm.entries.collectAsState()
    val totals by vm.totals.collectAsState()
    val filter by vm.filter.collectAsState()
    val selected by vm.selected.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // The search text is plain composition state: never saved, gone when the screen goes.
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) { vm.setQuery(query) }
    // A generated password nobody consumed must not linger for a later edit.
    LaunchedEffect(Unit) { GeneratedPasswordHandoff.clear() }

    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<ListDialog>(ListDialog.None) }
    val selectionMode = selected.isNotEmpty()
    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    val folderNames = remember(folders) { folders.associate { it.id to it.name } }
    val currentFolder = (filter as? ListFilter.Folder)?.let { f -> folders.firstOrNull { it.id == f.id } }
    val sections = remember(entries) { sectionsOf(entries) }
    // Inside a folder every row is in that folder, so its tag would only repeat the chip.
    val showFolderTags = filter !is ListFilter.Folder

    Scaffold(
        topBar = {
            if (selectionMode) {
                val starAction = starTargetFor(entries.filter { it.id in selected })
                TopAppBar(
                    title = { Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = { vm.starSelected() }, modifier = Modifier.size(48.dp)) {
                            if (starAction) Icon(Icons.Outlined.StarBorder, contentDescription = "Star selected")
                            else StarIcon(size = 24.dp, contentDescription = "Remove star from selected")
                        }
                        IconButton(onClick = { dialog = ListDialog.MoveSelected }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Folder, contentDescription = "Move to folder") }
                        IconButton(onClick = { dialog = ListDialog.DeleteSelected }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                HavenTopBar("Passwords") {
                    IconButton(
                        onClick = {
                            // Leave the keeper screens first so the vault gate does not bounce to the keeper unlock screen.
                            nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true }; launchSingleTop = true }
                            scope.launch { container.session.lockPasswords() }
                        },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Lock, contentDescription = "Lock password keeper") }
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Password generator") },
                            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null) },
                            onClick = { menuOpen = false; nav.navigate(Routes.PASSWORD_GENERATOR) },
                        )
                        DropdownMenuItem(
                            text = { Text("New folder…") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = { menuOpen = false; dialog = ListDialog.NewFolder },
                        )
                        if (currentFolder != null) {
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Rename folder…") }, onClick = { menuOpen = false; dialog = ListDialog.RenameFolder(currentFolder) })
                            DropdownMenuItem(text = { Text("Delete folder…") }, onClick = { menuOpen = false; dialog = ListDialog.DeleteFolder(currentFolder) })
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { nav.navigate(Routes.passwordEdit()) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New entry") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchField(query = query, onQueryChange = { query = it })
            FilterRow(filter = filter, totals = totals, folders = folders, onPick = { vm.setFilter(it) })
            if (entries.isEmpty()) {
                when {
                    query.isNotBlank() -> EmptyState("Nothing matches", "No entry title, website or username contains that text.")
                    filter == ListFilter.Starred -> EmptyState("No starred passwords", "Open an entry and tap the star to keep it at the top.")
                    filter != ListFilter.All -> EmptyState("This folder is empty", "Long-press entries in the list to move them here.")
                    else -> EmptyState(
                        "No passwords yet",
                        "Entries are kept only in this vault on this device.",
                        action = { Button(onClick = { nav.navigate(Routes.passwordEdit()) }) { Text("New entry") } },
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 96.dp)) {
                    fun group(list: List<PasswordEntryEntity>) = entryGroup(
                        entries = list,
                        folderNames = if (showFolderTags) folderNames else emptyMap(),
                        selectionMode = selectionMode,
                        selected = selected,
                        onOpen = { nav.navigate(Routes.passwordDetail(it.id)) },
                        onToggle = { vm.toggleSelected(it.id) },
                    )
                    // Headers keep position-based keys on purpose: the list holds on to the key of its
                    // first visible item, so a fixed header key would keep "Other entries" in place and
                    // push a newly appearing Starred section out of view above it. Rows keep their ids.
                    if (sections.starred.isNotEmpty()) {
                        item {
                            SectionLabel(
                                "Starred",
                                count = sections.starred.size,
                                description = "Starred, ${entryCount(sections.starred.size)}",
                                leading = { StarIcon(size = 15.dp, contentDescription = null) },
                            )
                        }
                        group(sections.starred)
                    }
                    if (sections.others.isNotEmpty()) {
                        item {
                            val title = if (sections.starred.isEmpty()) "Entries" else "Other entries"
                            SectionLabel(title, count = sections.others.size, description = "$title, ${entryCount(sections.others.size)}")
                        }
                        group(sections.others)
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        ListDialog.None -> Unit
        ListDialog.NewFolder -> FolderNameDialog(
            title = "New folder", initial = "", confirmLabel = "Create",
            onConfirm = { name -> dialog = ListDialog.None; vm.createFolder(name) { vm.setFilter(ListFilter.Folder(it.id)) } },
            onDismiss = { dialog = ListDialog.None },
        )
        is ListDialog.RenameFolder -> FolderNameDialog(
            title = "Rename folder", initial = d.folder.name, confirmLabel = "Rename",
            onConfirm = { name -> dialog = ListDialog.None; vm.renameFolder(d.folder.id, name) },
            onDismiss = { dialog = ListDialog.None },
        )
        is ListDialog.DeleteFolder -> ConfirmDialog(
            title = "Delete folder '${d.folder.name}'",
            body = "Entries are kept, unfiled. Only the folder is removed.",
            confirmLabel = "Delete folder",
            onConfirm = { dialog = ListDialog.None; vm.deleteFolder(d.folder.id) },
            onDismiss = { dialog = ListDialog.None },
        )
        ListDialog.DeleteSelected -> ConfirmDialog(
            title = if (selected.size == 1) "Delete 1 selected entry" else "Delete ${selected.size} selected entries",
            body = "The selected entries are removed from the password keeper. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                val n = selected.size
                dialog = ListDialog.None
                vm.deleteSelected()
                scope.launch { snackbar.showSnackbar(if (n == 1) "1 entry deleted" else "$n entries deleted") }
            },
            onDismiss = { dialog = ListDialog.None },
        )
        ListDialog.MoveSelected -> MoveToFolderDialog(
            folders = folders,
            onPick = { folderId -> dialog = ListDialog.None; vm.moveSelected(folderId) },
            onNewFolder = { dialog = ListDialog.MoveToNewFolder },
            onDismiss = { dialog = ListDialog.None },
        )
        ListDialog.MoveToNewFolder -> FolderNameDialog(
            title = "New folder", initial = "", confirmLabel = "Create and move",
            onConfirm = { name -> dialog = ListDialog.None; vm.createFolder(name) { vm.moveSelected(it.id) } },
            onDismiss = { dialog = ListDialog.MoveSelected },
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text("Search titles, websites, usernames", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surface,
            unfocusedContainerColor = scheme.surface,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outlineVariant,
        ),
    )
}

/** All, Starred, each folder, Unfiled. One row that scrolls sideways; the selected chip is filled. */
@Composable
private fun FilterRow(filter: ListFilter, totals: EntryTotals, folders: List<FolderEntity>, onPick: (ListFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillChip(filter == ListFilter.All, { onPick(ListFilter.All) }, "All", count = totals.all)
        PillChip(filter == ListFilter.Starred, { onPick(ListFilter.Starred) }, "Starred", count = totals.starred) {
            StarIcon(size = 17.dp, contentDescription = null)
        }
        folders.forEach { f ->
            PillChip((filter as? ListFilter.Folder)?.id == f.id, { onPick(ListFilter.Folder(f.id)) }, f.name) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        PillChip(filter == ListFilter.Unfiled, { onPick(ListFilter.Unfiled) }, "Unfiled")
    }
}

private fun LazyListScope.entryGroup(
    entries: List<PasswordEntryEntity>,
    folderNames: Map<String, String>,
    selectionMode: Boolean,
    selected: Set<String>,
    onOpen: (PasswordEntryEntity) -> Unit,
    onToggle: (PasswordEntryEntity) -> Unit,
) {
    itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
        EntryRow(
            entry = entry,
            position = GroupPosition.of(index, entries.size),
            folderName = entry.folderId?.let { folderNames[it] },
            selectionMode = selectionMode,
            selected = entry.id in selected,
            onClick = { if (selectionMode) onToggle(entry) else onOpen(entry) },
            onLongClick = { onToggle(entry) },
            modifier = Modifier.animateItem(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: PasswordEntryEntity,
    position: GroupPosition,
    folderName: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .cardSegment(position, fill = if (selected) scheme.primaryContainer else scheme.surface, line = scheme.outlineVariant, dividerInset = 72.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 72.dp)
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode && selected) SelectedTile(size = 44.dp, corner = 13.dp)
        else EntryTile(entry.title, entry.color, size = 44.dp, corner = 13.dp, contentDescription = if (selectionMode) "Not selected" else null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.starred) {
                    Spacer(Modifier.width(6.dp))
                    StarIcon(size = 17.dp)
                }
            }
            val detail = entry.username.ifBlank { entry.website }
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (folderName != null) {
            Spacer(Modifier.width(8.dp))
            FolderTag(folderName)
        }
    }
}

/** Name prompt for creating or renaming a folder. */
@Composable
internal fun FolderNameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MoveToFolderDialog(folders: List<FolderEntity>, onPick: (String?) -> Unit, onNewFolder: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FolderChoice("No folder", onClick = { onPick(null) })
                folders.forEach { f -> FolderChoice(f.name, onClick = { onPick(f.id) }) }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                FolderChoice("New folder…", onClick = onNewFolder)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FolderChoice(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun entryCount(n: Int) = if (n == 1) "1 entry" else "$n entries"
