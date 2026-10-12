package com.norypt.haven.ui.screens.photos

import android.content.ContentResolver
import android.content.ContentUris
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.photos.ImageBounds
import com.norypt.haven.photos.PhotoImaging
import com.norypt.haven.photos.PhotoVault
import com.norypt.haven.security.SafeLog
import com.norypt.haven.storage.passwords.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/** When a photo was taken, or when it was added if the gallery did not say. */
internal fun PhotoEntity.shownAt(): Long = takenAt ?: addedAt

/** Newest first by [shownAt]: the order of the grid and of swiping in the viewer. */
internal fun sortedForGrid(photos: List<PhotoEntity>): List<PhotoEntity> =
    photos.sortedWith(compareByDescending<PhotoEntity> { it.shownAt() }.thenBy { it.id })

/** One month of photos in the grid, headed by e.g. "October 2026". */
internal data class MonthGroup(val month: YearMonth, val label: String, val photos: List<PhotoEntity>)

/** Groups photos by the month of [shownAt], newest month first; photos keep [sortedForGrid] order. */
internal fun monthGroups(photos: List<PhotoEntity>, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<MonthGroup> {
    val format = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    return sortedForGrid(photos)
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.shownAt()).atZone(zone)) }
        .map { (month, list) -> MonthGroup(month, format.format(month).replaceFirstChar { it.titlecase(locale) }, list) }
}

/** "12 October 2026": the date TalkBack reads for a photo. */
internal fun spokenDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy").format(Instant.ofEpochMilli(epochMs).atZone(zone))

