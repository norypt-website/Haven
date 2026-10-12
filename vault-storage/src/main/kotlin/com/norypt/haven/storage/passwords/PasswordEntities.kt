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
    /** Starred entries are listed first. Starring is not an edit: it leaves [updatedAt] alone. */
    @ColumnInfo(name = "starred", defaultValue = "0") val starred: Boolean = false,
    /** Tile colour: 0 = automatic (worked out from the title), otherwise a palette id chosen in the editor. */
    @ColumnInfo(name = "color", defaultValue = "0") val color: Int = 0,
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
    @Query("UPDATE entries SET starred = :starred WHERE id IN (:ids)") public suspend fun setStarred(ids: List<String>, starred: Boolean)
    @Query("DELETE FROM entries WHERE id = :id") public suspend fun delete(id: String)
    @Query("DELETE FROM entries WHERE id IN (:ids)") public suspend fun deleteAll(ids: List<String>)
    @Query("DELETE FROM entries") public suspend fun deleteEverything()
    @Query("SELECT COUNT(*) FROM entries") public fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM entries WHERE starred = 1") public fun observeStarredCount(): Flow<Int>
}

/**
 * One hidden photo. [dek] is the photo's own random key: the files under the photo directory are
 * unreadable without it, so deleting this row (secure_delete is on) erases the photo.
 */
@Entity(tableName = "photos")
public class PhotoEntity(
    @PrimaryKey public val id: String,
    @ColumnInfo(name = "file_id") public val fileId: String,
    public val dek: ByteArray,
    @ColumnInfo(name = "mime_type") public val mimeType: String,
    @ColumnInfo(name = "byte_size") public val byteSize: Long,
    public val width: Int,
    public val height: Int,
    @ColumnInfo(name = "taken_at") public val takenAt: Long?,
    @ColumnInfo(name = "added_at") public val addedAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is PhotoEntity && other.id == id && other.fileId == fileId && other.addedAt == addedAt
    override fun hashCode(): Int = id.hashCode()
}

@Dao
public interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY added_at DESC, id") public fun observeAll(): Flow<List<PhotoEntity>>
    @Query("SELECT COUNT(*) FROM photos") public fun observeCount(): Flow<Int>
    @Query("SELECT * FROM photos ORDER BY added_at DESC, id") public suspend fun all(): List<PhotoEntity>
    @Query("SELECT * FROM photos WHERE id = :id") public suspend fun byId(id: String): PhotoEntity?
    @androidx.room.Insert public suspend fun insert(photo: PhotoEntity)
    @androidx.room.Insert public suspend fun insertAll(photos: List<PhotoEntity>)
    @Query("DELETE FROM photos WHERE id IN (:ids)") public suspend fun deleteAll(ids: List<String>)
    @Query("DELETE FROM photos") public suspend fun deleteEverything()
}

/** The password keeper. A separate file, a separate random key, a separate hardware key. */
@Database(
    entities = [FolderEntity::class, PasswordEntryEntity::class, PhotoEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2), androidx.room.AutoMigration(from = 2, to = 3)],
)
public abstract class PasswordDatabase : RoomDatabase() {
    public abstract fun folders(): FolderDao
    public abstract fun entries(): PasswordEntryDao
    public abstract fun photos(): PhotoDao

    public companion object {
        public const val FILE_NAME: String = "passwords"
        public fun file(context: Context): File = EncryptedDatabaseOpener.databaseFile(context, FILE_NAME)
        public fun open(context: Context, rawKey: ByteArray): PasswordDatabase =
            EncryptedDatabaseOpener.open(context, PasswordDatabase::class.java, file(context), rawKey)
    }
}
