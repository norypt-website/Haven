package com.norypt.haven.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Random

internal val TEST_PARAMS = KdfParams(memoryKib = 8192, iterations = 1, parallelism = 1)
internal val TEST_PASSPHRASE = "correct horse battery staple".toByteArray()

internal fun seededRandom(seed: Long = 7): SecureRandom {
    val source = Random(seed)
    return object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) = source.nextBytes(bytes)
    }
}

internal fun pseudoRandomBytes(size: Int, seed: Long = 42): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

internal fun writeBackup(
    payload: ByteArray,
    passphrase: ByteArray = TEST_PASSPHRASE,
    key: BackupKey,
    kdf: PasswordKdf = FakeKdf(),
    contents: List<String> = listOf("content", "passwords"),
): ByteArray {
    val out = ByteArrayOutputStream()
    BackupWriter(kdf, seededRandom()).write(
        output = out,
        passphrase = passphrase,
        backupKey = key,
        params = TEST_PARAMS,
        appVersionCode = 3,
        contents = contents,
        createdAtEpochMs = 1_700_000_000_000L,
    ) { stream -> stream.write(payload) }
    return out.toByteArray()
}

/** Opens and reads the whole plaintext; any failure on open or read propagates. */
internal fun readBackup(
    file: ByteArray,
    passphrase: ByteArray = TEST_PASSPHRASE,
    key: BackupKey,
    kdf: PasswordKdf = FakeKdf(),
): ByteArray {
    val opened = BackupReader(kdf).open(ByteArrayInputStream(file), passphrase, key)
    return opened.plaintext.use { it.readBytes() }
}

/** Returns the failure raised while opening or reading to end-of-stream, or null on success. */
internal fun readFailure(
    file: ByteArray,
    passphrase: ByteArray = TEST_PASSPHRASE,
    key: BackupKey,
    kdf: PasswordKdf = FakeKdf(),
): BackupFormatException? =
    try {
        readBackup(file, passphrase, key, kdf)
        null
    } catch (e: BackupFormatException) {
        e
    }

internal fun ByteArray.flipped(position: Int, mask: Int = 0x01): ByteArray =
    copyOf().also { it[position] = (it[position].toInt() xor mask).toByte() }

internal fun framingLength(file: ByteArray): Int =
    BackupFormat.FRAMING_PREFIX_LENGTH + (((file[8].toInt() and 0xff) shl 8) or (file[9].toInt() and 0xff))

/** Builds framing bytes around an arbitrary header document, for header validation tests. */
internal fun frame(headerJson: String, declaredLength: Int? = null): ByteArray {
    val body = headerJson.toByteArray()
    val length = declaredLength ?: body.size
    val out = ByteArrayOutputStream()
    out.write(BackupFormat.MAGIC_BYTES)
    out.write(length ushr 8)
    out.write(length and 0xff)
    out.write(body)
    return out.toByteArray()
}

internal fun headerJson(
    version: Int = 1,
    memoryKib: Int = 65536,
    iterations: Int = 3,
    parallelism: Int = 4,
    salt: String = "AAAAAAAAAAAAAAAAAAAAAA==",
    backupKeyId: String = "AAAAAAAAAAA=",
    payloadFormat: Int = 1,
    extra: String = "",
): String =
    """{"version":$version,"createdAtEpochMs":1,"appVersionCode":1,""" +
        """"kdf":{"memoryKib":$memoryKib,"iterations":$iterations,"parallelism":$parallelism},""" +
        """"salt":"$salt","backupKeyId":"$backupKeyId","contents":["content"],"payloadFormat":$payloadFormat$extra}"""
