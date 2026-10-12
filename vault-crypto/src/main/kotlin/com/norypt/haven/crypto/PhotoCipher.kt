package com.norypt.haven.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import java.io.DataInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Encryption of one photo file (original or thumbnail). Every photo has its own random data key
 * (DEK), kept only in its row of the keeper database: deleting the row makes the file unreadable.
 * The plaintext is `u64 length || bytes || zeros`, padded to a bucket, so file sizes reveal only
 * the bucket. The associated data binds the file to its photo id and kind.
 */
public object PhotoCipher {
    public const val DEK_LENGTH: Int = 32
    public const val MAX_PLAINTEXT: Long = 64L * 1024 * 1024
    private const val SEGMENT_SIZE = 256 * 1024
    private const val LENGTH_PREFIX = 8

    public enum class Kind(internal val label: String, internal val bucket: Int) {
        ORIGINAL("orig", 64 * 1024),
        THUMBNAIL("thumb", 16 * 1024),
    }

    /** The file was modified, truncated, swapped, or the key is wrong. */
    public class Corrupt(cause: Throwable? = null) : IOException("photo file failed authentication", cause)

    private val parameters: AesGcmHkdfStreamingParameters by lazy {
        StreamingAeadConfig.register()
        AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(DEK_LENGTH)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(SEGMENT_SIZE)
            .build()
    }

    public fun newDek(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(DEK_LENGTH).also(random::nextBytes)

    public fun paddedLength(length: Int, kind: Kind): Long {
        require(length >= 0 && length <= MAX_PLAINTEXT) { "photo larger than $MAX_PLAINTEXT bytes" }
        val raw = length.toLong() + LENGTH_PREFIX
        return ((raw + kind.bucket - 1) / kind.bucket) * kind.bucket
    }

    public fun encrypt(dek: ByteArray, photoId: String, kind: Kind, plaintext: ByteArray, out: OutputStream) {
        val total = paddedLength(plaintext.size, kind)
        val stream = aead(dek).newEncryptingStream(NonClosing(out), aad(photoId, kind))
        stream.write(ByteBuffer.allocate(LENGTH_PREFIX).putLong(plaintext.size.toLong()).array())
        stream.write(plaintext)
        val zeros = ByteArray(8192)
        var left = total - LENGTH_PREFIX - plaintext.size
        while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); stream.write(zeros, 0, n); left -= n }
        stream.close()
    }

    public fun decrypt(dek: ByteArray, photoId: String, kind: Kind, input: InputStream): ByteArray {
        val plain = try {
            DataInputStream(aead(dek).newDecryptingStream(input, aad(photoId, kind))).use { s ->
                val length = s.readLong()
                if (length < 0 || length > MAX_PLAINTEXT) throw Corrupt()
                val bytes = ByteArray(length.toInt()); s.readFully(bytes)
                // Read the padding to the end so the final segment (and with it truncation) is verified.
                val sink = ByteArray(8192); while (s.read(sink) >= 0) { }
                bytes
            }
        } catch (e: Corrupt) { throw e } catch (e: IOException) { throw Corrupt(e) } catch (e: GeneralSecurityException) { throw Corrupt(e) }
        return plain
    }

    private fun aad(photoId: String, kind: Kind): ByteArray = "haven.photo.v1:$photoId:${kind.label}".toByteArray(Charsets.UTF_8)

    private fun aead(dek: ByteArray): StreamingAead {
        require(dek.size == DEK_LENGTH) { "DEK must be $DEK_LENGTH bytes" }
        val key = AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(dek, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { out.flush() }
    }
}
