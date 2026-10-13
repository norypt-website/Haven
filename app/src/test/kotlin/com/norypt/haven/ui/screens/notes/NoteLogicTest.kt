package com.norypt.haven.ui.screens.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NoteLogicTest {
    @Test fun titleWins() { assertThat(NoteLogic.displayTitle(" Groceries ", "milk")).isEqualTo("Groceries") }
    @Test fun untitledUsesFirstBodyLine() { assertThat(NoteLogic.displayTitle("", "\n  first line \nsecond")).isEqualTo("first line") }
    @Test fun emptyNoteIsUntitled() { assertThat(NoteLogic.displayTitle(" ", " ")).isEqualTo("Untitled") }
    @Test fun displayTitleUsesOnlyTheFirstLineOfAnOldMultiLineTitle() {
        assertThat(NoteLogic.displayTitle("Groceries\nMilk and bread", "x")).isEqualTo("Groceries")
        assertThat(NoteLogic.displayTitle("\n  \r\n Plan \nmore", "x")).isEqualTo("Plan")
    }
    @Test fun aTitleOfOnlyLineBreaksFallsBackToTheBody() { assertThat(NoteLogic.displayTitle("\n\r\n", "first\nsecond")).isEqualTo("first") }
    @Test fun cleanTitleKeepsOneLine() {
        assertThat(NoteLogic.cleanTitle("Groceries\nMilk")).isEqualTo("Groceries Milk")
        assertThat(NoteLogic.cleanTitle("  A\r\n\r\nB \n")).isEqualTo("A B")
        assertThat(NoteLogic.cleanTitle("Plain title")).isEqualTo("Plain title")
        assertThat(NoteLogic.cleanTitle("\n")).isEmpty()
    }
    @Test fun singleLineKeepsTypedSpaces() { assertThat(NoteLogic.singleLine("Hello ")).isEqualTo("Hello ") }
    @Test fun previewSkipsTheLineUsedAsTitle() { assertThat(NoteLogic.preview("", "first\nsecond\nthird")).isEqualTo("second\nthird") }
    @Test fun previewWithTitleIsTheBody() { assertThat(NoteLogic.preview("T", "  body ")).isEqualTo("body") }
    @Test fun newBlankNoteIsNeverStored() { assertThat(NoteLogic.shouldStore(isNew = true, title = " ", body = "\n")).isFalse() }
    @Test fun newNoteWithTextIsStored() { assertThat(NoteLogic.shouldStore(isNew = true, title = "", body = "x")).isTrue() }
    @Test fun existingNoteEmptiedIsStillStored() { assertThat(NoteLogic.shouldStore(isNew = false, title = "", body = "")).isTrue() }
    @Test fun likeWildcardsAreNeutralised() { assertThat(NoteLogic.escapeLike(" 50%_off ")).isEqualTo("50 off") }

    @Test fun wordsAreCountedInTitleAndBody() {
        assertThat(NoteLogic.wordCount("Shopping list", " milk,  eggs\nbread ")).isEqualTo(5)
        assertThat(NoteLogic.wordCount(" ", "\n")).isEqualTo(0)
    }

    @Test fun relativeTimes() {
        val now = 10_000_000_000L
        assertThat(relativeTime(now - 10_000, now)).isEqualTo("Just now")
        assertThat(relativeTime(now - 12 * 60_000, now)).isEqualTo("12 min ago")
        assertThat(relativeTime(now - 3 * 3_600_000, now)).isEqualTo("3 h ago")
        assertThat(relativeTime(now - 30 * 3_600_000, now)).isEqualTo("Yesterday")
    }
}
