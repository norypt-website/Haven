package com.norypt.haven.ui.screens.photos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.norypt.haven.photos.AndroidPhotoImaging
import com.norypt.haven.storage.passwords.PhotoItem
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.components.ConfirmDialog
import com.norypt.haven.ui.components.TagPill
import com.norypt.haven.ui.screens.passwords.PasswordVaultGate
import com.norypt.haven.ui.screens.settings.currentActivity
import com.norypt.haven.ui.up
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Full-screen photo viewer: swipe between photos, pinch or double-tap to zoom, tap to hide the controls. */
@Composable
fun PhotoViewerScreen(nav: NavHostController, id: String) {
    PasswordVaultGate(nav) { ViewerContent(nav, id) }
}

private val Frost = Color.White.copy(alpha = 0.16f)
private val Scrim = Color.Black.copy(alpha = 0.6f)

private sealed interface Loaded {
    data object Loading : Loaded
    data class Failed(val message: String) : Loaded
    data class Ready(val image: ImageBitmap) : Loaded
}

@Composable
private fun ViewerContent(nav: NavHostController, startId: String) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val vm: PhotoViewerViewModel = viewModel { PhotoViewerViewModel(container.photos) }
    val photos by vm.photos.collectAsState()
    LightStatusBarIcons()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = photos ?: return@Box
        if (list.isEmpty()) {
            LaunchedEffect(Unit) { nav.up() }
            return@Box
        }
        val start = remember { list.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
        val pager = rememberPagerState(initialPage = start) { list.size }
        val current = list.getOrNull(pager.currentPage) ?: list.last()
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        var chrome by rememberSaveable { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf<PhotoItem?>(null) }
        var askRemove by remember { mutableStateOf<PhotoItem?>(null) }
        val imaging = remember { AndroidPhotoImaging() }
        // About 1.5x the screen's longest side: sharp when zoomed in a little, without holding
        // several full-resolution bitmaps (current page and its neighbours) in memory.
        val config = LocalConfiguration.current
        val maxSide = with(LocalDensity.current) { (maxOf(config.screenHeightDp, config.screenWidthDp).dp.toPx() * 1.5f).toInt().coerceIn(1024, 3072) }

        // A deleted or swiped-away page takes its zoom with it.
        LaunchedEffect(current.id) { zoomed = false }

        val restoreOf: (PhotoItem) -> Unit = { p ->
            busy = true
            scope.launch {
                val ok = try { vm.restore(context, p) } finally { busy = false }
                if (ok) askRemove = p else snackbar.showSnackbar("Could not save the photo to the gallery.")
            }
        }
        val restore: () -> Unit = { restoreOf(current) }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            key = { list[it].id },
        ) { page ->
            val p = list[page]
            val loaded by produceState<Loaded>(Loaded.Loading, p.id) {
                value = when (val o = vm.image(p, maxSide, imaging)) {
                    is PhotoViewerViewModel.Opened.Image -> Loaded.Ready(o.bitmap)
                    PhotoViewerViewModel.Opened.NotDecodable -> Loaded.Failed("This photo can't be shown on this phone. Restore it to the gallery to open it elsewhere.")
                    PhotoViewerViewModel.Opened.Unreadable -> Loaded.Failed("This photo could not be opened.")
                }
            }
            ZoomablePhoto(
                loaded = loaded,
                description = "Photo ${page + 1} of ${list.size}, ${spokenDate(p.shownAt())}",
                isCurrent = pager.settledPage == page,
                onTap = { chrome = !chrome },
                onZoomChange = { if (pager.settledPage == page) zoomed = it },
                actions = listOf(
                    CustomAccessibilityAction("Restore to gallery") { if (!busy) restoreOf(p); true },
                    CustomAccessibilityAction("Delete photo") { confirmDelete = p; true },
                ),
            )
        }

        AnimatedVisibility(chrome, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.up() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    viewerDate(current),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                TagPill(
                    "${pager.currentPage + 1} / ${list.size}",
                    Modifier.semantics { contentDescription = "Photo ${pager.currentPage + 1} of ${list.size}" },
                    large = true,
                    container = Frost,
                    content = Color.White,
                )
            }
        }

        AnimatedVisibility(chrome, enter = fadeIn(tween(180)), exit = fadeOut(tween(180)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                FrostedPill(Icons.Filled.Restore, if (busy) "Restoring…" else "Restore", enabled = !busy, modifier = Modifier.weight(1f, fill = false), onClick = restore)
                FrostedPill(Icons.Filled.Delete, "Delete", enabled = !busy, modifier = Modifier.weight(1f, fill = false)) { confirmDelete = current }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))

        confirmDelete?.let { p ->
            ConfirmDialog(
                title = "Delete this photo?",
                body = "Its key is destroyed, so it cannot be recovered. Backups made earlier still contain it.",
                confirmLabel = "Delete",
                onConfirm = { confirmDelete = null; vm.delete(p.id) },
                onDismiss = { confirmDelete = null },
            )
        }
        askRemove?.let { p ->
            ConfirmDialog(
                title = "Saved to ${GalleryExport.FOLDER}",
                body = "The photo is back in your gallery. Remove it from the vault too?",
                confirmLabel = "Remove from vault",
                dismissLabel = "Keep in vault",
                destructive = false,
                onConfirm = { askRemove = null; vm.delete(p.id) },
                onDismiss = { askRemove = null },
            )
        }
    }
}

/** A 48dp frosted pill with a white icon and label, for the viewer's bottom bar. */
@Composable
private fun FrostedPill(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = Frost,
        contentColor = Color.White.copy(alpha = if (enabled) 1f else 0.6f),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One page. At 1x drags go to the pager (swipe between photos); once zoomed this page takes the
 * drags to pan, clamped so the photo never leaves the screen. Tap toggles the controls, double tap
 * zooms in or back out.
 */
@Composable
private fun ZoomablePhoto(
    loaded: Loaded,
    description: String,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    actions: List<CustomAccessibilityAction>,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun set(s: Float, o: Offset) {
        val wasZoomed = scale > 1f
        scale = s
        offset = if (s <= 1f) Offset.Zero else clamp(o, s)
        if (wasZoomed != (s > 1f)) onZoomChange(s > 1f)
    }
    // A page swiped away comes back at 1x.
    LaunchedEffect(isCurrent) { if (!isCurrent && scale != 1f) set(1f, Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) set(1f, Offset.Zero)
                        else {
                            // Zoom towards the tapped point.
                            val center = Offset(size.width / 2f, size.height / 2f)
                            set(2.5f, (center - tap) * 1.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || scale > 1f) {
                            val s = (scale * zoom).coerceIn(1f, 6f)
                            set(s, offset + pan)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        when (loaded) {
            Loaded.Loading -> CircularProgressIndicator(color = Color.White)
            is Loaded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.size(12.dp))
                Text(loaded.message, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            is Loaded.Ready -> Image(
                loaded.image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}

/** White status bar icons over the black viewer; the previous look comes back on leaving. */
@Composable
private fun LightStatusBarIcons() {
    val activity = currentActivity()
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (controller != null && before != null) controller.isAppearanceLightStatusBars = before }
    }
}

private fun viewerDate(p: PhotoItem): String =
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochMilli(p.shownAt()).atZone(ZoneId.systemDefault()))
