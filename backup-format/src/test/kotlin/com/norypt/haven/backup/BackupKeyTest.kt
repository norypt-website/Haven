package com.norypt.haven.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupKeyTest {
    private val key = BackupKey.generate(seededRandom(1))

    @Test
    fun displayFormIsGroupedCrockfordBase32() {
        val display = key.toDisplayString()
        assertThat(display).matches("^([0-9A-HJKMNP-TV-Z]{4}-){13}[0-9A-HJKMNP-TV-Z]$")
        assertThat(display.replace("-", "")).hasLength(BackupKey.DISPLAY_LENGTH)
    }

    @Test
    fun parseRoundTrips() {
        val parsed = BackupKey.parse(key.toDisplayString())
        assertThat(parsed.bytes()).isEqualTo(key.bytes())
        assertThat(parsed.keyId()).isEqualTo(key.keyId())
    }

    @Test
    fun parseAcceptsLowercaseMissingDashesAndWhitespace() {
        val display = key.toDisplayString()
        assertThat(BackupKey.parse(display.lowercase()).bytes()).isEqualTo(key.bytes())
        assertThat(BackupKey.parse(display.replace("-", "")).bytes()).isEqualTo(key.bytes())
        assertThat(BackupKey.parse(display.replace("-", " ") + "\n").bytes()).isEqualTo(key.bytes())
    }

    @Test
    fun parseMapsConfusableCharacters() {
        assertThat(CrockfordBase32.normalize("oO iI-lL")).isEqualTo("001111")
        val key = (1L..200L).map { BackupKey.generate(seededRandom(it)) }
            .first { val d = it.toDisplayString(); '0' in d && '1' in d }
        val display = key.toDisplayString()
        val confused = display.replace('0', 'O').replace('1', 'I')
        assertThat(BackupKey.parse(confused).bytes()).isEqualTo(key.bytes())
        val lowerConfused = display.replace('0', 'o').replace('1', 'l')
        assertThat(BackupKey.parse(lowerConfused).bytes()).isEqualTo(key.bytes())
    }

    @Test
    fun parseRejectsBadChecksum() {
        val chars = key.toDisplayString().replace("-", "").toCharArray()
        val last = chars.lastIndex
        chars[last] = if (chars[last] == 'A') 'B' else 'A'
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse(String(chars)) }
    }

    @Test
    fun parseRejectsCorruptedBody() {
        val chars = key.toDisplayString().toCharArray()
        chars[0] = if (chars[0] == 'A') 'B' else 'A'
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse(String(chars)) }
    }

    @Test
    fun parseRejectsWrongLengthAndAlphabet() {
        val display = key.toDisplayString()
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse(display.dropLast(1)) }
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse(display + "A") }
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse("U" + display.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) { BackupKey.parse("") }
    }

    @Test
    fun keyIdIsStableAndDoesNotRevealKey() {
        val again = BackupKey.fromBytes(key.bytes())
        assertThat(again.keyId()).isEqualTo(key.keyId())
        assertThat(key.keyId()).hasLength(12)
        assertThat(BackupKey.generate(seededRandom(2)).keyId()).isNotEqualTo(key.keyId())
    }

    @Test
    fun fromBytesCopiesAndValidatesLength() {
        val bytes = key.bytes()
        val copy = BackupKey.fromBytes(bytes)
        bytes.fill(0)
        assertThat(copy.bytes()).isEqualTo(key.bytes())
        assertThrows(IllegalArgumentException::class.java) { BackupKey.fromBytes(ByteArray(31)) }
    }

    @Test
    fun destroyWipesAndBlocksAccess() {
        val victim = BackupKey.fromBytes(key.bytes())
        victim.destroy()
        assertThat(victim.isDestroyed).isTrue()
        assertThrows(IllegalStateException::class.java) { victim.bytes() }
        assertThrows(IllegalStateException::class.java) { victim.toDisplayString() }
        assertThat(victim.toString()).doesNotContain(key.keyId())
    }

    @Test
    fun crockfordEncodingIsCanonical() {
        val zeros = CrockfordBase32.encode(ByteArray(5))
        assertThat(zeros).isEqualTo("00000000")
        assertThat(CrockfordBase32.decode(zeros, 5)).isEqualTo(ByteArray(5))
        val ones = CrockfordBase32.encode(ByteArray(5) { 0xff.toByte() })
        assertThat(ones).isEqualTo("ZZZZZZZZ")
    }
}
