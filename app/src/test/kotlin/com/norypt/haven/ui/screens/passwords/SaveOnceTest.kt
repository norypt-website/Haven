package com.norypt.haven.ui.screens.passwords

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SaveOnceTest {
    @Test fun aSecondTapWhileSavingIsIgnored() {
        val gate = SaveOnce()
        assertThat(gate.begin()).isTrue()
        assertThat(gate.begin()).isFalse()
    }

    @Test fun aSuccessfulSaveIsNeverRepeated() {
        // The editor stays on screen while it fades out; a tap then must not save a second copy.
        val gate = SaveOnce()
        gate.begin()
        gate.end(succeeded = true)
        assertThat(gate.done).isTrue()
        assertThat(gate.begin()).isFalse()
    }

    @Test fun aFailedSaveCanBeTriedAgain() {
        val gate = SaveOnce()
        gate.begin()
        gate.end(succeeded = false)
        assertThat(gate.busy).isFalse()
        assertThat(gate.done).isFalse()
        assertThat(gate.begin()).isTrue()
    }
}
