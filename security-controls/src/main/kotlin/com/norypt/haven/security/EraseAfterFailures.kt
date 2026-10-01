package com.norypt.haven.security

/**
 * The automatic erase after wrong passwords. Every wrong Haven password, whichever screen it was
 * typed on, adds to the one count kept by [GuessThrottle]; once the count reaches the chosen limit
 * Haven erases the whole vault, exactly as "Forgot your password? → Erase" does. A correct
 * password resets the count.
 *
 * The limit is stored as a plain number: one of [CHOICES], or [OFF].
 */
public object EraseAfterFailures {
    public const val OFF: Int = 0
    public const val DEFAULT: Int = 10
    public val CHOICES: List<Int> = listOf(3, 5, 7, 10)

    /** A stored value Haven does not offer (damaged preferences, a newer version) means the default, never Off. */
    public fun sanitize(stored: Int): Int = if (stored == OFF || stored in CHOICES) stored else DEFAULT

    public fun shouldErase(limit: Int, failures: Int): Boolean = limit > 0 && failures >= limit

    /** Wrong passwords left before the erase; null when the erase is off. */
    public fun attemptsLeft(limit: Int, failures: Int): Int? = if (limit <= 0) null else (limit - failures).coerceAtLeast(0)

    /** True when [to] gives someone guessing more attempts than [from]; such a change asks for the password. */
    public fun isWeaker(from: Int, to: Int): Boolean = when {
        to == OFF -> from != OFF
        from == OFF -> false
        else -> to > from
    }
}
