package com.norypt.haven.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The plaintext snapshot carried inside a backup, payload format 1. */
@Serializable
public data class BackupPayload(
    val format: Int = BackupFormat.PAYLOAD_FORMAT,
    val content: ContentSnapshot? = null,
    val passwords: PasswordSnapshot? = null,
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
public data class ContentSnapshot(
    val reminders: List<ReminderRecord>,
    val taskLists: List<TaskListRecord>,
    val tasks: List<TaskRecord>,
)

@Serializable
public data class ReminderRecord(
    val id: String,
    val title: String,
    val notes: String,
    val enabled: Boolean,
    val scheduleJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val priority: Int = 0,
    val starred: Boolean = false,
)

@Serializable
public data class TaskListRecord(
    val id: String,
    val name: String,
    val position: Int,
    val createdAtEpochMs: Long,
)

@Serializable
public data class TaskRecord(
    val id: String,
    val listId: String,
    val title: String,
    val notes: String,
    val completed: Boolean,
    val completedAtEpochMs: Long?,
    /** ISO-8601 local date-time without offset, or null when the task has no due date. */
    val dueLocal: String?,
    val reminderId: String?,
    val position: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val priority: Int = 0,
    val starred: Boolean = false,
    val followUpMinutes: Int? = null,
)

@Serializable
public data class PasswordSnapshot(
    val folders: List<FolderRecord>,
    val entries: List<PasswordEntryRecord>,
)

@Serializable
public data class FolderRecord(
    val id: String,
    val name: String,
    val position: Int,
)

@Serializable
public data class PasswordEntryRecord(
    val id: String,
    val folderId: String?,
    val title: String,
    val website: String,
    val username: String,
    val password: String,
    val notes: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** JSON encoding of [BackupPayload]. Decoding ignores unknown keys and rejects unknown formats. */
public object BackupPayloadJson {
    /** Upper bound on the size of a payload document accepted by [decode]. */
    /** Matches the app's restore bound; the decoded JSON is held in memory, so keep this phone-sized. */
    public const val MAX_PAYLOAD_BYTES: Long = 64L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    /** Writes the UTF-8 JSON document to [output] without closing it. */
    public fun encode(payload: BackupPayload, output: OutputStream) {
        output.write(encode(payload).toByteArray(Charsets.UTF_8))
    }

    public fun decode(text: String): BackupPayload {
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), text)
        } catch (e: SerializationException) {
            throw describeFailure(text, e)
        } catch (e: IllegalArgumentException) {
            throw describeFailure(text, e)
        }
        if (payload.format != BackupFormat.PAYLOAD_FORMAT) throw unsupportedFormat(payload.format)
        return payload
    }

    /**
     * Reads the whole document from [input] and decodes it. Streams larger than [maxBytes]
     * are rejected with [BackupFormatException.Malformed] before they are parsed.
     */
    public fun decode(input: InputStream, maxBytes: Long = MAX_PAYLOAD_BYTES): BackupPayload {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw BackupFormatException.Malformed("Payload exceeds $maxBytes bytes")
            buffer.write(chunk, 0, n)
        }
        return decode(buffer.toString(Charsets.UTF_8.name()))
    }

    @Serializable
    private class FormatProbe(val format: Int = BackupFormat.PAYLOAD_FORMAT)

    /** A failed decode of a newer format is reported as unsupported rather than malformed. */
    private fun describeFailure(text: String, cause: Exception): BackupFormatException {
        val format = try {
            json.decodeFromString(FormatProbe.serializer(), text).format
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (format != null && format != BackupFormat.PAYLOAD_FORMAT) return unsupportedFormat(format)
        return BackupFormatException.Malformed("Payload is not a valid snapshot document", cause)
    }

    private fun unsupportedFormat(format: Int): BackupFormatException.UnsupportedVersion =
        BackupFormatException.UnsupportedVersion(
            "Payload format $format is not supported; this build reads format ${BackupFormat.PAYLOAD_FORMAT}",
        )
}
