package com.norypt.haven.backup

/** Constants of the HVBK container, version 1. See FORMAT.md for the full specification. */
public object BackupFormat {
    public const val MAGIC: String = "HAVENBK1"
    public const val VERSION: Int = 1
    public const val PAYLOAD_FORMAT: Int = 1
    public const val FILE_EXTENSION: String = "hvbk"

    /** Upper bound of the header JSON in bytes; enforced before the header is parsed. */
    public const val MAX_HEADER_LENGTH: Int = 16_384
    public const val SALT_LENGTH: Int = 16
    public const val BACKUP_KEY_LENGTH: Int = 32
    public const val KEY_ID_LENGTH: Int = 8

    internal const val FRAMING_PREFIX_LENGTH: Int = 10
    internal const val FILE_KEY_LENGTH: Int = 32
    internal const val CIPHERTEXT_SEGMENT_SIZE: Int = 1 shl 20
    internal const val FILE_KEY_INFO: String = "com.norypt.haven.backup.v1.filekey"

    /** Streaming AEAD ciphertext of an empty plaintext: 40-byte Tink header plus a 16-byte tag. */
    internal const val KEY_CHECK_LENGTH: Int = 56

    /** Streaming AEAD ciphertext of the 8-byte body length: 40-byte Tink header, 8 bytes, 16-byte tag. */
    internal const val END_MARKER_LENGTH: Int = 64

    internal val MAGIC_BYTES: ByteArray = MAGIC.toByteArray(Charsets.US_ASCII)
    private val KEY_CHECK_LABEL: ByteArray = "HVBK1-KEYCHECK:".toByteArray(Charsets.US_ASCII)
    private val END_MARKER_LABEL: ByteArray = "HVBK1-END:".toByteArray(Charsets.US_ASCII)

    /** Associated data of the body: exactly the framing bytes (magic, length, header JSON). */
    internal fun bodyAad(framing: ByteArray): ByteArray = framing

    internal fun keyCheckAad(framing: ByteArray): ByteArray = KEY_CHECK_LABEL + framing

    internal fun endMarkerAad(framing: ByteArray): ByteArray = END_MARKER_LABEL + framing
}
