package com.norypt.haven.storage.passwords

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import android.content.Context
import com.norypt.haven.storage.EncryptedDatabaseOpener
import kotlinx.coroutines.flow.Flow
import java.io.File

@Entity(tableName = "folders")
public data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "entries",
    foreignKeys = [ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folder_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("folder_id")],
)
public data class PasswordEntryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    val title: String,
    val website: String,
    val username: String,
    val password: String,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
public interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY position, name") public fun observeAll(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders ORDER BY position, name") public suspend fun all(): List<FolderEntity>
    @Upsert public suspend fun upsert(folder: FolderEntity)
    @Upsert public suspend fun upsertAll(folders: List<FolderEntity>)
    @Query("DELETE FROM folders WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM folders") public suspend fun deleteEverything()
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM folders") public suspend fun nextPosition(): Int
}

@Dao
public interface PasswordEntryDao {
    @Query("SELECT * FROM entries ORDER BY title COLLATE NOCASE") public fun observeAll(): Flow<List<PasswordEntryEntity>>
    @Query("SELECT * FROM entries WHERE folder_id = :folderId ORDER BY title COLLATE NOCASE")
    public fun observeByFolder(folderId: String): Flow<List<PasswordEntryEntity>>
    @Query("SELECT * FROM entries WHERE folder_id IS NULL ORDER BY title COLLATE NOCASE")
    public fun observeUnfiled(): Flow<List<PasswordEntryEntity>>
    /** Local search inside the decrypted connection; the query text never leaves the process. */
    @Query("SELECT * FROM entries WHERE title LIKE '%' || :q || '%' OR website LIKE '%' || :q || '%' OR username LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE")
    public fun search(q: String): Flow<List<PasswordEntryEntity>>
    @Query("SELECT * FROM entries") public suspend fun all(): List<PasswordEntryEntity>
    @Query("SELECT * FROM entries WHERE id = :id") public suspend fun byId(id: String): PasswordEntryEntity?
    @Query("SELECT * FROM entries WHERE id = :id") public fun observe(id: String): Flow<PasswordEntryEntity?>
    @Upsert public suspend fun upsert(entry: PasswordEntryEntity)
    @Upsert public suspend fun upsertAll(entries: List<PasswordEntryEntity>)
    @Query("DELETE FROM entries WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM entries WHERE id IN (:ids)") public suspend fun deleteAll(ids: List<String>)
    @Query("DELETE FROM entries") public suspend fun deleteEverything()
    @Query("SELECT COUNT(*) FROM entries") public fun observeCount(): Flow<Int>
}

/** The password keeper. A separate file, a separate random key, a separate hardware key. */
@Database(entities = [FolderEntity::class, PasswordEntryEntity::class], version = 1, exportSchema = true)
public abstract class PasswordDatabase : RoomDatabase() {
    public abstract fun folders(): FolderDao
    public abstract fun entries(): PasswordEntryDao

    public companion object {
        public const val FILE_NAME: String = "passwords"
        public fun file(context: Context): File = EncryptedDatabaseOpener.databaseFile(context, FILE_NAME)
        public fun open(context: Context, rawKey: ByteArray): PasswordDatabase =
            EncryptedDatabaseOpener.open(context, PasswordDatabase::class.java, file(context), rawKey)
    }
}
