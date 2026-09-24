package com.norypt.haven.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.norypt.haven.security.LockController
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Asks the system to confirm the device screen lock (PIN/pattern/password or a strong biometric).
 *
 * This is an ADDITIONAL check that lets Keystore keys created with a user-authentication
 * requirement be used; it never unlocks anything by itself. The Haven password is still required.
 * Returns false when the user cancels, the prompt errors, or the device has no screen lock.
 */
suspend fun confirmDeviceCredential(activity: Activity, title: String): Boolean = suspendCancellableCoroutine { cont ->
    val signal = CancellationSignal()
    val prompt = BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setSubtitle("Confirm your device screen lock to continue.")
        .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setConfirmationRequired(false)
        .build()
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            if (cont.isActive) cont.resume(true)
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            if (cont.isActive) cont.resume(false)
        }
        // onAuthenticationFailed: a single failed biometric try; the prompt stays open, so nothing to do.
    }
    cont.invokeOnCancellation { runCatching { signal.cancel() } }
    try {
        prompt.authenticate(signal, ContextCompat.getMainExecutor(activity), callback)
    } catch (t: Throwable) {
        if (cont.isActive) cont.resume(false)
    }
}

/** Runs [block] as a deliberate system excursion (prompt, picker) so backgrounding does not lock the vault mid-transaction. */
suspend fun <T> LockController.duringSystemInteraction(block: suspend () -> T): T {
    beginSystemInteraction()
    try {
        return block()
    } finally {
        endSystemInteraction()
    }
}

/** The hosting Activity, from the activity-compose local or by walking the context chain. */
@Composable
fun currentActivity(): Activity? = LocalActivity.current ?: LocalContext.current.findActivity()

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
