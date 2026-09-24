package com.norypt.haven

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.data.PasswordGenerator
import org.junit.Test

class PasswordGeneratorTest {
    @Test fun everySelectedClassAppearsAndLengthIsHonoured() {
        repeat(50) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 12))
            assertThat(pw.size).isEqualTo(12)
            assertThat(pw.any { it.isLowerCase() }).isTrue()
            assertThat(pw.any { it.isUpperCase() }).isTrue()
            assertThat(pw.any { it.isDigit() }).isTrue()
            assertThat(pw.any { !it.isLetterOrDigit() }).isTrue()
            assertThat(pw.none { it in "O0Il1|`'\"" }).isTrue()
        }
    }

    @Test fun lengthIsClampedAndClassesRespected() {
        val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 4, symbols = false, upper = false))
        assertThat(pw.size).isEqualTo(8)
        assertThat(pw.all { it.isLowerCase() || it.isDigit() }).isTrue()
    }

    @Test fun passphraseUsesBundledListAndReportsEntropy() {
        val p = String(PasswordGenerator.passphrase(6))
        assertThat(p.split(" ")).hasSize(6)
        assertThat(PasswordGenerator.passphraseEntropyBits(6)).isWithin(0.5).of(62.0) // 6 * log2(1296)
        assertThat(PasswordGenerator.entropyBits(PasswordGenerator.Options(length = 20))).isGreaterThan(100.0)
    }

    @Test fun outputsDiffer() {
        val a = String(PasswordGenerator.generate(PasswordGenerator.Options()))
        val b = String(PasswordGenerator.generate(PasswordGenerator.Options()))
        assertThat(a).isNotEqualTo(b)
    }
}
