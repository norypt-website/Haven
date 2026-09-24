package com.norypt.haven.backup

import com.google.crypto.tink.StreamingAead
import java.io.IOException
import java.io.InputStream

/** A backup whose header and credentials were accepted. Read [plaintext] to end-of-stream. */
public data class OpenedBackup(
    val header: BackupHeader,
    val plaintext: InputStream,
)

/**
 * Reads HVBK version 1 backups.
 *
 * Every header value is bounds-checked before it is used and before any KDF work happens.
 * Wrong credentials and modified files surface as [BackupFormatException.AuthenticationFailed];
 * files that end early surface as [BackupFormatException.Truncated].
 */
public class BackupReader(private val kdf: PasswordKdf) {

    /**
     * Reads and validates only the header. Consumes the framing bytes from [input] and performs
     * no key derivation, so it is cheap to call before asking the user for credentials.
     */
    public fun readHeader(input: InputStream): BackupHeader = HeaderCodec.readFraming(input).header

    /**
     * Validates the header, derives the file key from both factors and verifies them against the
     * key check block. Wrong credentials fail here with [BackupFormatException.AuthenticationFailed].
     *
     * Integrity of the payload is only established as it is read: every 1 MiB segment is
     * authenticated before it is returned, and the end marker is verified when the plaintext
     * stream reports end-of-stream. Callers must read the returned stream to its end before
     * trusting the payload as complete; anything read before a failure must be discarded.
     * Closing the plaintext stream closes [input].
     */
    public fun open(input: InputStream, passphrase: ByteArray, backupKey: BackupKey): OpenedBackup {
        val framing = HeaderCodec.readFraming(input)
        val aead = FileCipher.open(kdf, passphrase, framing.header.saltBytes(), framing.header.kdf, backupKey)

        val keyCheck = ByteArray(BackupFormat.KEY_CHECK_LENGTH)
        if (readUpTo(input, keyCheck) != keyCheck.size) {
            throw BackupFormatException.Truncated("Backup ends inside the key check block")
        }
        FileCipher.decryptAll(aead, BackupFormat.keyCheckAad(framing.bytes), keyCheck, 0)
            ?: throw BackupFormatException.AuthenticationFailed(
                "Wrong passphrase or backup key, or the header was modified",
            )

        val tail = TailHoldingInputStream(input, BackupFormat.END_MARKER_LENGTH)
        val decrypting = aead.newDecryptingStream(tail, BackupFormat.bodyAad(framing.bytes))
        val plaintext = VerifiedPlaintextStream(decrypting, tail) { marker ->
            FileCipher.decryptAll(aead, BackupFormat.endMarkerAad(framing.bytes), marker, Long.SIZE_BYTES)
                ?.let(::decodeLength)
        }
        return OpenedBackup(framing.header, plaintext)
    }

    private fun decodeLength(bytes: ByteArray): Long =
        bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

    /**
     * Maps Tink's failures onto [BackupFormatException]. Tink reports every decryption problem as
     * an [IOException]; the end marker and the position of the failure decide whether the file was
     * cut short or modified.
     */
    private class VerifiedPlaintextStream(
        private val decrypting: InputStream,
        private val tail: TailHoldingInputStream,
        private val declaredBodyLength: (ByteArray) -> Long?,
    ) : InputStream() {
        private var endVerified = false
        private var failure: BackupFormatException? = null

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            failure?.let { throw it }
            if (len == 0) return 0
            val n = try {
                decrypting.read(b, off, len)
            } catch (e: SourceReadException) {
                throw e.source
            } catch (e: BackupFormatException) {
                throw fail(e)
            } catch (e: IOException) {
                tail.sourceError?.let { throw it }
                throw fail(classify(e))
            }
            if (n < 0 && !endVerified) {
                verifyEndMarker()
                endVerified = true
            }
            return n
        }

        override fun close(): Unit = decrypting.close()

        private fun fail(e: BackupFormatException): BackupFormatException {
            failure = e
            return e
        }

        private fun classify(cause: IOException): BackupFormatException {
            if (!tail.isExhausted) {
                return BackupFormatException.AuthenticationFailed("Backup payload failed authentication", cause)
            }
            val marker = tail.tail()
                ?: return BackupFormatException.Truncated("Backup ends before its end marker", cause)
            val declared = declaredBodyLength(marker)
            return if (declared != null && declared == tail.delivered) {
                BackupFormatException.AuthenticationFailed("Backup payload failed authentication", cause)
            } else {
                BackupFormatException.Truncated("Backup ends before its end marker", cause)
            }
        }

        private fun verifyEndMarker() {
            val marker = tail.tail() ?: throw fail(BackupFormatException.Truncated("Backup has no end marker"))
            val declared = declaredBodyLength(marker)
                ?: throw fail(BackupFormatException.AuthenticationFailed("Backup end marker failed authentication"))
            if (declared != tail.delivered) {
                throw fail(BackupFormatException.AuthenticationFailed("Backup end marker does not match the payload"))
            }
        }
    }
}
