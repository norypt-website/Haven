package com.norypt.haven.session

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.alarm.AlarmRuntimeConfig
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.KeyringStore
import com.norypt.haven.crypto.VaultCryptoException
import com.norypt.haven.crypto.VaultIds
import com.norypt.haven.crypto.VaultKeyManager
import com.norypt.haven.security.EraseAfterFailures
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.security.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Every wrong Haven password, on any screen, counts toward one limit; reaching it erases the
 * vault. Runs the real VaultSession and VaultKeyManager with a fast KDF and in-memory keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WrongPasswordEraseTest {
    @get:Rule val tmp = TemporaryFolder()

    private val params = Argon2Params.RFC9106_MEMORY_CONSTRAINED
    private lateinit var app: Application
    private val kdf = TestKdf()
    private val wrapping = TestWrapping()
    private lateinit var keys: VaultKeyManager
    private lateinit var throttle: GuessThrottle
    private var clock = 1_000_000L
    private var limit = 3
    private var autoErased = 0

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AlarmRuntime.config = AlarmRuntimeConfig(mainActivity = Any::class.java, ringActivity = Any::class.java)
        AlarmRuntime.resetForTesting()
        keys = VaultKeyManager(KeyringStore(File(tmp.root, "keyring.json")), kdf, wrapping)
        keys.initialise(RIGHT.toByteArray(), params, requireDeviceAuth = false).values.forEach { it.destroy() }
        throttle = GuessThrottle({ clock })
    }

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    private fun session() = VaultSession(app, keys, AlarmRuntime.get(app), throttle, eraseLimit = { limit }, onAutoErased = { autoErased++ }) {}

    /** Lets the wait after the previous wrong password run out. */
    private fun waitItOut() { clock += throttle.remainingDelayMs() }

    private fun String.pw() = toCharArray()

    @Test fun wrongPasswordsCountDownAndTheLastOneErasesTheVault() = runBlocking {
        val s = session()
        assertThat(s.unlockContent("guess 1".pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
        waitItOut()
        assertThat(s.unlockContent("guess 2".pw())).isEqualTo(UnlockResult.WrongPassword(15_000, attemptsLeft = 1))
        waitItOut()
        assertThat(s.unlockContent("guess 3".pw())).isEqualTo(UnlockResult.Erased)
        assertThat(keys.isInitialised()).isFalse()
        assertThat(autoErased).isEqualTo(1)
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        // The next vault starts with a clean count and no wait.
        assertThat(throttle.failureCount).isEqualTo(0)
        assertThat(throttle.remainingDelayMs()).isEqualTo(0)
    }

    @Test fun theKeeperAndTheMainUnlockShareOneCount() = runBlocking {
        val s = session()
        s.unlockContent("guess 1".pw()); waitItOut()
        assertThat(s.unlockPasswords("guess 2".pw())).isEqualTo(UnlockResult.WrongPassword(15_000, attemptsLeft = 1))
        waitItOut()
        assertThat(s.unlockPasswords("guess 3".pw())).isEqualTo(UnlockResult.Erased)
        assertThat(keys.isInitialised()).isFalse()
    }

    @Test fun aTryDuringTheWaitIsRefusedAndNotCounted() = runBlocking {
        val s = session()
        s.unlockContent("guess 1".pw())
        clock += 2_000
        assertThat(s.unlockContent(RIGHT.pw())).isEqualTo(UnlockResult.Throttled(3_000))
        assertThat(s.verifyPassword(RIGHT.pw())).isEqualTo(UnlockResult.Throttled(3_000))
        assertThat(throttle.failureCount).isEqualTo(1)
    }

    @Test fun passwordChecksInSettingsCountToo() = runBlocking {
        val s = session()
        assertThat(s.verifyPassword("guess 1".pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
        waitItOut()
        assertThat(s.changePassword("guess 2".pw(), "a new password".pw(), params)).isEqualTo(UnlockResult.WrongPassword(15_000, attemptsLeft = 1))
        waitItOut()
        assertThat(s.setDeviceAuthRequirement("guess 3".pw(), require = true)).isEqualTo(UnlockResult.Erased)
        assertThat(keys.isInitialised()).isFalse()
    }

    @Test fun duressSettingsCountToo() = runBlocking {
        val s = session()
        assertThat(s.setDuressPassword("guess 1".pw(), "duress words".pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
        waitItOut()
        assertThat(s.clearDuressPassword("guess 2".pw())).isEqualTo(UnlockResult.WrongPassword(15_000, attemptsLeft = 1))
        assertThat(keys.isInitialised()).isTrue()
    }

    @Test fun aCorrectPasswordResetsTheCount() = runBlocking {
        val s = session()
        s.unlockContent("guess 1".pw()); waitItOut()
        s.unlockContent("guess 2".pw()); waitItOut()
        assertThat(s.verifyPassword(RIGHT.pw())).isEqualTo(UnlockResult.Success)
        assertThat(s.attemptsLeft()).isNull()
        assertThat(s.unlockContent("guess 3".pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
    }

    @Test fun withTheEraseOffWrongPasswordsOnlyWait() = runBlocking {
        limit = EraseAfterFailures.OFF
        val s = session()
        repeat(12) {
            assertThat(s.unlockContent("guess $it".pw())).isInstanceOf(UnlockResult.WrongPassword::class.java)
            waitItOut()
        }
        assertThat(s.unlockContent("guess 13".pw())).isEqualTo(UnlockResult.WrongPassword(1_800_000, attemptsLeft = null))
        assertThat(keys.isInitialised()).isTrue()
        assertThat(autoErased).isEqualTo(0)
    }

    @Test fun theDuressPasswordLooksExactlyLikeAWrongOne() = runBlocking {
        keys.setDuress(RIGHT.toByteArray(), DURESS.toByteArray(), params)
        val s = session()
        val generation = s.generation.value
        assertThat(s.unlockContent(DURESS.pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
        // Nothing else moves: the locked unlock screen stays exactly as it does after a wrong password.
        assertThat(s.generation.value).isEqualTo(generation)
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        assertThat(keys.isInitialised()).isTrue()
        waitItOut()
        s.unlockContent("guess".pw()); waitItOut()
        // At the limit it erases, as any wrong password would.
        assertThat(s.unlockContent(DURESS.pw())).isEqualTo(UnlockResult.Erased)
        assertThat(keys.isInitialised()).isFalse()
    }

    @Test fun aDuressEntryTakesAsLongAsAWrongPassword() = runBlocking<Unit> {
        keys.setDuress(RIGHT.toByteArray(), DURESS.toByteArray(), params)
        val s = session()
        var before = kdf.calls
        s.unlockContent("guess".pw())
        val wrong = kdf.calls - before
        waitItOut()
        before = kdf.calls
        s.unlockContent(DURESS.pw())
        // Key derivations are what costs time; the wipe itself must add none.
        assertThat(kdf.calls - before).isEqualTo(wrong)
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { keys.open(VaultIds.CONTENT, RIGHT.toByteArray()) }
    }

    @Test fun aDuressCheckThatRunsOutOfMemoryStillCountsTheWrongPassword() = runBlocking {
        val s = session()
        // The password's own derivation works; the duress check after it fails.
        kdf.succeedBeforeFailing = 1
        assertThat(s.unlockContent("guess 1".pw())).isEqualTo(UnlockResult.WrongPassword(5_000, attemptsLeft = 2))
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        waitItOut()
        kdf.succeedBeforeFailing = 1
        assertThat(s.verifyPassword("guess 2".pw())).isEqualTo(UnlockResult.WrongPassword(15_000, attemptsLeft = 1))
    }

    @Test fun aWrongPasswordIsCountedBeforeTheSlowDuressCheck() = runBlocking {
        var saved = 0
        throttle = GuessThrottle({ clock }, persist = { failures, _ -> saved = failures })
        val s = session()
        // Haven is killed while the duress check derives its key, after the password itself was
        // refused: someone timing a force-stop must not get that guess for free.
        kdf.dieAfter = 1
        assertThat(runCatching { s.unlockContent("guess 1".pw()) }.exceptionOrNull()).isInstanceOf(SimulatedProcessDeath::class.java)
        assertThat(saved).isEqualTo(1)
        waitItOut()
        kdf.dieAfter = 1
        assertThat(runCatching { s.verifyPassword("guess 2".pw()) }.exceptionOrNull()).isInstanceOf(SimulatedProcessDeath::class.java)
        assertThat(saved).isEqualTo(2)
    }

    @Test fun anEmptyPasswordIsRefusedWithoutCounting() = runBlocking {
        val s = session()
        assertThat(s.unlockContent(CharArray(0))).isInstanceOf(UnlockResult.Failed::class.java)
        assertThat(s.verifyPassword(CharArray(0))).isInstanceOf(UnlockResult.Failed::class.java)
        assertThat(s.setDuressPassword(CharArray(0), "duress words".pw())).isInstanceOf(UnlockResult.Failed::class.java)
        assertThat(throttle.failureCount).isEqualTo(0)
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
    }

    @Test fun settingUpANewVaultStartsWithACleanCount() = runBlocking {
        keys.eraseAll()
        throttle = GuessThrottle({ clock }, initialFailures = 9)
        val s = session()
        // The databases cannot be created on the JVM (no SQLCipher), so setup fails after the count
        // has been cleared; on a phone it completes.
        runCatching { s.setUp(RIGHT.pw(), requireDeviceAuth = false, params) }
        assertThat(throttle.failureCount).isEqualTo(0)
    }

    @Test fun anEraseThatCannotRetireTheKeysKeepsTheCountAndTriesAgain() = runBlocking {
        val s = session()
        s.unlockContent("guess 1".pw()); waitItOut()
        s.unlockContent("guess 2".pw()); waitItOut()
        wrapping.failDelete = true
        assertThat(runCatching { s.unlockContent("guess 3".pw()) }.isFailure).isTrue()
        assertThat(keys.isInitialised()).isTrue()
        assertThat(throttle.failureCount).isEqualTo(3)
        assertThat(s.state.value).isEqualTo(SessionState.Locked)
        wrapping.failDelete = false
        waitItOut()
        assertThat(s.unlockContent("guess 4".pw())).isEqualTo(UnlockResult.Erased)
        assertThat(keys.isInitialised()).isFalse()
    }

    @Test fun erasingByHandAlsoClearsTheCount() = runBlocking {
        val s = session()
        s.unlockContent("guess 1".pw()); waitItOut()
        s.eraseEverything {}
        assertThat(throttle.failureCount).isEqualTo(0)
        assertThat(autoErased).isEqualTo(0)
    }

    @Test fun attemptsLeftIsReportedOnlyAfterAWrongPassword() = runBlocking {
        val s = session()
        assertThat(s.attemptsLeft()).isNull()
        s.unlockContent("guess".pw())
        assertThat(s.attemptsLeft()).isEqualTo(2)
        limit = EraseAfterFailures.OFF
        assertThat(s.attemptsLeft()).isNull()
    }

    private companion object {
        const val RIGHT = "the right password"
        const val DURESS = "the duress password"
    }
}
