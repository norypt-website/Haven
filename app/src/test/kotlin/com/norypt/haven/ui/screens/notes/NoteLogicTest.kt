package com.norypt.haven.ui.screens.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NoteLogicTest {
    @Test fun titleWins() { assertThat(NoteLogic.displayTitle(" Groceries ", "milk")).isEqualTo("Groceries") }
    @Test fun untitledUsesFirstBodyLine() { assertThat(NoteLogic.displayTitle("", "\n  first line \nsecond")).isEqualTo("first line") }
    @Test fun emptyNoteIsUntitled() { assertThat(NoteLogic.displayTitle(" ", " ")).isEqualTo("Untitled") }
    @Test fun previewSkipsTheLineUsedAsTitle() { assertThat(NoteLogic.preview("", "first\nsecond\nthird")).isEqualTo("second\nthird") }
    @Test fun previewWithTitleIsTheBody() { assertThat(NoteLogic.preview("T", "  body ")).isEqualTo("body") }
    @Test fun newBlankNoteIsNeverStored() { assertThat(NoteLogic.shouldStore(isNew = true, title = " ", body = "\n")).isFalse() }
    @Test fun newNoteWithTextIsStored() { assertThat(NoteLogic.shouldStore(isNew = true, title = "", body = "x")).isTrue() }
    @Test fun existingNoteEmptiedIsStillStored() { assertThat(NoteLogic.shouldStore(isNew = false, title = "", body = "")).isTrue() }
    @Test fun likeWildcardsAreNeutralised() { assertThat(NoteLogic.escapeLike(" 50%_off ")).isEqualTo("50 off") }
}
