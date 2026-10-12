package com.norypt.haven.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.security.SessionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Before-lock hooks (an open editor's last save) run at the start of a lock, before the session
 * moves to Locking and closes its databases, and can never keep the vault open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BeforeLockTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    /** A session that is not locked. Unit tests cannot open the encrypted databases, so a backup operation stands in for "open". */
    private fun openSession(): VaultSession = lockedTestSession(ApplicationProvider.getApplicationContext(), tmp.root).also {
        runBlocking { it.beginBackupOperation(restore = false) }
    }

    @Test fun hooksRunBeforeTheDatabasesClose() = runBlocking {
        val s = openSession()
        var seenState: SessionState? = null
        var seenGeneration = -1
        s.addBeforeLock { seenState = s.state.value; seenGeneration = s.generation.value }
        s.lock()
        // Still the open state and the old generation: lock() had not started closing anything yet.
        assertThat(seenState).isEqualTo(SessionState.BackupOperation(false))
        assertThat(seenGeneration).isEqualTo(0)
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        assertThat(s.generation.value).isEqualTo(1)
    }

    @Test fun aFailingHookDoesNotStopTheLockOrTheOtherHooks() = runBlocking {
        val s = openSession()
        var otherRan = false
        s.addBeforeLock { throw IllegalStateException("vault closed") }
        s.addBeforeLock { otherRan = true }
        s.lock()
        assertThat(otherRan).isTrue()
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
    }

    @Test fun aSlowHookDoesNotHoldUpTheLock() = runBlocking {
        val s = openSession()
        // Non-cancellable on purpose, like the editor's save: the lock stops waiting, it does not cancel.
        s.addBeforeLock { withContext(NonCancellable) { delay(30_000) } }
        val started = System.nanoTime()
        s.lock()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        assertThat(tookMs).isLessThan(BEFORE_LOCK_TIMEOUT_MS + 2_000)
    }

    @Test fun anUnregisteredHookDoesNotRun() = runBlocking {
        val s = openSession()
        var ran = false
        val unregister = s.addBeforeLock { ran = true }
        unregister()
        s.lock()
        assertThat(ran).isFalse()
    }

    @Test fun onlyALockRunsTheHooks() = runBlocking {
        val s = openSession()
        var runs = 0
        s.addBeforeLock { runs++ }
        s.lockPasswords()
        s.eraseEverything {}
        assertThat(runs).isEqualTo(0)
        // Already locked: a second lock has nothing to save before.
        s.lock()
        assertThat(runs).isEqualTo(0)
    }
}
