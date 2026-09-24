package com.norypt.haven.ui.screens.tasks

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.data.Reminder
import com.norypt.haven.di.AppContainer
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.session.VaultLockedException
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.storage.content.TaskListEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/*
 * Task screens keep no content in SavedStateHandle, rememberSaveable or navigation arguments.
 * Form text lives in plain ViewModel state so it survives rotation but never leaves process memory.
 */

/**
 * Repository flows throw [VaultLockedException] (and Room may throw IllegalStateException on a
 * closed handle) once the vault is locked. The root guard already redirects to Unlock, so the
 * screens simply stop receiving data instead of crashing.
 */
private fun <T> safeFlow(block: () -> Flow<T>): Flow<T> =
    (try { block() } catch (e: VaultLockedException) { emptyFlow() })
        .catch { e -> if (e !is VaultLockedException && e !is IllegalStateException) throw e }

private fun <T> Flow<T>.asState(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

internal abstract class TaskBaseViewModel : ViewModel() {
    /** Runs a repository action; a lock in the middle of it is swallowed (the root guard takes over). */
    protected fun act(block: suspend () -> Unit): Job = viewModelScope.launch {
        try { block() } catch (e: VaultLockedException) { /* vault closed: nothing to do here */ }
    }
}

// ---------------------------------------------------------------- overview

internal data class ListSummary(val list: TaskListEntity, val open: Int, val total: Int, val starred: Int, val overdue: Int) {
    val completed: Int get() = total - open
}

/** One search result together with the name of the list it lives in. */
internal data class TaskSearchHit(val task: TaskEntity, val listName: String)

@OptIn(ExperimentalCoroutinesApi::class)
internal class TasksOverviewViewModel(private val container: AppContainer) : TaskBaseViewModel() {
    private val tasks get() = container.tasks
    private val listsFlow: Flow<List<TaskListEntity>> = safeFlow { tasks.observeLists() }

    /** `null` while the first values are loading, so the empty state is not flashed. */
    val summaries: StateFlow<List<ListSummary>?> = combine(listsFlow, safeFlow { tasks.observeAllTasks() }) { lists, all ->
        val byList = all.groupBy { it.listId }
        lists.map { l ->
            val t = byList[l.id].orEmpty()
            ListSummary(l, open = t.count { !it.completed }, total = t.size, starred = t.count { it.starred }, overdue = t.count { it.isOverdue() })
        }
    }.asState(this, null)

    /** The screen debounces typing and pushes the query here; it is never persisted. */
    private val query = MutableStateFlow("")
    fun setQuery(q: String) { query.value = q.trim() }

    /** `null` when there is no query; otherwise every matching task across all lists with its list name. */
    val results: StateFlow<List<TaskSearchHit>?> = query.flatMapLatest { q ->
        if (q.isEmpty()) flowOf(null)
        else combine(safeFlow { tasks.search(q) }, listsFlow) { hits, lists ->
            val names = lists.associate { it.id to it.name }
            hits.map { TaskSearchHit(it, names[it.listId] ?: "Unknown list") }
        }
    }.asState(this, null)

    fun createList(name: String) = act { if (name.isNotBlank()) tasks.createList(name) }
    fun renameList(id: String, name: String) = act { if (name.isNotBlank()) tasks.renameList(id, name) }
    /** Removes the list and its tasks only; linked reminders are kept by the repository. */
    fun deleteList(id: String) = act { tasks.deleteList(id) }
    fun deleteCompletedEverywhere() = act { tasks.deleteCompleted(null) }
    fun deleteAllTasks() = act { tasks.deleteAllTasks() }
}

// ---------------------------------------------------------------- one list

internal class TaskListViewModel(private val container: AppContainer, private val listId: String) : TaskBaseViewModel() {
    private val tasks get() = container.tasks

    val lists: StateFlow<List<TaskListEntity>> = safeFlow { tasks.observeLists() }.asState(this, emptyList())
    val list: StateFlow<TaskListEntity?> = lists.map { l -> l.find { it.id == listId } }.asState(this, null)
    /** `null` while loading. */
    val items: StateFlow<List<TaskEntity>?> = safeFlow { tasks.observeTasks(listId) }.asState(this, null)

    fun createTask(title: String) = act { if (title.isNotBlank()) tasks.createTask(listId, title) }
    /** Only the completion flag changes; a linked reminder is never touched. */
    fun setCompleted(id: String, completed: Boolean) = act { tasks.setCompleted(id, completed) }
    fun move(ids: Collection<String>, targetListId: String) = act { if (ids.isNotEmpty()) tasks.move(ids, targetListId) }
    fun deleteTasks(ids: Collection<String>) = act { if (ids.isNotEmpty()) tasks.deleteTasks(ids) }
    fun deleteCompletedHere() = act { tasks.deleteCompleted(listId) }
}

// ---------------------------------------------------------------- detail

/** The reminder a task points at. [reminder] is null when the id no longer resolves. */
internal data class LinkedReminder(val reminderId: String, val reminder: Reminder?, val next: Occurrence?)

internal data class TaskDetailUi(
    val loaded: Boolean,
    val task: TaskEntity?,
    val listName: String?,
    val linked: LinkedReminder?,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class TaskDetailViewModel(private val container: AppContainer, private val id: String) : TaskBaseViewModel() {
    private val tasks get() = container.tasks
    private val reminders get() = container.reminders

    private val taskFlow: Flow<TaskEntity?> = safeFlow { tasks.observeTask(id) }
    private val listsFlow: Flow<List<TaskListEntity>> = safeFlow { tasks.observeLists() }
    private val linkedFlow: Flow<LinkedReminder?> = taskFlow.map { it?.reminderId }.distinctUntilChanged().flatMapLatest { rid ->
        if (rid == null) flowOf(null)
        else safeFlow { reminders.observe(rid) }.map { r ->
            LinkedReminder(rid, r, r?.let { runCatching { reminders.nextOccurrences(it, 1).firstOrNull() }.getOrNull() })
        }
    }

    val ui: StateFlow<TaskDetailUi> = combine(taskFlow, listsFlow, linkedFlow) { task, lists, linked ->
        TaskDetailUi(loaded = true, task = task, listName = lists.find { it.id == task?.listId }?.name, linked = linked)
    }.asState(this, TaskDetailUi(loaded = false, task = null, listName = null, linked = null))

    /** For the "Link existing reminder" chooser. */
    val allReminders: StateFlow<List<Reminder>> = safeFlow { reminders.observeAll() }.asState(this, emptyList())

    fun setCompleted(completed: Boolean) = act { tasks.setCompleted(id, completed) }
    fun setStarred(starred: Boolean) = act { tasks.setStarred(id, starred) }
    fun setPriority(priority: Priority) = act { tasks.setPriority(id, priority) }
    /** Follow-up alert in minutes after the due time; null turns it off. The repository re-arms or cancels the alarm. */
    fun setFollowUp(minutes: Int?) = act { tasks.getTask(id)?.let { tasks.updateTask(it, followUpMinutes = minutes) } }
    fun link(reminderId: String) = act { tasks.getTask(id)?.let { tasks.updateTask(it, reminderId = reminderId) } }
    /** Clears the task's pointer only; the reminder itself is left exactly as it is. */
    fun unlink() = act { tasks.getTask(id)?.let { tasks.updateTask(it, reminderId = null) } }
    /** Deletes the task only. The screen navigates away when the observed task becomes null. */
    fun delete() = act { tasks.deleteTask(id) }
}

// ---------------------------------------------------------------- edit

internal class TaskEditViewModel(private val container: AppContainer, private val taskId: String?, initialListId: String?) : TaskBaseViewModel() {
    private val tasks get() = container.tasks

    var title by mutableStateOf("")
    var notes by mutableStateOf("")
    var listId by mutableStateOf(initialListId)
    var dueDate by mutableStateOf<LocalDate?>(null)
    var dueTime by mutableStateOf<LocalTime?>(null)
    var reminderId by mutableStateOf<String?>(null)
    var priority by mutableStateOf(Priority.NONE)
    var starred by mutableStateOf(false)
    /** Minutes after due at which to ring again if still incomplete; null = off. Only meaningful with a due date. */
    var followUpMinutes by mutableStateOf<Int?>(null)
    var showTitleError by mutableStateOf(false)
    var saving by mutableStateOf(false)
        private set
    /** False until an existing task has been read into the form (immediately true for a new task). */
    var loaded by mutableStateOf(taskId == null)
        private set
    var notFound by mutableStateOf(false)
        private set

    val lists: StateFlow<List<TaskListEntity>> = safeFlow { tasks.observeLists() }.asState(this, emptyList())
    val reminders: StateFlow<List<Reminder>> = safeFlow { container.reminders.observeAll() }.asState(this, emptyList())

    init {
        if (taskId != null) act {
            val t = tasks.getTask(taskId)
            if (t == null) {
                notFound = true
            } else {
                title = t.title
                notes = t.notes
                listId = t.listId
                parseDue(t.dueLocal)?.let { dueDate = it.toLocalDate(); dueTime = it.toLocalTime() }
                reminderId = t.reminderId
                priority = t.priorityEnum()
                starred = t.starred
                followUpMinutes = t.followUpMinutes
            }
            loaded = true
        }
    }

    /** The chosen list if it still exists, otherwise the first list. */
    fun effectiveListId(available: List<TaskListEntity>): String? =
        listId?.takeIf { chosen -> available.any { it.id == chosen } } ?: available.firstOrNull()?.id

    fun clearDue() { dueDate = null; dueTime = null }

    /** Returns false (and shows the title error) when the form is not valid. */
    fun save(targetListId: String, onSaved: () -> Unit): Boolean {
        val t = title.trim()
        if (t.isEmpty()) { showTitleError = true; return false }
        if (saving) return true
        saving = true
        val due = dueDate?.atTime(dueTime ?: DefaultDueTime)
        val rid = reminderId
        val n = notes
        val p = priority
        val star = starred
        // A follow-up only makes sense with a due date; store nothing otherwise.
        val followUp = followUpMinutes?.takeIf { due != null && it > 0 }
        act {
            try {
                if (taskId == null) {
                    tasks.createTask(targetListId, t, n, due, rid, p, star, followUp)
                } else {
                    val fresh = tasks.getTask(taskId)
                    if (fresh != null) {
                        tasks.updateTask(fresh, title = t, notes = n, due = due, reminderId = rid, listId = targetListId, priority = p, starred = star, followUpMinutes = followUp)
                    } else {
                        tasks.createTask(targetListId, t, n, due, rid, p, star, followUp)
                    }
                }
                onSaved()
            } finally {
                saving = false
            }
        }
        return true
    }
}
