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