class PhotosViewModel(private val vault: PhotoVault) : ViewModel() {
    val photos: StateFlow<List<PhotoEntity>?> = vault.observeAll().map { sortedForGrid(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val selected = MutableStateFlow<Set<String>>(emptySet())

    init {
        viewModelScope.launch { runCatching { vault.reconcile() } }
    }

    fun toggle(id: String) = selected.update { if (id in it) it - id else it + id }
    fun clearSelection() { selected.value = emptySet() }

    /** Null while the keeper is closed or the thumbnail cannot be read; the tile then keeps its placeholder. */
    suspend fun thumbnail(p: PhotoEntity): ImageBitmap? = runCatching { vault.thumbnail(p) }.getOrNull()

    fun deleteSelected() {
        val ids = selected.value
        clearSelection()
        // A keeper closed meanwhile makes delete throw; the photos then simply stay.
        viewModelScope.launch { runCatching { vault.delete(ids) } }
    }
}

/** A photo Haven may see in the gallery, for the import picker. */
internal data class GalleryItem(val uri: Uri, val takenAt: Long?)

/** Every image the gallery lets Haven see, newest first. Only ids and dates are read here. */
internal fun queryGallery(resolver: ContentResolver): List<GalleryItem> {
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
    return runCatching {
        resolver.query(collection, projection, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            buildList {
                while (c.moveToNext()) {
                    add(GalleryItem(ContentUris.withAppendedId(collection, c.getLong(idCol)), c.getLong(takenCol).takeIf { it > 0 }))
                }
            }
        }
    }.getOrNull().orEmpty()
}

/**
 * Hands picker results to the import screen in memory: never through route arguments or saved
 * state, so no photo address outlives the process or lands in a saved-state bundle.
 */
internal object PickedForImport {
    @Volatile private var uris: List<Uri>? = null
    fun set(list: List<Uri>) { uris = list }
    fun take(): List<Uri>? = uris.also { uris = null }
    fun clear() { uris = null }
}

/**
 * The import screen's state. Everything lives in this view model (memory only), so turning the
 * phone neither restarts an import nor forgets where the photos came from.
 *
 * Imports one item at a time, so at most one original is in memory at once.
 */
class PhotoImportViewModel(
    private val vault: PhotoVault,
    private val resolver: ContentResolver,
    private val keepAwake: () -> Unit,
) : ViewModel() {
    data class Progress(val done: Int, val total: Int, val refused: List<String>, val imported: List<Uri>, val finished: Boolean)

    /** Photos chosen in the system picker. Android cannot delete those originals for Haven. */
    var fromPicker: Boolean = false
        private set

    val progress = MutableStateFlow<Progress?>(null)
    internal val gallery = MutableStateFlow<List<GalleryItem>?>(null)
    val chosen = MutableStateFlow<Set<Uri>>(emptySet())

    /** Null until Android answered the request to delete the originals; true when they were deleted. */
    val deleteResult = MutableStateFlow<Boolean?>(null)
    var deleteRequested: Boolean = false

    init {
        val picked = PickedForImport.take()
        if (picked != null) importPicked(picked)
        else viewModelScope.launch { gallery.value = withContext(Dispatchers.IO) { queryGallery(resolver) } }
    }

    fun toggle(uri: Uri) = chosen.update { if (uri in it) it - uri else it + uri }

    fun importChosen() {
        val pick = chosen.value
        import(gallery.value.orEmpty().filter { it.uri in pick }.map { it.uri to it.takenAt })
    }

    /** Photos picked with the system picker from this screen (when Haven may not see the gallery). */
    fun importPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        fromPicker = true
        import(uris.map { it to null })
    }

    private fun import(items: List<Pair<Uri, Long?>>) {
        if (progress.value != null || items.isEmpty()) return
        progress.value = Progress(0, items.size, emptyList(), emptyList(), false)
        viewModelScope.launch {
            val refused = mutableListOf<String>()
            val imported = mutableListOf<Uri>()
            for ((i, item) in items.withIndex()) {
                val (uri, takenAt) = item
                var bytes: ByteArray? = null
                val result = try {
                    // Assigned inside the block so a cancelled read still leaves the array here to wipe.
                    withContext(Dispatchers.IO) { bytes = readBounded(uri) }
                    val b = bytes
                    if (b == null) PhotoVault.ImportResult.Refused("Larger than 64 MB or unreadable.") else vault.import(b, takenAt)
                } finally {
                    bytes?.fill(0)
                }
                when (result) {
                    is PhotoVault.ImportResult.Ok -> imported += uri
                    is PhotoVault.ImportResult.Refused -> refused += result.reason
                }
                progress.value = Progress(i + 1, items.size, refused.toList(), imported.toList(), false)
                if (result is PhotoVault.ImportResult.Refused && result.reason == LOCKED) return@launch
                // An import the user started counts as activity: the auto-lock must not cut it off
                // halfway. The vault still locks on screen-off, backgrounding and the manual lock.
                keepAwake()
            }
            progress.update { it?.copy(finished = true) }
        }
    }

    /**
     * Reads one original into memory, refusing anything over [ImageBounds.MAX_BYTES]. When the size
     * is known up front the bytes land in one exactly sized array (no growing copies left behind);
     * otherwise a buffer that is zeroed after use. Every intermediate buffer is wiped.
     */
    private fun readBounded(uri: Uri): ByteArray? {
        val chunk = ByteArray(64 * 1024)
        try {
            val known = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: AssetFileDescriptor.UNKNOWN_LENGTH
            if (known > ImageBounds.MAX_BYTES) return null
            if (known > 0) resolver.openInputStream(uri)?.use { readExactly(it, known.toInt(), chunk) }?.let { return it }
            return resolver.openInputStream(uri)?.use { readGrowing(it, chunk) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SafeLog.e("PhotoImport", "read failed", e)
            return null
        } finally {
            chunk.fill(0)
        }
    }

    private fun readExactly(input: InputStream, size: Int, chunk: ByteArray): ByteArray? {
        val out = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val n = input.read(out, filled, size - filled)
            if (n < 0) break
            filled += n
        }
        if (filled == size && input.read(chunk) < 0) return out
        // Shorter or longer than announced: read it the slow way instead of trusting the size.
        out.fill(0)
        return null
    }

    private fun readGrowing(input: InputStream, chunk: ByteArray): ByteArray? {
        val out = WipeableBuffer()
        try {
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > ImageBounds.MAX_BYTES) return null
                out.write(chunk, 0, n)
            }
            return out.toByteArray()
        } finally {
            out.wipe()
        }
    }

    /** ByteArrayOutputStream whose internal buffer can be zeroed (toByteArray() only hands out a copy). */
    private class WipeableBuffer : ByteArrayOutputStream(256 * 1024) {
        fun wipe() = buf.fill(0)
    }

    private companion object {
        const val LOCKED = "The vault was locked."
    }
}

class PhotoViewerViewModel(private val vault: PhotoVault) : ViewModel() {
    val photos: StateFlow<List<PhotoEntity>?> = vault.observeAll().map { sortedForGrid(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The decoded photo, or null when it cannot be opened (keeper closed, damaged file, unsupported). */
    suspend fun image(p: PhotoEntity, maxSide: Int, imaging: PhotoImaging): ImageBitmap? {
        val bytes = try {
            vault.original(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return try {
            withContext(Dispatchers.Default) { imaging.decode(bytes, maxSide) }
        } finally {
            bytes.fill(0)
        }
    }

    /** Writes the original to Pictures/Haven Restored. False when it could not be read or saved. */
    suspend fun restore(context: android.content.Context, p: PhotoEntity): Boolean {
        val bytes = try {
            vault.original(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        return try {
            GalleryExport.save(context, bytes, p.mimeType)
        } finally {
            bytes.fill(0)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { runCatching { vault.delete(listOf(id)) } }
    }
}
