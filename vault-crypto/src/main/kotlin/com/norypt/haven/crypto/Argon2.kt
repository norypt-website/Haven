package com.norypt.haven.crypto

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import java.nio.ByteBuffer

/**
 * Argon2id parameters. Stored verbatim in each key envelope so the exact cost that protected a
 * key is always known; Haven never lowers stored parameters automatically.
 *
 * Defaults follow RFC 9106 §4 "second recommended option" for memory-constrained environments
 * (m = 64 MiB, t = 3, p = 4). [Argon2Benchmark] may raise the memory cost on capable devices.
 */
public data class Argon2Params(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val version: Int = 0x13,
) {
    init {
        require(memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "memoryKib out of bounds: $memoryKib" }
        require(iterations in 1..MAX_ITERATIONS) { "iterations out of bounds: $iterations" }
        require(parallelism in 1..MAX_PARALLELISM) { "parallelism out of bounds: $parallelism" }
        require(version == 0x13) { "unsupported argon2 version: $version" }
    }

    public companion object {
        public const val MIN_MEMORY_KIB: Int = 64 * 1024
        /**
         * Reader ceiling. The benchmark never chooses above [PROFILE_256M], so 512 MiB leaves 2x
         * headroom for future profiles while capping what a tampered envelope or backup header can
         * make a phone allocate before any authentication happens.
         */
        public const val MAX_MEMORY_KIB: Int = 512 * 1024
        public const val MAX_ITERATIONS: Int = 16
        public const val MAX_PARALLELISM: Int = 8
        public val RFC9106_MEMORY_CONSTRAINED: Argon2Params = Argon2Params(memoryKib = 64 * 1024, iterations = 3, parallelism = 4)
        public val PROFILE_128M: Argon2Params = Argon2Params(memoryKib = 128 * 1024, iterations = 3, parallelism = 4)
        public val PROFILE_256M: Argon2Params = Argon2Params(memoryKib = 256 * 1024, iterations = 3, parallelism = 4)

        /** Validates untrusted parameters (e.g. read from a file) without throwing on bad input. */
        public fun validate(memoryKib: Int, iterations: Int, parallelism: Int, version: Int): Argon2Params? =
            runCatching { Argon2Params(memoryKib, iterations, parallelism, version) }.getOrNull()
    }
}

/** Password-based key derivation. Implementations must be deterministic for equal inputs. */
public interface PasswordKeyDerivation {
    /** Returns a fresh array of [outputLength] bytes. Caller wipes it. */
    public fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray
}

/**
 * Argon2id via the argon2kt binding of the reference implementation (native code bundled in the
 * APK; no network). Inputs are copied into direct ByteBuffers and wiped afterwards.
 */
public class Argon2idKeyDerivation : PasswordKeyDerivation {
    // Lazy so that merely constructing the class (e.g. in JVM tests) does not load the native library.
    private val argon2 by lazy { Argon2Kt() }

    override fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray {
        require(salt.size >= 16) { "salt too short" }
        require(outputLength in 16..64) { "unsupported output length" }
        val pw = ByteBuffer.allocateDirect(password.size).put(password).also { it.flip() }
        val st = ByteBuffer.allocateDirect(salt.size).put(salt).also { it.flip() }
        try {
            val result = try {
                argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = pw,
                salt = st,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKib,
                parallelism = params.parallelism,
                hashLengthInBytes = outputLength,
                version = Argon2Version.V13,
                )
            } catch (e: OutOfMemoryError) {
                // A huge cost from a tampered file must be a clean refusal, not a process kill.
                throw VaultCryptoException.KeyDerivationFailed(e)
            } catch (e: com.lambdapioneer.argon2kt.Argon2Exception) {
                throw VaultCryptoException.KeyDerivationFailed(e)
            }
            val out = result.rawHashAsByteArray()
            Wipe.buffer(result.rawHash)
            Wipe.buffer(result.encodedOutput)
            return out
        } finally {
            Wipe.buffer(pw)
            Wipe.buffer(st)
        }
    }
}

/**
 * Picks Argon2id parameters for this device at vault-creation time. Never goes below the
 * RFC 9106 memory-constrained profile; raises memory when the device is fast enough that the
 * baseline finishes well under the target. The chosen parameters are stored and never changed
 * silently.
 */
public class Argon2Benchmark(private val kdf: PasswordKeyDerivation) {
    public data class Result(val params: Argon2Params, val measuredMillis: Long)

    public fun choose(targetMillis: Long = 750, maxMemoryKib: Int = Argon2Params.PROFILE_256M.memoryKib): Result {
        val pw = ByteArray(16) { 0x41 }
        val salt = ByteArray(16) { 0x42 }
        var chosen = Argon2Params.RFC9106_MEMORY_CONSTRAINED
        var measured = time(pw, salt, chosen)
        for (candidate in listOf(Argon2Params.PROFILE_128M, Argon2Params.PROFILE_256M)) {
            if (candidate.memoryKib > maxMemoryKib) break
            // Only escalate if the previous profile ran comfortably faster than the target.
            if (measured * 2 > targetMillis) break
            val t = runCatching { time(pw, salt, candidate) }.getOrNull() ?: break // OOM => keep previous
            chosen = candidate
            measured = t
            if (t >= targetMillis) break
        }
        return Result(chosen, measured)
    }

    private fun time(pw: ByteArray, salt: ByteArray, params: Argon2Params): Long {
        val start = System.nanoTime()
        Wipe.bytes(kdf.deriveKey(pw, salt, params, 32))
        return (System.nanoTime() - start) / 1_000_000
    }
}
