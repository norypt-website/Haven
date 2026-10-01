package com.norypt.haven.ui.components

import com.google.common.truth.Truth.assertThat
import com.norypt.haven.session.UnlockResult
import org.junit.Test

class AttemptTextTest {
    @Test fun waitsReadAsMinutesAndSeconds() {
        assertThat(waitText(5_000)).isEqualTo("5 s")
        assertThat(waitText(1)).isEqualTo("1 s")
        assertThat(waitText(60_000)).isEqualTo("1 min")
        assertThat(waitText(59_001)).isEqualTo("1 min")
        assertThat(waitText(90_000)).isEqualTo("1 min 30 s")
        assertThat(waitText(1_799_001)).isEqualTo("30 min")
    }

    @Test fun theWarningCountsDownAndTheLastAttemptIsSpelledOut() {
        assertThat(attemptsLeftText(null)).isNull()
        assertThat(attemptsLeftText(9)).isEqualTo("9 attempts left before Haven erases this vault.")
        assertThat(attemptsLeftText(2)).isEqualTo("2 attempts left before Haven erases this vault.")
        assertThat(attemptsLeftText(1)).isEqualTo("Last attempt: one more wrong password erases this vault.")
    }

    @Test fun aRefusedPasswordInSettingsSaysWhatHappensNext() {
        // The wait itself is a live countdown next to the field, so the message leaves it out.
        assertThat(refusalText(UnlockResult.WrongPassword(15_000, attemptsLeft = 4)))
            .isEqualTo("Wrong password. 4 attempts left before Haven erases this vault.")
        assertThat(refusalText(UnlockResult.WrongPassword(5_000, attemptsLeft = null))).isEqualTo("Wrong password.")
        assertThat(refusalText(UnlockResult.Throttled(65_000))).isEqualTo("Too many wrong passwords. Try again in 1 min 5 s.")
        assertThat(refusalText(UnlockResult.Failed("Disk full."))).isEqualTo("Disk full.")
    }

    @Test fun successAndEraseNeedNoMessage() {
        assertThat(refusalText(UnlockResult.Success)).isNull()
        assertThat(refusalText(UnlockResult.Erased)).isNull()
    }
}
