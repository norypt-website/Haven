package com.norypt.haven.storage.content

import android.content.Context
import androidx.room.Database
import androidx.room.RoomDatabase
import com.norypt.haven.storage.EncryptedDatabaseOpener
import java.io.File

/** Reminders, task lists, tasks and vault-scoped settings. Encrypted with the content vault key. */
@Database(
    entities = [ReminderEntity::class, TaskListEntity::class, TaskEntity::class, VaultMetaEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
public abstract class ContentDatabase : RoomDatabase() {
    public abstract fun reminders(): ReminderDao
    public abstract fun taskLists(): TaskListDao
    public abstract fun tasks(): TaskDao
    public abstract fun meta(): VaultMetaDao

    public companion object {
        public const val FILE_NAME: String = "content"
        public fun file(context: Context): File = EncryptedDatabaseOpener.databaseFile(context, FILE_NAME)
        public fun open(context: Context, rawKey: ByteArray): ContentDatabase =
            EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file(context), rawKey)
    }
}
