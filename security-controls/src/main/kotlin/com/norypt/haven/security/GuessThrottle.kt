package com.norypt.haven.security

/**
 * In-process, escalating delay after wrong passwords. It slows an attacker who has the
 * unlocked phone in hand; it does NOT wipe anything and it does NOT claim to throttle an
 * attacker who copied the files (Argon2id cost is the protection there).
 */
public class GuessThrottle(private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var failures: Int = 0
    @Volatile private var lockedUntil: Long = 0L

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
    }

    public fun recordSuccess() {
        failures = 0
        lockedUntil = 0L
    }

    public val failureCount: Int get() = failures
}
