package com.norypt.haven.photos

import com.norypt.haven.crypto.PhotoCipher
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The encrypted photo files. Names are random ids (no extension, no date); `<id>` is the original,
 * `<id>.t` the thumbnail. Writes go to a `.tmp` file that is synced and renamed, so a crash leaves
 * either a complete file or a temp file that [reconcile] removes.
 */
class PhotoFileStore(val dir: File, private val random: SecureRandom = SecureRandom()) {

    fun newFileId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun file(fileId: String, kind: PhotoCipher.Kind): File {
        require(fileId.matches(ID)) { "bad file id" }
        return File(dir, if (kind == PhotoCipher.Kind.THUMBNAIL) "$fileId.t" else fileId)
    }

    fun write(fileId: String, kind: PhotoCipher.Kind, writer: (OutputStream) -> Unit) {
        dir.mkdirs()
        val target = file(fileId, kind)
        val temp = File(dir, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { fos ->
                val buffered = BufferedOutputStream(fos)
                writer(NonClosing(buffered))
                buffered.flush()
                fos.fd.sync()
            }
            if (!temp.renameTo(target)) throw IOException("rename failed")
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    fun open(fileId: String, kind: PhotoCipher.Kind): InputStream = file(fileId, kind).inputStream().buffered()

    fun delete(fileId: String) {
        file(fileId, PhotoCipher.Kind.ORIGINAL).delete()
        file(fileId, PhotoCipher.Kind.THUMBNAIL).delete()
    }

    /**
     * Removes files not in [knownFileIds], temp files, and retired photo directories. Returns the count removed.
     * Must not run concurrently with [write]: it deletes `.tmp` files that a write may still be using.
     */
    fun reconcile(knownFileIds: Set<String>): Int {
        var removed = 0
        dir.parentFile?.listFiles { f -> f.isDirectory && f.name.startsWith(RETIRED_PREFIX) }?.forEach {
            if (it.deleteRecursively()) removed++
        }
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".t")
            val keep = !f.name.endsWith(".tmp") && id.matches(ID) && id in knownFileIds
            if (!keep && f.delete()) removed++
        }
        return removed
    }

    /**
     * Makes renames into [dir] durable before rows refer to them. Best effort: [android.system.Os]
     * is not usable in JVM unit tests (and fsync of a directory may be refused), so any failure is
     * ignored and this becomes a no-op there.
     */
    fun syncDirectory() {
        runCatching {
            val fd = android.system.Os.open(dir.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(fd)
            } finally {
                android.system.Os.close(fd)
            }
        }
    }

    fun deleteAll() {
        dir.deleteRecursively()
    }

    companion object {
        private val ID = Regex("[0-9a-f]{32}")

        /** Passes writes through; [close] only flushes, so the writer cannot close the descriptor before the fsync. */
        private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() = flush()
        }
        private const val RETIRED_PREFIX = "photos.del-"

        /** Name of the directory, beside the live one, that holds photos being restored from a backup. */
        const val STAGING_DIR = "photos.staging"

        /** The staging directory that belongs beside [liveDir]. */
        fun stagingDir(liveDir: File): File = File(liveDir.parentFile, STAGING_DIR)

        /** Renames [dir] away at once, then deletes it on a background thread. */
        fun retireAsync(dir: File) {
            if (!dir.exists()) return
            val retired = File(dir.parentFile, RETIRED_PREFIX + System.nanoTime().toString(16))
            val target = if (dir.renameTo(retired)) retired else dir
            Thread({ target.deleteRecursively() }, "haven-photo-retire").apply { isDaemon = true }.start()
        }
    }
}
