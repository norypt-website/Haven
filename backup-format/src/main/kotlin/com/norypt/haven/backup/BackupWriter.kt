package com.norypt.haven.backup

import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64

/**
 * Writes HVBK version 1 backups.
 *
 * Encryption is streaming: the caller writes the plaintext snapshot into the stream handed to
 * `writePayload` and only 1 MiB segments are buffered at a time. No plaintext is ever written to
 * [OutputStream] `output`; it receives framing and ciphertext only.
 */
public class BackupWriter(
    private val kdf: PasswordKdf,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Writes a complete backup to [output], which is closed when this method returns, whether it
     * succeeds or fails. If [writePayload] throws, the file is left without its end marker and
     * readers report it as [BackupFormatException.Truncated].
     *
     * @param passphrase UTF-8 bytes of the user's backup passphrase. The caller owns and wipes it.
     * @param params Argon2id parameters; must satisfy [KdfParams.isWithinBounds].
     */
    public fun write(
        output: OutputStream,
        passphrase: ByteArray,
        backupKey: BackupKey,
        params: KdfParams,
        appVersionCode: Int,
        contents: List<String>,
        createdAtEpochMs: Long,
        writePayload: (OutputStream) -> Unit,
    ) {
        require(params.isWithinBounds()) { "KDF parameters $params are outside the bounds readers accept" }
        output.use { out ->
            val salt = ByteArray(BackupFormat.SALT_LENGTH).also(random::nextBytes)
            val header = BackupHeader(
                version = BackupFormat.VERSION,
                createdAtEpochMs = createdAtEpochMs,
                appVersionCode = appVersionCode,
                kdf = params,
                salt = Base64.getEncoder().encodeToString(salt),
                backupKeyId = backupKey.keyId(),
                contents = contents,
                payloadFormat = BackupFormat.PAYLOAD_FORMAT,
            )
            val framing = HeaderCodec.encodeFraming(header)
            val aead = FileCipher.open(kdf, passphrase, salt, params, backupKey)

            out.write(framing)

            val keyCheck = FileCipher.encryptAll(aead, BackupFormat.keyCheckAad(framing), ByteArray(0))
            check(keyCheck.size == BackupFormat.KEY_CHECK_LENGTH) { "Unexpected key check size ${keyCheck.size}" }
            out.write(keyCheck)

            val counting = CountingOutputStream(NonClosingOutputStream(out))
            val payloadStream = PayloadOutputStream(
                aead.newEncryptingStream(counting, BackupFormat.bodyAad(framing)),
            )
            try {
                writePayload(payloadStream)
            } finally {
                payloadStream.close()
            }

            val endMarker = FileCipher.encryptAll(
                aead,
                BackupFormat.endMarkerAad(framing),
                encodeLength(counting.count),
            )
            check(endMarker.size == BackupFormat.END_MARKER_LENGTH) { "Unexpected end marker size ${endMarker.size}" }
            out.write(endMarker)
            out.flush()
        }
    }

    private fun encodeLength(value: Long): ByteArray =
        ByteArray(Long.SIZE_BYTES) { i -> (value ushr (8 * (Long.SIZE_BYTES - 1 - i))).toByte() }

    /** Routes every write through the encrypting stream and makes close idempotent. */
    private class PayloadOutputStream(private val encrypting: OutputStream) : OutputStream() {
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "payload stream is closed" }
            encrypting.write(b, off, len)
        }

        override fun flush() {
            if (!closed) encrypting.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            encrypting.close()
        }
    }
}
