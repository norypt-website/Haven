package com.norypt.haven.storage.content

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A reminder. The schedule (timing only) is stored as recurrence-engine JSON. */
@Entity(tableName = "reminders")
public data class ReminderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String,
    val enabled: Boolean,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    /** Incremented on every change that affects scheduling; mirrored into the alarm store. */
    val revision: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** 0 = none, 1 = low, 2 = medium, 3 = high (see [Priority]). */
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(name = "starred", defaultValue = "0") val starred: Boolean = false,
)

/** Importance levels shared by reminders and tasks. Always shown with a label/icon, never colour alone. */
public enum class Priority(public val level: Int) {
    NONE(0), LOW(1), MEDIUM(2), HIGH(3);

    public companion object {
        public fun of(level: Int): Priority = entries.firstOrNull { it.level == level } ?: NONE
    }
}

@Entity(tableName = "task_lists")
public data class TaskListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(entity = TaskListEntity::class, parentColumns = ["id"], childColumns = ["list_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("list_id"), Index("reminder_id")],
)
public data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    val title: String,
    val notes: String,
    val completed: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    /** ISO local date-time "yyyy-MM-ddTHH:mm" (no zone: a due date is a calendar concept). */
    @ColumnInfo(name = "due_local") val dueLocal: String?,
    /** Optional linked reminder. Dismissing that reminder never completes the task. */
    @ColumnInfo(name = "reminder_id") val reminderId: String?,
    val position: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "priority", defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(name = "starred", defaultValue = "0") val starred: Boolean = false,
    /**
     * Optional follow-up: minutes after the due time at which Haven rings again if the task is
     * still incomplete ("remind again when an important task remains incomplete"). Null = off.
     */
    @ColumnInfo(name = "follow_up_minutes") val followUpMinutes: Int? = null,
)

/** Small encrypted key/value area for vault-scoped settings (e.g. backup key, default alarm time). */
@Entity(tableName = "vault_meta")
public data class VaultMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
