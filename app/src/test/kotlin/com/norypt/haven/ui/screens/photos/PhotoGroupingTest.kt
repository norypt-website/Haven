package com.norypt.haven.ui.screens.photos

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.storage.passwords.PhotoItem
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class PhotoGroupingTest {
    private fun at(y: Int, m: Int, d: Int) = LocalDateTime.of(y, m, d, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun photo(id: String, takenAt: Long?, addedAt: Long) =
        PhotoItem(id, "image/jpeg", 1, 1, 1, takenAt, addedAt)

    @Test fun groupsByMonthNewestFirstUsingTakenDateWhenKnown() {
        val photos = listOf(
            photo("a", at(2026, 9, 3), addedAt = at(2026, 10, 12)),   // taken in September, added in October
            photo("b", null, addedAt = at(2026, 10, 1)),
            photo("c", at(2026, 10, 9), addedAt = at(2026, 10, 9)),
        )
        val groups = monthGroups(photos, ZoneOffset.UTC, Locale.ENGLISH)
        assertThat(groups.map { it.label }).containsExactly("October 2026", "September 2026").inOrder()
        assertThat(groups[0].photos.map { it.id }).containsExactly("c", "b").inOrder()
        assertThat(groups[1].photos.map { it.id }).containsExactly("a")
    }

    @Test fun viewerOrderMatchesTheGrid() {
        val photos = listOf(photo("x", at(2025, 1, 1), at(2026, 1, 1)), photo("y", null, at(2026, 2, 1)))
        assertThat(sortedForGrid(photos).map { it.id }).containsExactly("y", "x").inOrder()
        assertThat(monthGroups(photos, ZoneOffset.UTC, Locale.ENGLISH).flatMap { it.photos }).isEqualTo(sortedForGrid(photos))
    }
}
