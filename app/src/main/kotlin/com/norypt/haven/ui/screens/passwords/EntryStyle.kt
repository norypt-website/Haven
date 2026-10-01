package com.norypt.haven.ui.screens.passwords

enum class PasswordCharKind { LETTER, DIGIT, SYMBOL }

/** Characters [start] (inclusive) to [end] (exclusive) of a password, all of one [kind]. */
data class PasswordRun(val kind: PasswordCharKind, val start: Int, val end: Int)

/**
 * Splits a password into runs of letters, digits and everything else, so a shown password can
 * colour digits and symbols (telling 0 from O and 1 from l). Runs hold positions only, never text.
 */
fun passwordRuns(password: CharSequence): List<PasswordRun> {
    val runs = ArrayList<PasswordRun>()
    var i = 0
    while (i < password.length) {
        val cp = Character.codePointAt(password, i)
        val kind = when {
            Character.isDigit(cp) -> PasswordCharKind.DIGIT
            Character.isLetter(cp) -> PasswordCharKind.LETTER
            else -> PasswordCharKind.SYMBOL
        }
        val next = i + Character.charCount(cp)
        val last = runs.lastOrNull()
        if (last != null && last.kind == kind) runs[runs.lastIndex] = last.copy(end = next) else runs += PasswordRun(kind, i, next)
        i = next
    }
    return runs
}
