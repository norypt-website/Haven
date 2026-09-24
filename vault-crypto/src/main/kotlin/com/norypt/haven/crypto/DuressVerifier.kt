package com.norypt.haven.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/**
 * Duress password verifier (opt-in feature).
 *
 * The duress password is never stored. Its Argon2id-derived key (own salt) encrypts a fixed
 * 32-byte marker with AES-256-GCM; a candidate password "is the duress password" when that
 * decryption succeeds. It is checked only after the real password has already failed, so a
 * wrong password and the duress password cost the same time (two KDF runs) and are
 * indistinguishable from the outside.
 */
public class DuressVerifier(
    public val kdf: Argon2Params,
    public val salt: ByteArray,
    public val ciphertext: ByteArray,
) {
    public fun toJson(): JsonObject = buildJsonObject {
        put("v", 1)
        put("memKib", kdf.memoryKib); put("iter", kdf.iterations); put("par", kdf.parallelism); put("ver", kdf.version)
        put("salt", Base64.getEncoder().encodeToString(salt))
        put("ct", Base64.getEncoder().encodeToString(ciphertext))
    }

    /** True if [password] is the duress password. Performs one Argon2id derivation. */
    public fun matches(password: ByteArray, derivation: PasswordKeyDerivation): Boolean {
        val key = derivation.deriveKey(password, salt, kdf, 32)
        return try {
            val marker = PasswordLayerAead.decrypt(key, ciphertext, AAD)
            val ok = marker.contentEquals(MARKER)
            Wipe.bytes(marker)
            ok
        } catch (e: GeneralSecurityException) {
            false
        } finally {
            Wipe.bytes(key)
        }
    }

    public companion object {
        private const val PREFIX = "haven-duress-v1"
        private val AAD: ByteArray = PREFIX.toByteArray(Charsets.UTF_8)
        private val MARKER: ByteArray = ByteArray(32) { 0x5A }

        public fun create(password: ByteArray, params: Argon2Params, derivation: PasswordKeyDerivation, random: SecureRandom = SecureRandom()): DuressVerifier {
            val salt = ByteArray(32).also(random::nextBytes)
            val key = derivation.deriveKey(password, salt, params, 32)
            try {
                return DuressVerifier(params, salt, PasswordLayerAead.encrypt(key, MARKER, AAD))
            } finally {
                Wipe.bytes(key)
            }
        }

        public fun fromJson(json: JsonObject): DuressVerifier {
            fun int(k: String) = (json[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw VaultCryptoException.Malformed("duress $k")
            fun bytes(k: String, len: Int): ByteArray {
                val s = (json[k] as? JsonPrimitive)?.content ?: throw VaultCryptoException.Malformed("duress $k")
                if (s.length > 4096) throw VaultCryptoException.Malformed("duress $k too long")
                val b = runCatching { Base64.getDecoder().decode(s) }.getOrElse { throw VaultCryptoException.Malformed("duress $k") }
                if (b.size != len) throw VaultCryptoException.Malformed("duress $k length")
                return b
            }
            if (int("v") != 1) throw VaultCryptoException.Malformed("duress version")
            val kdf = Argon2Params.validate(int("memKib"), int("iter"), int("par"), int("ver")) ?: throw VaultCryptoException.Malformed("duress kdf")
            return DuressVerifier(kdf, bytes("salt", 32), bytes("ct", 32 + 12 + 16))
        }
    }
}
