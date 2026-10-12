package com.norypt.haven.ui.screens.photos

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.security.LockController
import com.norypt.haven.security.LockPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExcursionTest {
    private var unlocked = true
    private val reasons = mutableListOf<LockController.LockReason>()

    private fun TestScope.controller() =
        LockController(backgroundScope, { LockPolicy() }, { unlocked }, { r -> reasons += r; unlocked = false }, { testScheduler.currentTime })

    /** Backgrounds the app and reports whether the background lock fired after the grace period. */
    private fun TestScope.backgroundLocks(c: LockController): Boolean {
        c.onAppBackground()
        advanceTimeBy(LockPolicy().backgroundGraceMs + 1)
        val locked = LockController.LockReason.BACKGROUND in reasons
        c.onAppForeground()
        return locked
    }

    @Test fun anOpenExcursionHoldsOffTheBackgroundLock() = runTest {
        val c = controller()
        c.onUnlocked()
        val e = Excursion(c)
        assertThat(e.launch { }).isTrue()
        assertThat(backgroundLocks(c)).isFalse()
    }

    @Test fun endingTwiceIsHarmlessAndTheBackgroundLockComesBack() = runTest {
        val c = controller()
        c.onUnlocked()
        val other = Excursion(c).also { it.launch { } }
        val e = Excursion(c).also { it.launch { } }
        assertThat(e.end()).isTrue()   // the result callback
        assertThat(e.end()).isFalse()  // then the screen leaves composition
        other.end()
        assertThat(backgroundLocks(c)).isTrue()
    }

    @Test fun aSecondTapWhileOpenDoesNotLaunchAgain() = runTest {
        val c = controller()
        val e = Excursion(c)
        var launches = 0
        e.launch { launches++ }
        assertThat(e.launch { launches++ }).isFalse()
        assertThat(launches).isEqualTo(1)
        e.end()
        c.onUnlocked()
        assertThat(backgroundLocks(c)).isTrue() // one begin, one end
    }

    @Test fun aLaunchThatThrowsEndsTheExcursionAtOnce() = runTest {
        val c = controller()
        c.onUnlocked()
        val e = Excursion(c)
        assertThat(e.launch { throw IllegalStateException("no picker") }).isFalse()
        assertThat(e.open).isFalse()
        assertThat(backgroundLocks(c)).isTrue()
    }
}
