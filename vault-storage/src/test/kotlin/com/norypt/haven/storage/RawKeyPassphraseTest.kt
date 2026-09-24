package com.norypt.haven.storage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RawKeyPassphraseTest {
    @Test fun encodesSqlcipherRawKeySyntax() {
        val key = ByteArray(32) { it.toByte() }
        val s = String(EncryptedDatabaseOpener.rawKeyPassphrase(key), Charsets.US_ASCII)
        assertThat(s).isEqualTo("x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'")
        assertThat(s).hasLength(67)
    }
}
