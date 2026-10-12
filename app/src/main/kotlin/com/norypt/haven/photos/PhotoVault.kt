package com.norypt.haven.photos

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import com.norypt.haven.crypto.PhotoCipher
import com.norypt.haven.security.SafeLog
import com.norypt.haven.session.VaultSession
import com.norypt.haven.storage.passwords.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
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
                    try {
                        s.write(fileId, PhotoCipher.Kind.ORIGINAL) { PhotoCipher.encrypt(dek, id, PhotoCipher.Kind.ORIGINAL, bytes, it) }
                        s.write(fileId, PhotoCipher.Kind.THUMBNAIL) { PhotoCipher.encrypt(dek, id, PhotoCipher.Kind.THUMBNAIL, thumb, it) }
                        val photo = PhotoEntity(id, fileId, dek.copyOf(), probe.mimeType, bytes.size.toLong(), probe.width, probe.height, takenAt, System.currentTimeMillis())
                        // Never write into a keeper opened again after this import began.
                        if (session.passwordsOrNull() !== db) throw IllegalStateException("keeper closed")
                        db.photos().insert(photo)
                        ImportResult.Ok(photo)
                    } catch (t: Throwable) {
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
        if (session.passwordsOrNull() == null) return@withContext null
        thumbs.get(photo.id)?.let { return@withContext it }
        val plain = try {
            store().open(photo.fileId, PhotoCipher.Kind.THUMBNAIL).use { PhotoCipher.decrypt(photo.dek, photo.id, PhotoCipher.Kind.THUMBNAIL, it) }
        } catch (e: Exception) {
            SafeLog.e("PhotoVault", "thumbnail unreadable", e)
            return@withContext null
        }
        try {
            imaging.decode(plain, AndroidPhotoImaging.THUMB_SIDE)?.also { if (session.passwordsOrNull() != null) thumbs.put(photo.id, it) }
        } finally {
            plain.fill(0)
        }
    }

    /** The original bytes. The caller wipes the array after use. Throws when the keeper is closed or the file fails authentication. */
    suspend fun original(photo: PhotoEntity): ByteArray = withContext(Dispatchers.IO) {
        session.passwords()
        store().open(photo.fileId, PhotoCipher.Kind.ORIGINAL).use { PhotoCipher.decrypt(photo.dek, photo.id, PhotoCipher.Kind.ORIGINAL, it) }
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

    private companion object {
        const val LOCKED = "The vault was locked."
    }
}
