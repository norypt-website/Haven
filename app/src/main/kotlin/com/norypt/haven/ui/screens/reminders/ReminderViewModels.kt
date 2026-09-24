package com.norypt.haven.ui.screens.reminders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.alarm.AlarmActions
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.alarm.store.OccurrenceEntity
import com.norypt.haven.alarm.store.OccurrenceKind
import com.norypt.haven.data.Reminder
import com.norypt.haven.data.TaskRepository
import com.norypt.haven.di.AppContainer
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.recurrence.OccurrenceKey
import com.norypt.haven.recurrence.OccurrenceKeys
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Repository flows throw [com.norypt.haven.session.VaultLockedException] (an IllegalStateException)
 * when the vault is closed, either when the flow is built or mid-collection after a lock. Both are
 * turned into "no data": the root guard is already navigating to the unlock screen.
 */
/** Built at collection time (not at view-model creation), so a restart after a lock reads the current database. */
private fun <T> safeFlow(build: () -> Flow<T>): Flow<T> =
    kotlinx.coroutines.flow.flow { emitAll(runCatching(build).getOrElse { emptyFlow() }) }
        .catch { e -> if (e !is IllegalStateException) throw e }

private fun ViewModel.io(block: suspend () -> Unit) = viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { block() } } }

/** Reminder titles are looked up through the vault; the test alarm has a fixed label. */
private suspend fun AppContainer.reminderLabel(cache: MutableMap<String, Reminder?>, reminderId: String): Pair<String, String> {
    if (reminderId.startsWith(AlarmActions.TEST_REMINDER_ID)) return "Test alarm" to ""
    if (TaskRepository.isFollowUpReminderId(reminderId)) {
        val t = runCatching { tasks.getTask(TaskRepository.taskIdOf(reminderId)) }.getOrNull()
        return (t?.title?.ifBlank { "Task" } ?: "Task (deleted)") to (t?.notes ?: "")
    }
    val r = cache.getOrPut(reminderId) { runCatching { reminders.get(reminderId) }.getOrNull() }
    return (r?.title?.ifBlank { "Reminder" } ?: "Reminder (deleted)") to (r?.notes ?: "")
}

/** Four quick ways to move one occurrence. Targets never land in the past. */
enum class QuickMove(val label: String) {
    PLUS_1H("Move: +1 hour"),
    PLUS_3H("Move: +3 hours"),
    TOMORROW("Move to tomorrow, same time"),
    NEXT_WEEK("Move to next week");

    fun target(base: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val n = now.withSecond(0).withNano(0)
        val b = base.withSecond(0).withNano(0)
        return when (this) {
            PLUS_1H -> maxOf(b, n).plusHours(1)
            PLUS_3H -> maxOf(b, n).plusHours(3)
            TOMORROW -> maxOf(b.toLocalDate(), n.toLocalDate()).plusDays(1).atTime(b.toLocalTime())
            NEXT_WEEK -> maxOf(b.toLocalDate(), n.toLocalDate()).plusWeeks(1).atTime(b.toLocalTime())
        }
    }
}

/** The nominal local time of an occurrence key, with any override of the series applied. */
internal fun nominalLocalOf(reminder: Reminder?, key: OccurrenceKey): LocalDateTime? {
    val override = reminder?.schedule?.overrides?.get(key)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
    return override ?: runCatching { OccurrenceKeys.toLocal(key) }.getOrNull()
}

// ---------------------------------------------------------------------------------------------

class ReminderListViewModel(private val container: AppContainer) : ViewModel() {
    data class Row(val reminder: Reminder, val next: Occurrence?)

