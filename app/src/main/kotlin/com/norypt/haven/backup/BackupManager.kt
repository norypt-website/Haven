package com.norypt.haven.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.norypt.haven.BuildConfig
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.Argon2idKeyDerivation
import com.norypt.haven.crypto.PasswordNormalizer
import com.norypt.haven.crypto.VaultCryptoException
import com.norypt.haven.crypto.Wipe
import com.norypt.haven.photos.PhotoVault
import com.norypt.haven.session.ScheduleSync
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.content.NoteEntity
import com.norypt.haven.storage.content.ReminderEntity
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.storage.content.TaskListEntity
import com.norypt.haven.storage.content.VaultMetaEntity
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordDatabase
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import com.norypt.haven.storage.passwords.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Adapts vault-crypto's Argon2id to backup-format's KDF interface. Never lowers cost below Haven's floor. */
class Argon2BackupKdf : PasswordKdf {
    private val kdf = Argon2idKeyDerivation()
    override fun deriveKey(password: ByteArray, salt: ByteArray, params: KdfParams, outputLength: Int): ByteArray {
        val p = Argon2Params.validate(params.memoryKib, params.iterations, params.parallelism, 0x13)
            ?: throw BackupFormatException.ParametersOutOfBounds("KDF parameters below Haven's minimum (64 MiB) or above its maximum")
        return try {
            kdf.deriveKey(password, salt, p, outputLength)
        } catch (e: VaultCryptoException.KeyDerivationFailed) {
            throw BackupFormatException.ParametersOutOfBounds("The backup's key derivation cost exceeds this device's memory")
        }
    }
}

sealed interface ExportResult {
    data class Success(val displayName: String, val bytes: Long) : ExportResult
    data class Failure(val message: String) : ExportResult
}

sealed interface RestoreResult {
    data class Success(val reminders: Int, val tasks: Int, val passwords: Int, val notes: Int, val photos: Int) : RestoreResult
    data class Failure(val message: String) : RestoreResult
}

data class BackupInspection(val header: BackupHeader, val keyIdMatchesStored: Boolean?)

/**
 * Manual, local, encrypted backups.
 *
 * Export destination: the device's Downloads/Haven folder via MediaStore (app-created files,
 * no storage permission). Import: the system document picker restricted to LOCAL providers
 * (external storage or Downloads provider); any other authority is rejected because a
 * cloud-backed provider could transfer the file even though Haven has no INTERNET permission.
 * Haven cannot stop the user, or another app, from copying the file elsewhere afterwards.
 */
