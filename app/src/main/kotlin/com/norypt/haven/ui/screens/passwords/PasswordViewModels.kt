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
import com.norypt.haven.ui.components.EntryColor
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
        if (keeperNeedsUnlock(open, state is SessionState.Unlocked, nav.currentBackStackEntry?.destination?.route)) {
            nav.navigate(Routes.PASSWORD_UNLOCK) {
                popUpTo(Routes.PASSWORDS) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    if (open) content()
}

/** The keeper screens composed through [PasswordVaultGate]; the keeper unlock screen is not one of them. */
private val GatedRoutes = setOf(Routes.PASSWORDS, Routes.PASSWORD_DETAIL, Routes.PASSWORD_EDIT, Routes.PASSWORD_GENERATOR)

/**
 * Whether the gate should send the user to the keeper unlock screen. Only a keeper screen that is
 * the current destination asks: one that is merely fading out (the lock button navigates to Today
 * and then locks) must not pull the user back into the keeper.
 */
internal fun keeperNeedsUnlock(open: Boolean, appUnlocked: Boolean, currentRoute: String?): Boolean =
    !open && appUnlocked && currentRoute in GatedRoutes

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

    private val _attemptsLeft = MutableStateFlow(session.attemptsLeft())
    /** Wrong passwords left before the automatic erase; null when it is off or nothing has gone wrong. */
    val attemptsLeft: StateFlow<Int?> = _attemptsLeft

    private fun initialStatus(): Status {
        val wait = throttle.remainingDelayMs()
        return if (wait > 0) Status.Wait(System.currentTimeMillis() + wait, "Too many wrong passwords.") else Status.Idle
    }

    /** Takes ownership of [password] and wipes it once the attempt has finished. */
    fun unlock(password: CharArray) {
        if (_status.value is Status.Busy) { password.fill('\u0000'); return }
        _status.value = Status.Busy
        viewModelScope.launch {
            val result = try {
                withContext(NonCancellable) { session.unlockPasswords(password) }
            } catch (e: Exception) {
                UnlockResult.Failed("Could not open the keeper (${e.javaClass.simpleName}).")
            } finally {
                password.fill('\u0000')
            }
            val now = System.currentTimeMillis()
            _status.value = when (result) {
                UnlockResult.Success -> Status.Done
                is UnlockResult.WrongPassword -> {
                    _attemptsLeft.value = result.attemptsLeft
                    if (result.waitMs > 0) Status.Wait(now + result.waitMs, "Wrong password.") else Status.Message("Wrong password.")
                }
                is UnlockResult.Throttled -> Status.Wait(now + result.waitMs, "Too many wrong passwords.")
                // The vault is gone and Haven is back at first run; navigation moves to Welcome.
                UnlockResult.Erased -> Status.Message("Haven erased this vault after too many wrong passwords.")
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

data class EntryTotals(val all: Int = 0, val starred: Int = 0)

sealed interface ListFilter {
    data object All : ListFilter
    data object Starred : ListFilter
    data object Unfiled : ListFilter
    data class Folder(val id: String) : ListFilter
}

internal fun List<PasswordEntryEntity>.filteredBy(listFilter: ListFilter): List<PasswordEntryEntity> = when (listFilter) {
    ListFilter.All -> this
    ListFilter.Starred -> filter { it.starred }
    ListFilter.Unfiled -> filter { it.folderId == null }
    is ListFilter.Folder -> filter { it.folderId == listFilter.id }
}

/** The list as shown: starred entries first, each part keeping the incoming order. */
internal data class EntrySections(val starred: List<PasswordEntryEntity>, val others: List<PasswordEntryEntity>)

internal fun sectionsOf(entries: List<PasswordEntryEntity>): EntrySections {
    val (starred, others) = entries.partition { it.starred }
    return EntrySections(starred, others)
}

/** What the star action does for a selection: star them all, unless every one is already starred. */
internal fun starTargetFor(entries: Collection<PasswordEntryEntity>): Boolean = entries.any { !it.starred }

/**
 * Search/filter/selection state for the entry list. Nothing here is saved state: the query and
 * selection live only in memory and vanish with the destination.
 */
class PasswordsListViewModel(private val repo: PasswordRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    private val _filter = MutableStateFlow<ListFilter>(ListFilter.All)
    val filter: StateFlow<ListFilter> = _filter
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

    private fun source(query: String, filter: ListFilter): Flow<List<PasswordEntryEntity>> = flow {
        val base = if (query.isEmpty()) repo.observeEntries() else repo.search(query)
        emitAll(base.map { it.filteredBy(filter) })
    }

    /** Counts for the All and Starred chips, over the whole keeper whatever the filter. */
    val totals: StateFlow<EntryTotals> = combine(
        flow { emitAll(repo.observeCount()) },
        flow { emitAll(repo.observeStarredCount()) },
    ) { all, starred -> EntryTotals(all = all, starred = starred) }
        .catch { emit(EntryTotals()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), EntryTotals())

    /** A new query changes what is visible, so a selection made before it is dropped (as with filters). */
    fun setQuery(q: String) {
        if (q == _query.value) return
        _query.value = q
        clearSelection()
    }
    fun setFilter(f: ListFilter) { _filter.value = f; clearSelection() }

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
            if ((_filter.value as? ListFilter.Folder)?.id == id) _filter.value = ListFilter.All
        }
    }

    fun deleteSelected() {
        val ids = _selected.value; if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch { runCatching { repo.deleteMany(ids) } }
    }

    /** Stars every selected entry, or removes the star from all of them when every one is already starred. */
    fun starSelected() {
        val ids = _selected.value; if (ids.isEmpty()) return
        val star = starTargetFor(entries.value.filter { it.id in ids })
        clearSelection()
        viewModelScope.launch { runCatching { repo.setStarred(ids, star) } }
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
/**
 * One save per edit session. After a save has gone through the editor stays on screen while it
 * fades out; a tap in that moment must not store a second copy (with the already wiped password).
 */
internal class SaveOnce {
    var busy: Boolean by mutableStateOf(false); private set
    var done: Boolean by mutableStateOf(false); private set

    /** Starts a save, or returns false while one is running or after one has succeeded. */
    fun begin(): Boolean {
        if (busy || done) return false
        busy = true
        return true
    }

    fun end(succeeded: Boolean) {
        busy = false
        if (succeeded) done = true
    }
}

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
    var starred by mutableStateOf(false)
    /** [EntryColor.AUTO] or a palette id. */
    var color by mutableStateOf(EntryColor.AUTO)

    private val gate = SaveOnce()
    val saving: Boolean get() = gate.busy
    val saved: Boolean get() = gate.done
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
                starred = e.starred; color = e.color
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
        if (gate.busy || gate.done) return
        if (title.isBlank()) { titleError = true; return }
        titleError = false
        if (!gate.begin()) return
        viewModelScope.launch {
            val ok = runCatching {
                withContext(NonCancellable) {
                    val current = existing
                    if (current == null) repo.create(title, website, username, password, notes, folderId, starred, color)
                    else repo.update(current, title, website, username, password, notes, folderId, starred, color)
                }
            }.isSuccess
            if (ok) wipe()
            gate.end(succeeded = ok)
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