    /** Starred first, then by next occurrence; disabled reminders last. */
    val rows: StateFlow<List<Row>> = safeFlow { container.reminders.observeAll() }
        .map { list ->
            list.map { r ->
                Row(r, if (r.enabled) runCatching { container.reminders.nextOccurrences(r, 1).firstOrNull() }.getOrNull() else null)
            }.sortedWith(
                compareBy<Row> { !it.reminder.starred }
                    .thenBy { !it.reminder.enabled }
                    .thenBy { it.next == null }
                    .thenBy { it.next?.instant }
                    .thenBy { it.reminder.title.lowercase() },
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    fun toggleSelected(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun clearSelection() { selected = emptySet() }

    fun setEnabled(id: String, enabled: Boolean) = io { container.reminders.setEnabled(id, enabled) }
    fun setStarred(id: String, starred: Boolean) = io { container.reminders.setStarred(id, starred) }
    fun deleteSelected() { val ids = selected; selected = emptySet(); io { container.reminders.deleteMany(ids) } }
    fun deleteAll() { selected = emptySet(); io { container.reminders.deleteAll() } }
}

// ---------------------------------------------------------------------------------------------

class ReminderDetailViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    val reminder: StateFlow<Reminder?> = safeFlow { container.reminders.observe(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val occurrences: StateFlow<List<Occurrence>> = reminder
        .map { r -> r?.let { runCatching { container.reminders.nextOccurrences(it, 10) }.getOrDefault(emptyList()) } ?: emptyList() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val linkedTasks: StateFlow<List<TaskEntity>> = safeFlow { container.tasks.observeAllTasks() }
        .map { list -> list.filter { it.reminderId == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val defaultMinuteOfDay: Int get() = container.alarmRuntime.prefs.defaultAlarmMinuteOfDay
    fun preview(schedule: Schedule): List<Occurrence> = container.reminders.preview(schedule, 5)
    fun tailScheduleFrom(key: OccurrenceKey): Schedule? = reminder.value?.let { runCatching { container.alarmRuntime.engine.splitSeries(it.schedule, key).tail }.getOrNull() }

    fun setEnabled(enabled: Boolean) = io { container.reminders.setEnabled(id, enabled) }
    fun setStarred(starred: Boolean) = io { container.reminders.setStarred(id, starred) }
    fun setPriority(priority: Priority) = io { container.reminders.setPriority(id, priority) }
    fun moveOccurrence(key: OccurrenceKey, to: LocalDateTime) = io { reminder.value?.let { container.reminders.moveOccurrence(it, key, to) } }
    fun quickMove(o: Occurrence, move: QuickMove) = io {
        reminder.value?.let { container.reminders.quickReschedule(it, o.key, move.target(o.nominalLocal)) }
    }
    fun deleteOccurrence(key: OccurrenceKey) = io { reminder.value?.let { container.reminders.skipOccurrence(it, key) } }
    fun editThisAndFuture(key: OccurrenceKey, title: String, notes: String, tail: Schedule, onDone: (String) -> Unit) = viewModelScope.launch {
        val r = reminder.value ?: return@launch
        val created = runCatching { withContext(Dispatchers.IO) { container.reminders.splitAndUpdateFuture(r, key, title, notes, tail) } }.getOrNull() ?: return@launch
        onDone(created.id)
    }
    fun deleteThisAndFuture(key: OccurrenceKey) = io { reminder.value?.let { container.reminders.endSeriesBefore(it, key) } }
    fun deleteSeries(onDone: () -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { container.reminders.delete(id) } }
        onDone()
    }
}

// ---------------------------------------------------------------------------------------------

class ReminderEditViewModel(private val container: AppContainer, private val id: String?, private val taskId: String?) : ViewModel() {
    var title: String by mutableStateOf("")
    var notes: String by mutableStateOf("")
    var enabled: Boolean by mutableStateOf(true)
    var priority: Priority by mutableStateOf(Priority.NONE)
    var starred: Boolean by mutableStateOf(false)
    val draft = ScheduleDraft()
    var loaded: Boolean by mutableStateOf(id == null)
        private set
    var missing: Boolean by mutableStateOf(false)
        private set
    var saving: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
    var showTitleError: Boolean by mutableStateOf(false)
    private var original: Reminder? = null

    val isEdit: Boolean get() = id != null
    val defaultMinuteOfDay: Int get() = container.alarmRuntime.prefs.defaultAlarmMinuteOfDay

    init {
        if (id != null) viewModelScope.launch {
            val r = runCatching { container.reminders.get(id) }.getOrNull()
            if (r == null) missing = true else {
                original = r
                title = r.title
                notes = r.notes
                enabled = r.enabled
                priority = r.priority
                starred = r.starred
                draft.load(r.schedule)
            }
            loaded = true
        }
    }

    fun preview(schedule: Schedule): List<Occurrence> = container.reminders.preview(schedule, 5)

    fun save(onDone: () -> Unit) {
        if (saving) return
        if (title.isBlank()) { showTitleError = true; return }
        val schedule = draft.toSchedule(defaultMinuteOfDay)
        if (schedule == null) { error = draft.problem() ?: "The schedule is not valid."; return }
        if (container.reminders.preview(schedule, 1).isEmpty()) {
            error = "This reminder would never ring: its only time is already in the past. Pick a later date or time."
            return
        }
        saving = true
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val existing = original
                    if (existing == null) {
                        val created = container.reminders.create(title, notes, schedule, enabled, priority, starred)
                        if (taskId != null) container.tasks.getTask(taskId)?.let { container.tasks.updateTask(it, reminderId = created.id) }
                    } else {
                        container.reminders.update(existing, title = title, notes = notes, schedule = schedule, enabled = enabled, priority = priority, starred = starred)
                    }
                }
            }
            saving = false
            result.onSuccess { onDone() }.onFailure { error = "Could not save the reminder. Check the schedule and try again." }
        }
    }
}

// ---------------------------------------------------------------------------------------------

class RingingViewModel(private val container: AppContainer) : ViewModel() {
    /**
     * One ringing occurrence. [followUpTask] is set for a task follow-up alert ("Unfinished task"),
     * [reminder] for a normal reminder (null for the test alarm, follow-ups and deleted reminders).
     */
    data class Item(
        val occurrence: OccurrenceEntity,
        val title: String,
        val notes: String,
        val tasks: List<TaskEntity>,
        val isTest: Boolean,
        val reminder: Reminder? = null,
        val followUpTask: TaskEntity? = null,
    ) {
        val isEarly: Boolean get() = occurrence.kind == OccurrenceKind.EARLY
        val isFollowUp: Boolean get() = TaskRepository.isFollowUpReminderId(occurrence.reminderId)
        val followUpTaskId: String get() = TaskRepository.taskIdOf(occurrence.reminderId)
    }

    private val cache = HashMap<String, Reminder?>()

    /** Null until the first emission, so the screen does not leave before it has seen anything. */
    val items: StateFlow<List<Item>?> = container.alarmRuntime.store.occurrences().observeRinging()
        .combine(safeFlow { container.tasks.observeAllTasks() }.startWithEmpty()) { ringing, tasks ->
            ringing.map { occ ->
                val isTest = occ.reminderId.startsWith(AlarmActions.TEST_REMINDER_ID)
                if (TaskRepository.isFollowUpReminderId(occ.reminderId)) {
                    val taskId = TaskRepository.taskIdOf(occ.reminderId)
                    val task = tasks.firstOrNull { it.id == taskId } ?: runCatching { container.tasks.getTask(taskId) }.getOrNull()
                    Item(occ, task?.title?.ifBlank { "Task" } ?: "Task (deleted)", task?.notes ?: "", emptyList(), false, followUpTask = task)
                } else {
                    val (title, notes) = container.reminderLabel(cache, occ.reminderId)
                    Item(occ, title, notes, tasks.filter { it.reminderId == occ.reminderId && !it.completed }, isTest, reminder = if (isTest) null else cache[occ.reminderId])
                }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val snoozePresets: List<Int> get() = container.alarmRuntime.prefs.snoozePresetsMinutes
    val defaultSnooze: Int get() = container.alarmRuntime.prefs.defaultSnoozeMinutes

    fun snooze(occurrenceId: String, minutes: Int) = io { container.alarmRuntime.actions.snooze(occurrenceId, minutes) }
    fun snoozeUntil(occurrenceId: String, epochMs: Long) = io { container.alarmRuntime.actions.snoozeUntil(occurrenceId, epochMs) }
    fun dismiss(occurrenceId: String) = io { container.alarmRuntime.actions.dismiss(occurrenceId) }
    /** Only completes the task. It never dismisses or changes the alarm. */
    fun completeTask(taskId: String) = io { container.tasks.setCompleted(taskId, true) }
    /** Follow-up alert: completing the task is what the alert asked for, so the alert is dismissed afterwards. */
    fun completeFollowUp(taskId: String, occurrenceId: String) = io {
        container.tasks.setCompleted(taskId, true)
        container.alarmRuntime.actions.dismiss(occurrenceId)
    }
    /** Moves the ringing occurrence to a new time, then dismisses the current ring. */
    fun quickReschedule(item: Item, move: QuickMove) = io {
        val key = OccurrenceKey(item.occurrence.occurrenceKey)
        val fresh = runCatching { container.reminders.get(item.occurrence.reminderId) }.getOrNull() ?: return@io
        val base = nominalLocalOf(fresh, key)
            ?: Instant.ofEpochMilli(item.occurrence.triggerAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        container.reminders.quickReschedule(fresh, key, move.target(base))
        container.alarmRuntime.actions.dismiss(item.occurrence.occurrenceId)
    }
    /** Disables the series. The current ring keeps going until the user dismisses or snoozes it. */
    fun disableReminder(reminderId: String) = io { container.reminders.setEnabled(reminderId, false) }
    fun deleteSeries(reminderId: String) = io { container.reminders.delete(reminderId) }
}

/** Emits an empty list first so [combine] produces output even while the vault flow is silent. */
private fun Flow<List<TaskEntity>>.startWithEmpty(): Flow<List<TaskEntity>> = kotlinx.coroutines.flow.flow {
    emit(emptyList())
    collect { emit(it) }
}

// ---------------------------------------------------------------------------------------------

class MissedViewModel(private val container: AppContainer) : ViewModel() {
    data class Item(val occurrence: OccurrenceEntity, val title: String, val isTest: Boolean, val followUpTaskId: String? = null)

    private val cache = HashMap<String, Reminder?>()

    /** Early "coming up" alerts never count as missed (the runtime dismisses them on timeout), so they are filtered defensively. */
    val items: StateFlow<List<Item>?> = container.alarmRuntime.store.occurrences().observeMissed()
        .map { list ->
            list.filter { it.kind != OccurrenceKind.EARLY }.map { occ ->
                val followUp = TaskRepository.isFollowUpReminderId(occ.reminderId)
                val label = container.reminderLabel(cache, occ.reminderId).first
                Item(
                    occ,
                    if (followUp) "Unfinished task: $label" else label,
                    occ.reminderId.startsWith(AlarmActions.TEST_REMINDER_ID),
                    if (followUp) TaskRepository.taskIdOf(occ.reminderId) else null,
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun acknowledge(occurrenceId: String) = io { container.alarmRuntime.actions.acknowledgeMissed(occurrenceId) }
    fun acknowledgeAll() = io { container.alarmRuntime.actions.acknowledgeAllMissed() }
}

// ---------------------------------------------------------------------------------------------

class TodayViewModel(private val container: AppContainer) : ViewModel() {
    data class Upcoming(val reminder: Reminder, val occurrence: Occurrence)
    data class DueTask(val task: TaskEntity, val due: LocalDateTime, val overdue: Boolean)
    /** One day of the "coming up this week" list. */
    data class WeekDay(val date: LocalDate, val items: List<Upcoming>, val tasks: List<DueTask> = emptyList())

    sealed interface Starred {
        data class ReminderItem(val reminder: Reminder, val next: Occurrence?) : Starred
        data class TaskItem(val task: TaskEntity, val due: LocalDateTime?) : Starred
    }

    val ringingCount: StateFlow<Int> = container.alarmRuntime.store.occurrences().observeRinging().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val missedCount: StateFlow<Int> = container.alarmRuntime.store.occurrences().observeMissed().map { list -> list.count { it.kind != OccurrenceKind.EARLY } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val reminders: Flow<List<Reminder>> = safeFlow { container.reminders.observeAll() }
    private val tasks: Flow<List<TaskEntity>> = safeFlow { container.tasks.observeAllTasks() }

    /** Starred reminders (by next occurrence) followed by starred open tasks (by due date). */
    val starred: StateFlow<List<Starred>> = reminders.startWithEmptyReminders()
        .combine(tasks.startWithEmpty()) { rs, ts ->
            val r = rs.filter { it.starred }
                .map { Starred.ReminderItem(it, if (it.enabled) runCatching { container.reminders.nextOccurrences(it, 1).firstOrNull() }.getOrNull() else null) }
                .sortedWith(compareBy<Starred.ReminderItem> { it.next == null }.thenBy { it.next?.instant }.thenBy { it.reminder.title.lowercase() })
            val t = ts.filter { it.starred && !it.completed }
                .map { Starred.TaskItem(it, it.dueLocal?.let { d -> runCatching { LocalDateTime.parse(d) }.getOrNull() }) }
                .sortedWith(compareBy<Starred.TaskItem> { it.due == null }.thenBy { it.due }.thenBy { it.task.title.lowercase() })
            r + t
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val upcoming: StateFlow<List<Upcoming>> = reminders
        .map { list ->
            val now = Instant.now()
            val horizon = now.plus(Duration.ofHours(24))
            list.filter { it.enabled }.flatMap { r ->
                runCatching { container.reminders.nextOccurrences(r, 3, now) }.getOrDefault(emptyList())
                    .filter { it.instant.isBefore(horizon) }
                    .map { Upcoming(r, it) }
            }.sortedBy { it.occurrence.instant }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Reminder occurrences from 24 hours to 7 days ahead and open tasks due after today within 7 days, grouped by day. */
    val week: StateFlow<List<WeekDay>> = kotlinx.coroutines.flow.combine(reminders, tasks) { list, ts ->
            val now = Instant.now()
            val from = now.plus(Duration.ofHours(24))
            val to = now.plus(Duration.ofDays(7))
            val zone = ZoneId.systemDefault()
            val byDay = list.filter { it.enabled }.flatMap { r ->
                runCatching { container.reminders.nextOccurrences(r, 10, from) }.getOrDefault(emptyList())
                    .filter { !it.instant.isBefore(from) && it.instant.isBefore(to) }
                    .map { Upcoming(r, it) }
            }.sortedBy { it.occurrence.instant }
                .take(20)
                .groupBy { it.occurrence.instant.atZone(zone).toLocalDate() }
            val endOfToday = LocalDate.now().plusDays(1).atStartOfDay()
            val weekEnd = LocalDate.now().plusDays(7).atStartOfDay()
            val tasksByDay = ts.filter { !it.completed }.mapNotNull { t ->
                val due = t.dueLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (!due.isBefore(endOfToday) && due.isBefore(weekEnd)) DueTask(t, due, false) else null
            }.sortedWith(compareBy<DueTask> { it.due }.thenBy { it.task.title.lowercase() })
                .groupBy { it.due.toLocalDate() }
            (byDay.keys + tasksByDay.keys).sorted().map { date -> WeekDay(date, byDay[date].orEmpty(), tasksByDay[date].orEmpty()) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Overdue tasks first (oldest first), then tasks due later today. */
    val dueTasks: StateFlow<List<DueTask>> = tasks
        .map { list ->
            val now = LocalDateTime.now()
            val endOfToday = LocalDate.now().plusDays(1).atStartOfDay()
            list.filter { !it.completed }.mapNotNull { t ->
                val due = t.dueLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (due.isBefore(endOfToday)) DueTask(t, due, due.isBefore(now)) else null
            }.sortedWith(compareBy<DueTask> { !it.overdue }.thenBy { it.due }.thenBy { it.task.title.lowercase() })
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var readiness: AlarmReadiness.Report? by mutableStateOf(null)
        private set

    suspend fun checkReadiness() {
        readiness = runCatching { withContext(Dispatchers.IO) { container.readiness.check(container.alarmRuntime) } }.getOrNull()
    }

    fun completeTask(id: String, completed: Boolean) = io { container.tasks.setCompleted(id, completed) }
    fun quickMove(u: Upcoming, move: QuickMove) = io {
        val fresh = runCatching { container.reminders.get(u.reminder.id) }.getOrNull() ?: return@io
        container.reminders.quickReschedule(fresh, u.occurrence.key, move.target(u.occurrence.nominalLocal))
    }
    fun lockNow() = viewModelScope.launch { container.session.lock() }
}

private fun Flow<List<Reminder>>.startWithEmptyReminders(): Flow<List<Reminder>> = kotlinx.coroutines.flow.flow {
    emit(emptyList())
    collect { emit(it) }
}
