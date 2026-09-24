package com.norypt.haven.crypto

/** Failures of the vault key-management layer. Messages never contain secrets. */
public sealed class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The password did not open the envelope. Indistinguishable (by design) from a tampered envelope. */
    public class WrongPasswordOrCorrupt : VaultCryptoException("Wrong password, or the vault key envelope is damaged")

    /** The hardware wrapping key is gone or unusable (factory reset, key invalidation, keystore failure). */
    public class UnrecoverableHardwareKey(cause: Throwable? = null) : VaultCryptoException("Hardware wrapping key is unavailable", cause)

    /** The device could not provide a key at the required security level and no fallback is allowed. */
    public class HardwareKeyUnavailable(public val level: SecurityLevel) : VaultCryptoException("No hardware-backed keystore available (level: $level)")

    /** The wrapping key demands a recent device authentication that has not happened. */
    public class DeviceAuthenticationRequired(cause: Throwable? = null) : VaultCryptoException("Device authentication required", cause)

    /** Structural problems: bad version, missing fields, out-of-bounds parameters. Never triggers automatic replacement. */
    public class Malformed(message: String, cause: Throwable? = null) : VaultCryptoException(message, cause)

    public class NoSuchVault(public val vaultId: String) : VaultCryptoException("No vault envelope for '$vaultId'")

    /**
     * The Argon2id derivation could not run to completion (native allocation failure or the JVM
     * ran out of memory for the requested cost). Not a password error; the caller should ask the
     * user to retry with more free memory. Never triggers automatic replacement.
     */
    public class KeyDerivationFailed(cause: Throwable? = null) : VaultCryptoException("Key derivation could not complete (memory)", cause)
}

/** Actual security level of a Keystore key, as reported by KeyInfo. Never inferred. */
public enum class SecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE, UNKNOWN }
