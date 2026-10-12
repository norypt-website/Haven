package com.norypt.haven.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeviceAuthRetryTest {
    private var prompts = 0
    private var checks = 0

    private fun prompt(confirmed: Boolean): suspend () -> Boolean = { prompts++; confirmed }
    private fun results(vararg r: UnlockResult): suspend () -> UnlockResult = { r[checks++] }

    @Test fun theScreenLockIsAskedForOnlyWhenTheKeyDemandsIt() = runTest {
        assertThat(retryWithDeviceAuth(prompt(true), results(UnlockResult.WrongPassword(5_000, null))))
            .isEqualTo(UnlockResult.WrongPassword(5_000, null))
        assertThat(prompts).isEqualTo(0)
        assertThat(checks).isEqualTo(1)
    }

    @Test fun aConfirmedScreenLockRetriesTheCheckOnce() = runTest {
        val result = retryWithDeviceAuth(prompt(true), results(UnlockResult.DeviceAuthRequired, UnlockResult.Success))
        assertThat(result).isEqualTo(UnlockResult.Success)
        assertThat(prompts).isEqualTo(1)
        assertThat(checks).isEqualTo(2)
    }

    @Test fun aCancelledPromptLeavesTheRefusalWithoutASecondCheck() = runTest {
        val result = retryWithDeviceAuth(prompt(false), results(UnlockResult.DeviceAuthRequired, UnlockResult.Success))
        assertThat(result).isEqualTo(UnlockResult.DeviceAuthRequired)
        assertThat(checks).isEqualTo(1)
    }

    @Test fun theSecondRefusalIsReportedInsteadOfPromptingAgain() = runTest {
        val result = retryWithDeviceAuth(prompt(true), results(UnlockResult.DeviceAuthRequired, UnlockResult.DeviceAuthRequired))
        assertThat(result).isEqualTo(UnlockResult.DeviceAuthRequired)
        assertThat(prompts).isEqualTo(1)
    }
}
