package com.norypt.haven.ui.screens.passwords

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.HavenTopBar
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

    Scaffold(topBar = { HavenTopBar(if (id == null) "New entry" else "Edit entry", onBack = { cancel() }) }) { padding ->
        if (!vm.loaded) { Spacer(Modifier.padding(padding).fillMaxSize()); return@Scaffold }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.title = it },
                label = { Text("Title") },
                singleLine = true,
                isError = vm.titleError,
                supportingText = if (vm.titleError) ({ Text("A title is required") }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
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
                Button(onClick = { vm.save() }, enabled = !vm.saving, modifier = Modifier.weight(1f)) { Text("Save") }
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
