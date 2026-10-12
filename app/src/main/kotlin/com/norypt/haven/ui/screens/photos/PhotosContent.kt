package com.norypt.haven.ui.screens.photos

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.security.LockController
import com.norypt.haven.storage.passwords.PhotoEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.HavenTopBar
import com.norypt.haven.ui.components.IconTile
import com.norypt.haven.ui.components.SectionLabel
import com.norypt.haven.ui.components.SelectedTile
import com.norypt.haven.ui.components.brandBrush
import com.norypt.haven.ui.navigation.Routes
import com.norypt.haven.ui.theme.NoryptColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val TileShape = RoundedCornerShape(14.dp)

/**
 * The photo half of the Vault tab: the Vault [header], then the hidden photos in a three-column
 * grid grouped by month. Long-press selects; selected photos can be restored to the gallery or
 * deleted for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotosContent(nav: NavHostController, header: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: PhotosViewModel = viewModel { PhotosViewModel(container.photos) }
    val photos by vm.photos.collectAsState()
    val selected by vm.selected.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    val selectionMode = selected.isNotEmpty()
    BackHandler(enabled = selectionMode) { vm.clearSelection() }

    val addPhotos = rememberAddPhotos(container.lockController, keeperOpen = { container.session.passwordsOrNull() != null }) { picked ->
        if (picked != null) PickedForImport.set(picked) else PickedForImport.clear()
        nav.navigate(Routes.PHOTO_IMPORT)
    }

    Scaffold(
        topBar = {
            if (selectionMode) TopAppBar(
                title = { Text("${selected.size} selected", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = vm::clearSelection, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                },
                actions = {
                    IconButton(
                        enabled = !restoring,
                        onClick = {
                            val list = photos.orEmpty().filter { it.id in selected }
                            restoring = true
                            scope.launch {
                                var ok = 0
                                try {
                                    list.forEachIndexed { i, p ->
                                        val bytes = try {
                                            container.photos.original(p)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            null // keeper closed or file damaged: count it as not restored
                                        }
                                        if (bytes != null) try {
                                            if (GalleryExport.save(context, bytes, p.mimeType, i)) ok++
                                        } finally {
                                            bytes.fill(0)
                                        }
                                    }
                                } finally {
                                    restoring = false
                                }
                                vm.clearSelection()
                                snackbar.showSnackbar(
                                    if (ok == list.size) "${photoCount(ok)} restored to ${GalleryExport.FOLDER}. They are still in the vault."
                                    else "$ok of ${list.size} restored to ${GalleryExport.FOLDER}. They are still in the vault.",
                                )
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Restore, contentDescription = "Restore selected to gallery") }
                    IconButton(enabled = !restoring, onClick = { confirmDelete = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            ) else HavenTopBar("Vault") {
                IconButton(
                    onClick = {
                        // Leave the keeper screens first so the vault gate does not bounce to the keeper unlock screen.
                        nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true }; launchSingleTop = true }
                        scope.launch { container.session.lockPasswords() }
                    },
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Filled.Lock, contentDescription = "Lock vault") }
            }
        },
        floatingActionButton = {
            // The empty state brings its own button; one "Add photos" on screen is enough.
            if (!selectionMode && !photos.isNullOrEmpty()) ExtendedFloatingActionButton(
                onClick = addPhotos,
                icon = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                text = { Text("Add photos") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                val list = photos ?: return@LazyVerticalGrid
                if (list.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { PhotosEmptyState(addPhotos) }
                    return@LazyVerticalGrid
                }
                monthGroups(list).forEach { group ->
                    item(key = "month-${group.month}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionLabel(group.label, count = group.photos.size, description = "${group.label}, ${photoCount(group.photos.size)}")
                    }
                    items(group.photos, key = { it.id }) { p ->
                        PhotoTile(
                            photo = p,
                            selected = p.id in selected,
                            selectionMode = selectionMode,
                            thumbnail = vm::thumbnail,
                            onClick = { if (selectionMode) vm.toggle(p.id) else nav.navigate(Routes.photoViewer(p.id)) },
                            onLongClick = { vm.toggle(p.id) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val n = selected.size
        ConfirmDialog(
            title = if (n == 1) "Delete 1 photo?" else "Delete $n photos?",
            body = "Each photo's key is destroyed, so they cannot be recovered. Backups made earlier still contain them.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmDelete = false
                vm.deleteSelected()
                scope.launch { snackbar.showSnackbar(if (n == 1) "1 photo deleted" else "$n photos deleted") }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/**
 * One grid tile. The thumbnail is decrypted on demand and fades in over a placeholder with a small
 * lock, so loading reads as "decrypting". Selected tiles shrink a little, gain a blue border and a
 * check badge; TalkBack hears the date and the selection state.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoTile(
    photo: PhotoEntity,
    selected: Boolean,
    selectionMode: Boolean,
    thumbnail: suspend (PhotoEntity) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(null, photo.id) { value = thumbnail(photo) }
    val scale by animateFloatAsState(if (selected) 0.9f else 1f, tween(160), label = "tileScale")
    val label = "Photo, ${spokenDate(photo.shownAt())}"
    Box(
        modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(TileShape)
            .background(scheme.surfaceContainerHigh)
            .then(if (selected) Modifier.border(3.dp, scheme.primary, TileShape) else Modifier)
            .combinedClickable(
                onClickLabel = if (selectionMode) (if (selected) "Deselect" else "Select") else "Open",
                onLongClickLabel = if (selected) "Deselect" else "Select",
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = label
                if (selectionMode) this.selected = selected
            },
    ) {
        Crossfade(targetState = thumb, animationSpec = tween(220), label = "thumb") { bitmap ->
            if (bitmap != null) {
                Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                }
            }
        }
        if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SelectedTile(size = 28.dp, corner = 14.dp, contentDescription = null) }
    }
}

@Composable
private fun PhotosEmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconTile(Icons.Filled.AddPhotoAlternate, brandBrush(), size = 88.dp, corner = 26.dp, glow = NoryptColors.Blue)
        Spacer(Modifier.height(24.dp))
        Text("Hide your first photos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Each photo gets its own key. Delete it here and it is gone for good.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add photos")
        }
    }
}

/**
 * "Add photos": on Android 13+ asks to see the gallery so the originals can be deleted after
 * import; refused (or Android 12) falls back to the system photo picker, which needs no permission
 * but cannot delete. Each system screen is an [Excursion], so the auto-lock does not fire behind
 * it and a second tap while one is open does nothing. Results that arrive after the keeper closed
 * are dropped. [onReady] receives the picker's photos, or null when Haven may read the gallery itself.
 */
