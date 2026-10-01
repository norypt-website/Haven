package com.norypt.haven.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.storage.passwords.PasswordDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Upgrades a password keeper written by Haven 0.1.x (schema 1) through Room's real migration path,
 * including Room's schema validation. Plain SQLite stands in for SQLCipher: encryption does not
 * change the schema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "passwords-migration-test.db"

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanup() { context.deleteDatabase(name) }

    /** Creates the database exactly as the exported schema 1 describes it, identity row included. */
    private fun createVersion1(rows: SQLiteDatabase.() -> Unit) {
        val schema = JSONObject(File("schemas/com.norypt.haven.storage.passwords.PasswordDatabase/1.json").readText())
            .getJSONObject("database")
        val path = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.rows()
        db.version = 1
        db.close()
    }

    private fun openCurrent(): PasswordDatabase = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()

    @Test fun version1EntriesSurviveUnstarredWithAutomaticColour(): Unit = runBlocking {
        createVersion1 {
            execSQL("INSERT INTO folders (id, name, position) VALUES ('f1', 'Finance', 0)")
            execSQL("INSERT INTO entries VALUES ('e1', 'f1', 'Bank account', 'bank.example', 'j.doe', 's3cret', 'note', 100, 200)")
            execSQL("INSERT INTO entries VALUES ('e2', NULL, 'Router', '', 'admin', 'pw', '', 300, 400)")
        }
        val db = openCurrent()
        val bank = db.entries().byId("e1")!!
        assertThat(bank.title).isEqualTo("Bank account")
        assertThat(bank.folderId).isEqualTo("f1")
        assertThat(bank.password).isEqualTo("s3cret")
        assertThat(bank.updatedAt).isEqualTo(200L)
        assertThat(bank.starred).isFalse()
        assertThat(bank.color).isEqualTo(0)
        assertThat(db.entries().all().map { it.id }).containsExactly("e1", "e2")
        db.close()
    }

    @Test fun starringChangesOnlyTheStar(): Unit = runBlocking {
        createVersion1 {
            execSQL("INSERT INTO entries VALUES ('e1', NULL, 'Mail', '', 'me', 'pw', '', 100, 200)")
            execSQL("INSERT INTO entries VALUES ('e2', NULL, 'Shop', '', 'me', 'pw', '', 100, 200)")
        }
        val db = openCurrent()
        db.entries().setStarred(listOf("e1"), true)
        val mail = db.entries().byId("e1")!!
        assertThat(mail.starred).isTrue()
        assertThat(mail.updatedAt).isEqualTo(200L)
        assertThat(db.entries().byId("e2")!!.starred).isFalse()
        db.entries().setStarred(listOf("e1", "e2"), false)
        assertThat(db.entries().all().none { it.starred }).isTrue()
        db.close()
    }
}
