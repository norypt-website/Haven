package com.norypt.haven.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.norypt.haven.storage.content.ContentDatabase
import com.norypt.haven.storage.content.ReminderEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/** Instrumented: needs the SQLCipher native library, so it runs on a device or emulator. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file = File(context.noBackupFilesDir, "vaults/test-content.db")
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val secret = "TESTSECRET-cafe-babe-0042"

    @Before fun clean() { EncryptedDatabaseOpener.deleteDatabaseFiles(file) }
    @After fun cleanup() { EncryptedDatabaseOpener.deleteDatabaseFiles(file) }

    @Test fun writesAreEncryptedOnDiskAndReadableWithTheKey() : Unit = runBlocking {
        val db = EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key)
        db.reminders().upsert(ReminderEntity("id1", secret, "notes $secret", true, "{\"v\":1}", 1, 1, 1))
        db.close()
        val bytes = file.readBytes()
        assertFalse("plaintext leaked to the database file", String(bytes, Charsets.ISO_8859_1).contains(secret))
        assertFalse("plaintext SQLite header present (file is not encrypted)", String(bytes.copyOf(16), Charsets.US_ASCII).startsWith("SQLite format 3"))
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val side = File(file.path + suffix)
            if (side.exists()) assertFalse("plaintext in $suffix", String(side.readBytes(), Charsets.ISO_8859_1).contains(secret))
        }
        val reopened = EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key)
        assertEquals(secret, reopened.reminders().byId("id1")!!.title)
        reopened.close()
    }

    @Test fun wrongKeyFailsToOpen() : Unit = runBlocking {
        EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key).close()
        val wrong = ByteArray(32).also { SecureRandom().nextBytes(it) }
        assertThrows(Exception::class.java) {
            EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, wrong).also { runBlocking { it.reminders().all() } }.close()
        }
    }

    @Test fun tamperedPageIsDetected() : Unit = runBlocking {
        val db = EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key)
        repeat(50) { db.reminders().upsert(ReminderEntity("id$it", "t$it", "", true, "{}", 1, 1, 1)) }
        db.close()
        val raw = file.readBytes()
        // Corrupt one byte in every 4096-byte page (SQLCipher checks the HMAC of each page it reads).
        var off = 100
        while (off < raw.size) { raw[off] = (raw[off].toInt() xor 0x5a).toByte(); off += 4096 }
        file.writeBytes(raw)
        assertThrows(Exception::class.java) {
            val d = EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key)
            runBlocking { d.reminders().all() }
            d.close()
        }
    }

    @Test fun deleteRemovesEverySidecar() {
        EncryptedDatabaseOpener.open(context, ContentDatabase::class.java, file, key).close()
        assertTrue(EncryptedDatabaseOpener.deleteDatabaseFiles(file))
        assertFalse(file.exists())
    }
}
