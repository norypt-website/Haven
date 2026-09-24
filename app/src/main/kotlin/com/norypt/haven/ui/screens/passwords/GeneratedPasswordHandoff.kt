package com.norypt.haven.ui.screens.passwords

/**
 * In-memory hand-off of a generated password from the generator screen to the edit screen.
 * Never persisted, never placed in navigation arguments or saved state. The value is consumed
 * (and the slot cleared) by the first [take]; callers wipe the array once they have used it.
 */
object GeneratedPasswordHandoff {
    @Volatile var value: CharArray? = null

    /** Stores a private copy, wiping any previous value. */
    fun put(chars: CharArray) {
        value?.fill('\u0000')
        value = chars.copyOf()
    }

    /** Returns the pending value (if any) and clears the slot. The caller owns wiping it. */
    fun take(): CharArray? {
        val v = value
        value = null
        return v
    }

    /** Discards a pending value that no edit screen is going to consume. */
    fun clear() {
        value?.fill('\u0000')
        value = null
    }
}
