package com.norypt.haven.photos

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.backup.PhotoRecord
import com.norypt.haven.session.UnlockResult
import kotlinx.coroutines.flow.first
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
        val oldDek = old.dek.copyOf()
        val staging = vault.beginStaging()
        staging.add(PhotoRecord("r1", "image/jpeg", 2, 40, 30, null, 7), byteArrayOf(1, 6))
        vault.commitStaging(staging)
        val now = vault.observeAll().first()
        assertThat(now.map { it.id }).containsExactly("r1")
        assertThat(now.single().dek).isNotEqualTo(oldDek)
        assertThat(now.single().addedAt).isEqualTo(7)
        assertThat(vault.original(now.single())).isEqualTo(byteArrayOf(1, 6))
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
        assertThat(vault.original(old)).isEqualTo(byteArrayOf(1, 5))
        assertThat(stagingDir(store).exists()).isFalse()
    }

    @Test fun stagingRefusesAnUnreadableImage() = runTest {
        val (vault, store) = newVault()
        val staging = vault.beginStaging()
        assertThrows(IllegalArgumentException::class.java) { staging.add(PhotoRecord("r", "image/jpeg", 1, 1, 1, null, 0), byteArrayOf(7)) }
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
        assertThat(vault.original(vault.observeAll().first().single())).isEqualTo(byteArrayOf(1, 6, 8))
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
        assertThat(store.dir.list()!!.toList()).containsExactly(old.fileId, "${old.fileId}.t")
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
        assertThat(vault.original(vault.observeAll().first().single())).isEqualTo(byteArrayOf(1, 6))
        assertThat(stagingDir(store).exists()).isFalse()
    }
}
