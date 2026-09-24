package com.norypt.haven.data

import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.recurrence.Occurrence
import com.norypt.haven.recurrence.OccurrenceKey
import com.norypt.haven.recurrence.RecurrenceEngine
import com.norypt.haven.recurrence.Schedule
import com.norypt.haven.recurrence.ScheduleJson
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

data class Reminder(
    val id: String,
    val title: String,
    val notes: String,
    val enabled: Boolean,
    val schedule: Schedule,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val priority: Priority = Priority.NONE,
    val starred: Boolean = false,
)

private fun ReminderEntity.toModel() = Reminder(id, title, notes, enabled, ScheduleJson.decode(scheduleJson), revision, createdAt, updatedAt, Priority.of(priority), starred)

/**
 * Reminders live in the content vault; their timing is mirrored to the alarm runtime's Direct
 * Boot store on every change. Content (title, notes) never leaves the vault.
 */
class ReminderRepository(private val session: VaultSession, private val alarms: AlarmRuntime, private val engine: RecurrenceEngine = RecurrenceEngine()) {
    private fun dao() = session.content().reminders()

    fun observeAll(): Flow<List<Reminder>> = dao().observeAll().map { list -> list.mapNotNull { runCatching { it.toModel() }.getOrNull() } }
    fun observe(id: String): Flow<Reminder?> = dao().observe(id).map { it?.let { e -> runCatching { e.toModel() }.getOrNull() } }
    suspend fun get(id: String): Reminder? = withContext(Dispatchers.IO) { dao().byId(id)?.toModel() }
    suspend fun getMany(ids: Collection<String>): List<Reminder> = withContext(Dispatchers.IO) { dao().byIds(ids.toList()).map { it.toModel() } }

    fun nextOccurrences(reminder: Reminder, count: Int, after: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> =
        engine.occurrencesAfter(reminder.schedule, after, zone, count)

    fun preview(schedule: Schedule, count: Int = 5, after: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> =
        runCatching { engine.occurrencesAfter(schedule, after, zone, count) }.getOrDefault(emptyList())

    suspend fun create(title: String, notes: String, schedule: Schedule, enabled: Boolean = true, priority: Priority = Priority.NONE, starred: Boolean = false): Reminder = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = ReminderEntity(UUID.randomUUID().toString(), title.trim(), notes, enabled, ScheduleJson.encode(schedule), 1L, now, now, priority.level, starred)
        dao().upsert(entity)
        alarms.scheduler.upsertReminder(entity.id, entity.scheduleJson, entity.enabled, entity.revision)
        entity.toModel()
    }

    /** Edits the entire series (title/notes/schedule). Bumps the revision so stale alarm deliveries are ignored. */
    suspend fun update(
        reminder: Reminder,
        title: String = reminder.title,
        notes: String = reminder.notes,
        schedule: Schedule = reminder.schedule,
        enabled: Boolean = reminder.enabled,
        priority: Priority = reminder.priority,
        starred: Boolean = reminder.starred,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = ReminderEntity(reminder.id, title.trim(), notes, enabled, ScheduleJson.encode(schedule), reminder.revision + 1, reminder.createdAt, now, priority.level, starred)
        dao().upsert(entity)
        alarms.scheduler.upsertReminder(entity.id, entity.scheduleJson, entity.enabled, entity.revision)
    }

    suspend fun setStarred(id: String, starred: Boolean) = withContext(Dispatchers.IO) { dao().setStarred(id, starred, System.currentTimeMillis()) }
    suspend fun setPriority(id: String, priority: Priority) = withContext(Dispatchers.IO) { dao().setPriority(id, priority.level, System.currentTimeMillis()) }

    /**
     * Quick reschedule of one occurrence to a new local date-time (e.g. "+1 hour", "tomorrow same time").
     * For one-time reminders the start date/time itself moves; for series the occurrence is overridden.
     */
    suspend fun quickReschedule(reminder: Reminder, key: OccurrenceKey, newLocal: LocalDateTime) {
        val s = reminder.schedule
        if (s.rule.frequency == com.norypt.haven.recurrence.Frequency.ONCE && s.extraDates.isEmpty()) {
            val t = newLocal.withSecond(0).withNano(0)
            update(reminder, schedule = s.copy(startDate = t.toLocalDate().toString(), time = t.toLocalTime().toString(), overrides = emptyMap(), skippedKeys = emptySet()))
        } else {
            moveOccurrence(reminder, key, newLocal)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val current = dao().byId(id) ?: return@withContext
        dao().setEnabled(id, enabled, System.currentTimeMillis())
        alarms.scheduler.upsertReminder(id, current.scheduleJson, enabled, current.revision + 1)
    }

    /** Edit one occurrence: move it (override) without touching the rest of the series. */
    suspend fun moveOccurrence(reminder: Reminder, key: OccurrenceKey, newLocal: LocalDateTime) {
        val s = reminder.schedule
        update(reminder, schedule = s.copy(overrides = s.overrides + (key to newLocal.withSecond(0).withNano(0).toString())))
    }

    /** Delete one occurrence only. */
    suspend fun skipOccurrence(reminder: Reminder, key: OccurrenceKey) {
        val s = reminder.schedule
        update(reminder, schedule = s.copy(skippedKeys = s.skippedKeys + key, overrides = s.overrides - key))
    }

    /** Edit this and all future occurrences: the series is split; the tail becomes a new reminder with the new values. */
    suspend fun splitAndUpdateFuture(reminder: Reminder, fromKey: OccurrenceKey, newTitle: String, newNotes: String, newTailSchedule: Schedule): Reminder {
        val split = engine.splitSeries(reminder.schedule, fromKey)
        update(reminder, schedule = split.head)
        return create(newTitle, newNotes, newTailSchedule)
    }

    /** Delete this and future occurrences: the head keeps everything before [fromKey]. */
    suspend fun endSeriesBefore(reminder: Reminder, fromKey: OccurrenceKey) {
        val split = engine.splitSeries(reminder.schedule, fromKey)
        update(reminder, schedule = split.head)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        session.content().tasks().unlinkReminder(id, System.currentTimeMillis())
        dao().delete(id)
        alarms.scheduler.removeReminder(id)
    }

    suspend fun deleteMany(ids: Collection<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        ids.forEach { session.content().tasks().unlinkReminder(it, now) }
        dao().deleteAll(ids.toList())
        ids.forEach { alarms.scheduler.removeReminder(it) }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val all = dao().all()
        val now = System.currentTimeMillis()
        all.forEach { session.content().tasks().unlinkReminder(it.id, now) }
        dao().deleteEverything()
        all.forEach { alarms.scheduler.removeReminder(it.id) }
    }
}
