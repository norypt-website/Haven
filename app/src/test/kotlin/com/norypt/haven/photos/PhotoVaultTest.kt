package com.norypt.haven.photos

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.crypto.PhotoCipher
import com.norypt.haven.security.SessionState
import com.norypt.haven.session.UnlockResult
import com.norypt.haven.session.VaultSession
import com.norypt.haven.session.unlockedTestSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The real VaultSession with an in-memory keeper, real encryption and files; only image decoding is faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoVaultTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    private val imaging = FakeImaging()

    private fun newVaultWithSession(onKeeperClosed: () -> Unit = {}): Triple<PhotoVault, PhotoFileStore, VaultSession> =
        newTestVault(tmp, imaging, onKeeperClosed)

    private fun newVault(): Pair<PhotoVault, PhotoFileStore> = newVaultWithSession().let { (v, s, _) -> v to s }

    @Test fun importStoresEncryptedFilesAndARow() = runTest {
        val (vault, store) = newVault()
        val r = vault.import(byteArrayOf(1, 2, 3), takenAt = 5) as PhotoVault.ImportResult.Ok
        val row = vault.row(r.photo.id)
        assertThat(store.dir.list()!!.toList()).containsExactly(row.fileId, "${row.fileId}.t")
        // Padded to the 64 KiB bucket and encrypted: the file is never the 3 plaintext bytes.
        assertThat(store.open(row.fileId, PhotoCipher.Kind.ORIGINAL).readBytes().size).isAtLeast(65_536)
        assertThat(vault.original(r.photo.id)).isEqualTo(byteArrayOf(1, 2, 3))
        // The import wiped its own copy of the key, not the stored one.
        assertThat(row.dek.any { it != 0.toByte() }).isTrue()
        assertThat(r.photo.width).isEqualTo(40)
        assertThat(r.photo.takenAt).isEqualTo(5)
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(r.photo.id)
    }

    @Test fun importWipesTheInputArray() = runTest {
        val (vault, _) = newVault(); val input = byteArrayOf(1, 2, 3)
        vault.import(input, null)
        assertThat(input).isEqualTo(byteArrayOf(0, 0, 0))
    }

    @Test fun unsupportedImageRefused() = runTest {
        val (vault, store) = newVault()
        val input = byteArrayOf(7, 7)
        assertThat(vault.import(input, null)).isInstanceOf(PhotoVault.ImportResult.Refused::class.java)
        assertThat(store.dir.list()?.toList().orEmpty()).isEmpty()
        assertThat(input).isEqualTo(byteArrayOf(0, 0))
    }

    @Test fun importRefusesOversizeBytes() = runTest {
        val (vault, store) = newVault()
        val big = ByteArray((ImageBounds.MAX_BYTES + 1).toInt()).also { it[0] = 1 }
        assertThat(vault.import(big, null)).isInstanceOf(PhotoVault.ImportResult.Refused::class.java)
        assertThat(store.dir.list()?.toList().orEmpty()).isEmpty()
    }

    @Test fun deleteRemovesRowAndFiles() = runTest {
        val (vault, store) = newVault()
        val p = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        vault.delete(listOf(p.id))
        assertThat(store.dir.list()!!.toList()).isEmpty()
        assertThat(vault.observeCount().first()).isEqualTo(0)
    }

    @Test fun importAfterKeeperClosedFailsCleanly() = runTest {
        val (vault, store, session) = newVaultWithSession()
        session.lockPasswords()
        assertThat(vault.import(byteArrayOf(1), null)).isInstanceOf(PhotoVault.ImportResult.Refused::class.java)
        assertThat(store.dir.list()?.toList().orEmpty()).isEmpty()
    }

    @Test fun lockingTheKeeperDuringAnImportLeavesNothing() = runTest {
        val (vault, store, session) = newVaultWithSession()
        imaging.duringThumbnail = { runBlocking { session.lockPasswords() } }
        val input = byteArrayOf(1, 2, 3)
        assertThat(vault.import(input, null)).isEqualTo(PhotoVault.ImportResult.Refused("The vault was locked."))
        assertThat(store.dir.list()?.toList().orEmpty()).isEmpty()
        assertThat(input).isEqualTo(byteArrayOf(0, 0, 0))
        imaging.duringThumbnail = {}
        assertThat(session.unlockPasswords(PASSWORD.toCharArray())).isEqualTo(UnlockResult.Success)
        assertThat(vault.observeCount().first()).isEqualTo(0)
    }

    @Test fun rowsSurviveClosingAndReopeningTheKeeper() = runTest {
        // Guards the test above: the keeper really is the same database after unlocking again.
        val (vault, _, session) = newVaultWithSession()
        vault.import(byteArrayOf(1), null)
        session.lockPasswords()
        assertThat(session.unlockPasswords(PASSWORD.toCharArray())).isEqualTo(UnlockResult.Success)
        assertThat(vault.observeCount().first()).isEqualTo(1)
    }

    @Test fun thumbnailsAreCachedWhileTheKeeperIsOpenAndDroppedWhenItCloses() = runTest {
        val (vault, session) = unlockedWithHook()
        val p = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        imaging.decodes = true
        assertThat(vault.thumbnail(p.id)).isNotNull()
        assertThat(vault.cachedThumbnailCount()).isEqualTo(1)
        session.lockPasswords()
        assertThat(vault.cachedThumbnailCount()).isEqualTo(0)
        assertThat(vault.thumbnail(p.id)).isNull()
    }

    @Test fun aThumbnailDecodedWhileTheKeeperClosesIsNotKept() = runTest {
        val (vault, session) = unlockedWithHook()
        val p = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        imaging.decodes = true
        imaging.duringDecode = { runBlocking { session.lockPasswords() } }
        assertThat(vault.thumbnail(p.id)).isNull()
        assertThat(vault.cachedThumbnailCount()).isEqualTo(0)
    }

    /** A session whose keeper-closed hook clears the vault's caches, as in AppContainer. */
    private fun unlockedWithHook(): Pair<PhotoVault, VaultSession> {
        var vault: PhotoVault? = null
        val session = unlockedTestSession(ApplicationProvider.getApplicationContext(), tmp.newFolder(), password = PASSWORD, onKeeperClosed = { vault?.clearCaches() })
        val store = PhotoFileStore(File(tmp.root, "vaults/photos"))
        return PhotoVault(session, { store }, imaging).also { vault = it } to session
    }

    @Test fun reconcileDropsRowsWithoutFiles() = runTest {
        val (vault, store) = newVault()
        val p = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        store.delete(vault.row(p.id).fileId)
        vault.reconcile()
        assertThat(vault.observeCount().first()).isEqualTo(0)
    }

    @Test fun reconcileKeepsAPhotoWhoseThumbnailIsMissing() = runTest {
        val (vault, store) = newVault()
        val p = (vault.import(byteArrayOf(1, 4), null) as PhotoVault.ImportResult.Ok).photo
        val fileId = vault.row(p.id).fileId
        File(store.dir, "$fileId.t").delete()
        assertThat(vault.reconcile()).isEqualTo(0)
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(p.id)
        assertThat(vault.original(p.id)).isEqualTo(byteArrayOf(1, 4))
        imaging.decodes = true
        assertThat(vault.thumbnail(p.id)).isNull() // shown as "No preview"
    }

    @Test fun aDamagedPhotoHasNoThumbnailAndItsOriginalThrows() = runTest {
        val (vault, store) = newVault()
        val p = (vault.import(byteArrayOf(1, 4), null) as PhotoVault.ImportResult.Ok).photo
        val fileId = vault.row(p.id).fileId
        listOf(fileId, "$fileId.t").forEach { name ->
            val f = File(store.dir, name)
            f.writeBytes(f.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        }
        imaging.decodes = true
        assertThat(vault.thumbnail(p.id)).isNull()
        assertThat(runCatching { vault.original(p.id) }.exceptionOrNull()).isNotNull()
    }

    @Test fun theListTheScreensSeeCarriesNoKeys() = runTest {
        val (vault, _) = newVault()
        vault.import(byteArrayOf(1), null)
        val item = vault.observeAll().first().single()
        // PhotoItem has no key or file id at all; only these fields reach the UI.
        assertThat(item.javaClass.declaredFields.map { it.name }.filterNot { it.startsWith("$") })
            .containsExactly("id", "mimeType", "byteSize", "width", "height", "takenAt", "addedAt")
    }

    @Test fun reconcileRemovesFilesWithoutRows() = runTest {
        val (vault, store) = newVault()
        val kept = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        val stray = store.newFileId()
        store.write(stray, PhotoCipher.Kind.ORIGINAL) { it.write(1) }
        assertThat(vault.reconcile()).isEqualTo(1)
        val keptFile = vault.row(kept.id).fileId
        assertThat(store.dir.list()!!.toList()).containsExactly(keptFile, "$keptFile.t")
    }

    @Test fun theKeeperClosedHookRunsOnCloseAndOnLock() = runTest {
        var closed = 0
        val (_, _, session) = newVaultWithSession { closed++ }
        session.lockPasswords()
        assertThat(closed).isEqualTo(1)
        session.lock()
        assertThat(closed).isEqualTo(2)
    }

    @Test fun aFailingKeeperClosedHookNeverStopsTheLock() = runTest {
        val (_, _, session) = newVaultWithSession { throw IllegalStateException("boom") }
        session.lock()
        assertThat(session.state.value).isEqualTo(SessionState.Locked)
        assertThat(session.passwordsOrNull()).isNull()
    }

    private companion object {
        const val PASSWORD = TEST_PASSWORD
    }
}
