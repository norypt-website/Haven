package com.norypt.haven.ui.screens.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.norypt.haven.ui.components.StarButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.components.PasswordField
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up

@Composable
fun PasswordEditScreen(nav: NavHostController, id: String?) {
    PasswordVaultGate(nav) { EditContent(nav, id) }
}

@Composable
private fun EditContent(nav: NavHostController, id: String?) {
    val container = LocalAppContainer.current
    val vm = rememberPasswordEditViewModel(container, id)
    val folders by vm.folders.collectAsState()
    var folderMenu by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }

    // Runs on every (re)entry into composition, i.e. also when the generator pops back here.
    LaunchedEffect(Unit) { vm.applyHandoff() }
    LaunchedEffect(vm.saved) { if (vm.saved) nav.up() }
    LaunchedEffect(vm.missing) { if (vm.missing) nav.up() }

    fun cancel() { GeneratedPasswordHandoff.clear(); nav.up() }
    BackHandler { cancel() }

    val folderLabel = vm.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name } ?: "No folder"
    val noAutocorrect = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)

    Scaffold(
        topBar = {
            HavenTopBar(if (id == null) "New entry" else "Edit entry", onBack = { cancel() }) {
                if (vm.loaded) StarButton(starred = vm.starred, onToggle = { vm.starred = !vm.starred })
            }
        },
    ) { padding ->
        if (!vm.loaded) { Spacer(Modifier.padding(padding).fillMaxSize()); return@Scaffold }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                // Live preview: initials and colour follow the title and the colour picked below.
                EntryTile(vm.title, vm.color, size = 56.dp, corner = 16.dp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = vm.title,
                    onValueChange = { vm.title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    isError = vm.titleError,
                    supportingText = if (vm.titleError) ({ Text("A title is required") }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
            }
            ColourPicker(title = vm.title, selectedId = vm.color, onSelect = { vm.color = it })
            OutlinedTextField(
                value = vm.website,
                onValueChange = { vm.website = it },
                label = { Text("Website") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.username,
                onValueChange = { vm.username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = noAutocorrect,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(Modifier.fillMaxWidth()) {
                PasswordField(value = vm.password, onValueChange = { vm.password = it }, label = "Password", imeAction = ImeAction.Next)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { nav.navigate(Routes.PASSWORD_GENERATOR) }) { Text("Generate…") }
                }
            }
            OutlinedTextField(
                value = vm.notes,
                onValueChange = { vm.notes = it },
                label = { Text("Notes") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { folderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    Text("Folder: $folderLabel", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text("No folder") }, onClick = { folderMenu = false; vm.folderId = null })
                    folders.forEach { f ->
                        DropdownMenuItem(text = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { folderMenu = false; vm.folderId = f.id })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("New folder…") }, onClick = { folderMenu = false; newFolderDialog = true })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { cancel() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { vm.save() }, enabled = !vm.saving && !vm.saved, modifier = Modifier.weight(1f)) { Text("Save") }
            }
            Text(
                "Saved only in this vault on this device. Haven never fills passwords into other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (newFolderDialog) {
        FolderNameDialog(
            title = "New folder", initial = "", confirmLabel = "Create",
            onConfirm = { name -> newFolderDialog = false; vm.createFolder(name) },
            onDismiss = { newFolderDialog = false },
        )
    }
}

/** Auto (a colour worked out from the title) followed by the ten palette colours, six to a row. */
@Composable
private fun ColourPicker(title: String, selectedId: Int, onSelect: (Int) -> Unit) {
    val chosen = EntryColor.entries.firstOrNull { it.id == selectedId }
    val options = listOf(EntryColor.AUTO to EntryColor.auto(title).color) + EntryColor.entries.map { it.id to it.color }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Colour", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(chosen?.label ?: "Auto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            // Six 48dp swatches to a row when they fit; on narrow screens the row wraps instead of
            // shrinking the touch targets.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = ((maxWidth - SwatchSize * 6) / 5).coerceIn(4.dp, 24.dp)
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    maxItemsInEachRow = 6,
                ) {
                    options.forEach { (id, color) ->
                        Swatch(
                            color = color,
                            selected = if (id == EntryColor.AUTO) chosen == null else id == selectedId,
                            auto = id == EntryColor.AUTO,
                            label = if (id == EntryColor.AUTO) "Auto colour from the title" else EntryColor.entries.first { it.id == id }.label,
                            onClick = { onSelect(id) },
                        )
                    }
                }
            }
            Text(
                "The first circle is Auto: a colour chosen from the title. Pick any other to set it yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            )
        }
    }
}

private val SwatchSize = 48.dp

@Composable
private fun Swatch(color: Color, selected: Boolean, auto: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(SwatchSize)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                .padding(4.dp)
                .background(tileBrush(color), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                auto -> Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
        }
    }
}
