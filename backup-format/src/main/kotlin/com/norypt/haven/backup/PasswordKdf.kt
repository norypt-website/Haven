package com.norypt.haven.backup

import kotlinx.serialization.Serializable

/**
 * Memory-hard password key derivation (Argon2id) supplied by the host platform.
 *
 * The backup format module is pure JVM; the Android module provides the native Argon2id
 * implementation. Implementations must be deterministic for equal inputs and return exactly
 * [outputLength] bytes.
 */
public interface PasswordKdf {
    public fun deriveKey(password: ByteArray, salt: ByteArray, params: KdfParams, outputLength: Int): ByteArray
}

/** Argon2id cost parameters recorded in the backup header. */
@Serializable
public data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    /** True when every parameter lies within the bounds a reader is willing to process. */
    public fun isWithinBounds(): Boolean =
        memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB &&
            iterations in MIN_ITERATIONS..MAX_ITERATIONS &&
            parallelism in MIN_PARALLELISM..MAX_PARALLELISM

    public companion object {
        public val DEFAULT: KdfParams = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 4)

        public const val MIN_MEMORY_KIB: Int = 8_192
        /** Writers use [DEFAULT]; 512 MiB caps what an untrusted header can make a reader allocate. */
        public const val MAX_MEMORY_KIB: Int = 524_288
        public const val MIN_ITERATIONS: Int = 1
        public const val MAX_ITERATIONS: Int = 16
        public const val MIN_PARALLELISM: Int = 1
        public const val MAX_PARALLELISM: Int = 8
    }
}