@Composable
private fun rememberAddPhotos(lock: LockController, keeperOpen: () -> Boolean, onReady: (List<Uri>?) -> Unit): () -> Unit {
    val pickerTrip = rememberExcursion(lock)
    val permissionTrip = rememberExcursion(lock)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (pickerTrip.end() && keeperOpen() && uris.isNotEmpty()) onReady(uris)
    }
    val launchPicker: () -> Unit = { pickerTrip.launch { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (!permissionTrip.end() || !keeperOpen()) return@rememberLauncherForActivityResult
        if (grants.values.any { it }) onReady(null) else launchPicker()
    }
    return {
        if (!pickerTrip.open && !permissionTrip.open) {
            if (Build.VERSION.SDK_INT >= 33) {
                val perms = buildList {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
                permissionTrip.launch { permission.launch(perms.toTypedArray()) }
            } else {
                launchPicker()
            }
        }
    }
}

/** The system photo picker, for screens that launch it themselves (the import screen's fallback). */
@Composable
internal fun rememberSystemPicker(lock: LockController, keeperOpen: () -> Boolean, onPicked: (List<Uri>) -> Unit): () -> Unit {
    val trip = rememberExcursion(lock)
    val picker: ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>> =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
            if (trip.end() && keeperOpen() && uris.isNotEmpty()) onPicked(uris)
        }
    return { trip.launch { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }; Unit }
}

internal fun photoCount(n: Int) = if (n == 1) "1 photo" else "$n photos"

private const val MAX_PICK = 50
