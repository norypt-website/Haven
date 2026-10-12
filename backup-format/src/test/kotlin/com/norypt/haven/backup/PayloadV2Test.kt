package com.norypt.haven.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class PayloadV2Test {
    private val photoA = PhotoRecord("a", "image/jpeg", 3, 4, 3, 10, 20)
    private val photoB = PhotoRecord("b", "image/png", 0, 1, 1, null, 21)
    private val payload = BackupPayload(
        content = ContentSnapshot(emptyList(), emptyList(), emptyList(), notes = listOf(NoteRecord("n", "T", "B", 1, 2))),
        passwords = PasswordSnapshot(emptyList(), emptyList(), photos = listOf(photoA, photoB)),
    )
    private val blobs = mapOf("a" to byteArrayOf(1, 2, 3), "b" to ByteArray(0))

    private fun encode(p: BackupPayload = payload, bytes: (PhotoRecord) -> ByteArray = { blobs.getValue(it.id).copyOf() }) =
        ByteArrayOutputStream().also { PayloadV2.write(it, p, bytes) }.toByteArray()

    @Test fun roundTrip() {
        val seen = mutableMapOf<String, ByteArray>()
        val back = PayloadV2.read(ByteArrayInputStream(encode())) { r, b -> seen[r.id] = b.copyOf() }
        assertThat(back.content!!.notes.single().title).isEqualTo("T")
        assertThat(back.passwords!!.photos).containsExactly(photoA, photoB).inOrder()
        assertThat(seen["a"]).isEqualTo(byteArrayOf(1, 2, 3)); assertThat(seen["b"]).isEmpty()
    }

    @Test fun writerRefusesASizeMismatch() {
        assertThrows(IllegalStateException::class.java) { encode(bytes = { byteArrayOf(1) }) }
    }

    @Test fun truncatedPhotoSectionIsRejected() {
        val e = encode()
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(e.copyOf(e.size - 2))) { _, _ -> } }
    }

    @Test fun trailingBytesAreRejected() {
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(encode() + byteArrayOf(0))) { _, _ -> } }
    }

    @Test fun declaredLengthDifferentFromRecordIsRejected() {
        val e = encode(); val jsonLen = ByteBuffer.wrap(e, 0, 4).int
        // first blob length field sits right after the JSON
        ByteBuffer.wrap(e, 4 + jsonLen, 8).putLong(2)
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(e)) { _, _ -> } }
    }

    @Test fun oversizeJsonLengthIsRejectedBeforeAllocation() {
        val bad = ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(bad)) { _, _ -> } }
    }

    @Test fun tooManyPhotosRejected() {
        val many = PasswordSnapshot(emptyList(), emptyList(), photos = List(PayloadV2.MAX_PHOTOS + 1) { PhotoRecord("$it", "image/jpeg", 0, 1, 1, null, 0) })
        val e = encode(BackupPayload(passwords = many)) { ByteArray(0) }
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(e)) { _, _ -> } }
    }

    @Test fun oversizePhotoRecordRejected() {
        val big = BackupPayload(passwords = PasswordSnapshot(emptyList(), emptyList(), photos = listOf(PhotoRecord("x", "image/jpeg", PayloadV2.MAX_PHOTO_BYTES + 1, 1, 1, null, 0))))
        val json = BackupPayloadJson.encode(big).toByteArray()
        val bytes = ByteBuffer.allocate(4).putInt(json.size).array() + json
        assertThrows(BackupFormatException.Malformed::class.java) { PayloadV2.read(ByteArrayInputStream(bytes)) { _, _ -> } }
    }

    @Test fun formatOneDocumentsStillDecode() {
        val v1 = """{"format":1,"content":{"reminders":[],"taskLists":[],"tasks":[]},"settings":{}}"""
        val p = BackupPayloadJson.decode(v1)
        assertThat(p.content!!.notes).isEmpty()
    }

    @Test fun fullFileRoundTripThroughTheContainer() {
        val key = BackupKey.generate()
        val file = writeBackup(encode(), key = key)
        val plain = readBackup(file, key = key)
        val back = PayloadV2.read(ByteArrayInputStream(plain)) { _, _ -> }
        assertThat(back.passwords!!.photos).hasSize(2)
    }
}
