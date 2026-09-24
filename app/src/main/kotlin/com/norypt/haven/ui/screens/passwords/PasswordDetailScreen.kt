package com.norypt.haven.ui.screens.passwords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.ScreenPadding
import com.norypt.haven.ui.components.SectionCard
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.up
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun PasswordDetailScreen(nav: NavHostController, id: String) {
    PasswordVaultGate(nav) { DetailContent(nav, id) }
}

private sealed interface Load {
    data object Loading : Load
    data class Ready(val entry: PasswordEntryEntity?) : Load
}

private const val MASK = "••••••••••••"

@Composable
private fun DetailContent(nav: NavHostController, id: String) {
    val container = LocalAppContainer.current
    val repo = container.passwords
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val load by produceState<Load>(Load.Loading, id) {
        flow { emitAll(repo.observeEntry(id)) }.catch { emit(null) }.collect { value = Load.Ready(it) }
    }
    val folders by produceState<List<FolderEntity>>(emptyList(), id) {
        flow { emitAll(repo.observeFolders()) }.catch { emit(emptyList()) }.collect { value = it }
    }
    // Reveal is per visit: this state is created fresh every time the screen is composed.
    var revealed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val entry = (load as? Load.Ready)?.entry
    val title = entry?.title?.ifBlank { "Untitled" } ?: "Entry"

    fun copy(value: String) {
        copySecret(container, value)
        scope.launch { snackbar.showSnackbar(copiedMessage(container)) }
    }

    Scaffold(
        topBar = {
            HavenTopBar(title, onBack = { nav.up() }) {
                if (entry != null && !deleting) {
                    IconButton(onClick = { nav.navigate(Routes.passwordEdit(entry.id)) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Edit, contentDescription = "Edit entry") }
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, contentDescription = "Delete entry") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            deleting || load is Load.Loading -> Spacer(Modifier.padding(padding).fillMaxSize())
            entry == null -> EmptyState("Entry not found", "It may have been deleted.")
            else -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionCard {
                    // Websites are plain text on purpose: never a link, never opened, never previewed.
                    LabeledValue("Website", entry.website.ifBlank { "—" })
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { LabeledValue("Username", entry.username.ifBlank { "—" }) }
                        if (entry.username.isNotBlank()) {
                            IconButton(onClick = { copy(entry.username) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy username") }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Column(Modifier.fillMaxWidth()) {
                        Text("Password", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (entry.password.isEmpty()) "—" else if (revealed) entry.password else MASK,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        )
                        if (entry.password.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Reveal") }
                                IconButton(onClick = { copy(entry.password) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy password") }
                            }
                        }
                    }
                }
                if (entry.notes.isNotBlank()) {
                    SectionCard { LabeledValue("Notes", entry.notes) }
                }
                SectionCard {
                    val folderName = entry.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name }
                    LabeledValue("Folder", folderName ?: "No folder")
                    Spacer(Modifier.height(8.dp))
                    LabeledValue("Created", formatDate(entry.createdAt))
                    Spacer(Modifier.height(8.dp))
                    LabeledValue("Updated", formatDate(entry.updatedAt))
                }
            }
        }
    }

    if (confirmDelete && entry != null) {
        ConfirmDialog(
            title = "Delete '${entry.title.ifBlank { "Untitled" }}'",
            body = "The entry is removed from the password keeper. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                deleting = true
                scope.launch {
                    withContext(NonCancellable) { runCatching { repo.delete(entry.id) } }
                    nav.up()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
