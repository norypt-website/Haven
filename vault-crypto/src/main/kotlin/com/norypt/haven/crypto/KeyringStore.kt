package com.norypt.haven.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Persists all vault key envelopes in ONE file so a password change (which rewrites every
 * envelope) is atomic: write to a temp file, fsync, rename over the old file.
 *
 * The file lives in credential-encrypted app storage, never in Direct Boot storage.
 * The envelopes are useless without the password and the device-bound keys, but the file is
 * still treated as sensitive and excluded from Android backups.
 */
public class KeyringStore(private val file: File) {
    public class Keyring(
        public val envelopes: Map<String, VaultKeyEnvelope>,
        public val createdAtEpochMs: Long,
        /** Optional duress-password verifier (see [DuressVerifier]). */
        public val duress: DuressVerifier? = null,
    )

    public fun exists(): Boolean = file.isFile && file.length() > 0

    public fun read(): Keyring? {
        if (!exists()) return null
        if (file.length() > MAX_BYTES) throw VaultCryptoException.Malformed("keyring file too large")
        val text = file.readText(StandardCharsets.UTF_8)
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject ?: throw VaultCryptoException.Malformed("keyring root")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw VaultCryptoException.Malformed("keyring json", e)
        } catch (e: IllegalArgumentException) {
            throw VaultCryptoException.Malformed("keyring json", e)
        }
        if ((json["v"] as? JsonPrimitive)?.intOrNull != 1) throw VaultCryptoException.Malformed("unsupported keyring version")
        val envs = json["vaults"] as? JsonObject ?: throw VaultCryptoException.Malformed("missing vaults")
        val map = LinkedHashMap<String, VaultKeyEnvelope>()
        for ((key, value) in envs) {
            if (map.size >= 8) throw VaultCryptoException.Malformed("too many vaults")
            map[key] = VaultKeyEnvelope.fromJson(value as? JsonObject ?: throw VaultCryptoException.Malformed("bad envelope"))
        }
        val duress = (json["duress"] as? JsonObject)?.let { DuressVerifier.fromJson(it) }
        return Keyring(map, (json["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L, duress)
    }

    public fun write(keyring: Keyring) {
        val json = buildJsonObject {
            put("v", 1)
            put("createdAt", keyring.createdAtEpochMs)
            put("vaults", buildJsonObject { keyring.envelopes.forEach { (id, env) -> put(id, env.toJson()) } })
            keyring.duress?.let { put("duress", it.toJson()) }
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Fall back to copy+delete on filesystems without atomic rename.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    public fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024L
    }
}
