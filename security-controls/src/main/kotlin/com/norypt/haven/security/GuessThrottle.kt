package com.norypt.haven.security

/**
 * Escalating delay after wrong passwords. It slows an attacker who has the unlocked phone in
 * hand; it does NOT wipe anything and it does NOT claim to throttle an attacker who copied the
 * files (Argon2id cost is the protection there).
 *
 * State is handed to [persist] on every change and restored through [initialFailures] /
 * [initialLockedUntil], so a force-stop or process kill does not reset the count. A stored
 * lock time is capped at [MAX_LOCK_MS] in the future so a clock jump cannot lock the owner out.
 */
public class GuessThrottle(
    private val now: () -> Long = System::currentTimeMillis,
    initialFailures: Int = 0,
    initialLockedUntil: Long = 0L,
    private val persist: (failures: Int, lockedUntil: Long) -> Unit = { _, _ -> },
) {
    @Volatile private var failures: Int = initialFailures.coerceAtLeast(0)
    @Volatile private var lockedUntil: Long = initialLockedUntil.coerceAtMost(now() + MAX_LOCK_MS)

    /** Milliseconds the caller must wait before accepting another attempt (0 = none). */
    public fun remainingDelayMs(): Long = (lockedUntil - now()).coerceAtLeast(0L)

    public fun recordFailure() {
        failures++
        val delay = when {
            failures < 3 -> 0L
            failures < 6 -> 5_000L
            failures < 10 -> 30_000L
            else -> 60_000L
        }
        lockedUntil = now() + delay
        persist(failures, lockedUntil)
    }

    public fun recordSuccess() {
        failures = 0
        lockedUntil = 0L
        persist(0, 0L)
    }

    public val failureCount: Int get() = failures

    public companion object {
        public const val MAX_LOCK_MS: Long = 24L * 60 * 60 * 1000
    }
}
