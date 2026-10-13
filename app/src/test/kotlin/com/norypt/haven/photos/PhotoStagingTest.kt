package com.norypt.haven.photos

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.backup.PhotoRecord
import com.norypt.haven.session.UnlockResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    private fun newVault() = newTestVault(tmp).let { (v, s, _) -> v to s }
    private fun stagingDir(store: PhotoFileStore) = File(store.dir.parentFile, "photos.staging")

    @Test fun commitReplacesPhotosWithNewKeys() = runTest {
        val (vault, store) = newVault()
        val old = (vault.import(byteArrayOf(1, 5), null) as PhotoVault.ImportResult.Ok).photo
        val oldDek = vault.row(old.id).dek.copyOf()
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        vault.commitStaging(staging)
        val now = vault.rowsForTesting()
        assertThat(now.map { it.id }).containsExactly("r1")
        assertThat(now.single().dek).isNotEqualTo(oldDek)
        assertThat(now.single().addedAt).isEqualTo(7)
        assertThat(vault.original("r1")).isEqualTo(byteArrayOf(1, 6))
        assertThat(store.dir.list()!!.toList()).containsExactly(now.single().fileId, "${now.single().fileId}.t")
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun failedRestoreKeepsOldPhotos() = runTest {
        val (vault, store) = newVault()
        val old = (vault.import(byteArrayOf(1, 5), null) as PhotoVault.ImportResult.Ok).photo
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        staging.discard()
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(old.id)
        assertThat(vault.original(old.id)).isEqualTo(byteArrayOf(1, 5))
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun aPhotoThisPhoneCannotDecodeIsKeptWithoutAThumbnail() = runTest {
        val (vault, store) = newVault()
        val staging = vault.beginStaging()
        // FakeImaging cannot read bytes that do not start with 1: like a format this phone lacks a decoder for.
        staging.add(PhotoRecord("r", "image/heic", 3, 12, 9, 5, 6), byteArrayOf(7, 8, 9))
        assertThat(staging.withoutPreview).isEqualTo(1)
        assertThat(vault.commitStaging(staging)).isEqualTo(1)
        val row = vault.row("r")
        assertThat(row.mimeType).isEqualTo("image/heic")
        assertThat(row.byteSize).isEqualTo(3)
        assertThat(row.width).isEqualTo(12)
        assertThat(row.height).isEqualTo(9)
        assertThat(row.takenAt).isEqualTo(5)
        assertThat(store.dir.list()!!.toList()).containsExactly(row.fileId)
        assertThat(vault.thumbnail("r")).isNull()
        assertThat(vault.original("r")).isEqualTo(byteArrayOf(7, 8, 9))
        // Reconcile keeps it: a photo is its original.
        assertThat(vault.reconcile()).isEqualTo(0)
        assertThat(vault.observeAll().first().map { it.id }).containsExactly("r")
        assertThat(vault.original("r")).isEqualTo(byteArrayOf(7, 8, 9))
    }

    @Test fun anUndecodablePhotoWithAStrangeTypeIsStoredAsAnImage() = runTest {
        val (vault, _) = newVault()
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r", "text/html", 1, -4, 9, null, 0), byteArrayOf(7))
        vault.commitStaging(staging)
        assertThat(vault.row("r").mimeType).isEqualTo("image/jpeg")
        assertThat(vault.row("r").width).isEqualTo(0)
    }

    @Test fun stagingRefusesEmptyBytes() = runTest {
        val (vault, store) = newVault()
        val staging = vault.beginStaging()
        assertThrows(IllegalArgumentException::class.java) { staging.add(PhotoRecord("r", "image/jpeg", 0, 1, 1, null, 0), ByteArray(0)) }
        staging.discard()
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun addConsumesTheArrayBeforeReturning() = runTest {
        // The backup reader zeroes the array as soon as the callback returns.
        val (vault, _) = newVault()
        val staging = vault.beginStaging()
        val bytes = byteArrayOf(1, 6, 8)
        staging.add(PhotoRecord("r1", "image/jpeg", 3, 40, 30, null, 7), bytes)
        bytes.fill(0)
        vault.commitStaging(staging)
        assertThat(vault.original("r1")).isEqualTo(byteArrayOf(1, 6, 8))
    }

    @Test fun commitIntoAKeeperOpenedAgainIsRefused() = runTest {
        val (vault, store, session) = newTestVault(tmp)
        val old = (vault.import(byteArrayOf(1, 5), null) as PhotoVault.ImportResult.Ok).photo
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        session.lockPasswords()
        assertThat(session.unlockPasswords(TEST_PASSWORD.toCharArray())).isEqualTo(UnlockResult.Success)
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { vault.commitStaging(staging) } }
        staging.discard()
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(old.id)
        val oldFile = vault.row(old.id).fileId
        assertThat(store.dir.list()!!.toList()).containsExactly(oldFile, "$oldFile.t")
    }

    @Test fun reconcileRemovesALeftoverStagingDirectory() = runTest {
        val (vault, store) = newVault()
        val leftover = PhotoFileStore(stagingDir(store))
        leftover.write(leftover.newFileId(), com.norypt.haven.crypto.PhotoCipher.Kind.ORIGINAL) { it.write(1) }
        vault.reconcile()
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun reconcileLeavesARunningRestoreAlone() = runTest {
        val (vault, store) = newVault()
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        vault.reconcile()
        assertThat(vault.commitStaging(staging)).isEqualTo(1)
        assertThat(vault.original("r1")).isEqualTo(byteArrayOf(1, 6))
        assertThat(stagingDir(store).exists()).isFalse()
    }

    /** Every row's original and thumbnail exist, and no other photo file does. */
    private suspend fun assertConsistent(vault: PhotoVault, store: PhotoFileStore) {
        val rows = vault.rowsForTesting()
        assertThat(store.dir.list().orEmpty().toSet()).isEqualTo(rows.flatMap { listOf(it.fileId, "${it.fileId}.t") }.toSet())
    }

    @Test fun aFailureInsideTheTransactionKeepsTheOldSetAndRemovesTheMovedFiles() = runTest {
        val (vault, store) = newVault()
        val old = (vault.import(byteArrayOf(1, 5), null) as PhotoVault.ImportResult.Ok).photo
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { vault.commitStaging(staging) { error("passwords could not be written") } }
        }
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(old.id)
        assertThat(vault.original(old.id)).isEqualTo(byteArrayOf(1, 5))
        assertConsistent(vault, store)
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun cancellingDuringTheTransactionStillLeavesAConsistentSet() = runTest {
        val (vault, store) = newVault()
        vault.import(byteArrayOf(1, 5), null)
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        val inside = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                vault.commitStaging(staging) { inside.complete(Unit); gate.await() }
            }
            inside.await()
            job.cancel() // the screen went away while the swap was committing
            gate.complete(Unit)
            job.join()
        }
        // The swap ran to the end: the new set, with its files.
        assertThat(vault.observeAll().first().map { it.id }).containsExactly("r1")
        assertThat(vault.original("r1")).isEqualTo(byteArrayOf(1, 6))
        assertConsistent(vault, store)
        assertThat(stagingDir(store).exists()).isFalse()
    }
}
