package com.norypt.haven.crypto

/** Ciphertext produced by a device-bound wrapping key. */
public class WrappedBlob(public val iv: ByteArray, public val ciphertext: ByteArray)

/**
 * Device-bound wrapping keys. The Android implementation ([AndroidKeystoreWrappingKeys]) keeps
 * the key inside StrongBox or the TEE; the key material never enters application memory, only
 * the wrapped/unwrapped payload does.
 */
public interface WrappingKeyProvider {
    public class Created(public val alias: String, public val level: SecurityLevel)

    /** Creates a new AES-256-GCM wrapping key. [requireDeviceAuth] adds a device-credential/biometric requirement. */
    public fun create(alias: String, requireDeviceAuth: Boolean): Created

    /** Reports the level of an existing key, or null if it does not exist. */
    public fun securityLevelOf(alias: String): SecurityLevel?

    public fun wrap(alias: String, plaintext: ByteArray, associatedData: ByteArray): WrappedBlob

    /** Throws [VaultCryptoException.UnrecoverableHardwareKey] or [VaultCryptoException.DeviceAuthenticationRequired]. */
    public fun unwrap(alias: String, blob: WrappedBlob, associatedData: ByteArray): ByteArray

    /** Retires the key permanently (used by vault erasure and key rotation). Idempotent. */
    public fun delete(alias: String)
}
