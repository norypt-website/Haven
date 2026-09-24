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
import com.norypt.haven.session.ScheduleSync
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.content.ReminderEntity
import com.norypt.haven.storage.content.TaskEntity
import com.norypt.haven.storage.content.TaskListEntity
import com.norypt.haven.storage.content.VaultMetaEntity
import com.norypt.haven.storage.passwords.FolderEntity
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
    data class Success(val reminders: Int, val tasks: Int, val passwords: Int) : RestoreResult
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
class BackupManager(private val context: Context, private val session: VaultSession, private val alarms: AlarmRuntime) {
    private val kdf = Argon2BackupKdf()

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
            val payload = snapshot(includePasswords)
            val key = backupKey()
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
                    BackupWriter(kdf).write(
                        output = counting, passphrase = pw, backupKey = key,
                        params = KdfParams(params.memoryKib, params.iterations, params.parallelism),
                        appVersionCode = BuildConfig.VERSION_CODE,
                        contents = buildList { add("content"); if (includePasswords) add("passwords") },
                        createdAtEpochMs = System.currentTimeMillis(),
                    ) { plain -> BackupPayloadJson.encode(payload, plain) }
                }
                // Only now is the file made visible; an interrupted export leaves a pending (hidden) file that MediaStore cleans up.
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                ExportResult.Success("Downloads/Haven/$name", written)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                ExportResult.Failure("Backup failed: ${t.javaClass.simpleName}")
            }
        } finally {
            Wipe.bytes(pw)
            session.endBackupOperation()
        }
    }

    private suspend fun snapshot(includePasswords: Boolean): BackupPayload {
        val c = session.content()
        val content = ContentSnapshot(
            reminders = c.reminders().all().map { ReminderRecord(it.id, it.title, it.notes, it.enabled, it.scheduleJson, it.createdAt, it.updatedAt, it.priority, it.starred) },
            taskLists = c.taskLists().all().map { TaskListRecord(it.id, it.name, it.position, it.createdAt) },
            tasks = c.tasks().all().map { TaskRecord(it.id, it.listId, it.title, it.notes, it.completed, it.completedAt, it.dueLocal, it.reminderId, it.position, it.createdAt, it.updatedAt, it.priority, it.starred, it.followUpMinutes) },
        )
        val passwords = if (includePasswords) {
            val p = session.passwords()
            PasswordSnapshot(
                folders = p.folders().all().map { FolderRecord(it.id, it.name, it.position) },
                entries = p.entries().all().map { PasswordEntryRecord(it.id, it.folderId, it.title, it.website, it.username, it.password, it.notes, it.createdAt, it.updatedAt) },
            )
        } else null
        val settings = c.meta().all().filter { it.key != META_BACKUP_KEY && it.key != META_BACKUP_KEY_VERIFIED }.associate { it.key to it.value }
        return BackupPayload(content = content, passwords = passwords, settings = settings)
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
     * Restore: decrypt and authenticate the WHOLE file into memory first (bounded), then replace
     * the vault contents inside database transactions. Existing data is untouched until the
     * payload has been fully validated.
     */
    suspend fun restore(uri: Uri, passphrase: CharArray, backupKey: BackupKey, restorePasswords: Boolean): RestoreResult = withContext(Dispatchers.IO) {
        if (!isLocalUri(uri)) return@withContext RestoreResult.Failure("Only files from this device's storage can be restored.")
        session.beginBackupOperation(restore = true)
        val pw = try { PasswordNormalizer.toBytes(passphrase) } catch (e: IllegalArgumentException) { session.endBackupOperation(); return@withContext RestoreResult.Failure("Enter the backup passphrase.") }
        try {
            val payload = try {
                open(uri).use { input ->
                    val opened = BackupReader(kdf).open(input, pw, backupKey)
                    val bytes = readBounded(opened.plaintext, MAX_PAYLOAD)
                    BackupPayloadJson.decode(String(bytes, Charsets.UTF_8))
                }
            } catch (e: BackupFormatException.AuthenticationFailed) {
                return@withContext RestoreResult.Failure("The passphrase or backup key is wrong, or the file was modified.")
            } catch (e: BackupFormatException.Truncated) {
                return@withContext RestoreResult.Failure("The backup file is incomplete (truncated).")
            } catch (e: BackupFormatException.ParametersOutOfBounds) {
                return@withContext RestoreResult.Failure("This backup asks for more key-derivation memory than Haven allows on this device. It was not made by a normal Haven export.")
            } catch (e: BackupFormatException) {
                return@withContext RestoreResult.Failure("The file is not a valid Haven backup (${e.javaClass.simpleName}).")
            } catch (e: IOException) {
                return@withContext RestoreResult.Failure("Could not read the file.")
            }
            val content = payload.content
            val c = session.content()
            var reminders = 0; var tasks = 0; var passwords = 0
            if (content != null) {
                c.withTransaction {
                    c.tasks().deleteEverything(); c.taskLists().deleteEverything(); c.reminders().deleteEverything()
                    c.reminders().upsertAll(content.reminders.map { ReminderEntity(it.id, it.title, it.notes, it.enabled, it.scheduleJson, 1L, it.createdAtEpochMs, it.updatedAtEpochMs, it.priority, it.starred) })
                    c.taskLists().upsertAll(content.taskLists.map { TaskListEntity(it.id, it.name, it.position, it.createdAtEpochMs) })
                    val listIds = content.taskLists.map { it.id }.toSet()
                    c.tasks().upsertAll(content.tasks.filter { it.listId in listIds }.map { TaskEntity(it.id, it.listId, it.title, it.notes, it.completed, it.completedAtEpochMs, it.dueLocal, it.reminderId, it.position, it.createdAtEpochMs, it.updatedAtEpochMs, it.priority, it.starred, it.followUpMinutes) })
                    payload.settings.forEach { (k, v) -> if (k.length <= 64 && v.length <= 4096) c.meta().put(VaultMetaEntity(k, v)) }
                }
                reminders = content.reminders.size; tasks = content.tasks.size
            }
            val pw2 = payload.passwords
            if (restorePasswords && pw2 != null) {
                val p = session.passwordsOrNull() ?: return@withContext RestoreResult.Failure("Reminders and tasks were restored, but the password keeper is locked. Unlock it and restore again to bring back passwords.")
                p.withTransaction {
                    p.entries().deleteEverything(); p.folders().deleteEverything()
                    p.folders().upsertAll(pw2.folders.map { FolderEntity(it.id, it.name, it.position) })
                    val folderIds = pw2.folders.map { it.id }.toSet()
                    p.entries().upsertAll(pw2.entries.map { PasswordEntryEntity(it.id, it.folderId?.takeIf { f -> f in folderIds }, it.title, it.website, it.username, it.password, it.notes, it.createdAtEpochMs, it.updatedAtEpochMs) })
                }
                passwords = pw2.entries.size
            }
            // Rebuild alarms from the restored reminders.
            ScheduleSync.syncAll(c, alarms)
            RestoreResult.Success(reminders, tasks, passwords)
        } finally {
            Wipe.bytes(pw)
            session.endBackupOperation()
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
