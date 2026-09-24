package com.norypt.haven.ui.screens.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.crypto.SecurityLevel
import com.norypt.haven.crypto.VaultCryptoException
import com.norypt.haven.data.PasswordGenerator
import com.norypt.haven.di.AppContainer
import com.norypt.haven.session.UnlockResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/*
 * Passwords live only in these ViewModels (plain Compose state, never rememberSaveable,
 * SavedStateHandle or navigation arguments) and are cleared as soon as they have been used
 * and again when the ViewModel is cleared.
 */

class SetupViewModel(private val container: AppContainer) : ViewModel() {
    sealed interface Phase {
        data object Editing : Phase
        data object Measuring : Phase
        data object Creating : Phase
        data class Created(val params: Argon2Params, val level: SecurityLevel?) : Phase
    }

    var password by mutableStateOf("")
    var confirm by mutableStateOf("")
    var suggestion by mutableStateOf<String?>(null)
    var requireDeviceAuth by mutableStateOf(false)
    var phase by mutableStateOf<Phase>(Phase.Editing)
    var error by mutableStateOf<String?>(null)
    /** True when the device cannot provide a hardware-backed key: setup cannot continue. */
    var fatal by mutableStateOf(false)

    val busy: Boolean get() = phase is Phase.Measuring || phase is Phase.Creating

    fun suggest() {
        val chars = PasswordGenerator.passphrase(6)
        suggestion = String(chars)
        chars.fill('\u0000')
    }

    fun useSuggestion() {
        suggestion?.let { password = it; confirm = it }
    }

    fun create() {
        if (busy || fatal) return
        error = null
        when {
            password.isEmpty() -> { error = "Enter a password."; return }
            password.length < MIN_LENGTH -> { error = "Use at least $MIN_LENGTH characters. A passphrase of several words is easiest to remember."; return }
            password != confirm -> { error = "The two entries do not match."; return }
        }
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                phase = Phase.Measuring
                val bench = container.session.benchmarkKdf()
                phase = Phase.Creating
                container.session.setUp(chars, requireDeviceAuth, bench.params)
                container.prefs.requireDeviceAuth = requireDeviceAuth
                password = ""; confirm = ""; suggestion = null
                phase = Phase.Created(bench.params, container.session.hardwareLevel)
            } catch (e: VaultCryptoException.HardwareKeyUnavailable) {
                phase = Phase.Editing
                fatal = true
                error = "This device cannot provide a hardware-backed key. Haven does not fall back to software keys."
            } catch (e: Exception) {
                phase = Phase.Editing
                error = "Could not create the vault (${e.javaClass.simpleName})."
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    override fun onCleared() {
        password = ""; confirm = ""; suggestion = null
    }

    companion object {
        const val MIN_LENGTH = 8
    }
}

class UnlockViewModel(private val container: AppContainer) : ViewModel() {
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var unrecoverable by mutableStateOf<String?>(null)
    /** Seconds the user must wait before the next attempt (0 = none). */
    var waitSeconds by mutableStateOf(0)
    var erasing by mutableStateOf(false)
    private var countdown: Job? = null

    val canSubmit: Boolean get() = !busy && waitSeconds == 0 && unrecoverable == null && !erasing

    /**
     * [deviceAuth] runs the system device-credential prompt; it is invoked at most once, only
     * when the Keystore key demands it, and the unlock is then retried automatically once.
     */
    fun unlock(deviceAuth: suspend () -> Boolean, onUnlocked: () -> Unit) {
        if (!canSubmit) return
        val chars = password.toCharArray()
        password = ""
        if (chars.isEmpty()) { message = "Enter your password."; return }
        busy = true
        message = null
        viewModelScope.launch {
            try {
                var result = container.session.unlockContent(chars)
                if (result is UnlockResult.DeviceAuthRequired) {
                    result = if (deviceAuth()) container.session.unlockContent(chars) else result
                }
                when (result) {
                    UnlockResult.Success -> onUnlocked()
                    is UnlockResult.WrongPassword -> { message = "Wrong password."; startCountdown(result.waitMs) }
                    is UnlockResult.Throttled -> { message = "Too many attempts."; startCountdown(result.waitMs) }
                    UnlockResult.DeviceAuthRequired -> message = "Your device screen lock was not confirmed. Try again."
                    is UnlockResult.Unrecoverable -> unrecoverable = result.reason
                    is UnlockResult.Failed -> message = result.message
                }
            } catch (e: Exception) {
                message = "Unlock failed (${e.javaClass.simpleName})."
            } finally {
                chars.fill('\u0000')
                busy = false
            }
        }
    }

    private fun startCountdown(waitMs: Long) {
        countdown?.cancel()
        if (waitMs <= 0) { waitSeconds = 0; return }
        countdown = viewModelScope.launch {
            var s = ceil(waitMs / 1000.0).toInt()
            while (s > 0) {
                waitSeconds = s
                delay(1000)
                s--
            }
            waitSeconds = 0
        }
    }

    /** Erases the whole vault (the Forgot-password screen is the user-facing path; kept for tests and tooling). */
    fun eraseAndStartOver() {
        if (erasing) return
        erasing = true
        viewModelScope.launch {
            try {
                container.session.eraseEverything { container.prefs.clearAll() }
            } catch (e: Exception) {
                message = "Erase failed (${e.javaClass.simpleName})."
            } finally {
                erasing = false
            }
        }
    }

    override fun onCleared() {
        password = ""
    }
}
