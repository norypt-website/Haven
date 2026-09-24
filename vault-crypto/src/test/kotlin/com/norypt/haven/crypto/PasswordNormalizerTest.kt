package com.norypt.haven.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordNormalizerTest {
    @Test fun neverTrimsOrChangesCase() {
        assertThat(PasswordNormalizer.toBytes(" Pa ss ".toCharArray())).isEqualTo(" Pa ss ".toByteArray(Charsets.UTF_8))
    }

    @Test fun appliesNfc() {
        val decomposed = "é".toCharArray() // e + combining acute
        val composed = "é".toCharArray()
        assertThat(PasswordNormalizer.toBytes(decomposed)).isEqualTo(PasswordNormalizer.toBytes(composed))
    }

    @Test fun rejectsEmptyAndOversized() {
        assertThrows(IllegalArgumentException::class.java) { PasswordNormalizer.toBytes(CharArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { PasswordNormalizer.toBytes(CharArray(PasswordNormalizer.MAX_PASSWORD_CHARS + 1) { 'a' }) }
    }

    @Test fun longPassphraseWithUnicodeSurvives() {
        val s = "correct horse battery staple ünïcödé 🔐 " + "x".repeat(500)
        assertThat(String(PasswordNormalizer.toBytes(s.toCharArray()), Charsets.UTF_8)).isEqualTo(s)
    }
}
