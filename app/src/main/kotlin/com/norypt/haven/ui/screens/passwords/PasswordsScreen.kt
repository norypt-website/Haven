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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
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
    val currentFolder = (filter as? FolderFilter.Folder)?.let { f -> folders.firstOrNull { it.id == f.id } }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge) },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
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
                        DropdownMenuItem(text = { Text("New folder…") }, onClick = { menuOpen = false; dialog = ListDialog.NewFolder })
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Search titles, websites, usernames") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = filter == FolderFilter.All, onClick = { vm.setFilter(FolderFilter.All) }, label = { Text("All") })
                FilterChip(selected = filter == FolderFilter.Unfiled, onClick = { vm.setFilter(FolderFilter.Unfiled) }, label = { Text("Unfiled") })
                folders.forEach { f ->
                    FilterChip(
                        selected = (filter as? FolderFilter.Folder)?.id == f.id,
                        onClick = { vm.setFilter(FolderFilter.Folder(f.id)) },
                        label = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { dialog = ListDialog.NewFolder },
                    label = { Text("New folder") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            if (entries.isEmpty()) {
                when {
                    query.isNotBlank() -> EmptyState("Nothing matches", "No entry title, website or username contains that text.")
                    filter != FolderFilter.All -> EmptyState("This folder is empty", "Long-press entries in the list to move them here.")
                    else -> EmptyState(
                        "No passwords yet",
                        "Entries are kept only in this vault on this device.",
                        action = { Button(onClick = { nav.navigate(Routes.passwordEdit()) }) { Text("New entry") } },
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            folderName = entry.folderId?.let { folderNames[it] },
                            selectionMode = selectionMode,
                            selected = entry.id in selected,
                            onClick = { if (selectionMode) vm.toggleSelected(entry.id) else nav.navigate(Routes.passwordDetail(entry.id)) },
                            onLongClick = { vm.toggleSelected(entry.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        ListDialog.None -> Unit
        ListDialog.NewFolder -> FolderNameDialog(
            title = "New folder", initial = "", confirmLabel = "Create",
            onConfirm = { name -> dialog = ListDialog.None; vm.createFolder(name) { vm.setFilter(FolderFilter.Folder(it.id)) } },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: PasswordEntryEntity,
    folderName: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(entry.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.username.isNotBlank()) {
                    Text(entry.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (folderName != null) {
                    Text(folderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
