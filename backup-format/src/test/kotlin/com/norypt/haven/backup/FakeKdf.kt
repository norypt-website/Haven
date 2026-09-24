package com.norypt.haven.backup

import java.security.MessageDigest

/**
 * Test-only stand-in for Argon2id: SHA-256(password || salt || params). Fast, deterministic and
 * counts invocations so tests can assert that bounds are enforced before any KDF work.
 */
class FakeKdf : PasswordKdf {
    var invocations: Int = 0
        private set

    override fun deriveKey(password: ByteArray, salt: ByteArray, params: KdfParams, outputLength: Int): ByteArray {
        invocations++
        require(outputLength == 32) { "FakeKdf only produces 32-byte keys" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(password)
        digest.update(salt)
        digest.update("${params.memoryKib}:${params.iterations}:${params.parallelism}".toByteArray())
        return digest.digest()
    }
}
