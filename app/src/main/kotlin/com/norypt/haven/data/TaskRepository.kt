package com.norypt.haven.data

import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.content.Priority
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.storage.content.TaskListEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.UUID

class TaskRepository(private val session: VaultSession, private val alarms: com.norypt.haven.alarm.AlarmRuntime) {
    private fun lists() = session.content().taskLists()
    private fun tasks() = session.content().tasks()

    /** Search titles and notes locally. */
    fun search(query: String): Flow<List<TaskEntity>> = tasks().search(query.replace("%", "").replace("_", " ").trim())

    fun observeLists(): Flow<List<TaskListEntity>> = lists().observeAll()
    fun observeTasks(listId: String): Flow<List<TaskEntity>> = tasks().observeByList(listId)
    fun observeAllTasks(): Flow<List<TaskEntity>> = tasks().observeAll()
    fun observeTask(id: String): Flow<TaskEntity?> = tasks().observe(id)
    fun observeOpenCount(): Flow<Int> = tasks().observeOpenCount()
    suspend fun getList(id: String): TaskListEntity? = withContext(Dispatchers.IO) { lists().byId(id) }
    suspend fun getTask(id: String): TaskEntity? = withContext(Dispatchers.IO) { tasks().byId(id) }
    suspend fun tasksForReminder(reminderId: String): List<TaskEntity> = withContext(Dispatchers.IO) { tasks().byReminder(reminderId) }

    suspend fun createList(name: String): TaskListEntity = withContext(Dispatchers.IO) {
        val e = TaskListEntity(UUID.randomUUID().toString(), name.trim(), lists().nextPosition(), System.currentTimeMillis())
        lists().upsert(e); e
    }

    suspend fun renameList(id: String, name: String) = withContext(Dispatchers.IO) {
        lists().byId(id)?.let { lists().upsert(it.copy(name = name.trim())) }
    }

    /** Deletes the list and every task in it (FK cascade). Reminders linked to those tasks stay. */
    suspend fun deleteList(id: String) = withContext(Dispatchers.IO) { lists().delete(id) }

    suspend fun createTask(
        listId: String,
        title: String,
        notes: String = "",
        due: LocalDateTime? = null,
        reminderId: String? = null,
        priority: Priority = Priority.NONE,
        starred: Boolean = false,
        followUpMinutes: Int? = null,
    ): TaskEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val e = TaskEntity(
            UUID.randomUUID().toString(), listId, title.trim(), notes, false, null, due?.withSecond(0)?.withNano(0)?.toString(), reminderId,
            tasks().nextPosition(listId), now, now, priority.level, starred, followUpMinutes,
        )
        tasks().upsert(e)
        syncFollowUp(e)
        e
    }

    suspend fun updateTask(
        task: TaskEntity,
        title: String = task.title,
        notes: String = task.notes,
        due: LocalDateTime? = task.dueLocal?.let(LocalDateTime::parse),
        reminderId: String? = task.reminderId,
        listId: String = task.listId,
        priority: Priority = Priority.of(task.priority),
        starred: Boolean = task.starred,
        followUpMinutes: Int? = task.followUpMinutes,
    ) = withContext(Dispatchers.IO) {
        val e = task.copy(
            title = title.trim(), notes = notes, dueLocal = due?.withSecond(0)?.withNano(0)?.toString(), reminderId = reminderId, listId = listId,
            priority = priority.level, starred = starred, followUpMinutes = followUpMinutes, updatedAt = System.currentTimeMillis(),
        )
        tasks().upsert(e)
        syncFollowUp(e)
    }

    suspend fun setStarred(id: String, starred: Boolean) = withContext(Dispatchers.IO) { tasks().setStarred(id, starred, System.currentTimeMillis()) }
    suspend fun setPriority(id: String, priority: Priority) = withContext(Dispatchers.IO) { tasks().setPriority(id, priority.level, System.currentTimeMillis()) }

    /** Completing a task never dismisses, disables or deletes any reminder. It does cancel the task's own follow-up alert. */
    suspend fun setCompleted(id: String, completed: Boolean) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        tasks().setCompleted(id, completed, if (completed) now else null, now)
        tasks().byId(id)?.let { syncFollowUp(it) }
    }

    /**
     * Follow-up alert for an unfinished task: a one-time alarm at due + followUpMinutes, kept in the
     * alarm store under the reserved id "task:<taskId>". Removed when the task is completed, loses its
     * due date or turns the option off. The ringing screen offers "Remind again" (snooze) and
     * "Mark complete" for it.
     */
    private fun syncFollowUp(task: TaskEntity) {
        val id = followUpReminderId(task.id)
        val due = task.dueLocal?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        val minutes = task.followUpMinutes
        if (task.completed || due == null || minutes == null || minutes <= 0) {
            alarms.scheduler.removeReminder(id)
            return
        }
        val at = due.plusMinutes(minutes.toLong()).withSecond(0).withNano(0)
        if (at.isBefore(LocalDateTime.now())) { alarms.scheduler.removeReminder(id); return }
        val schedule = com.norypt.haven.recurrence.Schedule(startDate = at.toLocalDate().toString(), time = at.toLocalTime().toString())
        alarms.scheduler.upsertReminder(id, com.norypt.haven.recurrence.ScheduleJson.encode(schedule), enabled = true, revision = task.updatedAt)
    }

    /** Re-arms follow-ups for every eligible task (after unlock/restore). */
    suspend fun syncAllFollowUps() = withContext(Dispatchers.IO) { tasks().withFollowUp().forEach { syncFollowUp(it) } }

    suspend fun move(ids: Collection<String>, listId: String) = withContext(Dispatchers.IO) { tasks().moveToList(ids.toList(), listId, System.currentTimeMillis()) }
    suspend fun deleteTask(id: String) = withContext(Dispatchers.IO) { tasks().delete(id); alarms.scheduler.removeReminder(followUpReminderId(id)) }
    suspend fun deleteTasks(ids: Collection<String>) = withContext(Dispatchers.IO) { tasks().deleteAll(ids.toList()); ids.forEach { alarms.scheduler.removeReminder(followUpReminderId(it)) } }
    suspend fun deleteCompleted(listId: String?) = withContext(Dispatchers.IO) { if (listId == null) tasks().deleteCompleted() else tasks().deleteCompletedInList(listId) }
    suspend fun deleteAllTasks() = withContext(Dispatchers.IO) {
        val all = tasks().all()
        tasks().deleteEverything()
        all.forEach { alarms.scheduler.removeReminder(followUpReminderId(it.id)) }
    }

    companion object {
        const val FOLLOW_UP_PREFIX = "task:"
        fun followUpReminderId(taskId: String) = FOLLOW_UP_PREFIX + taskId
        fun isFollowUpReminderId(reminderId: String) = reminderId.startsWith(FOLLOW_UP_PREFIX)
        fun taskIdOf(reminderId: String) = reminderId.removePrefix(FOLLOW_UP_PREFIX)
        /** Follow-up choices in minutes with labels. */
        val FOLLOW_UP_CHOICES: List<Pair<Int, String>> = listOf(30 to "30 minutes later", 60 to "1 hour later", 180 to "3 hours later", 1440 to "Next day")
    }
}
