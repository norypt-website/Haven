package com.norypt.haven.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.crypto.Argon2Params
import com.norypt.haven.photos.FakeImaging
import com.norypt.haven.photos.PhotoFileStore
import com.norypt.haven.photos.PhotoVault
import com.norypt.haven.photos.row
import com.norypt.haven.session.VaultSession
import com.norypt.haven.session.fullyUnlockedTestSession
import com.norypt.haven.storage.content.NoteEntity
import com.norypt.haven.storage.passwords.PhotoItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Export and restore through the real session, keeper, photo vault and container; only Argon2 and image decoding are faked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupPhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = AlarmRuntime.resetForTesting()

    /** SHA-256 stand-in for Argon2id; the native library cannot load on the JVM. */
    private class TestBackupKdf : PasswordKdf {
        override fun deriveKey(password: ByteArray, salt: ByteArray, params: KdfParams, outputLength: Int): ByteArray =
            MessageDigest.getInstance("SHA-256").run { update(password); update(salt); digest() }.copyOf(outputLength)
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var session: VaultSession
    private lateinit var vault: PhotoVault
    private lateinit var store: PhotoFileStore
    private lateinit var manager: BackupManager
    private val imaging = FakeImaging()
    private val passphrase = "backup passphrase"
    private val params = Argon2Params.RFC9106_MEMORY_CONSTRAINED

    /** Photos the manager reported as activity (one per photo written or restored). */
    private var activity = 0

    private fun setUp() {
        session = fullyUnlockedTestSession(app, tmp.newFolder("keys"))
        store = PhotoFileStore(File(tmp.root, "vaults/photos"))
        vault = PhotoVault(session, { store }, imaging)
        manager = BackupManager(app, session, AlarmRuntime.get(app), vault, TestBackupKdf(), onActivity = { activity++ })
    }

    /** A readable fake image of [size] bytes (FakeImaging accepts anything starting with 1). */
    private fun image(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }.also { it[0] = 1 }

    private suspend fun import(bytes: ByteArray): PhotoItem = (vault.import(bytes.copyOf(), null) as PhotoVault.ImportResult.Ok).photo

    private suspend fun export(includeVault: Boolean = true): ByteArray =
        ByteArrayOutputStream().also { manager.writeBackup(it, passphrase.toByteArray(), includeVault, params) }.toByteArray()

    private var uriCount = 0
    private suspend fun restore(file: ByteArray, restoreVault: Boolean = true): RestoreResult {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Ab${uriCount++}.hvbk")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(file))
        return manager.restore(uri, passphrase.toCharArray(), manager.backupKey(), restoreVault)
    }

    private suspend fun notes() = session.content().notes().all().map { it.id to it.body }
    private fun stagingDir() = File(store.dir.parentFile, "photos.staging")
    private fun liveFiles() = store.dir.list().orEmpty().toSet()

    /** The backup's plaintext payload, decrypted with the same credentials. */
    private suspend fun plaintext(file: ByteArray): Pair<BackupHeader, ByteArray> {
        val opened = BackupReader(TestBackupKdf()).open(ByteArrayInputStream(file), passphrase.toByteArray(), manager.backupKey())
        return opened.header to opened.plaintext.readBytes()
    }

    @Test fun exportAndRestoreBringBackNotesAndPhotosUnderNewKeys() = runTest {
        setUp()
        val c = session.content()
        c.notes().upsert(NoteEntity("n1", "Title", "kept", 1, 2))
        val big = image(1_500_000, 3)
        val small = image(10, 4)
        val p1 = import(big); val p2 = import(small)
        val oldDeks = listOf(vault.row(p1.id).dek.copyOf(), vault.row(p2.id).dek.copyOf())
        val file = export()

        // Change everything, then restore.
        c.notes().deleteEverything(); c.notes().upsert(NoteEntity("n2", "Other", "gone", 3, 4))
        vault.delete(listOf(p1.id, p2.id))
        val q = import(image(20, 5))
        val qFile = vault.row(q.id).fileId

        val r = restore(file) as RestoreResult.Success
        assertThat(r.notes).isEqualTo(1)
        assertThat(r.photos).isEqualTo(2)
        assertThat(r.photosWithoutPreview).isEqualTo(0)
        assertThat(notes()).containsExactly("n1" to "kept")
        val now = vault.rowsForTesting()
        assertThat(now.map { it.id }).containsExactly(p1.id, p2.id)
        assertThat(vault.original(p1.id)).isEqualTo(big)
        assertThat(vault.original(p2.id)).isEqualTo(small)
        now.forEach { e -> oldDeks.forEach { assertThat(e.dek).isNotEqualTo(it) } }
        assertThat(liveFiles()).containsExactlyElementsIn(now.flatMap { listOf(it.fileId, "${it.fileId}.t") })
        assertThat(liveFiles()).doesNotContain(qFile)
        assertThat(stagingDir().exists()).isFalse()
    }

    @Test fun exportsAreRealFormatTwoWithoutKeysOrFileIds() = runTest {
        setUp()
        session.content().notes().upsert(NoteEntity("n1", "Title", "body", 1, 2))
        val p = import(image(10, 1))
        val pFile = vault.row(p.id).fileId
        val (header, plain) = plaintext(export())
        assertThat(header.payloadFormat).isEqualTo(2)
        assertThat(header.contents).containsExactly("content", "passwords", "photos")
        val seen = mutableListOf<String>()
        val payload = PayloadV2.read(ByteArrayInputStream(plain)) { rec, _ -> seen += rec.id }
        assertThat(payload.format).isEqualTo(2)
        assertThat(payload.content!!.notes.single().body).isEqualTo("body")
        assertThat(seen).containsExactly(p.id)
        val jsonLength = java.nio.ByteBuffer.wrap(plain, 0, 4).int
        val json = String(plain, 4, jsonLength, Charsets.UTF_8)
        assertThat(json).doesNotContain(pFile)
        assertThat(json).doesNotContain("dek")
    }

    @Test fun exportWithoutTheVaultCarriesNoPhotos() = runTest {
        setUp()
        import(image(10, 1))
        val (header, plain) = plaintext(export(includeVault = false))
        assertThat(header.contents).containsExactly("content")
        val payload = PayloadV2.read(ByteArrayInputStream(plain)) { _, _ -> error("no photo expected") }
        assertThat(payload.passwords).isNull()
    }

    @Test fun aRestoreWhosePhotoSectionFailsMidStreamChangesNothing() = runTest {
        setUp()
        val c = session.content()
        c.notes().upsert(NoteEntity("n1", "Title", "from backup", 1, 2))
        val p1 = import(image(1_500_000, 3)); val p2 = import(image(1_500_000, 4))
        val file = export()
        c.notes().deleteEverything(); c.notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        vault.delete(listOf(p1.id, p2.id))
        val current = image(30, 9)
        val q = import(current)
        val filesBefore = liveFiles()

        // One flipped bit in the last ciphertext segment: the first photo has already been staged when it is found.
        val corrupt = file.copyOf().also { it[it.size - 64 - 1_000] = (it[it.size - 64 - 1_000].toInt() xor 1).toByte() }
        val r = restore(corrupt)
        assertThat(r).isEqualTo(RestoreResult.Failure("The passphrase or backup key is wrong, or the file was modified."))

        assertThat(notes()).containsExactly("n2" to "current")
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(q.id)
        assertThat(vault.original(q.id)).isEqualTo(current)
        assertThat(liveFiles()).isEqualTo(filesBefore)
        assertThat(stagingDir().exists()).isFalse()
    }

    @Test fun aTruncatedBackupChangesNothing() = runTest {
        setUp()
        import(image(1_500_000, 3))
        val file = export()
        session.content().notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        val filesBefore = liveFiles()
        val r = restore(file.copyOf(file.size - 200_000))
        assertThat(r).isInstanceOf(RestoreResult.Failure::class.java)
        assertThat(notes()).containsExactly("n2" to "current")
        assertThat(liveFiles()).isEqualTo(filesBefore)
        assertThat(stagingDir().exists()).isFalse()
    }

    @Test fun aPhotoThisPhoneCannotDecodeIsRestoredWithoutAPreview() = runTest {
        setUp()
        // Written by hand: a well-formed format 2 file whose second photo this phone cannot decode.
        val payload = BackupPayload(
            content = ContentSnapshot(emptyList(), emptyList(), emptyList(), notes = listOf(NoteRecord("n1", "T", "from backup", 1, 2))),
            passwords = PasswordSnapshot(
                emptyList(), emptyList(),
                photos = listOf(PhotoRecord("ok", "image/jpeg", 2, 40, 30, null, 0), PhotoRecord("x", "image/heic", 3, 8, 6, null, 0)),
            ),
        )
        val file = writeFile(2) { PayloadV2.write(it, payload) { rec -> if (rec.id == "ok") byteArrayOf(1, 2) else byteArrayOf(7, 7, 7) } }
        session.content().notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        import(image(30, 9))
        val r = restore(file) as RestoreResult.Success
        assertThat(r.photos).isEqualTo(2)
        assertThat(r.photosWithoutPreview).isEqualTo(1)
        assertThat(notes()).containsExactly("n1" to "from backup")
        assertThat(vault.observeAll().first().map { it.id }).containsExactly("ok", "x")
        assertThat(vault.original("x")).isEqualTo(byteArrayOf(7, 7, 7))
        assertThat(vault.row("x").mimeType).isEqualTo("image/heic")
        assertThat(liveFiles()).doesNotContain("${vault.row("x").fileId}.t")
        assertThat(stagingDir().exists()).isFalse()
        // The next unlock's cleanup keeps it.
        assertThat(vault.reconcile()).isEqualTo(0)
        assertThat(vault.original("x")).isEqualTo(byteArrayOf(7, 7, 7))
    }

    @Test fun everyPhotoWrittenOrRestoredCountsAsActivity() = runTest {
        setUp()
        import(image(10, 1)); import(image(10, 2)); import(image(10, 3))
        activity = 0
        val file = export()
        assertThat(activity).isEqualTo(3)
        activity = 0
        restore(file) as RestoreResult.Success
        assertThat(activity).isEqualTo(3)
    }

    @Test fun aDamagedPhotoStopsTheExportWithAClearMessage() = runTest {
        setUp()
        import(image(10, 1))
        val damaged = import(image(10, 2))
        val f = File(store.dir, vault.row(damaged.id).fileId)
        f.writeBytes(f.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        val e = runCatching { export() }.exceptionOrNull()
        assertThat(e).isInstanceOf(PhotoVault.DamagedPhotoException::class.java)
        assertThat(BackupManager.exportFailureMessage(e!!, includePasswords = true))
            .isEqualTo("One hidden photo is damaged and could not be read. Delete it from the Vault (it shows no preview) and try again.")
        // A keeper that closed mid-export is still told apart.
        assertThat(BackupManager.exportFailureMessage(IllegalStateException("keeper closed"), includePasswords = true))
            .isEqualTo("The password keeper was locked during the backup. No file was kept.")
    }

    private suspend fun writeFile(payloadFormat: Int, body: (OutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { out ->
        BackupWriter(TestBackupKdf()).write(
            output = out, passphrase = passphrase.toByteArray(), backupKey = manager.backupKey(),
            params = KdfParams.DEFAULT, appVersionCode = 1, contents = listOf("content", "passwords"),
            createdAtEpochMs = 0, payloadFormat = payloadFormat, writePayload = body,
        )
    }.toByteArray()

    private val formatOne = BackupPayload(
        format = 1,
        content = ContentSnapshot(emptyList(), emptyList(), emptyList()),
        passwords = PasswordSnapshot(listOf(FolderRecord("f", "Folder", 0)), emptyList()),
    )

    @Test fun formatOneBackupsStillRestoreAndLeavePhotosAlone() = runTest {
        setUp()
        val q = import(image(30, 9))
        session.content().notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        val r = restore(writeFile(1) { BackupPayloadJson.encode(formatOne, it) }) as RestoreResult.Success
        assertThat(r.notes).isEqualTo(0)
        assertThat(r.passwords).isEqualTo(0)
        assertThat(r.photos).isNull() // none replaced: a format 1 backup keeps the photos here
        assertThat(notes()).isEmpty()
        assertThat(session.passwords().folders().all().map { it.id }).containsExactly("f")
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(q.id)
    }

    @Test fun aPayloadFormatThatDisagreesWithTheHeaderIsRefused() = runTest {
        setUp()
        session.content().notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        // Header says 2, body is plain JSON (what the writer produced before format 2 was wired up).
        val plainJsonLabelledTwo = writeFile(2) { BackupPayloadJson.encode(formatOne.copy(format = 2), it) }
        assertThat(restore(plainJsonLabelledTwo)).isInstanceOf(RestoreResult.Failure::class.java)
        // Header says 1, JSON says 2.
        val mismatch = writeFile(1) { BackupPayloadJson.encode(formatOne.copy(format = 2), it) }
        assertThat(restore(mismatch)).isEqualTo(RestoreResult.Failure("The file is not a valid Haven backup (Malformed)."))
        assertThat(notes()).containsExactly("n2" to "current")
    }

    @Test fun anExportFailsWhenTheKeeperClosesPartWay() = runTest {
        setUp()
        import(image(1_500_000, 3)); import(image(1_500_000, 4))
        var written = 0L
        var locked = false
        val out = object : FilterOutputStream(ByteArrayOutputStream()) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len); written += len
                if (!locked && written > 1_000_000) { locked = true; runBlocking { session.lockPasswords() } }
            }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { manager.writeBackup(out, passphrase.toByteArray(), true, params) } }
        assertThat(locked).isTrue()
    }

    @Test fun cancellingARestoreWhileItReadsChangesNothing() = runTest {
        setUp()
        val c = session.content()
        c.notes().upsert(NoteEntity("n1", "Title", "from backup", 1, 2))
        val p1 = import(image(1_000, 3)); val p2 = import(image(1_000, 4))
        val file = export()
        c.notes().deleteEverything(); c.notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        vault.delete(listOf(p1.id, p2.id))
        val q = import(image(30, 9))
        val filesBefore = liveFiles()
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Acancel.hvbk")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(file))
        val key = manager.backupKey()
        runBlocking {
            lateinit var job: kotlinx.coroutines.Job
            // The screen goes away while the first restored photo is being staged.
            imaging.duringThumbnail = { job.cancel() }
            job = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                manager.restore(uri, passphrase.toCharArray(), key, true)
            }
            job.start()
            job.join()
            assertThat(job.isCancelled).isTrue()
        }
        imaging.duringThumbnail = {}
        assertThat(notes()).containsExactly("n2" to "current")
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(q.id)
        assertThat(liveFiles()).isEqualTo(filesBefore)
        assertThat(stagingDir().exists()).isFalse()
    }
}
