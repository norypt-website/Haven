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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.CardDivider
import com.norypt.haven.ui.components.FieldRow
import com.norypt.haven.ui.components.FieldValue
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.InfoRow
import com.norypt.haven.ui.components.RoundAction
import com.norypt.haven.ui.components.StarredPill
import com.norypt.haven.ui.components.StarButton
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.LocalHavenColors
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

    fun copy(value: String) {
        copySecret(container, value)
        scope.launch { snackbar.showSnackbar(copiedMessage(container)) }
    }

    Scaffold(
        topBar = {
            // The header below names the entry, so the bar carries only the actions.
            HavenTopBar("", onBack = { nav.up() }) {
                if (entry != null && !deleting) {
                    StarButton(
                        starred = entry.starred,
                        onToggle = {
                            val star = !entry.starred
                            scope.launch { runCatching { repo.setStarred(listOf(entry.id), star) } }
                        },
                    )
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
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val folderName = entry.folderId?.let { fid -> folders.firstOrNull { it.id == fid }?.name }
                EntryHeader(entry, folderName)
                GroupCard {
                    FieldRow(Icons.Filled.Person, "Username", value = { FieldValue(entry.username.ifBlank { "—" }) }) {
                        if (entry.username.isNotBlank()) RoundAction(Icons.Filled.ContentCopy, "Copy username") { copy(entry.username) }
                    }
                    CardDivider()
                    FieldRow(
                        Icons.Filled.Key,
                        "Password",
                        value = {
                            when {
                                entry.password.isEmpty() -> FieldValue("—")
                                revealed -> Text(
                                    colouredPassword(entry.password),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
                                )
                                else -> Text(MASK, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp), maxLines = 1)
                            }
                        },
                    ) {
                        if (entry.password.isNotEmpty()) {
                            RoundAction(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (revealed) "Hide password" else "Show password") { revealed = !revealed }
                            RoundAction(Icons.Filled.ContentCopy, "Copy password") { copy(entry.password) }
                        }
                    }
                    CardDivider()
                    // Websites are plain text on purpose: never a link, never opened, never previewed.
                    FieldRow(Icons.Filled.Language, "Website", value = { FieldValue(entry.website.ifBlank { "—" }) })
                }
                if (entry.notes.isNotBlank()) {
                    GroupCard {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(entry.notes, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
                        }
                    }
                }
                GroupCard {
                    InfoRow("Folder", folderName ?: "No folder")
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Created", formatDate(entry.createdAt))
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    InfoRow("Updated", formatDate(entry.updatedAt))
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

/** Large tile, name, website and the Starred / folder labels. */
@Composable
private fun EntryHeader(entry: PasswordEntryEntity, folderName: String?) {
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EntryTile(entry.title, entry.color, size = 76.dp, corner = 22.dp, glow = true)
        Spacer(Modifier.height(16.dp))
        Text(
            entry.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (entry.website.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.website,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.starred || folderName != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.starred) StarredPill()
                if (folderName != null) FolderTag(folderName, large = true)
            }
        }
    }
}

/** Digits in the primary blue and symbols in orange, so look-alikes such as 0/O and 1/l stand apart. */
@Composable
private fun colouredPassword(password: String): AnnotatedString {
    val digit = MaterialTheme.colorScheme.primary
    val symbol = LocalHavenColors.current.passwordSymbol
    return buildAnnotatedString {
        append(password)
        passwordRuns(password).forEach { run ->
            when (run.kind) {
                PasswordCharKind.DIGIT -> addStyle(SpanStyle(color = digit), run.start, run.end)
                PasswordCharKind.SYMBOL -> addStyle(SpanStyle(color = symbol), run.start, run.end)
                PasswordCharKind.LETTER -> Unit
            }
        }
    }
}

private fun formatDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
