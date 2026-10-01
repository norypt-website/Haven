package com.norypt.haven.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuessThrottleTest {
    @Test fun everyWrongPasswordWaitsLongerThanTheOneBefore() {
        var clock = 1_000_000L
        val t = GuessThrottle({ clock })
        val waits = (1..9).map {
            t.recordFailure()
            t.remainingDelayMs().also { clock += it }
        }
        assertThat(waits).containsExactly(5_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L).inOrder()
    }

    @Test fun theWaitStopsGrowingAtThirtyMinutes() {
        var clock = 1_000_000L
        val t = GuessThrottle({ clock })
        repeat(20) {
            t.recordFailure()
            clock += t.remainingDelayMs()
        }
        t.recordFailure()
        assertThat(t.remainingDelayMs()).isEqualTo(30L * 60 * 1000)
    }

    @Test fun stateIsHandedToPersistAndRestoredAfterARestart() {
        var clock = 1_000_000L
        var savedFailures = 0; var savedUntil = 0L
        val first = GuessThrottle({ clock }, persist = { f, u -> savedFailures = f; savedUntil = u })
        repeat(10) { first.recordFailure() }
        assertThat(first.remainingDelayMs()).isEqualTo(1_800_000L)
        assertThat(savedFailures).isEqualTo(10)
        // "Process killed": a new instance built from the persisted values keeps the lock.
        val second = GuessThrottle({ clock }, initialFailures = savedFailures, initialLockedUntil = savedUntil)
        assertThat(second.remainingDelayMs()).isEqualTo(1_800_000L)
        assertThat(second.failureCount).isEqualTo(10)
        second.recordFailure()
        assertThat(savedFailures).isEqualTo(10) // the second instance had no persist callback; the first's value is untouched
        second.recordSuccess()
        assertThat(second.remainingDelayMs()).isEqualTo(0L)
    }

    @Test fun resetForgetsTheCountAndTheWait() {
        var saved = -1 to -1L
        val t = GuessThrottle({ 1_000_000L }, persist = { f, u -> saved = f to u })
        repeat(4) { t.recordFailure() }
        t.reset()
        assertThat(t.failureCount).isEqualTo(0)
        assertThat(t.remainingDelayMs()).isEqualTo(0L)
        assertThat(saved).isEqualTo(0 to 0L)
    }

    @Test fun aStoredLockFarInTheFutureIsCappedSoAClockJumpCannotLockTheOwnerOut() {
        val clock = 1_000_000L
        val t = GuessThrottle({ clock }, initialFailures = 3, initialLockedUntil = clock + 10L * 24 * 60 * 60 * 1000)
        // Never longer than the wait that belongs to the third wrong password.
        assertThat(t.remainingDelayMs()).isEqualTo(30_000L)
    }

    @Test fun settingTheClockBackCannotStretchAWait() {
        var clock = 10_000_000L
        val t = GuessThrottle({ clock })
        t.recordFailure()
        clock -= 60L * 60 * 1000
        assertThat(t.remainingDelayMs()).isEqualTo(5_000L)
    }

    @Test fun aStoredLockWithoutWrongPasswordsMeansNoWait() {
        val clock = 1_000_000L
        val t = GuessThrottle({ clock }, initialFailures = 0, initialLockedUntil = clock + 60_000L)
        assertThat(t.remainingDelayMs()).isEqualTo(0L)
    }
}
