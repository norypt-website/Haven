package com.norypt.haven.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Test-only KDF: SHA-256(password || salt || params). Deterministic and fast. NOT Argon2. */
class FakeKdf : PasswordKeyDerivation {
    var calls = 0
    override fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray {
        calls++
        val md = MessageDigest.getInstance("SHA-256")
        md.update(password); md.update(salt)
        md.update("${params.memoryKib}/${params.iterations}/${params.parallelism}".toByteArray())
        return md.digest().copyOf(outputLength)
    }
}

/** Test-only in-memory stand-in for the Android Keystore using javax.crypto AES-GCM. */
class FakeWrapping(private val level: SecurityLevel = SecurityLevel.STRONGBOX) : WrappingKeyProvider {
    val keys = HashMap<String, ByteArray>()
    val authRequired = HashSet<String>()
    var deviceAuthenticated = true
    private val random = SecureRandom()

    override fun create(alias: String, requireDeviceAuth: Boolean): WrappingKeyProvider.Created {
        keys[alias] = ByteArray(32).also(random::nextBytes)
        if (requireDeviceAuth) authRequired += alias
        return WrappingKeyProvider.Created(alias, level)
    }

    override fun securityLevelOf(alias: String): SecurityLevel? = if (keys.containsKey(alias)) level else null

    /** Test hook: simulate a Keystore failure while wrapping under an alias containing this text. */
    var failWrapWhenAliasContains: String? = null

    override fun wrap(alias: String, plaintext: ByteArray, associatedData: ByteArray): WrappedBlob {
        val key = keys[alias] ?: throw VaultCryptoException.UnrecoverableHardwareKey()
        failWrapWhenAliasContains?.let { if (alias.contains(it)) throw java.security.GeneralSecurityException("simulated wrap failure") }
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(associatedData)
        return WrappedBlob(iv, c.doFinal(plaintext))
    }

    override fun unwrap(alias: String, blob: WrappedBlob, associatedData: ByteArray): ByteArray {
        val key = keys[alias] ?: throw VaultCryptoException.UnrecoverableHardwareKey()
        if (alias in authRequired && !deviceAuthenticated) throw VaultCryptoException.DeviceAuthenticationRequired()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.iv))
        c.updateAAD(associatedData)
        return try {
            c.doFinal(blob.ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw VaultCryptoException.Malformed("bad tag", e)
        }
    }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}
