package com.norypt.haven.ui.screens.photos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.norypt.haven.security.LockController

/**
 * One system screen (permission dialog, photo picker, delete request) opened as a deliberate
 * excursion, so the vault does not lock on backgrounding while it is open.
 *
 * Every begin is matched by exactly one end: in the result callback, or when the screen that
 * opened it leaves composition (for example the inactivity lock fired meanwhile and the result
 * will never be delivered). Otherwise lock-on-background would stay off for the whole process.
 * While open, further launches are ignored, which also debounces double taps.
 */
internal class Excursion(private val lock: LockController) {
    var open by mutableStateOf(false)
        private set

    /** Opens the system screen unless one is already open. Returns whether it opened. */
    fun launch(block: () -> Unit): Boolean {
        if (open) return false
        open = true
        lock.beginSystemInteraction()
        val ok = runCatching(block).isSuccess
        if (!ok) end()
        return ok
    }

    /** Ends the excursion if it is still open. Returns whether it was. */
    fun end(): Boolean {
        if (!open) return false
        open = false
        lock.endSystemInteraction()
        return true
    }

    /**
     * A result came back: ends the excursion (if this instance still holds it) and applies the
     * result while the keeper is open. The result is applied even when this instance never opened
     * the screen: after the activity is recreated (rotation) the old screen's excursion was ended
     * on dispose and the result arrives at a fresh instance, but the user's answer still counts.
     */
    fun onResult(keeperOpen: Boolean, apply: () -> Unit) {
        end()
        if (keeperOpen) apply()
    }
}

@Composable
internal fun rememberExcursion(lock: LockController): Excursion {
    val excursion = remember(lock) { Excursion(lock) }
    DisposableEffect(excursion) { onDispose { excursion.end() } }
    return excursion
}
