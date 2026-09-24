package com.norypt.haven.security

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockControllerTest {
    private var unlocked = true
    private val reasons = mutableListOf<LockController.LockReason>()

    private fun controller(scope: kotlinx.coroutines.CoroutineScope, timeMs: () -> Long, policy: LockPolicy = LockPolicy()) =
        LockController(scope, { policy }, { unlocked }, { r -> reasons += r; unlocked = false }, timeMs)

    @Test fun locksAfterInactivity() = runTest {
        val c = controller(backgroundScope, { testScheduler.currentTime })
        c.onUnlocked()
        advanceTimeBy(LockPolicy().inactivityTimeoutMs + 1)
        assertThat(reasons).containsExactly(LockController.LockReason.INACTIVITY)
    }

    @Test fun touchResetsTimer() = runTest {
        val c = controller(backgroundScope, { testScheduler.currentTime })
        c.onUnlocked()
        advanceTimeBy(90_000)
        c.touch()
        advanceTimeBy(90_000)
        assertThat(reasons).isEmpty()
        advanceTimeBy(40_000)
        assertThat(reasons).containsExactly(LockController.LockReason.INACTIVITY)
    }

    @Test fun backgroundLocksAfterGraceUnlessSystemInteraction() = runTest {
        val c = controller(backgroundScope, { testScheduler.currentTime })
        c.onUnlocked()
        c.beginSystemInteraction()
        c.onAppBackground()
        advanceTimeBy(20_000)
        assertThat(reasons).isEmpty()
        c.endSystemInteraction()
        c.onAppBackground()
        advanceTimeBy(16_000)
        assertThat(reasons).containsExactly(LockController.LockReason.BACKGROUND)
    }

    @Test fun returningToForegroundCancelsBackgroundLock() = runTest {
        val c = controller(backgroundScope, { testScheduler.currentTime })
        c.onUnlocked()
        c.onAppBackground()
        advanceTimeBy(5_000)
        c.onAppForeground()
        advanceTimeBy(20_000)
        assertThat(reasons).isEmpty()
    }

    @Test fun screenOffLocksImmediately() = runTest {
        val c = controller(backgroundScope, { testScheduler.currentTime })
        c.onUnlocked()
        c.onScreenOff()
        assertThat(reasons).containsExactly(LockController.LockReason.SCREEN_OFF)
    }

    @Test fun guessThrottleEscalatesWithoutWiping() {
        var t = 0L
        val g = GuessThrottle(now = { t })
        repeat(2) { g.recordFailure() }
        assertThat(g.remainingDelayMs()).isEqualTo(0)
        g.recordFailure()
        assertThat(g.remainingDelayMs()).isEqualTo(5_000)
        repeat(10) { g.recordFailure() }
        assertThat(g.remainingDelayMs()).isEqualTo(60_000)
        g.recordSuccess()
        assertThat(g.remainingDelayMs()).isEqualTo(0)
    }
}
