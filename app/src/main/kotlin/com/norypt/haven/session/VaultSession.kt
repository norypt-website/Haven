package com.norypt.haven.session

import android.content.Context
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.crypto.Argon2Benchmark
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.PasswordNormalizer
import com.norypt.haven.crypto.SecurityLevel
import com.norypt.haven.crypto.VaultCryptoException
import com.norypt.haven.crypto.VaultIds
import com.norypt.haven.crypto.VaultKeyManager
import com.norypt.haven.crypto.Wipe
import com.norypt.haven.security.EraseAfterFailures
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.security.SafeLog
import com.norypt.haven.security.SessionState
import com.norypt.haven.storage.EncryptedDatabaseOpener
import com.norypt.haven.storage.content.ContentDatabase
import com.norypt.haven.storage.content.TaskListEntity
import com.norypt.haven.storage.passwords.PasswordDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class VaultLockedException : IllegalStateException("Vault is locked")

/**
 * The outcome of entering the Haven password, whether to open a vault or to confirm a change in
 * settings. Every wrong password counts toward the same limit (see [EraseAfterFailures]).
 */
sealed interface UnlockResult {
    data object Success : UnlockResult
    /** [attemptsLeft]: wrong passwords left before Haven erases the vault; null when the erase is off. */
    data class WrongPassword(val waitMs: Long, val attemptsLeft: Int? = null) : UnlockResult
    data object DeviceAuthRequired : UnlockResult
    data class Unrecoverable(val reason: String) : UnlockResult
    /** The wait after earlier wrong passwords is still running; the password was not tried. */
    data class Throttled(val waitMs: Long) : UnlockResult
    /** That was the last wrong password allowed: Haven has erased the vault and is back at first run. */
    data object Erased : UnlockResult
    /** A non-cryptographic failure (database could not be opened, I/O). Not a wrong password. */
    data class Failed(val message: String) : UnlockResult
}

/** Empty input is not a guess: it is refused without counting, so a slip of the keyboard costs nothing. */
private val EMPTY_PASSWORD = UnlockResult.Failed("Enter your Haven password.")

/**
 * Owns the open database handles and the session state machine. Every transition runs under
 * one mutex so "lock during write" resolves deterministically: the write either completes
 * before the lock or fails with [VaultLockedException] once the handle is closed.
 */
