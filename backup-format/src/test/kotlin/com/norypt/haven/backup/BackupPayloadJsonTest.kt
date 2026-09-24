package com.norypt.haven.backup

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPayloadJsonTest {
    private val payload = BackupPayload(
        content = ContentSnapshot(
            reminders = listOf(
                ReminderRecord("r1", "Water plants", "", true, """{"kind":"daily"}""", 1L, 2L),
            ),
            taskLists = listOf(TaskListRecord("l1", "Inbox", 0, 1L)),
            tasks = listOf(
                TaskRecord("t1", "l1", "Buy milk", "2%", false, null, "2026-09-24T09:00", "r1", 0, 1L, 2L),
                TaskRecord("t2", "l1", "Done", "", true, 5L, null, null, 1, 1L, 5L),
            ),
        ),
        passwords = PasswordSnapshot(
            folders = listOf(FolderRecord("f1", "Work", 0)),
            entries = listOf(
                PasswordEntryRecord("p1", "f1", "Mail", "https://example.org", "me", "s3cret", "", 1L, 2L),
                PasswordEntryRecord("p2", null, "Root", "", "root", "pw", "notes", 1L, 2L),
            ),
        ),
        settings = mapOf("theme" to "dark"),
    )

    @Test
    fun roundTripsThroughString() {
        val text = BackupPayloadJson.encode(payload)
        assertThat(text).contains("\"format\":1")
        assertThat(BackupPayloadJson.decode(text)).isEqualTo(payload)
    }

    @Test
    fun roundTripsThroughStreams() {
        val out = ByteArrayOutputStream()
        BackupPayloadJson.encode(payload, out)
        assertThat(BackupPayloadJson.decode(ByteArrayInputStream(out.toByteArray()))).isEqualTo(payload)
    }

    @Test
    fun ignoresUnknownKeys() {
        val text = """{"format":1,"future":{"deep":[1,2,{"x":null}]},"content":null,"passwords":null,"settings":{},"extra":true}"""
        assertThat(BackupPayloadJson.decode(text)).isEqualTo(BackupPayload())
        val record = """{"format":1,"passwords":{"folders":[{"id":"f","name":"n","position":0,"colour":"red"}],"entries":[]}}"""
        assertThat(BackupPayloadJson.decode(record).passwords?.folders).containsExactly(FolderRecord("f", "n", 0))
    }

    @Test
    fun rejectsUnknownFormat() {
        assertThrows(BackupFormatException.UnsupportedVersion::class.java) {
            BackupPayloadJson.decode("""{"format":2}""")
        }
        assertThrows(BackupFormatException.UnsupportedVersion::class.java) {
            BackupPayloadJson.decode("""{"format":2,"content":{"totally":"different"}}""")
        }
    }

    @Test
    fun rejectsMalformedDocuments() {
        assertThrows(BackupFormatException.Malformed::class.java) { BackupPayloadJson.decode("not json") }
        assertThrows(BackupFormatException.Malformed::class.java) { BackupPayloadJson.decode("[]") }
        assertThrows(BackupFormatException.Malformed::class.java) {
            BackupPayloadJson.decode("""{"format":1,"content":{"reminders":[]}}""")
        }
    }

    @Test
    fun boundsStreamSize() {
        val text = BackupPayloadJson.encode(payload).toByteArray()
        assertThrows(BackupFormatException.Malformed::class.java) {
            BackupPayloadJson.decode(ByteArrayInputStream(text), maxBytes = (text.size - 1).toLong())
        }
        assertThat(BackupPayloadJson.decode(ByteArrayInputStream(text), maxBytes = text.size.toLong())).isEqualTo(payload)
    }
}
