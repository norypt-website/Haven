package com.norypt.haven.di

import android.app.Application
import com.norypt.haven.BuildConfig
import com.norypt.haven.alarm.AlarmReadiness
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.backup.BackupManager
import com.norypt.haven.crypto.AndroidKeystoreWrappingKeys
import com.norypt.haven.crypto.Argon2idKeyDerivation
import com.norypt.haven.crypto.KeyringStore
import com.norypt.haven.crypto.VaultKeyManager
import com.norypt.haven.data.AppPreferences
import com.norypt.haven.data.PasswordRepository
import com.norypt.haven.data.ReminderRepository
import com.norypt.haven.data.TaskRepository
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.security.LockController
import com.norypt.haven.security.SensitiveClipboard
import com.norypt.haven.session.VaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency wiring (no DI framework: fewer dependencies, no code generation, easy to audit).
 * Constructed lazily after the user has unlocked the device, because it touches
 * credential-encrypted storage.
 */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = AppPreferences(app)
    val alarmRuntime: AlarmRuntime = AlarmRuntime.get(app)
    val readiness = AlarmReadiness(app)
    val throttle = GuessThrottle(
        initialFailures = prefs.throttleFailures,
        initialLockedUntil = prefs.throttleLockedUntil,
        persist = { failures, until -> prefs.throttleFailures = failures; prefs.throttleLockedUntil = until },
    )
    val clipboard = SensitiveClipboard(app) { prefs.clipboardClearSeconds * 1000L }

    val keyManager = VaultKeyManager(
        store = KeyringStore(File(File(app.noBackupFilesDir, "vaults"), "keyring.json")),
        kdf = Argon2idKeyDerivation(),
        // Software-backed Keystore keys are refused in release builds; debug builds allow them so the
        // app can run on emulators, and the UI shows the real level in Security settings.
        wrapping = AndroidKeystoreWrappingKeys(allowSoftwareKeys = BuildConfig.DEBUG),
    )

    lateinit var lockController: LockController
        private set

    val session: VaultSession = VaultSession(app, keyManager, alarmRuntime, throttle) {
        clipboard.clearIfOwn()
        lockController.onLocked()
    }

    init {
        lockController = LockController(
            scope = appScope,
            policy = { prefs.lockPolicy },
            isUnlocked = { session.state.value.isAnyVaultOpen },
            lockNow = { _ -> appScope.launch { session.lock() } },
        )
    }

    val reminders = ReminderRepository(session, alarmRuntime)
    val tasks = TaskRepository(session, alarmRuntime)
    val passwords = PasswordRepository(session)
    val backups = BackupManager(app, session, alarmRuntime)
}
