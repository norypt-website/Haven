package com.norypt.haven.ui.screens.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.storage.content.NoteEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ColorTile
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.HeroCard
import com.norypt.haven.ui.components.HeroIcon
import com.norypt.haven.ui.components.HeroStat
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.components.TintedCard
import com.norypt.haven.ui.components.brandBrush
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.entryInitials
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.NoryptColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notes: a brand hero with counts, a pill search field and a two-column grid of cards, each
 * washed in its note's colour. Everything lives in the content vault.
 */
@Composable
fun NotesScreen(nav: NavHostController) {
    val container = LocalAppContainer.current
    if (container.session.contentOrNull() == null) return
    val vm: NotesListViewModel = viewModel { NotesListViewModel(container.notes) }
    val all by vm.all.collectAsState()
    val notes by vm.notes.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { vm.setQuery(query) }
    val everything = all
    val hasNotes = !everything.isNullOrEmpty()

    Scaffold(
        topBar = { HavenTopBar("Notes") },
        floatingActionButton = {
            // The empty state brings its own button; one "New note" on screen is enough.
            if (hasNotes) ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.noteEdit()) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New note") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { padding ->
        if (everything == null) return@Scaffold
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalItemSpacing = 12.dp,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item(key = "hero", span = StaggeredGridItemSpan.FullLine) { NotesHero(everything) }
            if (!hasNotes) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) { NotesEmptyState { nav.navigate(Routes.noteEdit()) } }
                return@LazyVerticalStaggeredGrid
            }
            item(key = "search", span = StaggeredGridItemSpan.FullLine) { NoteSearchField(query) { query = it } }
            val list = notes ?: return@LazyVerticalStaggeredGrid
            if (list.isEmpty() && query.isNotBlank()) {
                item(key = "nomatch", span = StaggeredGridItemSpan.FullLine) { NoMatches() }
            }
            items(list, key = { it.id }) { note ->
                NoteCard(note, Modifier.animateItem()) { nav.navigate(Routes.noteEdit(note.id)) }
            }
        }
    }
}

/** "Notes", when the last edit was, and two counts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesHero(all: List<NoteEntity>) {
    val now = System.currentTimeMillis()
    val latest = all.maxOfOrNull { it.updatedAt }
    val thisWeek = all.count { now - it.updatedAt < WEEK_MS }
    HeroCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Notes", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    if (latest != null) "Last edited ${relativeTime(latest, now).lowercaseFirstUnlessDate()}" else "Your private notebook",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
            Spacer(Modifier.width(12.dp))
            HeroIcon(Icons.Filled.EditNote)
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroStat(Icons.Filled.EditNote, if (all.size == 1) "1 note" else "${all.size} notes")
            HeroStat(Icons.Filled.Schedule, "$thisWeek this week")
        }
    }
}

/** Pill-shaped search on the raised surface, without an outline; a clear button once there is text. */
@Composable
private fun NoteSearchField(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text("Search notes") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        },
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerHigh,
            unfocusedContainerColor = scheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    )
}

@Composable
private fun NoteCard(note: NoteEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val title = NoteLogic.displayTitle(note.title, note.body)
    val accent = EntryColor.auto(title).color
    TintedCard(accent, onClick, modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteTile(title, accent, size = 34.dp, corner = 10.dp, glow = true)
                Spacer(Modifier.weight(1f))
                TagPill(relativeTime(note.updatedAt), Modifier.padding(start = 8.dp), container = accent.copy(alpha = 0.14f))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val preview = NoteLogic.preview(note.title, note.body)
            if (preview.isNotEmpty()) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NotesEmptyState(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(Icons.Filled.EditNote, brandBrush(), size = 88.dp, corner = 26.dp, glow = NoryptColors.Blue)
        Spacer(Modifier.height(24.dp))
        Text("Write your first note", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Encrypted with your Haven password. Never leaves this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNew, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New note")
        }
    }
}

@Composable
private fun NoMatches() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Nothing matches", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("No note title or text contains that.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * The note's colour tile with its initials (an edit glyph when the title has no letters). The
 * letters scale with the tile, not the font size setting; the title is always shown next to it.
 */
@Composable
internal fun NoteTile(title: String, accent: Color, size: Dp, corner: Dp, modifier: Modifier = Modifier, glow: Boolean = false) {
    val initials = entryInitials(title)
    ColorTile(tileBrush(accent), size, corner, modifier, glow = if (glow) accent else null) {
        if (initials.isEmpty()) {
            Icon(Icons.Filled.EditNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        } else {
            val fontSize = with(LocalDensity.current) { (size * 0.38f).toSp() }
            Text(initials, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

internal const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/** "Just now", "12 min ago", "3 h ago", "Yesterday", else a short date. */
internal fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - epochMs) / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }
}

/** "Just now" -> "just now" inside a sentence; dates and numbers stay as they are. */
internal fun String.lowercaseFirstUnlessDate(): String =
    if (this == "Just now" || this == "Yesterday") replaceFirstChar { it.lowercase() } else this
