package com.norypt.haven.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.data.NoteRepository
import com.norypt.haven.session.VaultSession
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
 * What the editor needs from storage and the session. The screen wires it to the app container;
 * tests pass fakes.
 */
class NoteEditDeps(
    val get: suspend (String) -> NoteEntity?,
    val save: suspend (id: String?, title: String, body: String) -> String?,
    val delete: suspend (String) -> Unit,
    /** The session's current generation (see VaultSession.generation). */
    val generation: () -> Int,
    /** Typing is user activity: it keeps the inactivity lock from closing the vault mid-sentence. */
    val onActivity: () -> Unit,
    /** Registers work to run before a lock closes the vault; returns the unregister function. */
    val addBeforeLock: (suspend () -> Unit) -> () -> Unit,
    /** Outlives the view model: it finishes the last save once the editor has gone. */
    val appScope: CoroutineScope,
    /** Whether the content vault is open. A save refused while it is closed is a lock racing the editor, not an error. */
    val vaultOpen: () -> Boolean = { true },
) {
    companion object {
        fun of(repo: NoteRepository, session: VaultSession, onActivity: () -> Unit, appScope: CoroutineScope) = NoteEditDeps(
            get = repo::get,
            save = repo::save,
            delete = { repo.delete(it) },
            generation = { session.generation.value },
            onActivity = onActivity,
            addBeforeLock = session::addBeforeLock,
            appScope = appScope,
            vaultOpen = { session.contentOrNull() != null },
        )
    }
}

/** A view model belongs to the session generation it was created in; after a lock its text is stale. */
internal fun isSameSession(createdIn: Int, current: Int): Boolean = createdIn == current

/**
 * Editor state with autosave: 600 ms after the last keystroke, when the editor is left, before a
 * lock closes the vault, and once more when the view model goes (on the app scope, which outlives
 * it). Saves and the delete run one at a time, so an autosave already on its way can never bring
 * a deleted note back. Nothing is written once the session has moved on (a lock, a restore): a
 * view model left over from before would otherwise write old text into the reopened vault.
 */
class NoteEditViewModel(private val deps: NoteEditDeps, initialId: String?) : ViewModel() {
    private val createdIn = deps.generation()
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
    private val unregisterBeforeLock = deps.addBeforeLock { saveNow() }

    private val _readFailed = MutableStateFlow(false)
    /** The note could not be read. The editor stays unloaded, so nothing can be saved over it. */
    val readFailed: StateFlow<Boolean> = _readFailed.asStateFlow()

    init {
        if (initialId != null) viewModelScope.launch {
            val note = runCatching { deps.get(initialId) }.getOrElse {
                // Never loaded means never saved: an empty editor must not overwrite the stored note.
                _readFailed.value = true
                return@launch
            }
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

    fun onTitle(v: String) { title.value = NoteLogic.singleLine(v); schedule() }
    fun onBody(v: String) { body.value = v; schedule() }

    private fun schedule() {
        deps.onActivity()
        _saved.value = false
        pending?.cancel()
        pending = viewModelScope.launch { delay(600); saveNow() }
    }

    private val _saveFailed = MutableStateFlow(false)
    /** The last save was refused while the vault was open (a database error); cleared by the next good save. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    /**
     * Stores the current text. False only when storing failed while the vault is open, so the
     * editor must stay open and say so; a save skipped because the vault closed, the session moved
     * on or the note was deleted counts as done, as does a new note without text.
     */
    suspend fun saveNow(): Boolean {
        if (!loaded.value) return true
        return withContext(NonCancellable) {
            writes.withLock {
                if (deleted || !isSameSession(createdIn, deps.generation())) return@withLock true
                val text = title.value to body.value
                if (text != lastStored) {
                    val stored = deps.save(id, text.first, text.second)
                    if (stored == null) {
                        val failed = NoteLogic.shouldStore(isNew = id == null, text.first, text.second) && deps.vaultOpen()
                        _saveFailed.value = failed
                        return@withLock !failed
                    }
                    id = stored
                    lastStored = text
                    _updatedAt.value = System.currentTimeMillis()
                }
                _saveFailed.value = false
                // Typing during the save makes this text stale; the next autosave marks it saved.
                if ((title.value to body.value) == text) _saved.value = true
                true
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        pending?.cancel()
        viewModelScope.launch {
            withContext(NonCancellable) {
                writes.withLock {
                    deleted = true
                    id?.let { runCatching { deps.delete(it) } }
                    id = null
                }
            }
            onDone()
        }
    }

    override fun onCleared() {
        pending?.cancel()
        unregisterBeforeLock()
        // viewModelScope is already cancelled here; the app scope finishes the last save.
        deps.appScope.launch { saveNow() }
    }
}
