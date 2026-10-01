package com.norypt.haven.ui.screens.passwords

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.storage.passwords.PasswordEntryEntity
import org.junit.Test

class PasswordListLogicTest {
    private fun entry(id: String, folderId: String? = null, starred: Boolean = false) =
        PasswordEntryEntity(id, folderId, "Title $id", "", "", "", "", 1L, 1L, starred = starred)

    private val bank = entry("bank", folderId = "finance", starred = true)
    private val mail = entry("mail", folderId = "work")
    private val wifi = entry("wifi", starred = true)
    private val shop = entry("shop")
    private val all = listOf(bank, mail, wifi, shop)

    @Test fun filtersKeepTheListOrder() {
        assertThat(all.filteredBy(ListFilter.All)).containsExactlyElementsIn(all).inOrder()
        assertThat(all.filteredBy(ListFilter.Starred)).containsExactly(bank, wifi).inOrder()
        assertThat(all.filteredBy(ListFilter.Unfiled)).containsExactly(wifi, shop).inOrder()
        assertThat(all.filteredBy(ListFilter.Folder("finance"))).containsExactly(bank)
        assertThat(all.filteredBy(ListFilter.Folder("missing"))).isEmpty()
    }

    @Test fun starredEntriesComeFirstAsTheirOwnSection() {
        val sections = sectionsOf(all)
        assertThat(sections.starred).containsExactly(bank, wifi).inOrder()
        assertThat(sections.others).containsExactly(mail, shop).inOrder()
    }

    @Test fun starringASelectionStarsAllUnlessAllAreStarred() {
        assertThat(starTargetFor(listOf(mail, shop))).isTrue()
        assertThat(starTargetFor(listOf(bank, mail))).isTrue()
        assertThat(starTargetFor(listOf(bank, wifi))).isFalse()
    }
}
