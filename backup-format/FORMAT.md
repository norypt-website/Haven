# HAVEN backup container (HVBK), version 1

This document specifies the encrypted backup file written by the `backup-format` module
(`com.norypt.haven.backup`). Files carry the extension `.hvbk`.

## Design goals

- Two independent factors are both required to open a backup: the user's backup passphrase and
  a random 256-bit backup key that the app shows once and the user records. There is no
  alternative envelope, recovery key or vendor escrow that bypasses either factor.
- Everything after the header is produced by a Tink streaming AEAD (`AES256_GCM_HKDF_1MB`).
  The module invents no cryptographic primitives: Tink supplies the AEAD and the HKDF; Argon2id
  is supplied by the host platform through the `PasswordKdf` interface.
- The whole file, including the cleartext header, is authenticated. Any modification is
  detected before plaintext from the affected segment is released.
- Writing and reading are streaming. At most one 1 MiB segment is buffered at a time, and this
  module never writes plaintext to disk.
- Every header value is parsed with bounds before any allocation it controls and before any
  key derivation work.

## File layout

All integers are big-endian.

```
offset 0       : magic "HAVENBK1"                              (8 bytes, ASCII)
offset 8       : u16 header length L, 1 <= L <= 16384
offset 10      : header JSON, UTF-8                             (L bytes)
offset 10+L    : key check block                                (56 bytes)
offset 66+L    : body: streaming AEAD ciphertext of the payload (variable)
end - 64       : end marker block                               (64 bytes)
```

The first three fields are the *framing*, `F = magic || u16 L || header JSON`, exactly as they
appear in the file. `F` is the associated data of every AEAD ciphertext in the file, so a change
to any header byte breaks authentication of the whole file.

### Key check block

A streaming AEAD ciphertext of the empty plaintext under the file key, with associated data
`"HVBK1-KEYCHECK:" || F`. Its size is fixed: Tink's 40-byte stream header (1 length byte,
32-byte salt, 7-byte nonce prefix) plus one 16-byte tag. It lets the reader tell "wrong
passphrase or wrong backup key or modified header" apart from "damaged or truncated payload"
before it starts streaming the body, and it reveals nothing beyond what the body itself would
reveal to an attacker testing candidate keys.

### Body

A streaming AEAD ciphertext of the payload under the file key, with associated data `F`. The
payload is the UTF-8 JSON snapshot described under *Payload*. Parameters:

```
AesGcmHkdfStreamingParameters
  keySizeBytes              = 32
  derivedAesGcmKeySizeBytes = 32
  hkdfHashType              = SHA256
  ciphertextSegmentSizeBytes = 1 MiB (1048576)
```

Every segment carries its own AES-GCM tag, so tampering inside the body is detected at the
segment where it happens and no later plaintext is released.

### End marker block

A streaming AEAD ciphertext of the body length as a u64 (the number of body bytes between the
key check block and the end marker), with associated data `"HVBK1-END:" || F`. Fixed size: 40 +
8 + 16 = 64 bytes. The reader uses it to distinguish a file that was cut short from a file that
was modified: an AEAD alone cannot tell a truncated final segment from a corrupted one.

## Header

The header is a JSON object. Decoders ignore unknown keys; encoders always emit every field.

| field              | type            | meaning                                                          |
|--------------------|-----------------|------------------------------------------------------------------|
| `version`          | int, must be 1  | container version                                                |
| `createdAtEpochMs` | long            | creation time, Unix milliseconds                                 |
| `appVersionCode`   | int             | version code of the app that wrote the file                      |
| `kdf`              | object          | Argon2id parameters `memoryKib`, `iterations`, `parallelism`     |
| `salt`             | string          | base64 of 16 random bytes, used by Argon2id and HKDF             |
| `backupKeyId`      | string          | base64 of the first 8 bytes of SHA-256(backupKey)                |
| `contents`         | array of string | payload sections present, e.g. `["content","passwords"]`         |
| `payloadFormat`    | int, must be 1  | schema version of the payload JSON                               |

`backupKeyId` lets the app tell the user which recorded backup key a file needs without
revealing the key. It is a hint only: it is not used for any security decision.

### Bounds enforced before parsing and before key derivation

| value            | accepted range                                   | failure                |
|------------------|--------------------------------------------------|------------------------|
| magic            | exactly `HAVENBK1`                               | `Malformed`            |
| header length    | 1 .. 16384 bytes                                 | `Malformed`            |
| header JSON      | well-formed object with the fields above          | `Malformed`            |
| `version`        | 1                                                | `UnsupportedVersion`   |
| `payloadFormat`  | 1                                                | `UnsupportedVersion`   |
| `kdf.memoryKib`  | 8192 .. 1048576 (8 MiB .. 1 GiB)                 | `ParametersOutOfBounds`|
| `kdf.iterations` | 1 .. 16                                          | `ParametersOutOfBounds`|
| `kdf.parallelism`| 1 .. 8                                           | `ParametersOutOfBounds`|
| `salt`           | valid base64 of exactly 16 bytes                 | `Malformed` / `ParametersOutOfBounds` |
| `backupKeyId`    | valid base64 of exactly 8 bytes                  | `Malformed`            |

The header length is checked before the header buffer is allocated. The JSON is parsed into a
tree first (the parser is iterative beyond a fixed nesting depth), the version is checked, and
only then are the remaining fields decoded and bounded. No KDF call happens before every check
has passed.

## Key derivation

```
pwKey   = Argon2id(passphraseBytes, salt, memoryKib, iterations, parallelism, outLen=32)   // supplied by PasswordKdf interface
ikm     = pwKey || backupKey (32 || 32 bytes)
fileKey = HKDF-SHA256(ikm, salt = header.salt bytes, info = "com.norypt.haven.backup.v1.filekey", length = 32)
```

