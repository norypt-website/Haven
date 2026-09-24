package com.norypt.haven.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom

/** Identifiers of Haven's two independent vaults. */
public object VaultIds {
    public const val CONTENT: String = "content"
    public const val PASSWORDS: String = "passwords"
    public val ALL: List<String> = listOf(CONTENT, PASSWORDS)
}

/** A freshly unwrapped 32-byte database key. Call [destroy] as soon as the database is open. */
public class UnwrappedVaultKey internal constructor(private val bytes: ByteArray, public val vaultId: String, public val hwLevel: SecurityLevel) {
    @Volatile private var destroyed = false

    /** Returns the live array (not a copy) so it can be wiped exactly once. */
    public fun bytes(): ByteArray {
        check(!destroyed) { "key destroyed" }
        return bytes
    }

    public fun destroy() {
        destroyed = true
        Wipe.bytes(bytes)
    }
}

/**
 * Creates, opens and re-wraps vault keys. See docs/KEY_MANAGEMENT.md.
 *
 * Every vault has its own random database key, its own salt (so its own password-derived key)
 * and its own hardware wrapping key. Opening the content vault never yields anything that opens
 * the password vault; the password must be entered again (fresh Argon2id derivation).
 */
public class VaultKeyManager(
    private val store: KeyringStore,
    private val kdf: PasswordKeyDerivation,
    private val wrapping: WrappingKeyProvider,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    public fun isInitialised(): Boolean = store.exists()

    public fun keyring(): KeyringStore.Keyring? = store.read()

    public fun envelope(vaultId: String): VaultKeyEnvelope =
        store.read()?.envelopes?.get(vaultId) ?: throw VaultCryptoException.NoSuchVault(vaultId)

    /**
     * Initialises all vaults with one password. Returns the unwrapped keys so the caller can
     * create the databases immediately; the caller must destroy them.
     */
    public fun initialise(password: ByteArray, params: Argon2Params, requireDeviceAuth: Boolean): Map<String, UnwrappedVaultKey> {
        check(!store.exists()) { "keyring already exists; refuse to overwrite" }
        val keys = LinkedHashMap<String, UnwrappedVaultKey>()
        val envelopes = LinkedHashMap<String, VaultKeyEnvelope>()
        try {
            for (vaultId in VaultIds.ALL) {
                val dbKey = ByteArray(32).also(random::nextBytes)
                val env = try {
                    wrap(vaultId, epoch = 0, dbKey, password, params, requireDeviceAuth)
                } catch (t: Throwable) {
                    Wipe.bytes(dbKey) // not yet owned by an UnwrappedVaultKey, so nobody else would wipe it
                    throw t
                }
                envelopes[vaultId] = env
                keys[vaultId] = UnwrappedVaultKey(dbKey, vaultId, env.hwLevel)
            }
            store.write(KeyringStore.Keyring(envelopes, clock()))
            return keys
        } catch (t: Throwable) {
            keys.values.forEach { it.destroy() }
            envelopes.values.forEach { wrapping.delete(it.hwAlias) }
            throw t
        }
    }

    /** Opens one vault. Wrong password and tampering both surface as [VaultCryptoException.WrongPasswordOrCorrupt]. */
    public fun open(vaultId: String, password: ByteArray): UnwrappedVaultKey {
        val env = envelope(vaultId)
        val pwKey = kdf.deriveKey(password, env.salt, env.kdf, 32)
        val inner = try {
            PasswordLayerAead.decrypt(pwKey, env.outer, env.outerAssociatedData())
        } catch (e: GeneralSecurityException) {
            throw VaultCryptoException.WrongPasswordOrCorrupt()
        } finally {
            Wipe.bytes(pwKey)
        }
        val dbKey = try {
            wrapping.unwrap(env.hwAlias, WrappedBlob(env.hwIv, inner), env.innerAssociatedData())
        } finally {
            Wipe.bytes(inner)
        }
        if (dbKey.size != 32) {
            Wipe.bytes(dbKey)
            throw VaultCryptoException.Malformed("unexpected key length")
        }
        return UnwrappedVaultKey(dbKey, vaultId, env.hwLevel)
    }

    /**
     * Changes the password for all vaults. Requires the old password (proves possession) and
     * rewraps every database key under fresh salts, fresh derived keys, a new epoch AND fresh
     * hardware wrapping keys. The old Keystore aliases are retired once the new keyring is
     * durable, so a copy of the old keyring taken before the change can no longer be opened even
     * by someone who later learns the old password: the change genuinely revokes it. The
     * database files are untouched (their random keys do not change). The duress verifier, if
     * any, is carried over; a new password equal to the duress password is refused because it
     * would wipe the vault on the next unlock.
     */
    public fun changePassword(oldPassword: ByteArray, newPassword: ByteArray, params: Argon2Params) {
        val ring = store.read() ?: throw VaultCryptoException.NoSuchVault("*")
        val updated = LinkedHashMap<String, VaultKeyEnvelope>()
        val opened = ArrayList<UnwrappedVaultKey>()
        var written = false
        try {
            for ((vaultId, env) in ring.envelopes) {
                val key = open(vaultId, oldPassword)
                opened += key
                updated[vaultId] = wrap(vaultId, env.epoch + 1, key.bytes(), newPassword, params, env.requiresDeviceAuth)
            }
            // Checked only after the old password has proven possession, so this costs nothing
            // for an attacker guessing passwords and cannot be used as a duress-password oracle.
            if (ring.duress?.matches(newPassword, kdf) == true) {
                throw IllegalArgumentException("new password must differ from the duress password")
            }
            store.write(KeyringStore.Keyring(updated, ring.createdAtEpochMs, ring.duress))
            written = true
            ring.envelopes.values.forEach { old -> runCatching { wrapping.delete(old.hwAlias) } }
        } finally {
            opened.forEach { it.destroy() }
            if (!written) updated.values.forEach { fresh -> runCatching { wrapping.delete(fresh.hwAlias) } }
        }
    }

    /** Rotates the hardware wrapping keys (e.g. to toggle device-auth requirement). Keeps the duress verifier. */
    public fun rotateHardwareKeys(password: ByteArray, requireDeviceAuth: Boolean) {
        val ring = store.read() ?: throw VaultCryptoException.NoSuchVault("*")
        val updated = LinkedHashMap<String, VaultKeyEnvelope>()
        val opened = ArrayList<UnwrappedVaultKey>()
        var written = false
        try {
            for ((vaultId, env) in ring.envelopes) {
                val key = open(vaultId, password)
                opened += key
                updated[vaultId] = wrap(vaultId, env.epoch + 1, key.bytes(), password, env.kdf, requireDeviceAuth)
            }
            store.write(KeyringStore.Keyring(updated, ring.createdAtEpochMs, ring.duress))
            written = true
            ring.envelopes.values.forEach { old -> runCatching { wrapping.delete(old.hwAlias) } }
        } finally {
            opened.forEach { it.destroy() }
            // Never leave freshly created Keystore keys behind when the new keyring was not persisted.
            if (!written) updated.values.forEach { fresh -> runCatching { wrapping.delete(fresh.hwAlias) } }
        }
    }

    // ---- Duress password (opt-in) ----

    public fun hasDuress(): Boolean = store.read()?.duress != null

    /** Sets or replaces the duress verifier. Requires the real password (proved by opening a vault). */
    public fun setDuress(realPassword: ByteArray, duressPassword: ByteArray, params: Argon2Params) {
        open(VaultIds.CONTENT, realPassword).destroy()
        // The duress password must differ from the real one, otherwise every unlock would wipe.
        if (realPassword.contentEquals(duressPassword)) throw IllegalArgumentException("duress password must differ from the real password")
        val ring = store.read() ?: throw VaultCryptoException.NoSuchVault("*")
        store.write(KeyringStore.Keyring(ring.envelopes, ring.createdAtEpochMs, DuressVerifier.create(duressPassword, params, kdf, random)))
    }

    public fun clearDuress(realPassword: ByteArray) {
        open(VaultIds.CONTENT, realPassword).destroy()
        val ring = store.read() ?: throw VaultCryptoException.NoSuchVault("*")
        store.write(KeyringStore.Keyring(ring.envelopes, ring.createdAtEpochMs, null))
    }

    /** One extra KDF run; call only after the real password already failed. */
    public fun isDuressPassword(password: ByteArray): Boolean {
        val d = store.read()?.duress ?: return false
        return d.matches(password, kdf)
    }

    /**
     * Duress response: retire the hardware keys, delete the keyring, then create a DECOY keyring
     * (new random vault keys wrapped under a random, discarded password) so the app keeps
     * looking like a locked vault and every password — including the real one — is simply
     * "wrong". The duress verifier is re-armed with the same duress password so repeated duress
     * entries behave identically. Returns the decoy keys so the caller can create empty
     * database files (the caller destroys them).
     */
    public fun duressReplaceWithDecoy(duressPassword: ByteArray, params: Argon2Params): Map<String, UnwrappedVaultKey> {
        val ring = store.read()
        // The decoy mirrors the real vault's KDF cost and device-auth flag so that neither the
        // time a (failing) unlock takes nor the stored settings betray that a wipe has happened.
        val reference = ring?.envelopes?.get(VaultIds.CONTENT) ?: ring?.envelopes?.values?.firstOrNull()
        val decoyParams = reference?.kdf ?: params
        val decoyDeviceAuth = reference?.requiresDeviceAuth ?: false
        ring?.envelopes?.values?.forEach { runCatching { wrapping.delete(it.hwAlias) } }
        store.delete()
        val randomPassword = ByteArray(32).also(random::nextBytes)
        try {
            val keys = initialise(randomPassword, decoyParams, requireDeviceAuth = decoyDeviceAuth)
            val fresh = store.read()!!
            store.write(KeyringStore.Keyring(fresh.envelopes, ring?.createdAtEpochMs ?: clock(), DuressVerifier.create(duressPassword, decoyParams, kdf, random)))
            return keys
        } finally {
            Wipe.bytes(randomPassword)
        }
    }

    /** Full erasure: retire hardware keys and remove the keyring. Database files are the storage layer's job. */
    public fun eraseAll() {
        val ring = runCatching { store.read() }.getOrNull()
        ring?.envelopes?.values?.forEach { wrapping.delete(it.hwAlias) }
        store.delete()
    }

    private fun wrap(
        vaultId: String,
        epoch: Int,
        dbKey: ByteArray,
        password: ByteArray,
        params: Argon2Params,
        requireDeviceAuth: Boolean,
        reuseAlias: String? = null,
    ): VaultKeyEnvelope {
        val salt = ByteArray(32).also(random::nextBytes)
        val alias: String
        val level: SecurityLevel
        val created: WrappingKeyProvider.Created?
        if (reuseAlias != null) {
            created = null
            alias = reuseAlias
            level = wrapping.securityLevelOf(alias) ?: throw VaultCryptoException.UnrecoverableHardwareKey()
        } else {
            created = wrapping.create("haven.wrap.$vaultId.${clock()}.${random.nextInt(1_000_000)}", requireDeviceAuth)
            alias = created.alias
            level = created.level
        }
        try {
            // The Keystore chooses the IV during wrapping, so the inner layer authenticates the header
            // without the IV; the outer layer then authenticates the complete header including the IV.
            val headerNoIv = VaultKeyEnvelope(
                VaultKeyEnvelope.CURRENT_VERSION, vaultId, VaultKeyEnvelope.PURPOSE_DB_KEY, epoch, params, salt,
                PasswordNormalizer.NORMALIZATION_ID, alias, level, ByteArray(0), requireDeviceAuth, ByteArray(0),
            )
            val innerBlob = wrapping.wrap(alias, dbKey, headerNoIv.innerAssociatedData())
            val header = headerNoIv.copy(hwIv = innerBlob.iv)
            val pwKey = kdf.deriveKey(password, salt, params, 32)
            val outer = try {
                PasswordLayerAead.encrypt(pwKey, innerBlob.ciphertext, header.outerAssociatedData())
            } finally {
                Wipe.bytes(pwKey)
                Wipe.bytes(innerBlob.ciphertext)
            }
            return header.copy(outer = outer)
        } catch (t: Throwable) {
            // A Keystore key that never made it into a persisted envelope must not linger.
            if (created != null) runCatching { wrapping.delete(created.alias) }
            throw t
        }
    }
}
