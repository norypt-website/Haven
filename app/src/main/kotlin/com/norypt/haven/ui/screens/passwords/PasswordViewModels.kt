package com.norypt.haven.ui.screens.passwords

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.norypt.haven.data.PasswordRepository
import com.norypt.haven.di.AppContainer
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.security.SessionState
import com.norypt.haven.session.UnlockResult
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.ui.LocalAppContainer
import com.norypt.haven.ui.navigation.Routes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Session gate
// ---------------------------------------------------------------------------------------------

/**
 * Every screen of the password keeper composes its content through this gate. When the password
 * vault is not open it navigates to the unlock screen and renders nothing, so no keeper UI is ever
 * composed against a closed database. A full lock (state Locked) is handled by HavenApp instead.
 */
@Composable
internal fun PasswordVaultGate(nav: NavHostController, content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val state by container.session.state.collectAsState()
    val open = state is SessionState.Unlocked && container.session.passwordsOrNull() != null
    LaunchedEffect(open, state) {
        if (!open && state is SessionState.Unlocked) {
            nav.navigate(Routes.PASSWORD_UNLOCK) {
                popUpTo(Routes.PASSWORDS) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    if (open) content()
}

/** Clipboard copy for secrets: a neutral label that never says which field was copied. */
internal fun copySecret(container: AppContainer, value: CharSequence) {
    container.clipboard.copy(label = "Haven", secret = value)
}

internal fun copiedMessage(container: AppContainer): String =
    "Copied. Haven clears the clipboard after about ${container.prefs.clipboardClearSeconds} seconds unless you copy something else. Apps that already read it keep it."

// ---------------------------------------------------------------------------------------------
// Unlock
// ---------------------------------------------------------------------------------------------

class PasswordUnlockViewModel(private val session: VaultSession, private val throttle: GuessThrottle) : ViewModel() {
    sealed interface Status {
        data object Idle : Status
        data object Busy : Status
        data class Message(val text: String) : Status
        data class Wait(val untilMs: Long, val prefix: String) : Status
        data object Done : Status
    }

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<Status> = _status

    private fun initialStatus(): Status {
        val wait = throttle.remainingDelayMs()
        return if (wait > 0) Status.Wait(System.currentTimeMillis() + wait, "Too many attempts.") else Status.Idle
    }

    /** Takes ownership of [password] and wipes it once the attempt has finished. */
    fun unlock(password: CharArray) {
        if (_status.value is Status.Busy) { password.fill('\u0000'); return }
        _status.value = Status.Busy
        viewModelScope.launch {
            val result = try {
                withContext(NonCancellable) { session.unlockPasswords(password) }
            } finally {
                password.fill('\u0000')
            }
            val now = System.currentTimeMillis()
            _status.value = when (result) {
                UnlockResult.Success -> Status.Done
                is UnlockResult.WrongPassword ->
                    if (result.waitMs > 0) Status.Wait(now + result.waitMs, "Wrong password.") else Status.Message("Wrong password.")
                is UnlockResult.Throttled -> Status.Wait(now + result.waitMs, "Too many attempts.")
                UnlockResult.DeviceAuthRequired -> Status.Message("Confirm your device screen lock, then try again.")
                is UnlockResult.Unrecoverable -> Status.Message(result.reason)
                is UnlockResult.Failed -> Status.Message(result.message)
            }
        }
    }

    fun waitOver() { if (_status.value is Status.Wait) _status.value = Status.Idle }
    fun consumeDone() { if (_status.value is Status.Done) _status.value = Status.Idle }
}

@Composable
internal fun rememberPasswordUnlockViewModel(container: AppContainer): PasswordUnlockViewModel =
    viewModel(factory = viewModelFactory { initializer { PasswordUnlockViewModel(container.session, container.throttle) } })

// ---------------------------------------------------------------------------------------------
// List
// ---------------------------------------------------------------------------------------------

sealed interface FolderFilter {
    data object All : FolderFilter
    data object Unfiled : FolderFilter
    data class Folder(val id: String) : FolderFilter
}

/**
 * Search/filter/selection state for the entry list. Nothing here is saved state: the query and
 * selection live only in memory and vanish with the destination.
 */
class PasswordsListViewModel(private val repo: PasswordRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow<FolderFilter>(FolderFilter.All)
    val filter: StateFlow<FolderFilter> = _filter
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    // The repository resolves the DAO on every call, so flows are built lazily inside `flow {}`:
    // a re-opened keeper (lock, unlock again) gets the live connection, and a closed one is
    // reported as an empty list instead of crashing the collector.
    val folders: StateFlow<List<FolderEntity>> = flow { emitAll(repo.observeFolders()) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<PasswordEntryEntity>> = combine(
        _query.debounce { if (it.isBlank()) 0L else 200L }.map { it.trim() }.distinctUntilChanged(),
        _filter,
    ) { q, f -> q to f }
        .flatMapLatest { (q, f) -> source(q, f) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    private fun source(query: String, filter: FolderFilter): Flow<List<PasswordEntryEntity>> = flow {
        val base: Flow<List<PasswordEntryEntity>> = if (query.isEmpty()) {
            when (filter) {
                FolderFilter.All -> repo.observeEntries()
                FolderFilter.Unfiled -> repo.observeByFolder(null)
                is FolderFilter.Folder -> repo.observeByFolder(filter.id)
            }
        } else {
            repo.search(query).map { list ->
                when (filter) {
                    FolderFilter.All -> list
                    FolderFilter.Unfiled -> list.filter { it.folderId == null }
                    is FolderFilter.Folder -> list.filter { it.folderId == filter.id }
                }
            }
        }
        emitAll(base)
    }

    fun setQuery(q: String) { _query.value = q }
    fun setFilter(f: FolderFilter) { _filter.value = f; clearSelection() }

    fun toggleSelected(id: String) { _selected.update { if (id in it) it - id else it + id } }
    fun clearSelection() { _selected.value = emptySet() }

    fun createFolder(name: String, onCreated: (FolderEntity) -> Unit = {}) {
        val n = name.trim(); if (n.isEmpty()) return
        viewModelScope.launch { runCatching { repo.createFolder(n) }.onSuccess(onCreated) }
    }

    fun renameFolder(id: String, name: String) {
        val n = name.trim(); if (n.isEmpty()) return
        viewModelScope.launch { runCatching { repo.renameFolder(id, n) } }
    }

    /** Entries in the folder are kept and become unfiled. */
    fun deleteFolder(id: String) {
        viewModelScope.launch {
            runCatching { repo.deleteFolder(id) }
            if ((_filter.value as? FolderFilter.Folder)?.id == id) _filter.value = FolderFilter.All
        }
    }

    fun deleteSelected() {
        val ids = _selected.value; if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch { runCatching { repo.deleteMany(ids) } }
    }

    /** Moves every selected entry into [folderId] (null = unfiled). */
    fun moveSelected(folderId: String?) {
        val ids = _selected.value; if (ids.isEmpty()) return
        val targets = entries.value.filter { it.id in ids }
        clearSelection()
        viewModelScope.launch {
            targets.forEach { e ->
                if (e.folderId != folderId) runCatching { repo.update(e, e.title, e.website, e.username, e.password, e.notes, folderId) }
            }
        }
    }
}

@Composable
internal fun rememberPasswordsListViewModel(container: AppContainer): PasswordsListViewModel =
    viewModel(factory = viewModelFactory { initializer { PasswordsListViewModel(container.passwords) } })

// ---------------------------------------------------------------------------------------------
// Edit
// ---------------------------------------------------------------------------------------------

/**
 * Form state for one edit session. It lives in plain memory for as long as the edit destination
 * is on the back stack (so a trip to the generator does not lose the form) and is wiped when the
 * destination goes away. Nothing is written to saved state.
 */
class PasswordEditViewModel(private val repo: PasswordRepository, val id: String?) : ViewModel() {
    var loaded by mutableStateOf(id == null); private set
    var missing by mutableStateOf(false); private set
    var existing: PasswordEntryEntity? by mutableStateOf(null); private set

    var title by mutableStateOf("")
    var website by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var notes by mutableStateOf("")
    var folderId: String? by mutableStateOf(null)

    var saving by mutableStateOf(false); private set
    var saved by mutableStateOf(false); private set
    var titleError by mutableStateOf(false); private set

    val folders: StateFlow<List<FolderEntity>> = flow { emitAll(repo.observeFolders()) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    init {
        if (id != null) viewModelScope.launch {
            val e = runCatching { repo.get(id) }.getOrNull()
            if (e == null) { missing = true } else {
                existing = e
                title = e.title; website = e.website; username = e.username; password = e.password; notes = e.notes; folderId = e.folderId
            }
            loaded = true
        }
    }

    /** Pulls a generated password out of the hand-off slot, if the generator left one. */
    fun applyHandoff() {
        val chars = GeneratedPasswordHandoff.take() ?: return
        password = String(chars)
        chars.fill('\u0000')
    }

    fun createFolder(name: String) {
        val n = name.trim(); if (n.isEmpty()) return
        viewModelScope.launch { runCatching { repo.createFolder(n) }.onSuccess { folderId = it.id } }
    }

    fun save() {
        if (saving) return
        if (title.isBlank()) { titleError = true; return }
        titleError = false
        saving = true
        viewModelScope.launch {
            val ok = runCatching {
                withContext(NonCancellable) {
                    val current = existing
                    if (current == null) repo.create(title, website, username, password, notes, folderId)
                    else repo.update(current, title, website, username, password, notes, folderId)
                }
            }.isSuccess
            saving = false
            if (ok) { wipe(); saved = true }
        }
    }

    private fun wipe() {
        password = ""
        existing = null
    }

    override fun onCleared() {
        wipe()
        GeneratedPasswordHandoff.clear()
    }
}

@Composable
internal fun rememberPasswordEditViewModel(container: AppContainer, id: String?): PasswordEditViewModel =
    viewModel(key = "password-edit-${id ?: "new"}", factory = viewModelFactory { initializer { PasswordEditViewModel(container.passwords, id) } })