`fileKey` is imported into Tink as an `AesGcmHkdfStreamingKey` with the parameters above, in a
single-entry keyset. Tink then derives a fresh per-stream AES-GCM key from `fileKey` and the
random 32-byte salt at the start of each ciphertext (key check, body, end marker), so the three
ciphertexts of a file, and the ciphertexts of different files written with the same
credentials, never share an AES-GCM key.

`pwKey`, `ikm` and `fileKey` are zeroed in `finally` blocks once the Tink primitive exists.
This is best effort on the JVM: the runtime may have copied the arrays, and the copy Tink keeps
inside its key object is not reachable. The caller owns the passphrase bytes and the
`BackupKey` and is responsible for wiping them.

## Backup key display form

The 32 key bytes are followed by one checksum byte (the first byte of SHA-256 of the key) and
the 33 bytes are encoded with Crockford-style base32 (alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`,
no padding, most significant bit first, final character carries the remaining 4 bits with a
zero pad). The 53 characters are shown in groups of four separated by `-`, for example
`ABCD-EFGH-...-XYZ1-2`. Parsing strips whitespace and dashes, uppercases, maps `O` to `0` and
`I`/`L` to `1`, decodes, rejects non-zero padding bits, and rejects a checksum mismatch.

## Error classification

| situation                                             | exception                                     |
|-------------------------------------------------------|-----------------------------------------------|
| not an HVBK file, bad framing, unparsable header      | `BackupFormatException.Malformed`             |
| container or payload version other than 1             | `BackupFormatException.UnsupportedVersion`    |
| KDF parameter or salt outside the bounds above        | `BackupFormatException.ParametersOutOfBounds` |
| wrong passphrase, wrong backup key, modified header   | `AuthenticationFailed` (at `open`)            |
| modified key check, body segment or end marker        | `AuthenticationFailed` (at `open` or on read) |
| file ends before the container is complete            | `BackupFormatException.Truncated`             |
| I/O error from the source stream                      | the original `IOException`, unchanged         |

Classification of a body failure: if Tink fails before the reader has handed it the last body
byte, the file was modified. If it fails at the end of the body, the reader verifies the end
marker: a valid marker whose length matches the bytes consumed means the final segment was
modified (`AuthenticationFailed`); anything else means the file was cut short (`Truncated`).
Error classification is a usability aid; a restore is refused in every failure case.

The plaintext stream returned by `BackupReader.open` authenticates each 1 MiB segment before
returning it and verifies the end marker when it reports end-of-stream. Callers must read to
end-of-stream before treating the payload as complete and must discard anything read before a
failure. After a failure every further read throws the same exception.

## Payload

The body plaintext is a UTF-8 JSON document (`BackupPayload`, `payloadFormat` 1):

```
BackupPayload      { format: 1, content: ContentSnapshot?, passwords: PasswordSnapshot?, settings: {string: string} }
ContentSnapshot    { reminders: [ReminderRecord], taskLists: [TaskListRecord], tasks: [TaskRecord] }
ReminderRecord     { id, title, notes, enabled, scheduleJson, createdAtEpochMs, updatedAtEpochMs }
TaskListRecord     { id, name, position, createdAtEpochMs }
TaskRecord         { id, listId, title, notes, completed, completedAtEpochMs?, dueLocal?, reminderId?, position, createdAtEpochMs, updatedAtEpochMs }
PasswordSnapshot   { folders: [FolderRecord], entries: [PasswordEntryRecord] }
FolderRecord       { id, name, position }
PasswordEntryRecord{ id, folderId?, title, website, username, password, notes, createdAtEpochMs, updatedAtEpochMs }
```

`dueLocal` is an ISO-8601 local date-time without offset. Decoding ignores unknown keys so a
newer app can add fields without breaking older readers; a `format` other than 1 is rejected
with `UnsupportedVersion`. `BackupPayloadJson.decode(InputStream)` refuses documents larger
than 256 MiB with `Malformed` before parsing them.

## Security properties

- Confidentiality and integrity of the payload rest on AES-256-GCM with per-segment tags, keyed
  through HKDF from a key that depends on both the passphrase and the backup key.
- An attacker who holds the file and the backup key must still brute-force the passphrase
  through Argon2id with the recorded cost. An attacker who holds the file and the passphrase
  gains nothing without the 256-bit backup key.
- Header, key check, every body segment and the end marker are all bound to the exact header
  bytes. Reordering, swapping or editing any part of the file is detected.
- Segments cannot be reordered, dropped or duplicated inside the body: Tink's streaming
  construction binds the segment index and the last-segment flag into each nonce.
- The reader never releases plaintext from a segment that failed authentication, and never
  starts key derivation on a header that failed validation.

## Limitations

- Authentication does not prevent rollback: an attacker with write access to the backup
  location can replace a newer backup with an older valid one. The app should show
  `createdAtEpochMs` and let the user judge.
- Old backups keep old data. Deleting an entry in the app does not remove it from backups
  written earlier, and changing the passphrase or backup key does not re-encrypt existing files.
- Losing either factor makes the backup unrecoverable. There is no recovery path, no key escrow
  and no hint stored in the file beyond `backupKeyId`. Norypt cannot recover a backup.
- The header is cleartext: creation time, app version, KDF cost, which sections are present and
  the backup key identifier are visible to anyone holding the file.
- The file size reveals the approximate payload size.
- Key material wiping on the JVM is best effort (see *Key derivation*).
- The container does not protect against an attacker who controls the device while the app is
  unlocked; it protects the file at rest and in transit.
