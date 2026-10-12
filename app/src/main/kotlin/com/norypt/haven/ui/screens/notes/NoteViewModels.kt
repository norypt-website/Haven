package com.norypt.haven.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.data.NoteRepository
import com.norypt.haven.storage.content.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Repository flows throw (an IllegalStateException) once the vault is closed; that reads as "no
 * data" while the root guard navigates to the unlock screen. Built at collection time.
 */
private fun <T> safeFlow(build: () -> Flow<T>): Flow<T> =
    flow { emitAll(runCatching(build).getOrElse { emptyFlow() }) }
        .catch { e -> if (e !is IllegalStateException) throw e }

/** The notes tab: every note (for the hero counts) and the notes matching the search. Null until loaded. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesListViewModel(private val repo: NoteRepository) : ViewModel() {
    private val query = MutableStateFlow("")

    val all: StateFlow<List<NoteEntity>?> = safeFlow { repo.observeAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Clearing the search shows everything at once; typing waits for a short pause. */
    val notes: StateFlow<List<NoteEntity>?> = query.debounce { if (it.isBlank()) 0L else 150L }
        .flatMapLatest { q -> if (q.isBlank()) safeFlow { repo.observeAll() } else safeFlow { repo.search(q) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(q: String) { query.value = q }
}

/**
 * Editor state with autosave: 600 ms after the last keystroke, when the editor is left, and once
 * more when the view model goes (on [appScope], which outlives it). Saves and the delete run one
 * at a time, so an autosave already on its way can never bring a deleted note back.
 */
class NoteEditViewModel(
    private val repo: NoteRepository,
    initialId: String?,
    private val appScope: CoroutineScope,
) : ViewModel() {
    var id: String? = initialId; private set
    val title = MutableStateFlow("")
    val body = MutableStateFlow("")
    val loaded = MutableStateFlow(initialId == null)

    private val _saved = MutableStateFlow(false)
    /** True once the latest text is stored; false again as soon as it changes. */
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    /** When the note was last stored; null for a note that is not stored yet. */
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    private val writes = Mutex()
    private var pending: Job? = null
    private var deleted = false
    /** The text as last read from or written to the database. */
    private var lastStored: Pair<String, String>? = null

    init {
        if (initialId != null) viewModelScope.launch {
            val note = runCatching { repo.get(initialId) }.getOrNull()
            if (note != null) {
                title.value = note.title; body.value = note.body; _updatedAt.value = note.updatedAt
                lastStored = note.title to note.body
            } else {
                // Gone (deleted elsewhere): edit it as a new note instead of recreating an empty one.
                id = null
            }
            loaded.value = true
        }
    }

    fun onTitle(v: String) { title.value = v; schedule() }
    fun onBody(v: String) { body.value = v; schedule() }

    private fun schedule() {
        _saved.value = false
        pending?.cancel()
        pending = viewModelScope.launch { delay(600); saveNow() }
    }

    suspend fun saveNow() {
        if (!loaded.value) return
        withContext(NonCancellable) {
            writes.withLock {
                if (deleted) return@withLock
                val text = title.value to body.value
                if (text != lastStored) {
                    id = repo.save(id, text.first, text.second) ?: return@withLock
                    lastStored = text
                    _updatedAt.value = System.currentTimeMillis()
                }
                // Typing during the save makes this text stale; the next autosave marks it saved.
                if ((title.value to body.value) == text) _saved.value = true
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        pending?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) {
                writes.withLock {
                    deleted = true
                    id?.let { runCatching { repo.delete(it) } }
                    id = null
                }
            }
            onDone()
        }
    }

    override fun onCleared() {
        pending?.cancel()
        // viewModelScope is already cancelled here; the app scope finishes the last save.
        appScope.launch { saveNow() }
    }
}
