package com.norypt.haven.alarm.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * DIRECT BOOT SCHEDULE STORE.
 *
 * Lives in device-encrypted (DE) storage so alarms can be restored after a reboot before the
 * user unlocks the phone. It is protected by Android's device encryption only, NOT by the Haven
 * password. Therefore it must never hold titles, notes, list names, passwords or vault keys.
 * It holds random identifiers, trigger times, recurrence/time-zone data and snooze state.
 *
 * Consequence (documented in docs/THREAT_MODEL.md): the OS or a privileged attacker can observe
 * that Haven is installed and WHEN its reminders fire, but not WHAT they are.
 */
@Entity(tableName = "scheduled_reminders")
public data class ScheduledReminderEntity(
    @PrimaryKey @ColumnInfo(name = "reminder_id") val reminderId: String,
    /** recurrence-engine ScheduleJson (timing only). */
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    val enabled: Boolean,
    /** Mirrors the vault revision; alarm deliveries carrying an older revision are ignored. */
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

public enum class OccurrenceState { PENDING, RINGING, SNOOZED, DISMISSED, MISSED }

/** MAIN = the reminder itself; EARLY = a "coming up" alert [earlyMinutes] before the main occurrence. */
public enum class OccurrenceKind { MAIN, EARLY }

@Entity(
    tableName = "occurrences",
    indices = [Index("reminder_id"), Index("state"), Index("trigger_at")],
)
public data class OccurrenceEntity(
    /** "<reminderId>|<occurrenceKey>" — stable, unique, content-free. */
    @PrimaryKey @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "reminder_id") val reminderId: String,
    @ColumnInfo(name = "occurrence_key") val occurrenceKey: String,
    /** Epoch millis of the nominal trigger (or the snooze deadline while SNOOZED). */
    @ColumnInfo(name = "trigger_at") val triggerAt: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    val state: OccurrenceState,
    val revision: Long,
    /** Durable snooze deadline (wall clock) for reboot recovery. */
    @ColumnInfo(name = "snooze_until") val snoozeUntil: Long?,
    /** Elapsed-realtime deadline, valid only while [snoozeBootToken] matches the current boot. */
    @ColumnInfo(name = "snooze_elapsed_deadline") val snoozeElapsedDeadline: Long?,
    @ColumnInfo(name = "snooze_boot_token") val snoozeBootToken: String?,
    @ColumnInfo(name = "snooze_count") val snoozeCount: Int,
    /** When ringing started (elapsed realtime) — for the ring timeout. */
    @ColumnInfo(name = "ring_started_elapsed") val ringStartedElapsed: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "kind", defaultValue = "MAIN") val kind: OccurrenceKind = OccurrenceKind.MAIN,
    /** For EARLY occurrences: how many minutes before the main occurrence. */
    @ColumnInfo(name = "early_minutes", defaultValue = "0") val earlyMinutes: Int = 0,
)

@Dao
public interface ScheduledReminderDao {
    @Query("SELECT * FROM scheduled_reminders") public fun all(): List<ScheduledReminderEntity>
    @Query("SELECT * FROM scheduled_reminders WHERE reminder_id = :id") public fun byId(id: String): ScheduledReminderEntity?
    @Upsert public fun upsert(entity: ScheduledReminderEntity)
    @Query("DELETE FROM scheduled_reminders WHERE reminder_id = :id") public fun delete(id: String)
    @Query("DELETE FROM scheduled_reminders") public fun deleteAll()
}

@Dao
public interface OccurrenceDao {
    @Query("SELECT * FROM occurrences") public fun all(): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE occurrence_id = :id") public fun byId(id: String): OccurrenceEntity?
    @Query("SELECT * FROM occurrences WHERE reminder_id = :reminderId") public fun byReminder(reminderId: String): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state IN (:states) ORDER BY trigger_at") public fun byStates(states: List<OccurrenceState>): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state IN ('PENDING','SNOOZED') ORDER BY trigger_at LIMIT 1") public fun nextArmed(): OccurrenceEntity?
    @Query("SELECT * FROM occurrences WHERE state IN ('PENDING','SNOOZED') ORDER BY trigger_at LIMIT :limit") public fun upcoming(limit: Int): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state = 'RINGING'") public fun ringing(): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state = 'MISSED' ORDER BY trigger_at DESC") public fun missed(): List<OccurrenceEntity>
    @Query("SELECT * FROM occurrences WHERE state = 'MISSED' ORDER BY trigger_at DESC") public fun observeMissed(): kotlinx.coroutines.flow.Flow<List<OccurrenceEntity>>
    @Query("SELECT * FROM occurrences WHERE state = 'RINGING'") public fun observeRinging(): kotlinx.coroutines.flow.Flow<List<OccurrenceEntity>>
    @Query("SELECT * FROM occurrences WHERE state IN ('PENDING','SNOOZED') ORDER BY trigger_at") public fun observeUpcoming(): kotlinx.coroutines.flow.Flow<List<OccurrenceEntity>>
    @Upsert public fun upsert(entity: OccurrenceEntity)
    @Query("DELETE FROM occurrences WHERE occurrence_id = :id") public fun delete(id: String)
    @Query("DELETE FROM occurrences WHERE reminder_id = :reminderId") public fun deleteByReminder(reminderId: String)
    @Query("DELETE FROM occurrences WHERE reminder_id = :reminderId AND state IN ('PENDING')") public fun deletePendingByReminder(reminderId: String)
    @Query("DELETE FROM occurrences WHERE state IN ('DISMISSED','MISSED') AND updated_at < :before") public fun pruneFinished(before: Long)
    @Query("DELETE FROM occurrences") public fun deleteAll()
}

@Database(
    entities = [ScheduledReminderEntity::class, OccurrenceEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
public abstract class AlarmStoreDatabase : RoomDatabase() {
    public abstract fun reminders(): ScheduledReminderDao
    public abstract fun occurrences(): OccurrenceDao

    public companion object {
        public const val FILE_NAME: String = "alarm_schedule.db"

        /** Always opened from the device-protected storage context. */
        public fun open(context: Context): AlarmStoreDatabase {
            val de = context.createDeviceProtectedStorageContext()
            return Room.databaseBuilder(de, AlarmStoreDatabase::class.java, FILE_NAME)
                .allowMainThreadQueries() // receivers run short queries on goAsync threads; the executor serialises writes
                .setJournalMode(JournalMode.TRUNCATE)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
        }
    }
}
