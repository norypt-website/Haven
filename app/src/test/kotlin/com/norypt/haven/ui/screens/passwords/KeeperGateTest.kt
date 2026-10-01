package com.norypt.haven.ui.screens.passwords

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.ui.navigation.Routes
import org.junit.Test

class KeeperGateTest {
    @Test fun aKeeperScreenShownWithTheKeeperClosedAsksForTheKeeperPassword() {
        listOf(Routes.PASSWORDS, Routes.PASSWORD_DETAIL, Routes.PASSWORD_EDIT, Routes.PASSWORD_GENERATOR).forEach { route ->
            assertThat(keeperNeedsUnlock(open = false, appUnlocked = true, currentRoute = route)).isTrue()
        }
    }

    @Test fun lockingTheKeeperWhileLeavingItDoesNotBounceBack() {
        // The lock button navigates to Today first; the list fading out must not reopen the unlock screen.
        assertThat(keeperNeedsUnlock(open = false, appUnlocked = true, currentRoute = Routes.TODAY)).isFalse()
        assertThat(keeperNeedsUnlock(open = false, appUnlocked = true, currentRoute = Routes.PASSWORD_UNLOCK)).isFalse()
        assertThat(keeperNeedsUnlock(open = false, appUnlocked = true, currentRoute = null)).isFalse()
    }

    @Test fun nothingHappensWhileTheKeeperIsOpenOrTheAppIsLocked() {
        assertThat(keeperNeedsUnlock(open = true, appUnlocked = true, currentRoute = Routes.PASSWORDS)).isFalse()
        assertThat(keeperNeedsUnlock(open = false, appUnlocked = false, currentRoute = Routes.PASSWORDS)).isFalse()
    }
}
