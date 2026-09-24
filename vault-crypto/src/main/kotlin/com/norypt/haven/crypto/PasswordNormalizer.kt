package com.norypt.haven.crypto

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Stable password encoding rules (recorded in every key envelope as `norm=NFC`):
 *
 * 1. The password is taken exactly as typed: no trimming, no case change, no truncation.
 *    Spaces and any Unicode character are allowed. Length is unbounded except by
 *    [MAX_PASSWORD_CHARS] to keep KDF input bounded.
 * 2. Unicode Normalization Form C (NFC) is applied so the same visible text typed on
 *    different keyboards yields the same bytes.
 * 3. The result is encoded as UTF-8.
 *
 * The caller receives a fresh ByteArray and is responsible for wiping it.
 */
public object PasswordNormalizer {
    public const val NORMALIZATION_ID: String = "NFC"
    public const val MAX_PASSWORD_CHARS: Int = 1024

    public fun toBytes(password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(password.size <= MAX_PASSWORD_CHARS) { "Password longer than $MAX_PASSWORD_CHARS characters" }
        // Normalizer only works on CharSequence; CharBuffer avoids creating an immutable String.
        val normalized: String = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC)
        // Unavoidable String copy above; encode and let it fall out of scope as fast as possible.
        val encoded: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(normalized))
        val out = ByteArray(encoded.remaining())
        encoded.get(out)
        Wipe.buffer(encoded)
        return out
    }
}
