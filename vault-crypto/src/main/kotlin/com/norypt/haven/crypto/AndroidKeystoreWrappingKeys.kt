package com.norypt.haven.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore backed wrapping keys (AES-256-GCM).
 *
 * Policy: try StrongBox first, then the TEE. If the resulting key reports SOFTWARE or UNKNOWN,
 * the key is deleted and [VaultCryptoException.HardwareKeyUnavailable] is thrown unless
 * [allowSoftwareKeys] is true (debug builds on emulators only; the UI must show the real level).
 * The reported level always comes from [KeyInfo.getSecurityLevel], never from what was requested.
 */
public class AndroidKeystoreWrappingKeys(
    private val allowSoftwareKeys: Boolean = false,
    private val deviceAuthTimeoutSeconds: Int = 30,
) : WrappingKeyProvider {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun create(alias: String, requireDeviceAuth: Boolean): WrappingKeyProvider.Created {
        delete(alias)
        val strongBoxResult = runCatching { generate(alias, strongBox = true, requireDeviceAuth) }
        val level = strongBoxResult.getOrElse { e ->
            if (e is StrongBoxUnavailableException || e.cause is StrongBoxUnavailableException || e is java.security.ProviderException) {
                generate(alias, strongBox = false, requireDeviceAuth)
            } else {
                throw VaultCryptoException.UnrecoverableHardwareKey(e)
            }
        }
        if (level == SecurityLevel.SOFTWARE || level == SecurityLevel.UNKNOWN) {
            if (!allowSoftwareKeys) {
                delete(alias)
                throw VaultCryptoException.HardwareKeyUnavailable(level)
            }
        }
        return WrappingKeyProvider.Created(alias, level)
    }

    private fun generate(alias: String, strongBox: Boolean, requireDeviceAuth: Boolean): SecurityLevel {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        if (requireDeviceAuth) {
            builder.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    deviceAuthTimeoutSeconds,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
                .setInvalidatedByBiometricEnrollment(false)
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(builder.build())
        generator.generateKey()
        return securityLevelOf(alias) ?: SecurityLevel.UNKNOWN
    }

    override fun securityLevelOf(alias: String): SecurityLevel? {
        val key = loadKey(alias) ?: return null
        val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TRUSTED_ENVIRONMENT
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> SecurityLevel.SOFTWARE
            // UNKNOWN_SECURE means "secure hardware, unknown which"; we still refuse to label it.
            else -> SecurityLevel.UNKNOWN
        }
    }

    private fun loadKey(alias: String): SecretKey? = try {
        keyStore().getKey(alias, null) as? SecretKey
    } catch (e: UnrecoverableKeyException) {
        throw VaultCryptoException.UnrecoverableHardwareKey(e)
    }

    override fun wrap(alias: String, plaintext: ByteArray, associatedData: ByteArray): WrappedBlob {
        val key = loadKey(alias) ?: throw VaultCryptoException.UnrecoverableHardwareKey()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key) // Keystore generates a fresh random IV
            cipher.updateAAD(associatedData)
            WrappedBlob(iv = cipher.iv.copyOf(), ciphertext = cipher.doFinal(plaintext))
        } catch (e: UserNotAuthenticatedException) {
            throw VaultCryptoException.DeviceAuthenticationRequired(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultCryptoException.UnrecoverableHardwareKey(e)
        }
    }

    override fun unwrap(alias: String, blob: WrappedBlob, associatedData: ByteArray): ByteArray {
        val key = loadKey(alias) ?: throw VaultCryptoException.UnrecoverableHardwareKey()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.iv))
            cipher.updateAAD(associatedData)
            cipher.doFinal(blob.ciphertext)
        } catch (e: UserNotAuthenticatedException) {
            throw VaultCryptoException.DeviceAuthenticationRequired(e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw VaultCryptoException.UnrecoverableHardwareKey(e)
        } catch (e: AEADBadTagException) {
            throw VaultCryptoException.Malformed("Hardware-wrapped blob failed authentication", e)
        }
    }

    override fun delete(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
