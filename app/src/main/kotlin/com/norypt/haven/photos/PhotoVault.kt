package com.norypt.haven.photos

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.room.withTransaction
import com.norypt.haven.backup.PhotoRecord
import com.norypt.haven.crypto.PhotoCipher
import com.norypt.haven.security.SafeLog
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.passwords.PasswordDatabase
import com.norypt.haven.storage.passwords.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * The photo vault. Needs the keeper open: every photo's key lives in the keeper database.
 * Plaintext exists only in memory; thumbnails are cached in RAM and dropped whenever the keeper closes.
 */
class PhotoVault(
    private val session: VaultSession,
    private val store: () -> PhotoFileStore,
    private val imaging: PhotoImaging,
) {
    sealed interface ImportResult {
        data class Ok(val photo: PhotoEntity) : ImportResult
        data class Refused(val reason: String) : ImportResult
    }

    private val thumbs = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Keeps [reconcile] from deleting the files of an import whose row is not written yet. */
    private val files = Mutex()

    /** The restore staging in progress, if any; [reconcile] leaves its directory alone. */
    private var activeStaging: Staging? = null

    private fun dao() = session.passwords().photos()

    fun observeAll(): Flow<List<PhotoEntity>> = dao().observeAll()
    fun observeCount(): Flow<Int> = dao().observeCount()

    /** Encrypts [bytes] into the vault. [bytes] is wiped when this returns, whatever the outcome. */
    suspend fun import(bytes: ByteArray, takenAt: Long?): ImportResult = withContext(Dispatchers.IO) {
        try {
            if (bytes.isEmpty() || bytes.size > ImageBounds.MAX_BYTES) return@withContext ImportResult.Refused("Larger than 64 MB or empty.")
            val db = session.passwordsOrNull() ?: return@withContext ImportResult.Refused(LOCKED)
            val probe = imaging.probe(bytes) ?: return@withContext ImportResult.Refused("Not a supported image, or larger than 100 megapixels.")
            val thumb = imaging.thumbnail(bytes) ?: return@withContext ImportResult.Refused("The image could not be read.")
            val dek = PhotoCipher.newDek()
            try {
                files.withLock {
                    val s = store()
                    val id = UUID.randomUUID().toString()
                    val fileId = s.newFileId()
                    var photo: PhotoEntity? = null
                    try {
                        s.write(fileId, PhotoCipher.Kind.ORIGINAL) { PhotoCipher.encrypt(dek, id, PhotoCipher.Kind.ORIGINAL, bytes, it) }
                        s.write(fileId, PhotoCipher.Kind.THUMBNAIL) { PhotoCipher.encrypt(dek, id, PhotoCipher.Kind.THUMBNAIL, thumb, it) }
                        val entity = PhotoEntity(id, fileId, dek.copyOf(), probe.mimeType, bytes.size.toLong(), probe.width, probe.height, takenAt, System.currentTimeMillis())
                        photo = entity
                        // Never write into a keeper opened again after this import began.
                        if (session.passwordsOrNull() !== db) throw IllegalStateException("keeper closed")
                        db.photos().insert(entity)
                        ImportResult.Ok(entity)
                    } catch (t: Throwable) {
                        photo?.dek?.fill(0)
                        s.delete(fileId)
                        if (t is CancellationException) throw t
                        SafeLog.e("PhotoVault", "import failed", t)
                        ImportResult.Refused(if (session.passwordsOrNull() !== db) LOCKED else "Could not save the photo.")
                    }
                }
            } finally {
                dek.fill(0)
                thumb.fill(0)
            }
        } finally {
            bytes.fill(0)
        }
    }

    /** The decoded thumbnail, from memory when it was shown before; null when the keeper is closed or the file is unreadable. */
    suspend fun thumbnail(photo: PhotoEntity): ImageBitmap? = withContext(Dispatchers.IO) {
        val db = session.passwordsOrNull() ?: return@withContext null
        thumbs.get(photo.id)?.let { return@withContext it }
        val plain = try {
            store().open(photo.fileId, PhotoCipher.Kind.THUMBNAIL).use { PhotoCipher.decrypt(photo.dek, photo.id, PhotoCipher.Kind.THUMBNAIL, it) }
        } catch (e: Exception) {
            SafeLog.e("PhotoVault", "thumbnail unreadable", e)
            return@withContext null
        }
        try {
            val bitmap = imaging.decode(plain, AndroidPhotoImaging.THUMB_SIDE) ?: return@withContext null
            if (session.passwordsOrNull() !== db) return@withContext null
            thumbs.put(photo.id, bitmap)
            // The keeper may have closed (and the cache been cleared) between the check and the put.
            // The handle is set to null before that clear, so this second check catches it.
            if (session.passwordsOrNull() !== db) {
                thumbs.remove(photo.id)
                return@withContext null
            }
            bitmap
        } finally {
            plain.fill(0)
        }
    }

    /** The original bytes. The caller wipes the array after use. Throws when the keeper is closed or the file fails authentication. */
    suspend fun original(photo: PhotoEntity): ByteArray = withContext(Dispatchers.IO) {
        session.passwords()
        store().open(photo.fileId, PhotoCipher.Kind.ORIGINAL).use { PhotoCipher.decrypt(photo.dek, photo.id, PhotoCipher.Kind.ORIGINAL, it) }
    }

    /**
     * The original bytes for a backup export, read only while [db] (the keeper the export started
     * with) is still the open one. The caller wipes the array after use.
     */
    internal fun originalForExport(db: PasswordDatabase, photo: PhotoEntity): ByteArray {
        check(session.passwordsOrNull() === db) { "keeper closed" }
        return store().open(photo.fileId, PhotoCipher.Kind.ORIGINAL).use { PhotoCipher.decrypt(photo.dek, photo.id, PhotoCipher.Kind.ORIGINAL, it) }
    }

    /**
     * Photos being restored from a backup. Each one is encrypted under a fresh key and file id into
     * `photos.staging`, beside the live directory; nothing live changes until [commitStaging].
     * The new keys exist only in memory until then, so a staging directory left by a crash is
     * unreadable, and [reconcile] removes it.
     */
    inner class Staging internal constructor(internal val db: PasswordDatabase, internal val store: PhotoFileStore) {
        private val staged = mutableListOf<PhotoEntity>()
        val entities: List<PhotoEntity> get() = staged.toList()

        /**
         * Encrypts a restored photo under a fresh key and file id; the thumbnail is made again here.
         * [bytes] is fully consumed before this returns, so the caller may wipe it at once.
         * Throws [IllegalArgumentException] for bytes that are not a readable image and
         * [IllegalStateException] when the keeper closed since [beginStaging].
         */
        fun add(record: PhotoRecord, bytes: ByteArray) {
            check(session.passwordsOrNull() === db) { "keeper closed" }
            require(bytes.isNotEmpty() && bytes.size <= ImageBounds.MAX_BYTES) { "photo in backup is empty or too large" }
            val probe = requireNotNull(imaging.probe(bytes)) { "unreadable image in backup" }
            val thumb = requireNotNull(imaging.thumbnail(bytes)) { "unreadable image in backup" }
            val fileId = store.newFileId()
            val dek = PhotoCipher.newDek()
            try {
                store.write(fileId, PhotoCipher.Kind.ORIGINAL) { PhotoCipher.encrypt(dek, record.id, PhotoCipher.Kind.ORIGINAL, bytes, it) }
                store.write(fileId, PhotoCipher.Kind.THUMBNAIL) { PhotoCipher.encrypt(dek, record.id, PhotoCipher.Kind.THUMBNAIL, thumb, it) }
                staged += PhotoEntity(record.id, fileId, dek.copyOf(), probe.mimeType, bytes.size.toLong(), probe.width, probe.height, record.takenAtEpochMs, record.addedAtEpochMs)
            } catch (t: Throwable) {
                store.delete(fileId)
                throw t
            } finally {
                dek.fill(0)
                thumb.fill(0)
            }
        }

        /** Drops every staged photo: keys wiped, files deleted. Safe to call more than once. */
        fun discard() {
            staged.forEach { it.dek.fill(0) }
            staged.clear()
            store.deleteAll()
            synchronized(this@PhotoVault) { if (activeStaging === this) activeStaging = null }
        }
    }

    /** Starts a restore into an empty staging directory. Needs the keeper open; it must stay the same keeper until [commitStaging]. */
    fun beginStaging(): Staging {
        val db = session.passwords()
        val dir = PhotoFileStore(PhotoFileStore.stagingDir(store().dir)).also { it.deleteAll() }
        return Staging(db, dir).also { synchronized(this) { activeStaging = it } }
    }

    /**
     * Replaces every photo with the staged ones and returns how many there are. Staged files are
     * moved in first (their ids are new, so nothing live is touched), then one keeper transaction
     * swaps the rows and runs [alsoInTransaction]; only after it commits are the old files deleted.
     * A crash at any point leaves either the old or the new set, and [reconcile] removes the rest.
     * Refused with [IllegalStateException] when the keeper is no longer the one staging began with.
     * The staging is discarded afterwards, whatever the outcome.
     */
    suspend fun commitStaging(staging: Staging, alsoInTransaction: suspend (PasswordDatabase) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        try {
            val db = staging.db
            files.withLock {
                check(session.passwordsOrNull() === db) { "keeper closed" }
                val entities = staging.entities
                val names = entities.flatMap { listOf(it.fileId, "${it.fileId}.t") }
                names.forEach { if (!File(staging.store.dir, it).isFile) throw IOException("a staged photo file is missing") }
                val live = store()
                live.dir.mkdirs()
                val old = db.photos().all()
                try {
                    names.forEach { if (!File(staging.store.dir, it).renameTo(File(live.dir, it))) throw IOException("move failed") }
                    db.withTransaction {
                        db.photos().deleteEverything()
                        db.photos().insertAll(entities)
                        alsoInTransaction(db)
                    }
                } catch (t: Throwable) {
                    entities.forEach { live.delete(it.fileId) }
                    throw t
                }
                // The old keys are gone with their rows: these files are unreadable already.
                old.forEach { live.delete(it.fileId); it.dek.fill(0) }
                clearCaches()
                entities.size
            }
        } finally {
            staging.discard()
        }
    }

    suspend fun delete(ids: Collection<String>): Unit = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val db = session.passwords()
        files.withLock {
            val photos = ids.mapNotNull { db.photos().byId(it) }
            if (photos.isEmpty()) return@withLock
            db.photos().deleteAll(photos.map { it.id }) // keys gone first: the files are unreadable from here on
            val s = store()
            photos.forEach { s.delete(it.fileId); thumbs.remove(it.id) }
        }
    }

    /** Called after every keeper unlock: removes files without rows, temp files, and rows without files. Returns the count removed. */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        val db = session.passwordsOrNull() ?: return@withContext 0
        files.withLock {
            val s = store()
            // Left by a restore that never finished; its keys died with it. A restore still running keeps its own.
            if (synchronized(this@PhotoVault) { activeStaging == null }) PhotoFileStore.stagingDir(s.dir).deleteRecursively()
            val rows = db.photos().all()
            val missing = rows.filter { !File(s.dir, it.fileId).exists() }.map { it.id }
            if (missing.isNotEmpty()) db.photos().deleteAll(missing)
            s.reconcile(rows.map { it.fileId }.toSet()) + missing.size
        }
    }

    /** Drops every decoded thumbnail. Runs whenever the keeper closes; never throws. */
    fun clearCaches() {
        thumbs.evictAll()
    }

    /** Thumbnails held in memory; for tests. */
    internal fun cachedThumbnailCount(): Int = thumbs.snapshot().size

    private companion object {
        const val LOCKED = "The vault was locked."
    }
}
