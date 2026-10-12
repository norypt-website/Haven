package com.norypt.haven.session

/**
 * Runs [check]; when a Keystore key demands the device screen lock, asks for it once through
 * [deviceAuth] and runs [check] again. A wrong password fails before the hardware layer is
 * reached, so the prompt only appears for the right password and never counts as an attempt.
 */
suspend fun retryWithDeviceAuth(deviceAuth: suspend () -> Boolean, check: suspend () -> UnlockResult): UnlockResult {
    val first = check()
    return if (first == UnlockResult.DeviceAuthRequired && deviceAuth()) check() else first
}
