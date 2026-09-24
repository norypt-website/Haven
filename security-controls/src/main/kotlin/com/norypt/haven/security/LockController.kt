package com.norypt.haven.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides WHEN to lock. It never touches keys itself; it invokes [lockNow] which the app wires to
 * VaultSession.lock().
 *
 * System interactions (file picker, device-credential prompt, share sheet) background the app.
 * Callers wrap them in [beginSystemInteraction]/[endSystemInteraction] so that a deliberate,
 * short excursion does not lock the vault mid-transaction. Time-based locking still applies:
 * an interaction that outlives [LockPolicy.inactivityTimeoutMs] locks regardless.
 */
public class LockController(
    private val scope: CoroutineScope,
    private val policy: () -> LockPolicy,
    private val isUnlocked: () -> Boolean,
    private val lockNow: (reason: LockReason) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public enum class LockReason { USER, INACTIVITY, BACKGROUND, SCREEN_OFF, PERMISSION_CHANGE }

    private var lastInteraction: Long = now()
    private var inactivityJob: Job? = null
    private var backgroundJob: Job? = null
    private val systemInteractions = AtomicInteger(0)

    /** Call on every user interaction (touch/key) while unlocked. */
    public fun touch() {
        lastInteraction = now()
        armInactivityTimer()
    }

    public fun onUnlocked() {
        lastInteraction = now()
        armInactivityTimer()
    }

    public fun onLocked() {
        inactivityJob?.cancel(); inactivityJob = null
        backgroundJob?.cancel(); backgroundJob = null
    }

    public fun onAppForeground() {
        backgroundJob?.cancel(); backgroundJob = null
        if (isUnlocked()) {
            // Enforce the timeout across the background period as well.
            if (now() - lastInteraction >= policy().inactivityTimeoutMs) lockNow(LockReason.INACTIVITY) else armInactivityTimer()
        }
    }

    public fun onAppBackground() {
        if (!isUnlocked() || !policy().lockOnBackground) return
        if (systemInteractions.get() > 0) return // deliberate excursion; the inactivity timer still runs
        val grace = policy().backgroundGraceMs
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(grace)
            if (isUnlocked()) lockNow(LockReason.BACKGROUND)
        }
    }

    public fun onScreenOff() {
        if (isUnlocked() && policy().lockOnScreenOff) lockNow(LockReason.SCREEN_OFF)
    }

    public fun beginSystemInteraction() { systemInteractions.incrementAndGet() }

    public fun endSystemInteraction() { systemInteractions.updateAndGet { if (it > 0) it - 1 else 0 } }

    private fun armInactivityTimer() {
        inactivityJob?.cancel()
        if (!isUnlocked()) return
        val timeout = policy().inactivityTimeoutMs
        inactivityJob = scope.launch {
            delay(timeout)
            if (isUnlocked() && now() - lastInteraction >= timeout) lockNow(LockReason.INACTIVITY)
        }
    }
}
