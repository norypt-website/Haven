package com.norypt.haven.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Argon2ParamsTest {
    @Test fun boundsAreEnforced() {
        assertThat(Argon2Params.validate(1024, 3, 4, 0x13)).isNull() // below RFC 9106 constrained profile
        assertThat(Argon2Params.validate(2 * 1024 * 1024, 3, 4, 0x13)).isNull()
        assertThat(Argon2Params.validate(65536, 0, 4, 0x13)).isNull()
        assertThat(Argon2Params.validate(65536, 3, 9, 0x13)).isNull()
        assertThat(Argon2Params.validate(65536, 3, 4, 0x10)).isNull()
        assertThat(Argon2Params.validate(65536, 3, 4, 0x13)).isEqualTo(Argon2Params.RFC9106_MEMORY_CONSTRAINED)
    }

    @Test fun benchmarkNeverGoesBelowBaseline() {
        val slowKdf = object : PasswordKeyDerivation {
            override fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray {
                Thread.sleep(50); return ByteArray(outputLength)
            }
        }
        val r = Argon2Benchmark(slowKdf).choose(targetMillis = 60)
        assertThat(r.params).isEqualTo(Argon2Params.RFC9106_MEMORY_CONSTRAINED)
    }

    @Test fun benchmarkEscalatesOnFastDevice() {
        val fastKdf = object : PasswordKeyDerivation {
            override fun deriveKey(password: ByteArray, salt: ByteArray, params: Argon2Params, outputLength: Int): ByteArray = ByteArray(outputLength)
        }
        val r = Argon2Benchmark(fastKdf).choose(targetMillis = 750)
        assertThat(r.params).isEqualTo(Argon2Params.PROFILE_256M)
    }
}
