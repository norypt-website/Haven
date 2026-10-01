package com.norypt.haven.ui.screens.passwords

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.ui.components.EntryColor
import com.norypt.haven.ui.components.entryInitials
import org.junit.Test

class EntryStyleTest {
    @Test fun initialsAreTheFirstLettersOfTheFirstTwoWords() {
        assertThat(entryInitials("Bank account")).isEqualTo("BA")
        assertThat(entryInitials("Streaming")).isEqualTo("S")
        assertThat(entryInitials("home wi-fi")).isEqualTo("HW")
        assertThat(entryInitials("  router   admin panel ")).isEqualTo("RA")
        assertThat(entryInitials("123 go")).isEqualTo("1G")
    }

    @Test fun initialsHandleAnyScript() {
        assertThat(entryInitials("銀行 口座")).isEqualTo("銀口")
        assertThat(entryInitials("élan vital")).isEqualTo("ÉV")
        assertThat(entryInitials("Élan")).isEqualTo("É")
        assertThat(entryInitials("🔑 vault")).isEqualTo("V")
    }

    @Test fun initialsAreEmptyWhenThereIsNothingToShow() {
        assertThat(entryInitials("")).isEmpty()
        assertThat(entryInitials("   ")).isEmpty()
        assertThat(entryInitials("🔑 !!")).isEmpty()
    }

    @Test fun paletteIdsAreStable() {
        // Ids are stored in the database and in backups: a change here re-colours people's entries.
        assertThat(EntryColor.entries.associate { it.name to it.id }).containsExactly(
            "RED", 1, "ORANGE", 2, "OLIVE", 3, "GREEN", 4, "TEAL", 5,
            "BLUE", 6, "INDIGO", 7, "PURPLE", 8, "PINK", 9, "GRAPHITE", 10,
        ).inOrder()
        assertThat(EntryColor.entries.none { it.id == EntryColor.AUTO }).isTrue()
    }

    @Test fun automaticColourDependsOnlyOnTheTitle() {
        assertThat(EntryColor.auto("Bank account")).isEqualTo(EntryColor.auto("  BANK ACCOUNT "))
        assertThat(EntryColor.auto("Café")).isEqualTo(EntryColor.auto("Café"))
    }

    @Test fun automaticColourIsTheSameInEveryRelease() {
        assertThat(EntryColor.auto("Bank account")).isEqualTo(EntryColor.ORANGE)
        assertThat(EntryColor.auto("Work email")).isEqualTo(EntryColor.BLUE)
        assertThat(EntryColor.auto("Home Wi-Fi")).isEqualTo(EntryColor.PURPLE)
        assertThat(EntryColor.auto("Streaming")).isEqualTo(EntryColor.INDIGO)
    }

    @Test fun automaticColoursSpreadAcrossThePalette() {
        val titles = (1..200).map { "Entry number $it" }
        assertThat(titles.map { EntryColor.auto(it) }.toSet()).hasSize(EntryColor.entries.size)
    }

    @Test fun chosenColourWinsAndUnknownIdsFallBackToAutomatic() {
        assertThat(EntryColor.resolve(EntryColor.BLUE.id, "Bank account")).isEqualTo(EntryColor.BLUE)
        assertThat(EntryColor.resolve(EntryColor.AUTO, "Bank account")).isEqualTo(EntryColor.ORANGE)
        // A backup written by a newer Haven may carry a colour this version does not know.
        assertThat(EntryColor.resolve(99, "Bank account")).isEqualTo(EntryColor.ORANGE)
        assertThat(EntryColor.resolve(-1, "Bank account")).isEqualTo(EntryColor.ORANGE)
    }

    @Test fun passwordCharactersAreGroupedIntoRunsByKind() {
        assertThat(passwordRuns("ab12!!c")).containsExactly(
            PasswordRun(PasswordCharKind.LETTER, 0, 2),
            PasswordRun(PasswordCharKind.DIGIT, 2, 4),
            PasswordRun(PasswordCharKind.SYMBOL, 4, 6),
            PasswordRun(PasswordCharKind.LETTER, 6, 7),
        ).inOrder()
    }

    @Test fun passwordRunsCoverEveryCharacter() {
        assertThat(passwordRuns("")).isEmpty()
        assertThat(passwordRuns("é٣ x")).containsExactly(
            PasswordRun(PasswordCharKind.LETTER, 0, 1),
            PasswordRun(PasswordCharKind.DIGIT, 1, 2),
            PasswordRun(PasswordCharKind.SYMBOL, 2, 3),
            PasswordRun(PasswordCharKind.LETTER, 3, 4),
        ).inOrder()
        // A surrogate pair stays inside one run, so a colour span never splits a character.
        assertThat(passwordRuns("a🔑b")).containsExactly(
            PasswordRun(PasswordCharKind.LETTER, 0, 1),
            PasswordRun(PasswordCharKind.SYMBOL, 1, 3),
            PasswordRun(PasswordCharKind.LETTER, 3, 4),
        ).inOrder()
    }
}
