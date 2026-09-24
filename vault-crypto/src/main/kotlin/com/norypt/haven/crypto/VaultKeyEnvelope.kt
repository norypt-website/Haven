package com.norypt.haven.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * One vault's key envelope: the random 256-bit database key wrapped twice.
 *
 * ```
 * dbKey --[Keystore AES-256-GCM, innerAAD]--> inner --[Tink AES-256-GCM under Argon2id(password, salt), outerAAD]--> outer
 * ```
 * Opening requires both the password (outer layer) and the device-bound key (inner layer).
 * The associated data binds format version, vault identity, purpose, epoch, KDF parameters and
 * the hardware key alias/level/IV, so none of these can be swapped between envelopes.
 */
public data class VaultKeyEnvelope(
    val version: Int,
    val vaultId: String,
    val purpose: String,
    val epoch: Int,
    val kdf: Argon2Params,
    val salt: ByteArray,
    val normalization: String,
    val hwAlias: String,
    val hwLevel: SecurityLevel,
    val hwIv: ByteArray,
    val requiresDeviceAuth: Boolean,
    val outer: ByteArray,
) {
    /** Associated data of the outer (password) layer: every header field, including the hardware IV. */
    public fun outerAssociatedData(): ByteArray = associatedData(includeIv = true)

    /** Associated data of the inner (hardware) layer: the header without the IV, because the Keystore chooses the IV during wrapping. */
    public fun innerAssociatedData(): ByteArray = associatedData(includeIv = false)

    private fun associatedData(includeIv: Boolean): ByteArray = buildString {
        append("haven-vault-envelope|v=").append(version)
        append("|vault=").append(vaultId)
        append("|purpose=").append(purpose)
        append("|epoch=").append(epoch)
        append("|kdf=argon2id;m=").append(kdf.memoryKib).append(";t=").append(kdf.iterations)
        append(";p=").append(kdf.parallelism).append(";v=").append(kdf.version)
        append(";salt=").append(b64(salt)).append(";norm=").append(normalization)
        append("|hw=").append(hwAlias).append(";level=").append(hwLevel.name).append(";iv=").append(if (includeIv) b64(hwIv) else "-")
        append(";deviceAuth=").append(requiresDeviceAuth)
    }.toByteArray(Charsets.UTF_8)

    public fun toJson(): JsonObject = buildJsonObject {
        put("v", version)
        put("vault", vaultId)
        put("purpose", purpose)
        put("epoch", epoch)
        put(
            "kdf",
            buildJsonObject {
                put("alg", "argon2id")
                put("memKib", kdf.memoryKib)
                put("iter", kdf.iterations)
                put("par", kdf.parallelism)
                put("ver", kdf.version)
                put("salt", b64(salt))
                put("norm", normalization)
            },
        )
        put(
            "hw",
            buildJsonObject {
                put("alias", hwAlias)
                put("level", hwLevel.name)
                put("iv", b64(hwIv))
                put("deviceAuth", requiresDeviceAuth)
            },
        )
        put("outer", b64(outer))
    }

    public companion object {
        public const val CURRENT_VERSION: Int = 1
        public const val PURPOSE_DB_KEY: String = "vault-db-key"
        private const val MAX_FIELD: Int = 4096

        private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String, maxLen: Int): ByteArray {
            if (s.length > maxLen) throw VaultCryptoException.Malformed("field too long")
            return try {
                Base64.getDecoder().decode(s)
            } catch (e: IllegalArgumentException) {
                throw VaultCryptoException.Malformed("bad base64", e)
            }
        }

        private fun JsonObject.str(key: String, max: Int): String {
            val p = this[key] as? JsonPrimitive ?: throw VaultCryptoException.Malformed("missing $key")
            if (!p.isString) throw VaultCryptoException.Malformed("bad $key")
            if (p.content.length > max) throw VaultCryptoException.Malformed("$key too long")
            return p.content
        }

        private fun JsonObject.int(key: String): Int {
            val p = this[key] as? JsonPrimitive ?: throw VaultCryptoException.Malformed("missing $key")
            return p.content.toIntOrNull() ?: throw VaultCryptoException.Malformed("bad $key")
        }

        private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: throw VaultCryptoException.Malformed("missing $key")

        /** Bounded parsing. Never performs KDF work and never deletes anything on failure. */
        public fun fromJson(json: JsonObject): VaultKeyEnvelope {
            val version = json.int("v")
            if (version != CURRENT_VERSION) throw VaultCryptoException.Malformed("unsupported envelope version $version")
            val vaultId = json.str("vault", 64)
            val purpose = json.str("purpose", 64)
            val epoch = json.int("epoch").also { if (it < 0) throw VaultCryptoException.Malformed("bad epoch") }
            val k = json.obj("kdf")
            if (k.str("alg", 32) != "argon2id") throw VaultCryptoException.Malformed("unsupported kdf")
            val kdf = Argon2Params.validate(k.int("memKib"), k.int("iter"), k.int("par"), k.int("ver"))
                ?: throw VaultCryptoException.Malformed("kdf parameters out of bounds")
            val salt = unb64(k.str("salt", MAX_FIELD), MAX_FIELD).also { if (it.size != 32) throw VaultCryptoException.Malformed("bad salt length") }
            val norm = k.str("norm", 16).also { if (it != PasswordNormalizer.NORMALIZATION_ID) throw VaultCryptoException.Malformed("unsupported normalization") }
            val h = json.obj("hw")
            val alias = h.str("alias", 128)
            val level = runCatching { SecurityLevel.valueOf(h.str("level", 32)) }.getOrElse { throw VaultCryptoException.Malformed("bad level") }
            val iv = unb64(h.str("iv", MAX_FIELD), MAX_FIELD).also { if (it.size != 12) throw VaultCryptoException.Malformed("bad iv length") }
            val deviceAuth = (h["deviceAuth"] as? JsonPrimitive)?.booleanOrNull ?: false
            val outer = unb64(json.str("outer", MAX_FIELD), MAX_FIELD).also { if (it.size > 1024) throw VaultCryptoException.Malformed("outer too long") }
            return VaultKeyEnvelope(version, vaultId, purpose, epoch, kdf, salt, norm, alias, level, iv, deviceAuth, outer)
        }
    }

    override fun equals(other: Any?): Boolean = other is VaultKeyEnvelope && other.toJson() == toJson()
    override fun hashCode(): Int = toJson().hashCode()
}
