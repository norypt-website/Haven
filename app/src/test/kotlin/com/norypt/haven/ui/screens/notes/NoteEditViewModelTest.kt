package com.norypt.haven.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.storage.content.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The editor's autosave against an in-memory store. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteEditViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val notes = mutableMapOf<String, NoteEntity>()
    private val saves = mutableListOf<String?>()
    private val hooks = mutableListOf<suspend () -> Unit>()
    private var generation = 0
    private var activity = 0
    private var failReads = false
    private val store = ViewModelStore()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun deps(appScope: CoroutineScope) = NoteEditDeps(
        get = { id -> if (failReads) error("vault closed") else notes[id] },
        save = { id, title, body ->
            saves += id
            val key = id ?: "new-${notes.size}"
            notes[key] = NoteEntity(key, title, body, 0, 0); key
        },
        delete = { notes.remove(it) },
        generation = { generation },
        onActivity = { activity++ },
        addBeforeLock = { hook -> hooks += hook; { hooks.remove(hook) } },
        appScope = appScope,
    )

    private fun TestScope.editor(id: String?): NoteEditViewModel {
        val d = deps(this)
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NoteEditViewModel(d, id) as T
        })[NoteEditViewModel::class.java]
    }

    @Test fun typingSavesAfterAPauseAndCountsAsActivity() = runTest(dispatcher) {
        val vm = editor(null)
        vm.onTitle("Groceries")
        advanceUntilIdle()
        assertThat(notes.values.single().title).isEqualTo("Groceries")
        assertThat(vm.saved.value).isTrue()
        assertThat(activity).isEqualTo(1)
    }

    @Test fun aViewModelFromBeforeALockWritesNothing() = runTest(dispatcher) {
        notes["n1"] = NoteEntity("n1", "Old", "text", 0, 0)
        val vm = editor("n1")
        advanceUntilIdle()
        vm.onBody("typed before the lock")
        generation++ // locked and unlocked again while the edit was pending
        advanceUntilIdle()
        store.clear() // the old back stack goes later, saving on the way out
        advanceUntilIdle()
        assertThat(notes.getValue("n1").body).isEqualTo("text")
        assertThat(saves).isEmpty()
    }

    @Test fun deletingCancelsThePendingAutosave() = runTest(dispatcher) {
        notes["n1"] = NoteEntity("n1", "Note", "", 0, 0)
        val vm = editor("n1")
        advanceUntilIdle()
        vm.onTitle("Note, edited")
        vm.delete {}
        advanceUntilIdle()
        store.clear()
        advanceUntilIdle()
        assertThat(notes).isEmpty()
    }

    @Test fun aFailedReadKeepsTheIdSoNoDuplicateIsMade() = runTest(dispatcher) {
        failReads = true
        val vm = editor("n1")
        advanceUntilIdle()
        assertThat(vm.id).isEqualTo("n1")
    }

    @Test fun aNoteDeletedElsewhereIsEditedAsANewOne() = runTest(dispatcher) {
        val vm = editor("gone")
        advanceUntilIdle()
        assertThat(vm.id).isNull()
    }

    @Test fun theLastTextIsSavedBeforeALock() = runTest(dispatcher) {
        val vm = editor(null)
        vm.onBody("half a sentence")
        assertThat(hooks).hasSize(1)
        hooks.single().invoke() // what VaultSession.lock() does before closing the vault
        assertThat(notes.values.single().body).isEqualTo("half a sentence")
        store.clear()
        assertThat(hooks).isEmpty()
        advanceUntilIdle()
        assertThat(saves).hasSize(1)
    }

    @Test fun sameSessionGuard() {
        assertThat(isSameSession(3, 3)).isTrue()
        assertThat(isSameSession(3, 4)).isFalse()
    }
}
