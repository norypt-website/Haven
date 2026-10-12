package com.norypt.haven.session

import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.PasswordKeyDerivation
import com.norypt.haven.crypto.SecurityLevel
import com.norypt.haven.crypto.VaultCryptoException
import com.norypt.haven.crypto.WrappedBlob
import com.norypt.haven.crypto.WrappingKeyProvider
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Stands for Haven being killed mid-derivation: an Error, so no handler in the app catches it. */
class SimulatedProcessDeath : Error("process killed")

/** Test-only KDF: SHA-256(password || salt). Deterministic and fast. NOT Argon2. */
class TestKdf : PasswordKeyDerivation {
    /** When set, this many more derivations succeed and every later one fails as if memory ran out. */
    var succeedBeforeFailing: Int? = null
    /** When set, this many more derivations succeed and the next one never returns (see [SimulatedProcessDeath]). */
    var dieAfter: Int? = null
    /** Derivations so far: the expensive step, so equal counts mean equal time. */
    var calls = 0

    override fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray {
        calls++
        succeedBeforeFailing?.let { left ->
            if (left == 0) throw VaultCryptoException.KeyDerivationFailed()
            succeedBeforeFailing = left - 1
        }
        dieAfter?.let { left ->
            if (left == 0) { dieAfter = null; throw SimulatedProcessDeath() }
            dieAfter = left - 1
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(password); md.update(salt)
        return md.digest().copyOf(outputLength)
    }
}

/** Test-only in-memory stand-in for the Android Keystore (AES-GCM keys in a map). */
class TestWrapping : WrappingKeyProvider {
    private val keys = HashMap<String, ByteArray>()
    /** Simulates a Keystore that refuses to retire keys. */
    var failDelete = false
    private val random = SecureRandom()

    override fun create(alias: String, requireDeviceAuth: Boolean): WrappingKeyProvider.Created {
        keys[alias] = ByteArray(32).also(random::nextBytes)
        return WrappingKeyProvider.Created(alias, SecurityLevel.STRONGBOX)
    }

    override fun securityLevelOf(alias: String): SecurityLevel? = if (keys.containsKey(alias)) SecurityLevel.STRONGBOX else null

    override fun wrap(alias: String, plaintext: ByteArray, associatedData: ByteArray): WrappedBlob {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys[alias] ?: throw VaultCryptoException.UnrecoverableHardwareKey(), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(associatedData)
        return WrappedBlob(iv, c.doFinal(plaintext))
    }

    override fun unwrap(alias: String, blob: WrappedBlob, associatedData: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys[alias] ?: throw VaultCryptoException.UnrecoverableHardwareKey(), "AES"), GCMParameterSpec(128, blob.iv))
        c.updateAAD(associatedData)
        return c.doFinal(blob.ciphertext)
    }

    override fun delete(alias: String) {
        check(!failDelete) { "simulated keystore failure" }
        keys.remove(alias)
    }
}

/**
 * A real [VaultSession] over an initialised keyring (test KDF, in-memory wrapping keys) that has
 * never been unlocked, so [VaultSession.contentOrNull] is null. [dir] holds the keyring file.
 */
fun lockedTestSession(
    app: android.app.Application,
    dir: java.io.File,
    password: String = "test password",
    kdf: TestKdf = TestKdf(),
    wrapping: TestWrapping = TestWrapping(),
    onKeeperClosed: () -> Unit = {},
): VaultSession {
    com.norypt.haven.alarm.AlarmRuntime.config = com.norypt.haven.alarm.AlarmRuntimeConfig(mainActivity = Any::class.java, ringActivity = Any::class.java)
    com.norypt.haven.alarm.AlarmRuntime.resetForTesting()
    val keys = com.norypt.haven.crypto.VaultKeyManager(com.norypt.haven.crypto.KeyringStore(java.io.File(dir, "keyring.json")), kdf, wrapping)
    keys.initialise(password.toByteArray(), Argon2Params.RFC9106_MEMORY_CONSTRAINED, requireDeviceAuth = false).values.forEach { it.destroy() }
    return VaultSession(
        app, keys, com.norypt.haven.alarm.AlarmRuntime.get(app), com.norypt.haven.security.GuessThrottle({ 0L }),
        eraseLimit = { com.norypt.haven.security.EraseAfterFailures.OFF }, onAutoErased = {},
        onKeeperClosed = onKeeperClosed,
    ) {}
}

/**
 * Like [lockedTestSession], with the keeper unlocked by [password]. The JVM has no SQLCipher, so
 * the keeper is a plain SQLite Room database in [dir] (same queries); unlocking again reopens it.
 */
fun unlockedTestSession(
    app: android.app.Application,
    dir: java.io.File,
    password: String = "test password",
    kdf: TestKdf = TestKdf(),
    wrapping: TestWrapping = TestWrapping(),
    onKeeperClosed: () -> Unit = {},
): VaultSession {
    val session = lockedTestSession(app, dir, password, kdf, wrapping, onKeeperClosed)
    session.openKeeperDatabase = { context, _ ->
        androidx.room.Room.databaseBuilder(context, com.norypt.haven.storage.passwords.PasswordDatabase::class.java, java.io.File(dir, "keeper.db").path).build()
    }
    val result = kotlinx.coroutines.runBlocking { session.unlockPasswords(password.toCharArray()) }
    check(result == UnlockResult.Success) { "test keeper did not open: $result" }
    return session
}

/**
 * Like [unlockedTestSession], with the content vault open too. Both databases are plain SQLite
 * Room files in [dir] (same queries as SQLCipher).
 */
fun fullyUnlockedTestSession(
    app: android.app.Application,
    dir: java.io.File,
    password: String = "test password",
    onKeeperClosed: () -> Unit = {},
): VaultSession {
    val session = unlockedTestSession(app, dir, password, onKeeperClosed = onKeeperClosed)
    session.openContentDatabase = { context, _ ->
        androidx.room.Room.databaseBuilder(context, com.norypt.haven.storage.content.ContentDatabase::class.java, java.io.File(dir, "content.db").path).build()
    }
    val result = kotlinx.coroutines.runBlocking { session.unlockContent(password.toCharArray()) }
    check(result == UnlockResult.Success) { "test content vault did not open: $result" }
    return session
}
