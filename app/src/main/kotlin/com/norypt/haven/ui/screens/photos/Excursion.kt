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

    /** Ends the excursion. Returns false when none was open (a stale or foreign result). */
    fun end(): Boolean {
        if (!open) return false
        open = false
        lock.endSystemInteraction()
        return true
    }
}

@Composable
internal fun rememberExcursion(lock: LockController): Excursion {
    val excursion = remember(lock) { Excursion(lock) }
    DisposableEffect(excursion) { onDispose { excursion.end() } }
    return excursion
}
