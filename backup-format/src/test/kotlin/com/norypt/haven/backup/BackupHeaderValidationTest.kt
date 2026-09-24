package com.norypt.haven.backup

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupHeaderValidationTest {
    private val kdf = FakeKdf()
    private val reader = BackupReader(kdf)
    private val key = BackupKey.generate(seededRandom(3))

    private inline fun <reified T : BackupFormatException> assertRejected(file: ByteArray): T {
        val fromHeader = assertThrows(T::class.java) { reader.readHeader(ByteArrayInputStream(file)) }
        assertThrows(T::class.java) { reader.open(ByteArrayInputStream(file), TEST_PASSPHRASE, key) }
        assertThat(kdf.invocations).isEqualTo(0)
        return fromHeader
    }

    @Test
    fun acceptsValidHeaderWithUnknownKeys() {
        val header = reader.readHeader(ByteArrayInputStream(frame(headerJson(extra = ""","future":{"a":[1]}"""))))
        assertThat(header.version).isEqualTo(1)
        assertThat(header.kdf).isEqualTo(KdfParams(65536, 3, 4))
        assertThat(header.contents).containsExactly("content")
        assertThat(kdf.invocations).isEqualTo(0)
    }

    @Test
    fun headerLengthAboveLimitIsMalformedBeforeKdf() {
        val huge = ByteArray(BackupFormat.MAX_HEADER_LENGTH + 100) { '{'.code.toByte() }
        assertRejected<BackupFormatException.Malformed>(frame(String(huge), declaredLength = huge.size))
        assertRejected<BackupFormatException.Malformed>(frame(String(huge), declaredLength = 0xffff))
    }

    @Test
    fun headerLengthAtLimitIsParsed() {
        val json = headerJson(extra = ""","pad":"${"x".repeat(BackupFormat.MAX_HEADER_LENGTH)}"""")
        val body = json.toByteArray()
        val padded = json.replaceFirst("x".repeat(body.size - BackupFormat.MAX_HEADER_LENGTH), "")
        assertThat(padded.toByteArray()).hasLength(BackupFormat.MAX_HEADER_LENGTH)
        assertThat(reader.readHeader(ByteArrayInputStream(frame(padded))).version).isEqualTo(1)
    }

    @Test
    fun zeroLengthHeaderIsMalformed() {
        assertRejected<BackupFormatException.Malformed>(frame("", declaredLength = 0))
    }

    @Test
    fun wrongMagicAndEmptyInputAreMalformed() {
        assertRejected<BackupFormatException.Malformed>(ByteArray(0))
        assertRejected<BackupFormatException.Malformed>("HAVEN".toByteArray())
        assertRejected<BackupFormatException.Malformed>(frame(headerJson()).also { it[0] = 'X'.code.toByte() })
        assertRejected<BackupFormatException.Malformed>("PK\u0003\u0004".toByteArray() + ByteArray(64))
    }

    @Test
    fun headerCutShortIsTruncated() {
        val file = frame(headerJson())
        assertRejected<BackupFormatException.Truncated>(file.copyOf(9))
        assertRejected<BackupFormatException.Truncated>(file.copyOf(file.size - 1))
    }

    @Test
    fun invalidJsonIsMalformed() {
        assertRejected<BackupFormatException.Malformed>(frame("{not json"))
        assertRejected<BackupFormatException.Malformed>(frame("[1,2,3]"))
        assertRejected<BackupFormatException.Malformed>(frame("""{"version":"one"}"""))
        assertRejected<BackupFormatException.Malformed>(frame("""{"version":1}"""))
        assertRejected<BackupFormatException.Malformed>(frame(headerJson().replace("\"iterations\":3", "\"iterations\":\"three\"")))
        assertRejected<BackupFormatException.Malformed>(frame(headerJson().replace("\"contents\":[\"content\"]", "\"contents\":\"content\"")))
    }

    @Test
    fun otherVersionsAreUnsupported() {
        assertRejected<BackupFormatException.UnsupportedVersion>(frame(headerJson(version = 2)))
        assertRejected<BackupFormatException.UnsupportedVersion>(frame("""{"version":2,"anything":"goes"}"""))
        assertRejected<BackupFormatException.UnsupportedVersion>(frame(headerJson(version = 0)))
        assertRejected<BackupFormatException.UnsupportedVersion>(frame(headerJson(payloadFormat = 2)))
    }

    @Test
    fun kdfParametersOutOfBoundsAreRejectedBeforeKdf() {
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(memoryKib = 524_289)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(memoryKib = Int.MAX_VALUE)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(memoryKib = 8191)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(memoryKib = -65536)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(iterations = 0)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(iterations = 17)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(parallelism = 0)))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(parallelism = 9)))
        reader.readHeader(ByteArrayInputStream(frame(headerJson(memoryKib = 524_288, iterations = 16, parallelism = 8))))
        reader.readHeader(ByteArrayInputStream(frame(headerJson(memoryKib = 8192, iterations = 1, parallelism = 1))))
    }

    @Test
    fun saltAndKeyIdAreValidated() {
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(salt = "AAAA")))
        assertRejected<BackupFormatException.ParametersOutOfBounds>(frame(headerJson(salt = "A".repeat(44))))
        assertRejected<BackupFormatException.Malformed>(frame(headerJson(salt = "not base64!")))
        assertRejected<BackupFormatException.Malformed>(frame(headerJson(backupKeyId = "AAAA")))
        assertRejected<BackupFormatException.Malformed>(frame(headerJson(backupKeyId = "*")))
    }

    @Test
    fun writerRejectsParametersReadersWouldRefuse() {
        assertThrows(IllegalArgumentException::class.java) {
            writeBackupWithParams(KdfParams(memoryKib = 4096, iterations = 1, parallelism = 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeBackupWithParams(KdfParams(memoryKib = 65536, iterations = 3, parallelism = 9))
        }
        assertThat(kdf.invocations).isEqualTo(0)
    }

    private fun writeBackupWithParams(params: KdfParams) {
        BackupWriter(kdf).write(
            output = java.io.ByteArrayOutputStream(),
            passphrase = TEST_PASSPHRASE,
            backupKey = key,
            params = params,
            appVersionCode = 1,
            contents = emptyList(),
            createdAtEpochMs = 0L,
        ) { }
    }
}
