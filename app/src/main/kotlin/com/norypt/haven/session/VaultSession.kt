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

sealed interface UnlockResult {
    data object Success : UnlockResult
    data class WrongPassword(val waitMs: Long) : UnlockResult
    data object DeviceAuthRequired : UnlockResult
    data class Unrecoverable(val reason: String) : UnlockResult
    data class Throttled(val waitMs: Long) : UnlockResult
    /** A non-cryptographic failure (database could not be opened, I/O). Not a wrong password. */
    data class Failed(val message: String) : UnlockResult
}

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
    private val onLocked: () -> Unit,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SessionState>(SessionState.Locked)
    val state: StateFlow<SessionState> = _state

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

    private suspend fun unlock(vaultId: String, password: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val wait = throttle.remainingDelayMs()
            if (wait > 0) return@withLock UnlockResult.Throttled(wait)
            val previous = _state.value
            _state.value = SessionState.Unlocking(vaultId)
            val pw = try {
                PasswordNormalizer.toBytes(password)
            } catch (e: IllegalArgumentException) {
                _state.value = previous
                throttle.recordFailure()
                return@withLock UnlockResult.WrongPassword(throttle.remainingDelayMs())
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
                // Duress check happens only after a real failure, so timing is identical for
                // "wrong" and "duress". If it matches, everything is replaced by an empty decoy.
                if (keys.hasDuress() && keys.isDuressPassword(pw)) duressWipe(pw)
                throttle.recordFailure()
                _state.value = previous
                UnlockResult.WrongPassword(throttle.remainingDelayMs())
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
            _state.value = SessionState.Locked
        }
        onLocked()
    }

    /** Synchronous variant for lifecycle callbacks that cannot suspend. */
    fun lockBlocking() {
        kotlinx.coroutines.runBlocking { lock() }
    }

    suspend fun beginBackupOperation(restore: Boolean) = mutex.withLock { _state.value = SessionState.BackupOperation(restore) }
    suspend fun endBackupOperation() = mutex.withLock {
        _state.value = if (contentDb != null || passwordDb != null) SessionState.Unlocked(contentDb != null, passwordDb != null) else SessionState.Locked
    }

    suspend fun changePassword(old: CharArray, new: CharArray, params: Argon2Params): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            val o = PasswordNormalizer.toBytes(old)
            val n = PasswordNormalizer.toBytes(new)
            try {
                keys.changePassword(o, n, params)
                true
            } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
                false
            } finally {
                Wipe.bytes(o); Wipe.bytes(n)
            }
        }
    }

    suspend fun setDeviceAuthRequirement(password: CharArray, require: Boolean): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            val pw = PasswordNormalizer.toBytes(password)
            try {
                keys.rotateHardwareKeys(pw, require); true
            } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
                false
            } finally {
                Wipe.bytes(pw)
            }
        }
    }

    /** Verifies the password without changing state (fresh authentication for destructive actions). Duress applies here too. */
    suspend fun verifyPassword(password: CharArray): Boolean = withContext(Dispatchers.Default) {
        val pw = PasswordNormalizer.toBytes(password)
        try {
            keys.open(VaultIds.CONTENT, pw).destroy(); true
        } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
            if (keys.hasDuress() && keys.isDuressPassword(pw)) mutex.withLock { duressWipe(pw) }
            false
        } catch (e: VaultCryptoException) {
            false
        } finally {
            Wipe.bytes(pw)
        }
    }

    // ---- Duress password (opt-in; see docs/THREAT_MODEL.md "Duress password") ----

    fun hasDuressPassword(): Boolean = runCatching { keys.hasDuress() }.getOrDefault(false)

    /** Sets the duress password. Returns false if [real] is wrong. Throws IllegalArgumentException if both are equal. */
    suspend fun setDuressPassword(real: CharArray, duress: CharArray): Boolean = withContext(Dispatchers.Default) {
        val r = PasswordNormalizer.toBytes(real); val d = PasswordNormalizer.toBytes(duress)
        try {
            val params = keys.envelope(VaultIds.CONTENT).kdf
            keys.setDuress(r, d, params); true
        } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
            false
        } finally {
            Wipe.bytes(r); Wipe.bytes(d)
        }
    }

    suspend fun clearDuressPassword(real: CharArray): Boolean = withContext(Dispatchers.Default) {
        val r = PasswordNormalizer.toBytes(real)
        try {
            keys.clearDuress(r); true
        } catch (e: VaultCryptoException.WrongPasswordOrCorrupt) {
            false
        } finally {
            Wipe.bytes(r)
        }
    }

    /**
     * Silent duress response, run while the caller holds [mutex]: close everything, retire the
     * hardware keys, delete both databases and the alarm store, then install an empty decoy vault
     * so the app keeps looking locked. Nothing is logged or shown.
     */
    private fun duressWipe(duressPassword: ByteArray) {
        passwordDb?.close(); passwordDb = null
        contentDb?.close(); contentDb = null
        val params = runCatching { keys.envelope(VaultIds.CONTENT).kdf }.getOrDefault(com.norypt.haven.crypto.Argon2Params.RFC9106_MEMORY_CONSTRAINED)
        EncryptedDatabaseOpener.deleteDatabaseFiles(ContentDatabase.file(context))
        EncryptedDatabaseOpener.deleteDatabaseFiles(PasswordDatabase.file(context))
        alarmRuntime.scheduler.removeAll()
        val decoy = keys.duressReplaceWithDecoy(duressPassword, params)
        try {
            // Empty decoy databases so the files exist like before.
            PasswordDatabase.open(context, decoy.getValue(VaultIds.PASSWORDS).bytes()).close()
            ContentDatabase.open(context, decoy.getValue(VaultIds.CONTENT).bytes()).close()
        } catch (e: Throwable) {
            SafeLog.e("VaultSession", "decoy db", e)
        } finally {
            decoy.values.forEach { it.destroy() }
        }
    }

    /**
     * Erase the entire Haven vault: retire hardware keys, delete the keyring and both database
     * files, clear the alarm store and preferences. Requires the caller to have verified the
     * password moments before.
     */
    suspend fun eraseEverything(alsoClearAppPreferences: () -> Unit) = withContext(Dispatchers.Default) {
        mutex.withLock {
            _state.value = SessionState.Locking
            passwordDb?.close(); passwordDb = null
            contentDb?.close(); contentDb = null
            keys.eraseAll()
            EncryptedDatabaseOpener.deleteDatabaseFiles(ContentDatabase.file(context))
            EncryptedDatabaseOpener.deleteDatabaseFiles(PasswordDatabase.file(context))
            alarmRuntime.scheduler.removeAll()
            alarmRuntime.prefs.clearAll()
            alsoClearAppPreferences()
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
