package com.norypt.haven.security

/** User-configurable lock behaviour. Defaults are the secure defaults documented in docs/THREAT_MODEL.md. */
public data class LockPolicy(
    /** Lock after this many milliseconds without user interaction while in the foreground. */
    val inactivityTimeoutMs: Long = 2 * 60_000L,
    /** Lock as soon as the app leaves the foreground (default on). */
    val lockOnBackground: Boolean = true,
    /** Grace period after backgrounding before the lock fires, so a quick app switch does not force re-entry. */
    val backgroundGraceMs: Long = 15_000L,
    /** Lock when the screen turns off (default on). */
    val lockOnScreenOff: Boolean = true,
) {
    public companion object {
        public val TIMEOUT_CHOICES_MS: List<Long> = listOf(30_000L, 60_000L, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L)
    }
}
