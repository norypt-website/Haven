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

    public fun write(out: OutputStream, payload: BackupPayload, photoBytes: (PhotoRecord) -> ByteArray) {
        val data = DataOutputStream(out)
        val json = BackupPayloadJson.encode(payload.copy(format = 2)).toByteArray(Charsets.UTF_8)
        data.writeInt(json.size)
        data.write(json)
        for (record in payload.passwords?.photos.orEmpty()) {
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

    public fun read(input: InputStream, onPhoto: (PhotoRecord, ByteArray) -> Unit): BackupPayload {
        val data = DataInputStream(input)
        try {
            val jsonLength = data.readInt()
            if (jsonLength < 2 || jsonLength > BackupPayloadJson.MAX_PAYLOAD_BYTES) {
                throw BackupFormatException.Malformed("JSON length $jsonLength out of bounds")
            }
            val json = ByteArray(jsonLength).also { data.readFully(it) }
            val payload = BackupPayloadJson.decode(String(json, Charsets.UTF_8))
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
