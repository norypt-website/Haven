package com.norypt.haven.security

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.security.EraseAfterFailures.OFF
import org.junit.Test

class EraseAfterFailuresTest {
    @Test fun theChoicesAreThreeFiveSevenAndTenWithTenAsTheDefault() {
        assertThat(EraseAfterFailures.CHOICES).containsExactly(3, 5, 7, 10).inOrder()
        assertThat(EraseAfterFailures.DEFAULT).isEqualTo(10)
    }

    @Test fun anUnknownStoredValueFallsBackToTheDefaultAndNeverToOff() {
        assertThat(EraseAfterFailures.sanitize(OFF)).isEqualTo(OFF)
        EraseAfterFailures.CHOICES.forEach { assertThat(EraseAfterFailures.sanitize(it)).isEqualTo(it) }
        listOf(-1, 1, 2, 4, 11, 1000).forEach { assertThat(EraseAfterFailures.sanitize(it)).isEqualTo(10) }
    }

    @Test fun theVaultIsErasedOnceTheWrongPasswordsReachTheLimit() {
        assertThat(EraseAfterFailures.shouldErase(limit = 3, failures = 2)).isFalse()
        assertThat(EraseAfterFailures.shouldErase(limit = 3, failures = 3)).isTrue()
        assertThat(EraseAfterFailures.shouldErase(limit = 3, failures = 4)).isTrue()
    }

    @Test fun offNeverErasesAndHasNoCountdown() {
        assertThat(EraseAfterFailures.shouldErase(OFF, failures = 1_000)).isFalse()
        assertThat(EraseAfterFailures.attemptsLeft(OFF, failures = 5)).isNull()
    }

    @Test fun attemptsLeftCountsDownToZero() {
        assertThat(EraseAfterFailures.attemptsLeft(10, failures = 0)).isEqualTo(10)
        assertThat(EraseAfterFailures.attemptsLeft(10, failures = 7)).isEqualTo(3)
        assertThat(EraseAfterFailures.attemptsLeft(10, failures = 10)).isEqualTo(0)
        assertThat(EraseAfterFailures.attemptsLeft(10, failures = 12)).isEqualTo(0)
    }

    @Test fun allowingMoreAttemptsOrTurningTheEraseOffWeakensTheProtection() {
        assertThat(EraseAfterFailures.isWeaker(from = 3, to = 10)).isTrue()
        assertThat(EraseAfterFailures.isWeaker(from = 10, to = OFF)).isTrue()
        assertThat(EraseAfterFailures.isWeaker(from = 10, to = 3)).isFalse()
        assertThat(EraseAfterFailures.isWeaker(from = OFF, to = 10)).isFalse()
        assertThat(EraseAfterFailures.isWeaker(from = 5, to = 5)).isFalse()
    }
}