class VaultSession(
    private val context: Context,
    private val keys: VaultKeyManager,
    private val alarmRuntime: AlarmRuntime,
    private val throttle: GuessThrottle,
    /** Wrong passwords before Haven erases the vault, or [EraseAfterFailures.OFF]. Read while the vault is locked. */
    private val eraseLimit: () -> Int,
    /** Runs at the end of an automatic erase, once the vault is gone (app preferences, the note for the welcome screen). */
    private val onAutoErased: () -> Unit,
    private val onLocked: () -> Unit,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SessionState>(SessionState.Locked)
    val state: StateFlow<SessionState> = _state

    /**
     * Incremented every time the database connections behind the UI are closed or replaced
     * (lock, erase, duress, restore). The navigation host is keyed on it, so no screen, saved
     * tab state or view model can outlive the data it was reading. Compose flows built on a
     * closed database would otherwise stay silent until the process is restarted.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation
    private var restoreInProgress = false

    /** Set by [duressWipe] when it closed databases the screens were reading; see [attempt]. */
    private var duressClosedDatabases = false

    @Volatile private var contentDb: ContentDatabase? = null
    @Volatile private var passwordDb: PasswordDatabase? = null
    @Volatile var hardwareLevel: SecurityLevel? = null
        private set

    fun isInitialised(): Boolean = keys.isInitialised()

    fun content(): ContentDatabase = contentDb ?: throw VaultLockedException()
    fun passwords(): PasswordDatabase = passwordDb ?: throw VaultLockedException()
    fun contentOrNull(): ContentDatabase? = contentDb
    fun passwordsOrNull(): PasswordDatabase? = passwordDb

    /** First-run setup: benchmark Argon2id, create both vaults, open the content vault. */
    suspend fun setUp(password: CharArray, requireDeviceAuth: Boolean, params: Argon2Params): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!keys.isInitialised())
            // A count left over from a vault that is gone (an erase cut short) must not follow the new one.
            throttle.reset()
            _state.value = SessionState.Unlocking(VaultIds.CONTENT)
            val pw = PasswordNormalizer.toBytes(password)
            try {
                val unwrapped = keys.initialise(pw, params, requireDeviceAuth)
                try {
                    val content = unwrapped.getValue(VaultIds.CONTENT)
                    val passwords = unwrapped.getValue(VaultIds.PASSWORDS)
                    hardwareLevel = content.hwLevel
                    // Create both database files now so their keys are exercised end-to-end.
                    val pdb = PasswordDatabase.open(context, passwords.bytes())
                    pdb.close()
                    val cdb = ContentDatabase.open(context, content.bytes())
                    val now = System.currentTimeMillis()
                    cdb.taskLists().upsert(TaskListEntity(UUID.randomUUID().toString(), "Tasks", 0, now))
                    contentDb = cdb
                    _state.value = SessionState.Unlocked(contentOpen = true, passwordsOpen = false)
                } finally {
                    unwrapped.values.forEach { it.destroy() }
                }
            } catch (t: Throwable) {
                // Setup is all-or-nothing: never leave a keyring behind without usable databases.
                runCatching { contentDb?.close() }; contentDb = null
                runCatching { keys.eraseAll() }
                EncryptedDatabaseOpener.deleteDatabaseFiles(ContentDatabase.file(context))
                EncryptedDatabaseOpener.deleteDatabaseFiles(PasswordDatabase.file(context))
                _state.value = SessionState.Locked
                throw t
            } finally {
                Wipe.bytes(pw)
            }
        }
    }

    suspend fun benchmarkKdf(): Argon2Benchmark.Result = withContext(Dispatchers.Default) { Argon2Benchmark(com.norypt.haven.crypto.Argon2idKeyDerivation()).choose() }

    suspend fun unlockContent(password: CharArray): UnlockResult = unlock(VaultIds.CONTENT, password)

    /** Requires a fresh password entry; opening the content vault never opens this one. */
    suspend fun unlockPasswords(password: CharArray): UnlockResult = unlock(VaultIds.PASSWORDS, password)

    private suspend fun unlock(vaultId: String, password: CharArray): UnlockResult = attempt {
        if (password.isEmpty()) return@attempt EMPTY_PASSWORD
        val previous = _state.value
        _state.value = SessionState.Unlocking(vaultId)
        val pw = try {
            PasswordNormalizer.toBytes(password)
        } catch (e: IllegalArgumentException) {
            _state.value = previous
            return@attempt recordWrongPassword()
        }
        try {
            val key = keys.open(vaultId, pw)
            try {
                hardwareLevel = key.hwLevel
                if (vaultId == VaultIds.CONTENT) {
                    contentDb?.close()
                    contentDb = ContentDatabase.open(context, key.bytes())
                } else {
                    passwordDb?.close()
                    passwordDb = PasswordDatabase.open(context, key.bytes())
                }
            } finally {
                key.destroy()
            }
            throttle.recordSuccess()
            _state.value = SessionState.Unlocked(contentOpen = contentDb != null, passwordsOpen = passwordDb != null)
            if (vaultId == VaultIds.CONTENT) ScheduleSync.syncAll(contentDb!!, alarmRuntime)
            UnlockResult.Success
        } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
            _state.value = previous
            // Duress check happens only after a real failure and always costs one derivation,
            // so "wrong", "duress" and "duress not armed" all take the same time.
            recordWrongPassword { duressCheck(pw) }
        } catch (e: VaultCryptoException.DeviceAuthenticationRequired) {
            _state.value = previous
            UnlockResult.DeviceAuthRequired
        } catch (e: VaultCryptoException.UnrecoverableHardwareKey) {
            SafeLog.e("VaultSession", "hardware key unavailable", e)
            _state.value = SessionState.UnrecoverableKeyError("The device-bound key for this vault is no longer available.")
            UnlockResult.Unrecoverable("The device-bound key for this vault is no longer available. This happens after a factory reset, some OS re-installs, or if the key was invalidated. Data can only be recovered from a backup.")
        } catch (e: VaultCryptoException.Malformed) {
            SafeLog.e("VaultSession", "envelope malformed", e)
            _state.value = SessionState.UnrecoverableKeyError("The vault key file is damaged.")
            UnlockResult.Unrecoverable("The vault key file is damaged or has an unsupported version. Haven will not replace it automatically. Restore from a backup, or erase the vault from Security settings.")
        } catch (e: VaultCryptoException.KeyDerivationFailed) {
            _state.value = previous
            UnlockResult.Failed("Not enough free memory to derive the key right now. Close other apps and try again.")
        } catch (e: Throwable) {
            SafeLog.e("VaultSession", "unlock failed", e)
            _state.value = previous
            UnlockResult.Failed("Could not open the vault (${e.javaClass.simpleName}). The password was accepted; the database could not be opened.")
        } finally {
            Wipe.bytes(pw)
        }
    }

    /**
     * Runs one password attempt under [mutex]. While the wait after earlier wrong passwords is
     * running, the password is not even tried. An attempt that ended in the automatic erase also
     * runs [onLocked], as a lock would.
     */
    private suspend fun attempt(block: suspend () -> UnlockResult): UnlockResult = withContext(Dispatchers.Default) {
        var lockedByDuress = false
        val result = mutex.withLock {
            val wait = throttle.remainingDelayMs()
            if (wait > 0) return@withLock UnlockResult.Throttled(wait)
            duressClosedDatabases = false
            var result: UnlockResult? = null
            try {
                block().also { result = it }
            } finally {
                // A duress wipe from inside the open app closed the databases its screens were
                // reading. Finish it as a lock, now that the key file is scrambled and the attempt
                // has been counted, so the unlock screen that follows already shows the wait and
                // the attempts left.
                if (duressClosedDatabases && result != UnlockResult.Erased) {
                    _state.value = SessionState.Locked
                    _generation.value++
                    lockedByDuress = true
                }
                duressClosedDatabases = false
            }
        }
        if (result == UnlockResult.Erased || lockedByDuress) onLocked()
        result
    }

    /**
     * Runs the duress check after a wrong password and, when it matches, the silent wipe. Whatever
     * happens in here, the caller still counts the wrong password: a failure (for example memory
     * for the extra derivation) must never become a free guess.
     */
    private fun duressCheck(pw: ByteArray) {
        try {
            if (keys.isDuressPassword(pw)) duressWipe()
        } catch (e: Exception) {
            SafeLog.e("VaultSession", "password check", e)
        }
    }

    /**
     * Every wrong Haven password ends here, whichever screen it was typed on: one shared count, a
     * longer wait each time and, once the chosen limit is reached, the automatic erase. The count
     * is saved before anything slow runs, so stopping Haven in the middle of a check can never turn
     * a guess into a free one; [thenCheckDuress] (one more key derivation) runs only after that.
     * The caller holds [mutex].
     */
    private fun recordWrongPassword(thenCheckDuress: () -> Unit = {}): UnlockResult {
        throttle.recordFailure()
        val limit = eraseLimit()
        if (EraseAfterFailures.shouldErase(limit, throttle.failureCount)) {
            eraseHoldingLock(onAutoErased)
            return UnlockResult.Erased
        }
        thenCheckDuress()
        return UnlockResult.WrongPassword(throttle.remainingDelayMs(), EraseAfterFailures.attemptsLeft(limit, throttle.failureCount))
    }

    /**
     * A password check inside the app (settings dialogs): [block] gets the normalised password and
     * throws [VaultCryptoException.WrongPasswordOrCorrupt] when it is wrong. Counts exactly like an
     * unlock attempt, so these prompts cannot be used to guess the password more freely. With
     * [duressApplies] the duress password triggers the silent wipe here too.
     */
    private suspend fun checkPassword(password: CharArray, duressApplies: Boolean = false, block: (ByteArray) -> Unit): UnlockResult = attempt {
        if (password.isEmpty()) return@attempt EMPTY_PASSWORD
        val pw = try {
            PasswordNormalizer.toBytes(password)
        } catch (e: IllegalArgumentException) {
            return@attempt recordWrongPassword()
        }
        try {
            block(pw)
            throttle.recordSuccess()
            UnlockResult.Success
        } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
            recordWrongPassword { if (duressApplies) duressCheck(pw) }
        } catch (e: VaultCryptoException.DeviceAuthenticationRequired) {
            UnlockResult.DeviceAuthRequired
        } finally {
            Wipe.bytes(pw)
        }
    }

    /** Closes only the password keeper (the "lock keeper" action). */
    suspend fun lockPasswords() = withContext(Dispatchers.Default) {
        mutex.withLock {
            passwordDb?.close()
            passwordDb = null
            if (contentDb != null) _state.value = SessionState.Unlocked(contentOpen = true, passwordsOpen = false)
        }
    }

    /** Full lock: close all connections. Safe to call repeatedly. */
    suspend fun lock() = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (_state.value is SessionState.Locked) return@withLock
            _state.value = SessionState.Locking
            passwordDb?.close(); passwordDb = null
            contentDb?.close(); contentDb = null
            _generation.value++
            _state.value = SessionState.Locked
        }
        onLocked()
    }

    /** Synchronous variant for lifecycle callbacks that cannot suspend. */
    fun lockBlocking() {
        kotlinx.coroutines.runBlocking { lock() }
    }

    suspend fun beginBackupOperation(restore: Boolean) = mutex.withLock { restoreInProgress = restore; _state.value = SessionState.BackupOperation(restore) }
    suspend fun endBackupOperation() = mutex.withLock {
        if (restoreInProgress) { restoreInProgress = false; _generation.value++ }
        _state.value = if (contentDb != null || passwordDb != null) SessionState.Unlocked(contentDb != null, passwordDb != null) else SessionState.Locked
    }

    suspend fun changePassword(old: CharArray, new: CharArray, params: Argon2Params): UnlockResult = checkPassword(old) { o ->
        val n = PasswordNormalizer.toBytes(new)
        try {
            keys.changePassword(o, n, params)
        } finally {
            Wipe.bytes(n)
        }
    }

    suspend fun setDeviceAuthRequirement(password: CharArray, require: Boolean): UnlockResult = checkPassword(password) { keys.rotateHardwareKeys(it, require) }

    /** Verifies the password without changing state (fresh authentication for destructive actions). Duress applies here too. */
    suspend fun verifyPassword(password: CharArray): UnlockResult = checkPassword(password, duressApplies = true) { keys.open(VaultIds.CONTENT, it).destroy() }

    /**
     * Wrong passwords left before the automatic erase, once at least one wrong password has been
     * entered; null while nothing has gone wrong, or when the erase is off. For the unlock screens.
     */
    fun attemptsLeft(): Int? =
        if (throttle.failureCount == 0) null else EraseAfterFailures.attemptsLeft(eraseLimit(), throttle.failureCount)

    // ---- Duress password (opt-in; see docs/THREAT_MODEL.md "Duress password") ----

    fun hasDuressPassword(): Boolean = runCatching { keys.hasDuress() }.getOrDefault(false)

    /** Sets the duress password. A wrong [real] counts like any wrong password. Throws IllegalArgumentException if both are equal. */
    suspend fun setDuressPassword(real: CharArray, duress: CharArray): UnlockResult = checkPassword(real) { r ->
        val d = PasswordNormalizer.toBytes(duress)
        try {
            keys.setDuress(r, d, keys.envelope(VaultIds.CONTENT).kdf)
        } finally {
            Wipe.bytes(d)
        }
    }

    suspend fun clearDuressPassword(real: CharArray): UnlockResult = checkPassword(real) { keys.clearDuress(it) }

    /**
     * Silent duress response, run while the caller holds [mutex]: close everything, cancel all
     * alarms, leave same-size random files where the databases were, retire the hardware keys and
     * scramble the key file so no password opens it any more. Nothing is logged or shown, and no
     * key derivation runs, so the whole entry takes as long as a wrong password.
     */
    private fun duressWipe() {
        duressClosedDatabases = contentDb != null || passwordDb != null
        passwordDb?.close(); passwordDb = null
        contentDb?.close(); contentDb = null
        EncryptedDatabaseOpener.replaceWithNoise(ContentDatabase.file(context))
        EncryptedDatabaseOpener.replaceWithNoise(PasswordDatabase.file(context))
        alarmRuntime.scheduler.removeAll()
        // No generation bump here. On the locked unlock screen nothing reads the databases, so the
        // screen must stay exactly as after a wrong password; when databases were open, [attempt]
        // finishes with a lock once the attempt has been counted.
        keys.duressScramble()
    }

    /**
     * Erase the entire Haven vault: retire hardware keys, delete the keyring and both database
     * files, clear the alarm store and preferences. Requires the caller to have verified the
     * password moments before.
     */
    suspend fun eraseEverything(alsoClearAppPreferences: () -> Unit) {
        withContext(Dispatchers.Default) { mutex.withLock { eraseHoldingLock(alsoClearAppPreferences) } }
        onLocked()
    }

    /** The erase itself, by hand or automatic; the caller holds [mutex]. */
    private fun eraseHoldingLock(alsoClearAppPreferences: () -> Unit) {
        _state.value = SessionState.Locking
        try {
            passwordDb?.close(); passwordDb = null
            contentDb?.close(); contentDb = null
            keys.eraseAll()
            EncryptedDatabaseOpener.deleteDatabaseFiles(ContentDatabase.file(context))
            EncryptedDatabaseOpener.deleteDatabaseFiles(PasswordDatabase.file(context))
            // The vault is already unreadable here; an alarm that cannot be cancelled must not stop the rest.
            try {
                alarmRuntime.scheduler.removeAll()
            } catch (e: Exception) {
                SafeLog.e("VaultSession", "erase: alarms", e)
            }
            alarmRuntime.prefs.clearAll()
            alsoClearAppPreferences()
        } finally {
            // Once the keys are gone the next vault starts with a clean count; an old one could erase
            // it after its first typo. If the keys could not be retired the count stays, so the next
            // wrong password tries the erase again.
            if (!keys.isInitialised()) throttle.reset()
            _generation.value++
            _state.value = SessionState.Locked
        }
    }

    /**
     * Replace vault contents from a restore: the caller supplies a block that writes into
     * freshly opened databases; old files stay untouched until the block succeeds.
     */
    suspend fun <T> withOpenDatabases(block: suspend (ContentDatabase, PasswordDatabase) -> T): T = withContext(Dispatchers.Default) {
        block(content(), passwords())
    }
}
