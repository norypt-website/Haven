package com.norypt.haven.storage

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Opens Room databases encrypted with SQLCipher (community edition, 4.x defaults:
 * AES-256-CBC page encryption with per-page HMAC-SHA512, 4096-byte pages).
 *
 * The key is supplied as a raw 256-bit key using SQLCipher's `x'HEX'` raw-key syntax, so
 * SQLCipher performs no PBKDF2 derivation of its own; the Argon2id/Keystore envelope in
 * vault-crypto is the only key derivation. The transient `x'..'` byte string is wiped after the
 * database has been opened.
 *
 * Journal mode is TRUNCATE (not WAL) to keep retained history in a single journal file that is
 * truncated after every transaction; `secure_delete` (SQLCipher default ON) overwrites freed
 * pages inside the file; `temp_store=MEMORY` keeps sort/temp tables out of the filesystem.
 * SQLCipher's `cipher_memory_security` (default ON) locks and wipes its own key buffers.
 */
public object EncryptedDatabaseOpener {
    @Volatile private var libraryLoaded = false

    private fun ensureLibrary() {
        if (!libraryLoaded) {
            synchronized(this) {
                if (!libraryLoaded) {
                    System.loadLibrary("sqlcipher")
                    libraryLoaded = true
                }
            }
        }
    }

    /** Directory for vault databases: excluded from Android auto-backup by default (noBackupFilesDir). */
    public fun vaultDir(context: Context): File = File(context.noBackupFilesDir, "vaults").apply { mkdirs() }

    public fun databaseFile(context: Context, name: String): File = File(vaultDir(context), "$name.db")

    /**
     * Builds a Room database. [rawKey] must be 32 bytes; it is NOT wiped by this method (the
     * caller owns it), but the derived passphrase bytes are wiped once the connection is open.
     */
    public fun <T : RoomDatabase> open(context: Context, klass: Class<T>, file: File, rawKey: ByteArray): T {
        require(rawKey.size == 32) { "raw key must be 32 bytes" }
        ensureLibrary()
        val passphrase = rawKeyPassphrase(rawKey)
        val hook = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {}
            override fun postKey(connection: SQLiteConnection) {
                // executeRaw steps the statement and ignores any result row; some PRAGMAs return a
                // row (secure_delete) and some do not (temp_store), and execute()/executeForString()
                // each reject one of the two shapes.
                connection.executeRaw("PRAGMA temp_store = MEMORY;", null, null)
                connection.executeRaw("PRAGMA secure_delete = ON;", null, null)
                connection.executeRaw("PRAGMA cipher_memory_security = ON;", null, null)
            }
        }
        val factory = SupportOpenHelperFactory(passphrase, hook, /* enableWriteAheadLogging = */ false)
        val db = Room.databaseBuilder(context, klass, file.absolutePath)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            // Force the connection open now so the passphrase copy can be wiped deterministically.
            db.openHelper.writableDatabase
        } catch (t: Throwable) {
            db.close()
            java.util.Arrays.fill(passphrase, 0.toByte())
            throw t
        }
        java.util.Arrays.fill(passphrase, 0.toByte())
        return db
    }

    /** `x'<64 hex chars>'` as UTF-8 bytes. */
    internal fun rawKeyPassphrase(rawKey: ByteArray): ByteArray {
        val hex = CharArray(64)
        val digits = "0123456789abcdef"
        for (i in rawKey.indices) {
            val v = rawKey[i].toInt() and 0xff
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0f]
        }
        val out = ByteArray(67)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        for (i in 0 until 64) out[2 + i] = hex[i].code.toByte()
        out[66] = '\''.code.toByte()
        java.util.Arrays.fill(hex, '\u0000')
        return out
    }

    /** Deletes the database and all sidecar files. Returns true if nothing remains. */
    public fun deleteDatabaseFiles(file: File): Boolean {
        val names = listOf(file, File(file.path + "-journal"), File(file.path + "-wal"), File(file.path + "-shm"))
        names.forEach { it.delete() }
        return names.none { it.exists() }
    }
}
