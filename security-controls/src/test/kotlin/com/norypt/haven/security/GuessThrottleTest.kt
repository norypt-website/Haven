package com.norypt.haven.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GuessThrottleTest {
    @Test fun stateIsHandedToPersistAndRestoredAfterARestart() {
        var clock = 1_000_000L
        var savedFailures = 0; var savedUntil = 0L
        val first = GuessThrottle({ clock }, persist = { f, u -> savedFailures = f; savedUntil = u })
        repeat(10) { first.recordFailure() }
        assertThat(first.remainingDelayMs()).isEqualTo(60_000L)
        assertThat(savedFailures).isEqualTo(10)
        // "Process killed": a new instance built from the persisted values keeps the lock.
        val second = GuessThrottle({ clock }, initialFailures = savedFailures, initialLockedUntil = savedUntil)
        assertThat(second.remainingDelayMs()).isEqualTo(60_000L)
        assertThat(second.failureCount).isEqualTo(10)
        second.recordFailure()
        assertThat(savedFailures).isEqualTo(10) // the second instance had no persist callback; the first's value is untouched
        second.recordSuccess()
        assertThat(second.remainingDelayMs()).isEqualTo(0L)
    }

    @Test fun aStoredLockFarInTheFutureIsCappedSoAClockJumpCannotLockTheOwnerOut() {
        val clock = 1_000_000L
        val t = GuessThrottle({ clock }, initialFailures = 3, initialLockedUntil = clock + 10L * GuessThrottle.MAX_LOCK_MS)
        assertThat(t.remainingDelayMs()).isEqualTo(GuessThrottle.MAX_LOCK_MS)
    }
}
