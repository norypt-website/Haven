package com.norypt.haven.ui.components

import androidx.compose.ui.graphics.Color
import java.text.Normalizer
import java.util.Locale

/**
 * The tile palette. Password entries store these ids in the database and in backups, so they never
 * change; [AUTO] (0) means "work it out from the title". Lists without a stored colour use [auto]. Every colour keeps white initials at a
 * contrast of at least 5:1.
 */
enum class EntryColor(val id: Int, val label: String, val argb: Long) {
    RED(1, "Red", 0xFFCF2E2E),
    ORANGE(2, "Orange", 0xFFC2410C),
    OLIVE(3, "Olive", 0xFF4A7310),
    GREEN(4, "Green", 0xFF157A3A),
    TEAL(5, "Teal", 0xFF0F766E),
    BLUE(6, "Blue", 0xFF155EEF),
    INDIGO(7, "Indigo", 0xFF4F46E5),
    PURPLE(8, "Purple", 0xFF9333EA),
    PINK(9, "Pink", 0xFFBE185D),
    GRAPHITE(10, "Graphite", 0xFF475569);

    companion object {
        const val AUTO: Int = 0

        /** Same title, same colour, in every release: case, outer spaces and accent encoding do not matter. */
        fun auto(title: String): EntryColor {
            val key = Normalizer.normalize(title.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
            return entries[Math.floorMod(key.hashCode(), entries.size)]
        }

        /** The colour an entry shows: the one chosen in the editor, else the automatic one (also for ids this version does not know). */
        fun resolve(id: Int, title: String): EntryColor = entries.firstOrNull { it.id == id } ?: auto(title)
    }
}

/** Up to two letters for a tile: the first letter or digit of each of the first two words. */
fun entryInitials(title: String): String {
    val text = Normalizer.normalize(title, Normalizer.Form.NFC)
    val initials = StringBuilder()
    var words = 0
    var inWord = false
    var i = 0
    while (i < text.length && words < 2) {
        val cp = text.codePointAt(i)
        when {
            Character.isLetterOrDigit(cp) -> if (!inWord) {
                initials.append(String(Character.toChars(cp)).uppercase(Locale.ROOT))
                words++
                inWord = true
            }
            isMark(cp) -> Unit // an accent belongs to the word it follows
            else -> inWord = false
        }
        i += Character.charCount(cp)
    }
    return initials.toString()
}

private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

val EntryColor.color: Color get() = Color(argb)
