package com.norypt.haven.storage.content

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
public interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY updated_at DESC")
    public fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders")
    public suspend fun all(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    public suspend fun byId(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE id = :id")
    public fun observe(id: String): Flow<ReminderEntity?>

    @Query("SELECT * FROM reminders WHERE id IN (:ids)")
    public suspend fun byIds(ids: List<String>): List<ReminderEntity>

    @Upsert public suspend fun upsert(reminder: ReminderEntity)
    @Upsert public suspend fun upsertAll(reminders: List<ReminderEntity>)

    @Query("UPDATE reminders SET enabled = :enabled, revision = revision + 1, updated_at = :now WHERE id = :id")
    public suspend fun setEnabled(id: String, enabled: Boolean, now: Long)

    @Query("UPDATE reminders SET starred = :starred, updated_at = :now WHERE id = :id")
    public suspend fun setStarred(id: String, starred: Boolean, now: Long)
    @Query("UPDATE reminders SET priority = :priority, updated_at = :now WHERE id = :id")
    public suspend fun setPriority(id: String, priority: Int, now: Long)
    @Query("DELETE FROM reminders WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM reminders WHERE id IN (:ids)") public suspend fun deleteAll(ids: List<String>)
    @Query("DELETE FROM reminders") public suspend fun deleteEverything()
    @Query("SELECT COUNT(*) FROM reminders") public fun observeCount(): Flow<Int>
}

@Dao
public interface TaskListDao {
    @Query("SELECT * FROM task_lists ORDER BY position, created_at")
    public fun observeAll(): Flow<List<TaskListEntity>>
    @Query("SELECT * FROM task_lists ORDER BY position, created_at") public suspend fun all(): List<TaskListEntity>
    @Query("SELECT * FROM task_lists WHERE id = :id") public suspend fun byId(id: String): TaskListEntity?
    @Upsert public suspend fun upsert(list: TaskListEntity)
    @Upsert public suspend fun upsertAll(lists: List<TaskListEntity>)
    @Query("DELETE FROM task_lists WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM task_lists") public suspend fun deleteEverything()
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM task_lists") public suspend fun nextPosition(): Int
}

@Dao
public interface TaskDao {
    @Query("SELECT * FROM tasks WHERE list_id = :listId ORDER BY completed, position, created_at")
    public fun observeByList(listId: String): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks ORDER BY completed, due_local IS NULL, due_local, position")
    public fun observeAll(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks") public suspend fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") public suspend fun byId(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE id = :id") public fun observe(id: String): Flow<TaskEntity?>
    @Query("SELECT * FROM tasks WHERE reminder_id = :reminderId") public suspend fun byReminder(reminderId: String): List<TaskEntity>
    @Upsert public suspend fun upsert(task: TaskEntity)
    @Upsert public suspend fun upsertAll(tasks: List<TaskEntity>)
    @Query("UPDATE tasks SET completed = :completed, completed_at = :completedAt, updated_at = :now WHERE id = :id")
    public suspend fun setCompleted(id: String, completed: Boolean, completedAt: Long?, now: Long)
    @Query("UPDATE tasks SET starred = :starred, updated_at = :now WHERE id = :id")
    public suspend fun setStarred(id: String, starred: Boolean, now: Long)
    @Query("UPDATE tasks SET priority = :priority, updated_at = :now WHERE id = :id")
    public suspend fun setPriority(id: String, priority: Int, now: Long)
    /** Local search across title and notes (runs inside the decrypted connection). */
    @Query("SELECT * FROM tasks WHERE title LIKE '%' || :q || '%' OR notes LIKE '%' || :q || '%' ORDER BY completed, due_local IS NULL, due_local, position")
    public fun search(q: String): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE completed = 0 AND follow_up_minutes IS NOT NULL AND due_local IS NOT NULL")
    public suspend fun withFollowUp(): List<TaskEntity>
    @Query("UPDATE tasks SET list_id = :listId, updated_at = :now WHERE id IN (:ids)")
    public suspend fun moveToList(ids: List<String>, listId: String, now: Long)
    @Query("UPDATE tasks SET reminder_id = NULL, updated_at = :now WHERE reminder_id = :reminderId")
    public suspend fun unlinkReminder(reminderId: String, now: Long)
    @Query("DELETE FROM tasks WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM tasks WHERE id IN (:ids)") public suspend fun deleteAll(ids: List<String>)
    @Query("DELETE FROM tasks WHERE completed = 1 AND list_id = :listId") public suspend fun deleteCompletedInList(listId: String)
    @Query("DELETE FROM tasks WHERE completed = 1") public suspend fun deleteCompleted()
    @Query("DELETE FROM tasks") public suspend fun deleteEverything()
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM tasks WHERE list_id = :listId") public suspend fun nextPosition(listId: String): Int
    @Query("SELECT COUNT(*) FROM tasks WHERE completed = 0") public fun observeOpenCount(): Flow<Int>
}

@Dao
public interface VaultMetaDao {
    @Query("SELECT value FROM vault_meta WHERE `key` = :key") public suspend fun get(key: String): String?
    @Query("SELECT value FROM vault_meta WHERE `key` = :key") public fun observe(key: String): Flow<String?>
    @Upsert public suspend fun put(entry: VaultMetaEntity)
    @Query("DELETE FROM vault_meta WHERE `key` = :key") public suspend fun remove(key: String)
    @Query("SELECT * FROM vault_meta") public suspend fun all(): List<VaultMetaEntity>
}
