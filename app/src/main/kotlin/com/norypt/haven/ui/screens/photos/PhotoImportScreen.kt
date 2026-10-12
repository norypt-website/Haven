package com.norypt.haven.ui.screens.photos

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.CardDivider
import com.norypt.haven.ui.components.EmptyState
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.FactLevel
import com.norypt.haven.ui.components.FactRow
import com.norypt.haven.ui.components.GroupCard
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.SelectedTile
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.components.brandBrush
import com.norypt.haven.ui.components.color
import com.norypt.haven.ui.components.tileBrush
import com.norypt.haven.ui.screens.passwords.PasswordVaultGate
import com.norypt.haven.ui.theme.NoryptColors
import com.norypt.haven.ui.up
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Choose gallery photos, encrypt them into the vault, then let Android delete the originals. */
@Composable
fun PhotoImportScreen(nav: NavHostController) {
    PasswordVaultGate(nav) { ImportContent(nav) }
}

private val PickShape = RoundedCornerShape(8.dp)

@Composable
private fun ImportContent(nav: NavHostController) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: PhotoImportViewModel = viewModel {
        PhotoImportViewModel(container.photos, context.contentResolver, keepAwake = { container.lockController.touch() })
    }
    val gallery by vm.gallery.collectAsState()
    val chosen by vm.chosen.collectAsState()
    val progress by vm.progress.collectAsState()
    val deleteResult by vm.deleteResult.collectAsState()
    // Photos from the system picker are not MediaStore items Haven may delete.
    val canDelete = !vm.fromPicker && Build.VERSION.SDK_INT >= 33

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        container.lockController.endSystemInteraction()
        vm.deleteResult.value = r.resultCode == Activity.RESULT_OK
    }
    LaunchedEffect(progress?.finished) {
        val p = progress ?: return@LaunchedEffect
        if (!p.finished || !canDelete || p.imported.isEmpty() || vm.deleteRequested) return@LaunchedEffect
        vm.deleteRequested = true
        val asked = launchGuarded(container.lockController) {
            val sender = MediaStore.createDeleteRequest(context.contentResolver, p.imported).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        // Android could not ask (for example the items vanished meanwhile): report the originals as kept.
        if (!asked) vm.deleteResult.value = false
    }
    val systemPicker = rememberSystemPicker(container.lockController) { vm.importPicked(it) }

    Scaffold(
        topBar = {
            HavenTopBar("Add photos", onBack = { nav.up() }) {
                if (progress == null && chosen.isNotEmpty()) {
                    TagPill("${chosen.size} selected", Modifier.padding(end = 12.dp), large = true)
                }
            }
        },
        bottomBar = {
            if (progress == null && !gallery.isNullOrEmpty()) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = vm::importChosen,
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (chosen.isEmpty()) "Select photos" else "Hide ${photoCount(chosen.size)}")
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val p = progress
            if (p != null) {
                ImportProgress(p, canDelete = canDelete, deleteResult = deleteResult, fromPicker = vm.fromPicker, onDone = { nav.up() })
                return@Box
            }
            val items = gallery
            if (items == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            if (items.isEmpty()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(
                        "No photos found",
                        "Allow Haven to see more photos in Android settings, or pick them with the system picker.",
                        action = {
                            OutlinedButton(onClick = systemPicker, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open the system picker")
                            }
                        },
                    )
                }
                return@Box
            }
            LazyVerticalGrid(
                GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "intro", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Photos Haven may see. Copies already in a cloud backup are not removed by Haven.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
                    )
                }
                items(items, key = { it.uri.toString() }) { g ->
                    GalleryTile(g, selected = g.uri in chosen, onToggle = { vm.toggle(g.uri) })
                }
            }
        }
    }
}

/** A gallery photo in the picker. Its thumbnail comes from Android straight into memory. */
@Composable
private fun GalleryTile(item: GalleryItem, selected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(item.uri, Size(256, 256), null).asImageBitmap() }.getOrNull()
        }
    }
    val scale by animateFloatAsState(if (selected) 0.9f else 1f, tween(160), label = "pickScale")
    val label = item.takenAt?.let { "Photo, ${spokenDate(it)}" } ?: "Photo"
    Box(
        Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(PickShape)
            .background(scheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, scheme.primary, PickShape) else Modifier)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
    ) {
        Crossfade(targetState = thumb, animationSpec = tween(220), label = "galleryThumb") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                }
            }
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { SelectedTile(size = 24.dp, corner = 12.dp, contentDescription = null) }
    }
}

@Composable
private fun ImportProgress(p: PhotoImportViewModel.Progress, canDelete: Boolean, deleteResult: Boolean?, fromPicker: Boolean, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedContent(
            targetState = p.finished,
            transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f)) togetherWith fadeOut(tween(150)) },
            label = "importTile",
        ) { finished ->
            if (finished) {
                IconTile(Icons.Filled.Check, tileBrush(EntryColor.GREEN.color), size = 96.dp, corner = 28.dp, glow = EntryColor.GREEN.color)
            } else {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val scale by pulse.animateFloat(1f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulseScale")
                IconTile(Icons.Filled.Lock, brandBrush(), Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, size = 96.dp, corner = 28.dp, glow = NoryptColors.Blue)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            if (p.finished) "${photoCount(p.imported.size)} hidden" else "Encrypting ${minOf(p.done + 1, p.total)} of ${p.total}…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { if (p.total == 0) 1f else p.done / p.total.toFloat() },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            strokeCap = StrokeCap.Round,
        )
        Spacer(Modifier.height(24.dp))
        val refused = p.refused.groupingBy { it }.eachCount()
        val showOriginals = p.finished && p.imported.isNotEmpty()
        if (refused.isNotEmpty() || showOriginals) {
            GroupCard {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    refused.entries.forEachIndexed { i, (reason, n) ->
                        if (i > 0) CardDivider(start = 34.dp)
                        FactRow(FactLevel.WARNING, if (n == 1) "1 not added" else "$n not added", reason)
                    }
                    if (showOriginals) {
                        if (refused.isNotEmpty()) CardDivider(start = 34.dp)
                        when {
                            fromPicker || !canDelete -> FactRow(FactLevel.INFO, "Originals are still in your gallery", "Delete them in your gallery app. Haven cannot remove photos chosen with the system picker.")
                            deleteResult == true -> FactRow(FactLevel.OK, "Originals removed from the gallery")
                            deleteResult == false -> FactRow(FactLevel.INFO, "Originals kept", "You chose to keep them in the gallery.")
                            else -> FactRow(FactLevel.INFO, "Waiting for Android", "Confirm in the dialog to remove the originals from the gallery.")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (p.finished) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Done") }
        }
    }
}
