package com.norypt.haven.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReplaceWithNoiseTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun theFilesKeepTheirNamesAndSizesButNotTheirContent() {
        val db = File(tmp.root, "content.db").apply { writeBytes(ByteArray(8192) { 7 }) }
        val journal = File(tmp.root, "content.db-journal").apply { writeBytes(ByteArray(0)) }
        EncryptedDatabaseOpener.replaceWithNoise(db)
        assertThat(db.length()).isEqualTo(8192L)
        assertThat(db.readBytes().count { it == 7.toByte() }).isLessThan(200)
        assertThat(journal.exists()).isTrue()
        assertThat(journal.length()).isEqualTo(0L)
    }

    @Test fun filesThatWereNotThereStayAbsent() {
        val db = File(tmp.root, "passwords.db")
        EncryptedDatabaseOpener.replaceWithNoise(db)
        assertThat(tmp.root.list()).isEmpty()
    }
}
