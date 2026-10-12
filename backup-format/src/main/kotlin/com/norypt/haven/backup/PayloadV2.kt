package com.norypt.haven.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Payload format 2: `u32 jsonLength || JSON || for each photo in JSON order: u64 length || bytes`.
 * Everything here is inside the container's authenticated stream; this layer only frames and
 * bounds. Photo bytes are handed over one at a time, so memory holds at most one photo.
 */
public object PayloadV2 {
    public const val MAX_PHOTOS: Int = 100_000
    public const val MAX_PHOTO_BYTES: Long = 64L * 1024 * 1024

    /**
     * Writes [payload] (its `format` is written as 2) followed by each photo's bytes, in the order of
     * `passwords.photos`. [photoBytes] is called once per photo, only while that photo is being
     * written; the array it returns is wiped as soon as it has been written, whatever the outcome,
     * so it must be a fresh array the caller does not use again.
     *
     * Refuses, with [IllegalArgumentException] and before writing anything, a payload the reader
     * would reject: more than [MAX_PHOTOS] photos, duplicate photo ids, a `byteSize` outside
     * 0..[MAX_PHOTO_BYTES], or JSON larger than [BackupPayloadJson.MAX_PAYLOAD_BYTES]. Throws
     * [IllegalStateException] when a photo's bytes do not match its `byteSize`.
     */
    public fun write(out: OutputStream, payload: BackupPayload, photoBytes: (PhotoRecord) -> ByteArray) {
        val photos = payload.passwords?.photos.orEmpty()
        require(photos.size <= MAX_PHOTOS) { "more than $MAX_PHOTOS photos" }
        require(photos.map { it.id }.toSet().size == photos.size) { "duplicate photo id" }
        require(photos.all { it.byteSize in 0..MAX_PHOTO_BYTES }) { "photo larger than $MAX_PHOTO_BYTES bytes" }
        val data = DataOutputStream(out)
        val json = BackupPayloadJson.encode(payload.copy(format = 2)).toByteArray(Charsets.UTF_8)
        require(json.size <= BackupPayloadJson.MAX_PAYLOAD_BYTES) { "payload JSON larger than ${BackupPayloadJson.MAX_PAYLOAD_BYTES} bytes" }
        data.writeInt(json.size)
        data.write(json)
        for (record in photos) {
            val bytes = photoBytes(record)
            try {
                check(bytes.size.toLong() == record.byteSize) { "photo ${record.id} changed size" }
                data.writeLong(bytes.size.toLong())
                data.write(bytes)
            } finally {
                bytes.fill(0)
            }
        }
        data.flush()
    }

    /**
     * Reads a format 2 payload. Each photo's bytes are passed to [onPhoto] as they arrive; the array
     * is ZEROED as soon as [onPhoto] returns, so the callback must consume or copy it synchronously.
     * Delivery is provisional: the photos handed over count only if this function returns normally,
     * because the rest of the stream (and its authentication, when [input] is a backup's plaintext)
     * may still fail. Callers stage what they receive and discard it on any exception.
     *
     * The JSON `format` must be 2, and every bound in FORMAT.md is checked before the bytes it
     * covers are allocated; violations are [BackupFormatException.Malformed].
     */
    public fun read(input: InputStream, onPhoto: (PhotoRecord, ByteArray) -> Unit): BackupPayload {
        val data = DataInputStream(input)
        try {
            val jsonLength = data.readInt()
            if (jsonLength < 2 || jsonLength > BackupPayloadJson.MAX_PAYLOAD_BYTES) {
                throw BackupFormatException.Malformed("JSON length $jsonLength out of bounds")
            }
            val json = ByteArray(jsonLength).also { data.readFully(it) }
            val payload = BackupPayloadJson.decode(String(json, Charsets.UTF_8))
            if (payload.format != 2) throw BackupFormatException.Malformed("payload format ${payload.format} inside the format 2 layout")
            val photos = payload.passwords?.photos.orEmpty()
            if (photos.size > MAX_PHOTOS) throw BackupFormatException.Malformed("more than $MAX_PHOTOS photos")
            if (photos.map { it.id }.toSet().size != photos.size) throw BackupFormatException.Malformed("duplicate photo id")
            for (record in photos) {
                if (record.byteSize !in 0..MAX_PHOTO_BYTES) {
                    throw BackupFormatException.Malformed("photo larger than $MAX_PHOTO_BYTES bytes")
                }
                val length = data.readLong()
                if (length != record.byteSize) throw BackupFormatException.Malformed("photo length does not match its record")
                val bytes = ByteArray(length.toInt())
                try {
                    data.readFully(bytes)
                    onPhoto(record, bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            if (data.read() != -1) throw BackupFormatException.Malformed("unexpected data after the last photo")
            return payload
        } catch (e: EOFException) {
            throw BackupFormatException.Malformed("payload ended early", e)
        }
    }
}
