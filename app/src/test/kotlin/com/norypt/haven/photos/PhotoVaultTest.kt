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

    private class FakeImaging : PhotoImaging {
        /** Runs inside [thumbnail], in the middle of an import. */
        var duringThumbnail: () -> Unit = {}
        /** Runs inside [decode], after the thumbnail was decrypted. */
        var duringDecode: () -> Unit = {}
        var decodes: Boolean = false
        override fun probe(bytes: ByteArray) = if (bytes.isNotEmpty() && bytes[0] == 1.toByte()) Probe(40, 30, "image/jpeg") else null
        override fun thumbnail(bytes: ByteArray): ByteArray { duringThumbnail(); return byteArrayOf(9, 9) }
        override fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? { duringDecode(); return if (decodes) android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() else null }
    }

    private val imaging = FakeImaging()

    private fun newVaultWithSession(onKeeperClosed: () -> Unit = {}): Triple<PhotoVault, PhotoFileStore, VaultSession> {
        val session = unlockedTestSession(ApplicationProvider.getApplicationContext(), tmp.newFolder("keys"), password = PASSWORD, onKeeperClosed = onKeeperClosed)
        val store = PhotoFileStore(File(tmp.root, "vaults/photos"))
        return Triple(PhotoVault(session, { store }, imaging), store, session)
    }

    private fun newVault(): Pair<PhotoVault, PhotoFileStore> = newVaultWithSession().let { (v, s, _) -> v to s }

    @Test fun importStoresEncryptedFilesAndARow() = runTest {
        val (vault, store) = newVault()
        val r = vault.import(byteArrayOf(1, 2, 3), takenAt = 5) as PhotoVault.ImportResult.Ok
        assertThat(store.dir.list()!!.toList()).containsExactly(r.photo.fileId, "${r.photo.fileId}.t")
        // Padded to the 64 KiB bucket and encrypted: the file is never the 3 plaintext bytes.
        assertThat(store.open(r.photo.fileId, PhotoCipher.Kind.ORIGINAL).readBytes().size).isAtLeast(65_536)
        assertThat(vault.original(r.photo)).isEqualTo(byteArrayOf(1, 2, 3))
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
        assertThat(vault.thumbnail(p)).isNotNull()
        assertThat(vault.cachedThumbnailCount()).isEqualTo(1)
        session.lockPasswords()
        assertThat(vault.cachedThumbnailCount()).isEqualTo(0)
        assertThat(vault.thumbnail(p)).isNull()
    }

    @Test fun aThumbnailDecodedWhileTheKeeperClosesIsNotKept() = runTest {
        val (vault, session) = unlockedWithHook()
        val p = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        imaging.decodes = true
        imaging.duringDecode = { runBlocking { session.lockPasswords() } }
        assertThat(vault.thumbnail(p)).isNull()
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
        store.delete(p.fileId)
        vault.reconcile()
        assertThat(vault.observeCount().first()).isEqualTo(0)
    }

    @Test fun reconcileRemovesFilesWithoutRows() = runTest {
        val (vault, store) = newVault()
        val kept = (vault.import(byteArrayOf(1), null) as PhotoVault.ImportResult.Ok).photo
        val stray = store.newFileId()
        store.write(stray, PhotoCipher.Kind.ORIGINAL) { it.write(1) }
        assertThat(vault.reconcile()).isEqualTo(1)
        assertThat(store.dir.list()!!.toList()).containsExactly(kept.fileId, "${kept.fileId}.t")
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
        const val PASSWORD = "test password"
    }
}
