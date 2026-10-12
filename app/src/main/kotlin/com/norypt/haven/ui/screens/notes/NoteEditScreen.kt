package com.norypt.haven.ui.screens.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.up
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A calm writing surface: a header with the note's live colour tile, a "Saved" pill and the edit
 * time and length, then the title and the body on the plain background. Saves by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditScreen(nav: NavHostController, id: String?) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: NoteEditViewModel = viewModel {
        NoteEditViewModel(NoteEditDeps.of(container.notes, container.session, { container.lockController.touch() }, container.appScope), id)
    }
    val title by vm.title.collectAsState()
    val body by vm.body.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val saved by vm.saved.collectAsState()
    val updatedAt by vm.updatedAt.collectAsState()
    val readFailed by vm.readFailed.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // The screen's own scope: if a lock takes the screen away first, the save still finishes
    // (it is non-cancellable) but no navigation happens behind the unlock screen.
    val scope = rememberCoroutineScope()
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch { vm.saveNow(); nav.up() }
        }
    }
    BackHandler(onBack = leave)
    // Leaving the app saves at once, well before any auto-lock closes the vault.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { container.appScope.launch { vm.saveNow() } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(loaded) { if (loaded && id == null) runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = leave, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete note") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; confirm = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (readFailed) {
            Column(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    "Could not open this note",
                    "Nothing was changed. Go back and try again.",
                    action = { Button(onClick = leave, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") } },
                )
            }
            return@Scaffold
        }
        if (!loaded) return@Scaffold
        val displayTitle = NoteLogic.displayTitle(title, body)
        val accent by animateColorAsState(EntryColor.auto(displayTitle).color, tween(400), label = "note accent")
        val placeholder = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NoteTile(displayTitle, accent, size = 56.dp, corner = 17.dp, glow = true)
                Spacer(Modifier.width(14.dp))
                EditMeta(updatedAt, NoteLogic.wordCount(title, body), Modifier.weight(1f))
                AnimatedVisibility(saved, enter = fadeIn(tween(300)), exit = fadeOut(tween(200))) {
                    TagPill(
                        "Saved",
                        Modifier.padding(start = 8.dp),
                        large = true,
                        icon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(15.dp)) },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            BasicTextField(
                value = title,
                onValueChange = vm::onTitle,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    if (title.isEmpty()) Text("Title", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = placeholder)
                    inner()
                },
            )
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = body,
                onValueChange = vm::onBody,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground, lineHeight = 26.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).padding(bottom = 48.dp),
                decorationBox = { inner ->
                    if (body.isEmpty()) Text("Start writing…", style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp), color = placeholder)
                    inner()
                },
            )
        }
    }
    if (confirm) ConfirmDialog(
        title = "Delete this note?",
        body = "It is removed from this phone. Backups made earlier still contain it.",
        confirmLabel = "Delete",
        onConfirm = { confirm = false; leaving = true; vm.delete { nav.up() } },
        onDismiss = { confirm = false },
    )
}

/** "Edited 2 min ago · 132 words", kept current while the editor is open. */
@Composable
private fun EditMeta(updatedAt: Long?, words: Int, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis(), updatedAt) {
        value = System.currentTimeMillis()
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    val edited = if (updatedAt == null) "New note" else "Edited " + relativeTime(updatedAt, now).lowercaseFirstUnlessDate()
    Text(
        "$edited · ${if (words == 1) "1 word" else "$words words"}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
