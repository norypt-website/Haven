package com.norypt.haven.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.storage.passwords.PasswordDatabase
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plain SQLite stands in for SQLCipher: the queries are the same. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordEntryDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PasswordDatabase::class.java).build()

    @After fun close() { db.close() }

    private fun entry(id: String, starred: Boolean = false) = PasswordEntryEntity(id, null, "Title $id", "", "", "secret-$id", "", 1L, 1L, starred = starred)

    @Test fun allAndStarredCountsFollowTheStars(): Unit = runBlocking {
        db.entries().upsertAll(listOf(entry("a", starred = true), entry("b"), entry("c")))
        assertThat(db.entries().observeCount().first()).isEqualTo(3)
        assertThat(db.entries().observeStarredCount().first()).isEqualTo(1)
        db.entries().setStarred(listOf("b", "c"), true)
        assertThat(db.entries().observeStarredCount().first()).isEqualTo(3)
    }
}
