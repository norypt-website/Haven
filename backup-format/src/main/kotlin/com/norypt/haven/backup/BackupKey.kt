package com.norypt.haven.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The independent random 256-bit backup key. Together with the user's passphrase it forms the
 * second factor required to open a backup; neither factor alone is sufficient.
 *
 * The display form is Crockford-style base32 of the key followed by a one-byte checksum, in
 * groups of four characters separated by dashes. [parse] tolerates lowercase, missing separators
 * and the common O/0 and I/L/1 confusions.
 *
 * Call [destroy] when the key is no longer needed. Wiping on the JVM is best effort: copies made
 * by the runtime or by Tink cannot be reached from here.
 */
public class BackupKey private constructor(private val key: ByteArray) {

    @Volatile
    private var destroyed: Boolean = false

    public val isDestroyed: Boolean
        get() = destroyed

    /** A fresh copy of the raw key bytes. The caller owns the copy and should wipe it. */
    public fun bytes(): ByteArray {
        checkNotDestroyed()
        return key.copyOf()
    }

    /** Base64 of the first 8 bytes of SHA-256(key). Identifies the key without revealing it. */
    public fun keyId(): String {
        checkNotDestroyed()
        return keyIdOf(key)
    }

    public fun toDisplayString(): String {
        checkNotDestroyed()
        val payload = ByteArray(LENGTH + 1)
        try {
            key.copyInto(payload)
            payload[LENGTH] = sha256(key)[0]
            return CrockfordBase32.encode(payload).chunked(DISPLAY_GROUP).joinToString(DISPLAY_SEPARATOR)
        } finally {
            payload.fill(0)
        }
    }

    public fun destroy() {
        destroyed = true
        key.fill(0)
    }

    override fun toString(): String = "BackupKey(${if (destroyed) "destroyed" else "keyId=" + keyId()})"

    private fun checkNotDestroyed() {
        check(!destroyed) { "BackupKey has been destroyed" }
    }

    public companion object {
        public const val LENGTH: Int = BackupFormat.BACKUP_KEY_LENGTH

        /** 33 bytes (key plus checksum) in base32 without padding: ceil(264 / 5) characters. */
        public const val DISPLAY_LENGTH: Int = 53
        private const val DISPLAY_GROUP = 4
        private const val DISPLAY_SEPARATOR = "-"

        public fun generate(random: SecureRandom = SecureRandom()): BackupKey =
            BackupKey(ByteArray(LENGTH).also(random::nextBytes))

        public fun fromBytes(bytes: ByteArray): BackupKey {
            require(bytes.size == LENGTH) { "Backup key must be $LENGTH bytes, got ${bytes.size}" }
            return BackupKey(bytes.copyOf())
        }

        /**
         * Parses the display form. Throws [IllegalArgumentException] when the text has the wrong
         * length, contains characters outside the alphabet, or the checksum does not match.
         */
        public fun parse(display: String): BackupKey {
            val normalized = CrockfordBase32.normalize(display)
            require(normalized.length == DISPLAY_LENGTH) {
                "Backup key must have $DISPLAY_LENGTH characters, got ${normalized.length}"
            }
            val decoded = CrockfordBase32.decode(normalized, LENGTH + 1)
            try {
                val key = decoded.copyOfRange(0, LENGTH)
                if (decoded[LENGTH] != sha256(key)[0]) {
                    key.fill(0)
                    throw IllegalArgumentException("Backup key checksum does not match")
                }
                return BackupKey(key)
            } finally {
                decoded.fill(0)
            }
        }

        internal fun keyIdOf(key: ByteArray): String =
            Base64.getEncoder().encodeToString(sha256(key).copyOf(BackupFormat.KEY_ID_LENGTH))

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    }
}

/** Crockford-style base32 without padding: alphabet 0-9 A-Z minus I, L, O, U. */
internal object CrockfordBase32 {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val VALUES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var acc = 0
        var bits = 0
        for (b in data) {
            acc = (acc shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(acc ushr bits) and 31])
            }
            acc = acc and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(ALPHABET[(acc shl (5 - bits)) and 31])
        return out.toString()
    }

    /** Drops separators and whitespace, uppercases, and maps O to 0 and I/L to 1. */
    fun normalize(text: String): String = buildString(text.length) {
        for (c in text) {
            if (c == '-' || c.isWhitespace()) continue
            when (val upper = c.uppercaseChar()) {
                'O' -> append('0')
                'I', 'L' -> append('1')
                else -> append(upper)
            }
        }
    }

    fun decode(text: String, expectedLength: Int): ByteArray {
        val out = ByteArray(expectedLength)
        var index = 0
        var acc = 0
        var bits = 0
        for (c in text) {
            val value = if (c.code < VALUES.size) VALUES[c.code] else -1
            require(value >= 0) { "Invalid character in backup key" }
            acc = (acc shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                require(index < expectedLength) { "Backup key is too long" }
                out[index++] = ((acc ushr bits) and 0xff).toByte()
                acc = acc and ((1 shl bits) - 1)
            }
        }
        require(index == expectedLength) { "Backup key is too short" }
        require(acc == 0) { "Backup key has invalid trailing bits" }
        return out
    }
}
