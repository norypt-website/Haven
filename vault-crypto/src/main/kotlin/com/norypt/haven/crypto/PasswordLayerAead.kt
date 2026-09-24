package com.norypt.haven.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKey
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.util.SecretBytes
import java.security.GeneralSecurityException

/**
 * AES-256-GCM (Tink, NO_PREFIX variant) keyed directly with a password-derived key.
 *
 * Nonce safety: Tink draws a random 96-bit IV per encryption. Every envelope write uses a freshly
 * generated salt, hence a fresh derived key, so each key encrypts exactly one message.
 */
public object PasswordLayerAead {
    init {
        AeadConfig.register()
    }

    private fun aead(rawKey: ByteArray): Aead {
        require(rawKey.size == 32) { "key must be 32 bytes" }
        val params = AesGcmParameters.builder()
            .setKeySizeBytes(32)
            .setIvSizeBytes(12)
            .setTagSizeBytes(16)
            .setVariant(AesGcmParameters.Variant.NO_PREFIX)
            .build()
        val key = AesGcmKey.builder()
            .setParameters(params)
            .setKeyBytes(SecretBytes.copyFrom(rawKey, InsecureSecretKeyAccess.get()))
            .build()
        val handle = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary())
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    public fun encrypt(rawKey: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        aead(rawKey).encrypt(plaintext, associatedData)

    /** @throws GeneralSecurityException on wrong key or tampering. */
    @Throws(GeneralSecurityException::class)
    public fun decrypt(rawKey: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        aead(rawKey).decrypt(ciphertext, associatedData)
}
