package com.norypt.haven.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.session.lockedTestSession
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    @Test fun saveAfterLockIsIgnored() = runTest {
        val repo = NoteRepository(lockedTestSession(ApplicationProvider.getApplicationContext(), tmp.root))
        assertThat(repo.save(id = "n1", title = "T", body = "B")).isNull()
        assertThat(repo.save(id = null, title = "", body = "")).isNull()
    }
}
