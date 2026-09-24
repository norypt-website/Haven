package com.norypt.haven.backup

import java.io.IOException

/**
 * Failure modes of the HVBK backup container.
 *
 * Extends [IOException] so that failures can propagate through [java.io.InputStream.read] on the
 * plaintext stream returned by [BackupReader.open].
 */
public sealed class BackupFormatException(message: String, cause: Throwable? = null) :
    IOException(message, cause) {

    /** The bytes are not a well-formed HVBK container: bad magic, bad framing or an unparsable header. */
    public class Malformed(message: String, cause: Throwable? = null) : BackupFormatException(message, cause)

    /** The container or payload declares a version this implementation does not understand. */
    public class UnsupportedVersion(message: String) : BackupFormatException(message)

    /** A header parameter lies outside the bounds this implementation is willing to process. */
    public class ParametersOutOfBounds(message: String) : BackupFormatException(message)

    /** Wrong passphrase, wrong backup key, or the file was modified after it was written. */
    public class AuthenticationFailed(message: String, cause: Throwable? = null) :
        BackupFormatException(message, cause)

    /** The file ends before the container is complete. */
    public class Truncated(message: String, cause: Throwable? = null) : BackupFormatException(message, cause)
}
