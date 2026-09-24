package com.norypt.haven.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.util.SecretBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Derives the file key from both factors and turns it into a Tink [StreamingAead].
 *
 * Intermediate key material is zeroed in `finally` blocks. This is best effort on the JVM: the
 * runtime may have copied the arrays, and the copy Tink keeps inside its key object cannot be
 * reached from here.
 */
internal object FileCipher {
    private val streamingParameters: AesGcmHkdfStreamingParameters by lazy {
        StreamingAeadConfig.register()
        AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(BackupFormat.FILE_KEY_LENGTH)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(BackupFormat.CIPHERTEXT_SEGMENT_SIZE)
            .build()
    }

    /**
     * pwKey   = Argon2id(passphrase, salt, params, 32)
     * ikm     = pwKey || backupKey
     * fileKey = HKDF-SHA256(ikm, salt, info = "com.norypt.haven.backup.v1.filekey", 32)
     */
    fun open(
        kdf: PasswordKdf,
        passphrase: ByteArray,
        salt: ByteArray,
        params: KdfParams,
        backupKey: BackupKey,
    ): StreamingAead {
        var pwKey: ByteArray? = null
        var keyBytes: ByteArray? = null
        var ikm: ByteArray? = null
        var fileKey: ByteArray? = null
        try {
            val derived = try {
                kdf.deriveKey(passphrase, salt, params, BackupFormat.FILE_KEY_LENGTH)
            } catch (e: OutOfMemoryError) {
                // The header is unauthenticated when its KDF cost is read; a cost this device cannot
                // afford is refused cleanly instead of killing the process.
                throw BackupFormatException.ParametersOutOfBounds("The backup's key derivation cost exceeds this device's memory")
            }
            pwKey = derived
            check(derived.size == BackupFormat.FILE_KEY_LENGTH) {
                "PasswordKdf returned ${derived.size} bytes; expected ${BackupFormat.FILE_KEY_LENGTH}"
            }
            val key = backupKey.bytes()
            keyBytes = key
            val combined = derived + key
            ikm = combined
            // The same public salt feeds Argon2id and HKDF. RFC 5869 §3.1: an HKDF salt need not be
            // secret or unique to the extract step; the two uses are independent operations.
            val file = Hkdf.computeHkdf(
                "HMACSHA256",
                combined,
                salt,
                BackupFormat.FILE_KEY_INFO.toByteArray(Charsets.US_ASCII),
                BackupFormat.FILE_KEY_LENGTH,
            )
            fileKey = file
            return streamingAeadFor(file)
        } finally {
            pwKey?.fill(0)
            keyBytes?.fill(0)
            ikm?.fill(0)
            fileKey?.fill(0)
        }
    }

    private fun streamingAeadFor(fileKey: ByteArray): StreamingAead {
        val key = AesGcmHkdfStreamingKey.create(
            streamingParameters,
            SecretBytes.copyFrom(fileKey, InsecureSecretKeyAccess.get()),
        )
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    /** Encrypts a short value as a self-contained streaming AEAD ciphertext. */
    fun encryptAll(aead: StreamingAead, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        aead.newEncryptingStream(out, aad).use { it.write(plaintext) }
        return out.toByteArray()
    }

    /**
     * Decrypts a self-contained ciphertext that must hold exactly [expectedLength] plaintext
     * bytes. Returns null on any authentication or framing failure.
     */
    fun decryptAll(aead: StreamingAead, aad: ByteArray, ciphertext: ByteArray, expectedLength: Int): ByteArray? =
        try {
            aead.newDecryptingStream(ByteArrayInputStream(ciphertext), aad).use { stream ->
                val buffer = ByteArray(expectedLength + 1)
                if (readUpTo(stream, buffer) == expectedLength) buffer.copyOf(expectedLength) else null
            }
        } catch (_: IOException) {
            null
        }
}
