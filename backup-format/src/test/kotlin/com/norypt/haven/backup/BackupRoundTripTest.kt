package com.norypt.haven.backup

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRoundTripTest {
    private val key = BackupKey.generate(seededRandom(11))
    private val smallPayload = "HAVEN-PLAINTEXT-MARKER ".repeat(40).toByteArray()
    private val smallFile = writeBackup(smallPayload, key = key)

    @Test
    fun roundTripsSmallPayload() {
        val reader = BackupReader(FakeKdf())
        val opened = reader.open(ByteArrayInputStream(smallFile), TEST_PASSPHRASE, key)
        assertThat(opened.header.version).isEqualTo(1)
        assertThat(opened.header.appVersionCode).isEqualTo(3)
        assertThat(opened.header.createdAtEpochMs).isEqualTo(1_700_000_000_000L)
        assertThat(opened.header.contents).containsExactly("content", "passwords").inOrder()
        assertThat(opened.header.backupKeyId).isEqualTo(key.keyId())
        assertThat(opened.header.kdf).isEqualTo(TEST_PARAMS)
        assertThat(opened.header.saltBytes()).hasLength(16)
        assertThat(opened.plaintext.use { it.readBytes() }).isEqualTo(smallPayload)
    }

    @Test
    fun roundTripsMultiMegabytePayload() {
        val payload = pseudoRandomBytes(3 * 1024 * 1024 + 12_345)
        val file = writeBackup(payload, key = key)
        assertThat(file.size).isGreaterThan(payload.size)
        assertThat(readBackup(file, key = key)).isEqualTo(payload)
    }

    @Test
    fun roundTripsPayloadOnSegmentBoundaryAndEmptyPayload() {
        val boundary = pseudoRandomBytes(2 * 1024 * 1024)
        assertThat(readBackup(writeBackup(boundary, key = key), key = key)).isEqualTo(boundary)
        assertThat(readBackup(writeBackup(ByteArray(0), key = key), key = key)).isEmpty()
    }

    @Test
    fun payloadWrittenInSmallPiecesAndSingleBytes() {
        val payload = pseudoRandomBytes(70_000)
        val out = ByteArrayOutputStream()
        BackupWriter(FakeKdf(), seededRandom()).write(out, TEST_PASSPHRASE, key, TEST_PARAMS, 1, listOf("content"), 5L) { stream ->
            for (i in 0 until 1000) stream.write(payload[i].toInt())
            stream.write(payload, 1000, payload.size - 1000)
            stream.flush()
        }
        assertThat(readBackup(out.toByteArray(), key = key)).isEqualTo(payload)
    }

    @Test
    fun readHeaderMatchesWrittenHeaderAndSkipsKdf() {
        val kdf = FakeKdf()
        val header = BackupReader(kdf).readHeader(ByteArrayInputStream(smallFile))
        assertThat(header.backupKeyId).isEqualTo(key.keyId())
        assertThat(kdf.invocations).isEqualTo(0)
    }

    @Test
    fun fileLayoutMatchesSpecification() {
        val framing = framingLength(smallFile)
        assertThat(String(smallFile, 0, 8, Charsets.US_ASCII)).isEqualTo("HAVENBK1")
        assertThat(String(smallFile, 10, framing - 10, Charsets.UTF_8)).startsWith("{\"version\":1,")
        val body = smallFile.size - framing - BackupFormat.KEY_CHECK_LENGTH - BackupFormat.END_MARKER_LENGTH
        // Tink streaming header (40) plus one final segment carrying the payload and a 16-byte tag.
        assertThat(body).isEqualTo(40 + smallPayload.size + 16)
    }

    @Test
    fun outputNeverContainsPlaintext() {
        val marker = "HAVEN-PLAINTEXT-MARKER".toByteArray()
        assertThat(indexOf(smallFile, marker)).isEqualTo(-1)
        val big = writeBackup(ByteArray(2 * 1024 * 1024 + 500) { 'A'.code.toByte() }, key = key)
        assertThat(indexOf(big, ByteArray(64) { 'A'.code.toByte() })).isEqualTo(-1)
    }

    @Test
    fun differentSaltPerBackupProducesDifferentFiles() {
        val a = ByteArrayOutputStream()
        val b = ByteArrayOutputStream()
        val writer = BackupWriter(FakeKdf())
        writer.write(a, TEST_PASSPHRASE, key, TEST_PARAMS, 1, listOf("content"), 1L) { it.write(smallPayload) }
        writer.write(b, TEST_PASSPHRASE, key, TEST_PARAMS, 1, listOf("content"), 1L) { it.write(smallPayload) }
        assertThat(a.toByteArray()).isNotEqualTo(b.toByteArray())
        assertThat(readBackup(a.toByteArray(), key = key)).isEqualTo(smallPayload)
        assertThat(readBackup(b.toByteArray(), key = key)).isEqualTo(smallPayload)
    }

    @Test
    fun wrongPassphraseFailsAuthenticationAtOpen() {
        val kdf = FakeKdf()
        assertThrows(BackupFormatException.AuthenticationFailed::class.java) {
            BackupReader(kdf).open(ByteArrayInputStream(smallFile), "wrong passphrase".toByteArray(), key)
        }
        assertThat(kdf.invocations).isEqualTo(1)
    }

    @Test
    fun wrongBackupKeyFailsAuthenticationAtOpen() {
        val other = BackupKey.generate(seededRandom(12))
        assertThrows(BackupFormatException.AuthenticationFailed::class.java) {
            BackupReader(FakeKdf()).open(ByteArrayInputStream(smallFile), TEST_PASSPHRASE, other)
        }
    }

    @Test
    fun bothFactorsAreRequired() {
        val freshKey = BackupKey.generate()
        assertThat(readFailure(smallFile, TEST_PASSPHRASE, freshKey))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        assertThat(readFailure(smallFile, "another passphrase".toByteArray(), key))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        assertThat(readFailure(smallFile, ByteArray(0), key))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        assertThat(readFailure(smallFile, "another passphrase".toByteArray(), freshKey))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        assertThat(readFailure(smallFile, TEST_PASSPHRASE, key)).isNull()
    }

    @Test
    fun everyHeaderByteFlipIsRejectedWithoutPlaintext() {
        val framing = framingLength(smallFile)
        for (position in 0 until framing) {
            for (mask in intArrayOf(0x01, 0x80)) {
                val failure = readFailure(smallFile.flipped(position, mask), key = key)
                assertThat(failure).isNotNull()
                assertThat(failure).isNotInstanceOf(BackupFormatException.Truncated::class.java)
                if (position >= BackupFormat.FRAMING_PREFIX_LENGTH) {
                    assertThat(failure).isAnyOf(
                        failure as? BackupFormatException.AuthenticationFailed,
                        failure as? BackupFormatException.Malformed,
                        failure as? BackupFormatException.UnsupportedVersion,
                        failure as? BackupFormatException.ParametersOutOfBounds,
                    )
                }
            }
        }
    }

    @Test
    fun semanticHeaderChangeFailsAuthentication() {
        val framing = framingLength(smallFile)
        val text = String(smallFile, 10, framing - 10, Charsets.UTF_8)
        assertThat(text).contains("\"createdAtEpochMs\":1700000000000")
        val edited = text.replace("\"createdAtEpochMs\":1700000000000", "\"createdAtEpochMs\":1700000000001").toByteArray()
        val file = smallFile.copyOf(10) + edited + smallFile.copyOfRange(framing, smallFile.size)
        assertThat(readFailure(file, key = key)).isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
    }

    @Test
    fun flippedCiphertextByteFailsAuthentication() {
        val framing = framingLength(smallFile)
        val keyCheckStart = framing
        val bodyStart = framing + BackupFormat.KEY_CHECK_LENGTH
        val endMarkerStart = smallFile.size - BackupFormat.END_MARKER_LENGTH
        for (position in listOf(keyCheckStart, keyCheckStart + 50, bodyStart, bodyStart + 41, (bodyStart + endMarkerStart) / 2, endMarkerStart - 1, endMarkerStart + 3, smallFile.size - 1)) {
            assertThat(readFailure(smallFile.flipped(position), key = key))
                .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        }
    }

    @Test
    fun flippedByteInLaterSegmentFailsAuthentication() {
        val payload = pseudoRandomBytes(3 * 1024 * 1024 + 999)
        val file = writeBackup(payload, key = key)
        val framing = framingLength(file)
        val bodyStart = framing + BackupFormat.KEY_CHECK_LENGTH
        val secondSegment = bodyStart + (1 shl 20) + 100
        assertThat(readFailure(file.flipped(secondSegment), key = key))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        val lastSegment = file.size - BackupFormat.END_MARKER_LENGTH - 20
        assertThat(readFailure(file.flipped(lastSegment), key = key))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
        assertThat(readFailure(file.flipped(file.size - 1), key = key))
            .isInstanceOf(BackupFormatException.AuthenticationFailed::class.java)
    }

    @Test
    fun truncatedFilesAreReportedAsTruncated() {
        val framing = framingLength(smallFile)
        val cuts = listOf(
            smallFile.size - 10,
            smallFile.size - 1,
            smallFile.size - BackupFormat.END_MARKER_LENGTH,
            smallFile.size - BackupFormat.END_MARKER_LENGTH - 5,
            framing + BackupFormat.KEY_CHECK_LENGTH + 20,
            framing + BackupFormat.KEY_CHECK_LENGTH,
            framing + 30,
            framing,
            framing - 5,
            9,
        )
        for (cut in cuts) {
            assertThat(readFailure(smallFile.copyOf(cut), key = key))
                .isInstanceOf(BackupFormatException.Truncated::class.java)
        }
    }

    @Test
    fun largeFileCutInTheMiddleIsTruncated() {
        val payload = pseudoRandomBytes(3 * 1024 * 1024 + 777)
        val file = writeBackup(payload, key = key)
        val bodyStart = framingLength(file) + BackupFormat.KEY_CHECK_LENGTH
        for (cut in listOf(file.size / 2, bodyStart + (1 shl 20), bodyStart + 40 + (1 shl 20) - 40, file.size - (1 shl 20) - 3, file.size - 70)) {
            assertThat(readFailure(file.copyOf(cut), key = key))
                .isInstanceOf(BackupFormatException.Truncated::class.java)
        }
    }

    @Test
    fun failureIsStickyAndReadBeforeFailureIsNotTrusted() {
        val payload = pseudoRandomBytes(2 * 1024 * 1024 + 100)
        val file = writeBackup(payload, key = key)
        val truncated = file.copyOf(file.size - 200)
        val opened = BackupReader(FakeKdf()).open(ByteArrayInputStream(truncated), TEST_PASSPHRASE, key)
        val first = ByteArray(1024)
        assertThat(opened.plaintext.read(first)).isEqualTo(1024)
        assertThat(first).isEqualTo(payload.copyOf(1024))
        assertThrows(BackupFormatException.Truncated::class.java) { opened.plaintext.readBytes() }
        assertThrows(BackupFormatException.Truncated::class.java) { opened.plaintext.read() }
    }

    @Test
    fun underlyingIoErrorsAreNotMisreportedAsFormatErrors() {
        val framing = framingLength(smallFile)
        val failAt = framing + BackupFormat.KEY_CHECK_LENGTH + 30
        val source = object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position >= failAt) throw IOException("disk unplugged")
                return smallFile[position++].toInt() and 0xff
            }
        }
        val opened = BackupReader(FakeKdf()).open(source, TEST_PASSPHRASE, key)
        val error = assertThrows(IOException::class.java) { opened.plaintext.readBytes() }
        assertThat(error).isNotInstanceOf(BackupFormatException::class.java)
        assertThat(error).hasMessageThat().isEqualTo("disk unplugged")
    }

    @Test
    fun payloadSnapshotSurvivesTheContainer() {
        val snapshot = BackupPayload(
            passwords = PasswordSnapshot(emptyList(), listOf(PasswordEntryRecord("p", null, "t", "w", "u", "pw", "n", 1L, 2L))),
        )
        val out = ByteArrayOutputStream()
        BackupWriter(FakeKdf()).write(out, TEST_PASSPHRASE, key, TEST_PARAMS, 1, listOf("passwords"), 1L) {
            BackupPayloadJson.encode(snapshot, it)
        }
        val opened = BackupReader(FakeKdf()).open(ByteArrayInputStream(out.toByteArray()), TEST_PASSPHRASE, key)
        assertThat(opened.plaintext.use { BackupPayloadJson.decode(it) }).isEqualTo(snapshot)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
