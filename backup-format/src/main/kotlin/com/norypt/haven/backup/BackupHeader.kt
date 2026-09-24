package com.norypt.haven.backup

import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The cleartext header of a backup. Every byte of it is bound to the ciphertext as associated
 * data, so it cannot be modified without breaking authentication, but it is not confidential.
 */
@Serializable
public data class BackupHeader(
    val version: Int = BackupFormat.VERSION,
    val createdAtEpochMs: Long,
    val appVersionCode: Int,
    val kdf: KdfParams,
    /** Base64 of the 16 random salt bytes used by Argon2id and HKDF. */
    val salt: String,
    /** Base64 of the first 8 bytes of SHA-256(backupKey); see [BackupKey.keyId]. */
    val backupKeyId: String,
    /** Names of the payload sections present, for example `content` and `passwords`. */
    val contents: List<String>,
    val payloadFormat: Int = BackupFormat.PAYLOAD_FORMAT,
) {
    /** The decoded salt. Only meaningful for a header that passed validation. */
    public fun saltBytes(): ByteArray = Base64.getDecoder().decode(salt)
}

/** A validated header together with the exact framing bytes that carried it. */
internal class Framing(val header: BackupHeader, val bytes: ByteArray)

internal object HeaderCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeFraming(header: BackupHeader): ByteArray {
        val body = json.encodeToString(BackupHeader.serializer(), header).toByteArray(Charsets.UTF_8)
        require(body.size in 1..BackupFormat.MAX_HEADER_LENGTH) {
            "Header is ${body.size} bytes; the limit is ${BackupFormat.MAX_HEADER_LENGTH}"
        }
        val out = ByteArray(BackupFormat.FRAMING_PREFIX_LENGTH + body.size)
        BackupFormat.MAGIC_BYTES.copyInto(out)
        out[8] = (body.size ushr 8).toByte()
        out[9] = body.size.toByte()
        body.copyInto(out, BackupFormat.FRAMING_PREFIX_LENGTH)
        return out
    }

    /**
     * Reads magic, length and header, enforcing every bound before the header is parsed and
     * before any allocation larger than [BackupFormat.MAX_HEADER_LENGTH].
     */
    fun readFraming(input: InputStream): Framing {
        val prefix = ByteArray(BackupFormat.FRAMING_PREFIX_LENGTH)
        val got = readUpTo(input, prefix)
        val magicLength = BackupFormat.MAGIC_BYTES.size
        if (got < magicLength || !prefix.copyOf(magicLength).contentEquals(BackupFormat.MAGIC_BYTES)) {
            throw BackupFormatException.Malformed("Not a HAVEN backup file")
        }
        if (got < prefix.size) throw BackupFormatException.Truncated("Backup ends inside the header length")
        val length = ((prefix[8].toInt() and 0xff) shl 8) or (prefix[9].toInt() and 0xff)
        if (length == 0 || length > BackupFormat.MAX_HEADER_LENGTH) {
            throw BackupFormatException.Malformed(
                "Header length $length is outside 1..${BackupFormat.MAX_HEADER_LENGTH}",
            )
        }
        val body = ByteArray(length)
        if (readUpTo(input, body) != length) throw BackupFormatException.Truncated("Backup ends inside the header")
        return Framing(parse(body), prefix + body)
    }

    fun parse(body: ByteArray): BackupHeader {
        val element = try {
            json.parseToJsonElement(String(body, Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw BackupFormatException.Malformed("Header is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException.Malformed("Header is not valid JSON", e)
        }
        val obj = element as? JsonObject ?: throw BackupFormatException.Malformed("Header is not a JSON object")
        val version = when (val v = obj["version"]) {
            null -> BackupFormat.VERSION
            is JsonPrimitive -> v.intOrNull ?: throw BackupFormatException.Malformed("Header version is not an integer")
            else -> throw BackupFormatException.Malformed("Header version is not an integer")
        }
        if (version != BackupFormat.VERSION) {
            throw BackupFormatException.UnsupportedVersion(
                "Backup version $version is not supported; this build reads version ${BackupFormat.VERSION}",
            )
        }
        val header = try {
            json.decodeFromJsonElement(BackupHeader.serializer(), obj)
        } catch (e: SerializationException) {
            throw BackupFormatException.Malformed("Header is missing or mistypes a field", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException.Malformed("Header is missing or mistypes a field", e)
        }
        validate(header)
        return header
    }

    private fun validate(header: BackupHeader) {
        if (header.payloadFormat != BackupFormat.PAYLOAD_FORMAT) {
            throw BackupFormatException.UnsupportedVersion(
                "Payload format ${header.payloadFormat} is not supported; this build reads format ${BackupFormat.PAYLOAD_FORMAT}",
            )
        }
        val kdf = header.kdf
        if (kdf.memoryKib !in KdfParams.MIN_MEMORY_KIB..KdfParams.MAX_MEMORY_KIB) {
            throw BackupFormatException.ParametersOutOfBounds(
                "Argon2 memory ${kdf.memoryKib} KiB is outside ${KdfParams.MIN_MEMORY_KIB}..${KdfParams.MAX_MEMORY_KIB}",
            )
        }
        if (kdf.iterations !in KdfParams.MIN_ITERATIONS..KdfParams.MAX_ITERATIONS) {
            throw BackupFormatException.ParametersOutOfBounds(
                "Argon2 iterations ${kdf.iterations} is outside ${KdfParams.MIN_ITERATIONS}..${KdfParams.MAX_ITERATIONS}",
            )
        }
        if (kdf.parallelism !in KdfParams.MIN_PARALLELISM..KdfParams.MAX_PARALLELISM) {
            throw BackupFormatException.ParametersOutOfBounds(
                "Argon2 parallelism ${kdf.parallelism} is outside ${KdfParams.MIN_PARALLELISM}..${KdfParams.MAX_PARALLELISM}",
            )
        }
        val salt = decodeBase64(header.salt) ?: throw BackupFormatException.Malformed("Header salt is not valid base64")
        if (salt.size != BackupFormat.SALT_LENGTH) {
            throw BackupFormatException.ParametersOutOfBounds(
                "Salt must be ${BackupFormat.SALT_LENGTH} bytes, got ${salt.size}",
            )
        }
        val keyId = decodeBase64(header.backupKeyId)
            ?: throw BackupFormatException.Malformed("Header backupKeyId is not valid base64")
        if (keyId.size != BackupFormat.KEY_ID_LENGTH) {
            throw BackupFormatException.Malformed("Header backupKeyId must be ${BackupFormat.KEY_ID_LENGTH} bytes")
        }
    }

    private fun decodeBase64(text: String): ByteArray? =
        try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            null
        }
}