class BackupManager(
    private val context: Context,
    private val session: VaultSession,
    private val alarms: AlarmRuntime,
    private val photos: PhotoVault,
    /** Argon2id in the app; JVM tests, which cannot load the native library, pass a stand-in. */
    private val kdf: PasswordKdf = Argon2BackupKdf(),
) {

    /** Backup key: generated once per vault, stored (encrypted) in the content vault. The user must record it separately. */
    suspend fun backupKey(): BackupKey = withContext(Dispatchers.IO) {
        val meta = session.content().meta()
        val hex = meta.get(META_BACKUP_KEY)
        if (hex != null) BackupKey.fromBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        else {
            val key = BackupKey.generate()
            meta.put(VaultMetaEntity(META_BACKUP_KEY, key.bytes().joinToString("") { "%02x".format(it) }))
            key
        }
    }

    suspend fun backupKeyVerified(): Boolean = withContext(Dispatchers.IO) { session.content().meta().get(META_BACKUP_KEY_VERIFIED) == "1" }
    suspend fun markBackupKeyVerified() = withContext(Dispatchers.IO) { session.content().meta().put(VaultMetaEntity(META_BACKUP_KEY_VERIFIED, "1")) }

    /** Generates a new backup key (old backups still need the old key). */
    suspend fun rotateBackupKey(): BackupKey = withContext(Dispatchers.IO) {
        val key = BackupKey.generate()
        session.content().meta().put(VaultMetaEntity(META_BACKUP_KEY, key.bytes().joinToString("") { "%02x".format(it) }))
        session.content().meta().remove(META_BACKUP_KEY_VERIFIED)
        key
    }

    suspend fun export(passphrase: CharArray, includePasswords: Boolean, params: Argon2Params): ExportResult = withContext(Dispatchers.IO) {
        if (includePasswords && session.passwordsOrNull() == null) return@withContext ExportResult.Failure("Unlock the password keeper first to include it.")
        session.beginBackupOperation(restore = false)
        val pw = try { PasswordNormalizer.toBytes(passphrase) } catch (e: IllegalArgumentException) { session.endBackupOperation(); return@withContext ExportResult.Failure("Enter a backup passphrase.") }
        try {
            val name = "haven-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".hvbk"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Haven")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return@withContext ExportResult.Failure("Could not create the backup file in Downloads.")
            var written = 0L
            try {
                resolver.openOutputStream(uri, "w")!!.use { out ->
                    val counting = object : java.io.FilterOutputStream(out) {
                        override fun write(b: Int) { super.write(b); written++ }
                        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
                    }
                    writeBackup(counting, pw, includePasswords, params)
                }
                // Only now is the file made visible; an interrupted export leaves a pending (hidden) file that MediaStore cleans up.
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                ExportResult.Success("Downloads/Haven/$name", written)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                if (t is CancellationException) throw t
                ExportResult.Failure(
                    if (includePasswords && t is IllegalStateException) "The password keeper was locked during the backup. No file was kept."
                    else "Backup failed: ${t.javaClass.simpleName}",
                )
            }
        } finally {
            Wipe.bytes(pw)
            withContext(NonCancellable) { session.endBackupOperation() }
        }
    }

    /**
     * Writes the whole encrypted backup to [out]: payload format 2, photos streamed one at a time.
     * Each photo is decrypted only while it is written (PayloadV2 wipes it straight after), and only
     * from the keeper the snapshot was taken from: if the keeper closes meanwhile this throws
     * [IllegalStateException]. Photo keys and file ids never enter the backup.
     */
    internal suspend fun writeBackup(out: OutputStream, pw: ByteArray, includePasswords: Boolean, params: Argon2Params) {
        val snap = snapshot(includePasswords)
        try {
            val key = backupKey()
            BackupWriter(kdf).write(
                output = out, passphrase = pw, backupKey = key,
                params = KdfParams(params.memoryKib, params.iterations, params.parallelism),
                appVersionCode = BuildConfig.VERSION_CODE,
                contents = buildList { add("content"); if (includePasswords) { add("passwords"); add("photos") } },
                createdAtEpochMs = System.currentTimeMillis(),
            ) { plain ->
                PayloadV2.write(plain, snap.payload) { rec ->
                    val db = checkNotNull(snap.keeper) { "keeper closed" }
                    photos.originalForExport(db, snap.photoById.getValue(rec.id))
                }
            }
        } finally {
            snap.photoById.values.forEach { it.dek.fill(0) }
        }
    }

    private class Snapshot(val payload: BackupPayload, val keeper: PasswordDatabase?, val photoById: Map<String, PhotoEntity>)

    private suspend fun snapshot(includePasswords: Boolean): Snapshot {
        val c = session.content()
        val content = ContentSnapshot(
            reminders = c.reminders().all().map { ReminderRecord(it.id, it.title, it.notes, it.enabled, it.scheduleJson, it.createdAt, it.updatedAt, it.priority, it.starred) },
            taskLists = c.taskLists().all().map { TaskListRecord(it.id, it.name, it.position, it.createdAt) },
            tasks = c.tasks().all().map { TaskRecord(it.id, it.listId, it.title, it.notes, it.completed, it.completedAt, it.dueLocal, it.reminderId, it.position, it.createdAt, it.updatedAt, it.priority, it.starred, it.followUpMinutes) },
            notes = c.notes().all().map { NoteRecord(it.id, it.title, it.body, it.createdAt, it.updatedAt) },
        )
        var keeper: PasswordDatabase? = null
        var photoEntities: List<PhotoEntity> = emptyList()
        val passwords = if (includePasswords) {
            val p = session.passwords()
            keeper = p
            photoEntities = p.photos().all()
            PasswordSnapshot(
                folders = p.folders().all().map { FolderRecord(it.id, it.name, it.position) },
                entries = p.entries().all().map { PasswordEntryRecord(it.id, it.folderId, it.title, it.website, it.username, it.password, it.notes, it.createdAt, it.updatedAt, it.starred, it.color) },
                // Index entries only: no key, no file id.
                photos = photoEntities.map { PhotoRecord(it.id, it.mimeType, it.byteSize, it.width, it.height, it.takenAt, it.addedAt) },
            )
        } else null
        val settings = c.meta().all().filter { it.key != META_BACKUP_KEY && it.key != META_BACKUP_KEY_VERIFIED }.associate { it.key to it.value }
        return Snapshot(BackupPayload(content = content, passwords = passwords, settings = settings), keeper, photoEntities.associateBy { it.id })
    }

    /** Only local document providers are accepted. */
    fun isLocalUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return uri.authority in LOCAL_AUTHORITIES
    }

    suspend fun inspect(uri: Uri): Result<BackupInspection> = withContext(Dispatchers.IO) {
        runCatching {
            require(isLocalUri(uri)) { "Only files from this device's storage can be restored." }
            val header = open(uri).use { BackupReader(kdf).readHeader(it) }
            val stored = runCatching { backupKey().keyId() }.getOrNull()
            BackupInspection(header, stored?.let { it == header.backupKeyId })
        }
    }

    /**
     * Restore: read and authenticate the WHOLE file first, then replace the vault contents inside
     * database transactions. Nothing existing changes until the stream has been read to its
     * authenticated end: a format 1 body is read into memory (bounded); in a format 2 body each
     * photo is re-encrypted under a new key into a staging directory as it arrives, and only after
     * the read succeeded are the content transaction, the keeper transaction and the photo swap run.
     * Any failure before that discards the staging and leaves notes, passwords, photos and files as
     * they were. A format 1 backup has no photos and leaves the existing ones alone.
     */
    suspend fun restore(uri: Uri, passphrase: CharArray, backupKey: BackupKey, restorePasswords: Boolean): RestoreResult = withContext(Dispatchers.IO) {
        if (!isLocalUri(uri)) return@withContext RestoreResult.Failure("Only files from this device's storage can be restored.")
        session.beginBackupOperation(restore = true)
        val pw = try { PasswordNormalizer.toBytes(passphrase) } catch (e: IllegalArgumentException) { session.endBackupOperation(); return@withContext RestoreResult.Failure("Enter the backup passphrase.") }
        var staging: PhotoVault.Staging? = null
        try {
            var format = 0
            val payload = try {
                open(uri).use { input ->
                    val opened = BackupReader(kdf).open(input, pw, backupKey)
                    format = opened.header.payloadFormat
                    val decoded = when (format) {
                        1 -> BackupPayloadJson.decode(String(readBounded(opened.plaintext, MAX_PAYLOAD), Charsets.UTF_8))
                        2 -> {
                            val stage = if (restorePasswords && session.passwordsOrNull() != null) photos.beginStaging().also { staging = it } else null
                            // Staging.add encrypts the bytes before returning: the reader zeroes them right after.
                            PayloadV2.read(opened.plaintext) { record, bytes -> stage?.add(record, bytes) }
                        }
                        else -> throw BackupFormatException.Malformed("payload format $format")
                    }
                    if (decoded.format != format) throw BackupFormatException.Malformed("payload says format ${decoded.format}, header says $format")
                    decoded
                }
            } catch (e: BackupFormatException.AuthenticationFailed) {
                return@withContext RestoreResult.Failure("The passphrase or backup key is wrong, or the file was modified.")
            } catch (e: BackupFormatException.Truncated) {
                return@withContext RestoreResult.Failure("The backup file is incomplete (truncated).")
            } catch (e: BackupFormatException.ParametersOutOfBounds) {
                return@withContext RestoreResult.Failure("This backup asks for more key-derivation memory than Haven allows on this device. It was not made by a normal Haven export.")
            } catch (e: BackupFormatException) {
                return@withContext RestoreResult.Failure("The file is not a valid Haven backup (${e.javaClass.simpleName}).")
            } catch (e: IllegalArgumentException) {
                return@withContext RestoreResult.Failure("A photo in the backup could not be read. Nothing was changed.")
            } catch (e: CancellationException) {
                throw e // an IllegalStateException too; never mistaken for a locked keeper
            } catch (e: IllegalStateException) {
                return@withContext RestoreResult.Failure("The password keeper was locked during the restore. Nothing was changed.")
            } catch (e: IOException) {
                return@withContext RestoreResult.Failure("Could not read the file, or could not store its photos. Nothing was changed.")
            }
            // Past this point the stream is authenticated: the swap runs to the end even if the caller is
            // cancelled (leaving the screen), so a cancel can never leave a half-applied restore.
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                val content = payload.content
                val c = session.content()
                var reminders = 0; var tasks = 0; var notes = 0; var passwords = 0; var photoCount = 0
                if (content != null) {
                    c.withTransaction {
                        c.tasks().deleteEverything(); c.taskLists().deleteEverything(); c.reminders().deleteEverything(); c.notes().deleteEverything()
                        c.reminders().upsertAll(content.reminders.map { ReminderEntity(it.id, it.title, it.notes, it.enabled, it.scheduleJson, 1L, it.createdAtEpochMs, it.updatedAtEpochMs, it.priority, it.starred) })
                        c.taskLists().upsertAll(content.taskLists.map { TaskListEntity(it.id, it.name, it.position, it.createdAtEpochMs) })
                        val listIds = content.taskLists.map { it.id }.toSet()
                        c.tasks().upsertAll(content.tasks.filter { it.listId in listIds }.map { TaskEntity(it.id, it.listId, it.title, it.notes, it.completed, it.completedAtEpochMs, it.dueLocal, it.reminderId, it.position, it.createdAtEpochMs, it.updatedAtEpochMs, it.priority, it.starred, it.followUpMinutes) })
                        c.notes().upsertAll(content.notes.map { NoteEntity(it.id, it.title, it.body, it.createdAtEpochMs, it.updatedAtEpochMs) })
                        payload.settings.forEach { (k, v) -> if (isRestorableSetting(k, v)) c.meta().put(VaultMetaEntity(k, v)) }
                    }
                    reminders = content.reminders.size; tasks = content.tasks.size; notes = content.notes.size
                }
                // Rebuild alarms from the restored reminders, also when the vault part fails below.
                ScheduleSync.syncAll(c, alarms)
                val pw2 = payload.passwords
                if (restorePasswords && pw2 != null) {
                    val stage = staging
                    val p = session.passwordsOrNull()
                    if (p == null || (format == 2 && stage == null)) {
                        return@withContext RestoreResult.Failure("Reminders, tasks and notes were restored, but the password keeper is locked. Unlock it and restore again to bring back passwords and photos.")
                    }
                    val replacePasswords: suspend (PasswordDatabase) -> Unit = { db ->
                        db.entries().deleteEverything(); db.folders().deleteEverything()
                        db.folders().upsertAll(pw2.folders.map { FolderEntity(it.id, it.name, it.position) })
                        val folderIds = pw2.folders.map { it.id }.toSet()
                        db.entries().upsertAll(pw2.entries.map { PasswordEntryEntity(it.id, it.folderId?.takeIf { f -> f in folderIds }, it.title, it.website, it.username, it.password, it.notes, it.createdAtEpochMs, it.updatedAtEpochMs, it.starred, it.color) })
                    }
                    try {
                        // Passwords and photo rows change in one keeper transaction.
                        if (stage != null) photoCount = photos.commitStaging(stage, replacePasswords)
                        else p.withTransaction { replacePasswords(p) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        return@withContext RestoreResult.Failure("Reminders, tasks and notes were restored, but passwords and photos could not be (${e.javaClass.simpleName}); they were left as they were.")
                    }
                    passwords = pw2.entries.size
                }
                RestoreResult.Success(reminders, tasks, passwords, notes, photoCount)
            }
        } finally {
            // Idempotent; after a commit there is nothing left to drop.
            staging?.discard()
            Wipe.bytes(pw)
            withContext(NonCancellable) { session.endBackupOperation() }
        }
    }

    private fun open(uri: Uri): InputStream = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")

    private fun readBounded(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw BackupFormatException.Malformed("payload larger than $max bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MIME = "application/octet-stream"
        const val META_BACKUP_KEY = "backup.key.hex"
        const val META_BACKUP_KEY_VERIFIED = "backup.key.verified"

        /**
         * Settings a backup may write into the vault. The vault's own backup key and its
         * "recorded" flag are never taken from a file: a backup that could replace them would let
         * whoever authored it decide which key protects every later export.
         */
        fun isRestorableSetting(key: String, value: String): Boolean =
            key.length <= 64 && value.length <= 4096 && key != META_BACKUP_KEY && key != META_BACKUP_KEY_VERIFIED
        const val MAX_PAYLOAD = 64 * 1024 * 1024
        /** Providers that are demonstrably local: the Android external-storage provider and the local Downloads provider. */
        val LOCAL_AUTHORITIES: Set<String> = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
        )
        fun isDocumentUri(context: Context, uri: Uri): Boolean = DocumentsContract.isDocumentUri(context, uri)
    }
}
