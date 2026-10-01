package com.norypt.haven.security

/**
 * Escalating delay after wrong passwords: each one waits longer than the one before (5 s, 15 s,
 * 30 s, 1, 2, 5, 10 and 15 minutes, then 30 minutes for every further one). It slows an attacker
 * who has the unlocked phone in hand; it does NOT wipe anything itself (see [EraseAfterFailures],
 * which reads [failureCount]) and it does NOT claim to throttle an attacker who copied the files
 * (Argon2id cost is the protection there).
 *
 * State is handed to [persist] on every change and restored through [initialFailures] /
 * [initialLockedUntil], so a force-stop or process kill does not reset the count. A wait is
 * never longer than the step that belongs to the count, so a clock set back, or a lock time stored
 * under a wrong clock, cannot lock the owner out.
 */
public class GuessThrottle(
    private val now: () -> Long = System::currentTimeMillis,
    initialFailures: Int = 0,
    initialLockedUntil: Long = 0L,
    private val persist: (failures: Int, lockedUntil: Long) -> Unit = { _, _ -> },
) {
    @Volatile private var failures: Int = initialFailures.coerceAtLeast(0)
    @Volatile private var lockedUntil: Long = initialLockedUntil

    /** Milliseconds the caller must wait before accepting another attempt (0 = none). */
    public fun remainingDelayMs(): Long = (lockedUntil - now()).coerceIn(0L, currentStepMs())

    public fun recordFailure() {
        failures++
        lockedUntil = now() + currentStepMs()
        persist(failures, lockedUntil)
    }

    /** The wait that belongs to the current count. */
    private fun currentStepMs(): Long = if (failures == 0) 0L else WAITS_MS[(failures - 1).coerceAtMost(WAITS_MS.lastIndex)]

    public fun recordSuccess(): Unit = reset()

    /** Forgets the count and any running wait: after a correct password, and when the vault is erased. */
    public fun reset() {
        failures = 0
        lockedUntil = 0L
        persist(0, 0L)
    }

    public val failureCount: Int get() = failures

    private companion object {
        /** The wait after the 1st, 2nd, 3rd … wrong password; the last one repeats. */
        private val WAITS_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000, 1_800_000)
    }
}
