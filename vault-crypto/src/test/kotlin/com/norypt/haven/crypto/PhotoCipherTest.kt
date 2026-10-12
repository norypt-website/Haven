package com.norypt.haven.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class PhotoCipherTest {
    private val dek = ByteArray(32) { it.toByte() }
    private fun bytes(n: Int) = ByteArray(n).also { Random(1).nextBytes(it) }
    private fun enc(plain: ByteArray, id: String = "p1", kind: PhotoCipher.Kind = PhotoCipher.Kind.ORIGINAL, key: ByteArray = dek) =
        ByteArrayOutputStream().also { PhotoCipher.encrypt(key, id, kind, plain, it) }.toByteArray()
    private fun dec(c: ByteArray, id: String = "p1", kind: PhotoCipher.Kind = PhotoCipher.Kind.ORIGINAL, key: ByteArray = dek) =
        PhotoCipher.decrypt(key, id, kind, ByteArrayInputStream(c))

    @Test fun roundTrip() {
        val plain = bytes(300_000)
        assertThat(dec(enc(plain))).isEqualTo(plain)
    }

    @Test fun emptyRoundTrip() { assertThat(dec(enc(ByteArray(0)))).isEmpty() }

    @Test fun wrongKeyFails() {
        val other = ByteArray(32) { 9 }
        assertThrows(PhotoCipher.Corrupt::class.java) { dec(enc(bytes(10)), key = other) }
    }

    @Test fun swappedPhotoIdFails() {
        assertThrows(PhotoCipher.Corrupt::class.java) { dec(enc(bytes(10), id = "p1"), id = "p2") }
    }

    @Test fun swappedKindFails() {
        assertThrows(PhotoCipher.Corrupt::class.java) { dec(enc(bytes(10)), kind = PhotoCipher.Kind.THUMBNAIL) }
    }

    @Test fun modifiedByteFails() {
        val c = enc(bytes(100_000)); c[c.size / 2] = (c[c.size / 2].toInt() xor 1).toByte()
        assertThrows(PhotoCipher.Corrupt::class.java) { dec(c) }
    }

    @Test fun truncatedFails() {
        val c = enc(bytes(100_000))
        assertThrows(PhotoCipher.Corrupt::class.java) { dec(c.copyOf(c.size - 100)) }
    }

    @Test fun sizesInTheSameBucketGiveTheSameFileSize() {
        assertThat(enc(bytes(1_000)).size).isEqualTo(enc(bytes(60_000)).size)
        assertThat(enc(bytes(70_000)).size).isGreaterThan(enc(bytes(60_000)).size)
    }

    @Test fun paddedLengthRoundsUpIncludingLengthPrefix() {
        assertThat(PhotoCipher.paddedLength(0, PhotoCipher.Kind.ORIGINAL)).isEqualTo(65_536L)
        assertThat(PhotoCipher.paddedLength(65_528, PhotoCipher.Kind.ORIGINAL)).isEqualTo(65_536L)
        assertThat(PhotoCipher.paddedLength(65_529, PhotoCipher.Kind.ORIGINAL)).isEqualTo(131_072L)
        assertThat(PhotoCipher.paddedLength(10, PhotoCipher.Kind.THUMBNAIL)).isEqualTo(16_384L)
    }

    @Test fun oversizeRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoCipher.paddedLength((PhotoCipher.MAX_PLAINTEXT + 1).toInt(), PhotoCipher.Kind.ORIGINAL)
        }
    }

    @Test fun newDekIsRandomAndRightLength() {
        val a = PhotoCipher.newDek(); val b = PhotoCipher.newDek()
        assertThat(a).hasLength(32); assertThat(a).isNotEqualTo(b)
    }

    @Test fun wrongDekLengthRefused() {
        assertThrows(IllegalArgumentException::class.java) { enc(bytes(1), key = ByteArray(16)) }
    }
}
