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
import com.norypt.haven.session.VaultSession
import com.norypt.haven.session.fullyUnlockedTestSession
import com.norypt.haven.storage.content.NoteEntity
import com.norypt.haven.storage.passwords.PhotoEntity
import kotlinx.coroutines.flow.first
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
    private val passphrase = "backup passphrase"
    private val params = Argon2Params.RFC9106_MEMORY_CONSTRAINED

    private fun setUp() {
        session = fullyUnlockedTestSession(app, tmp.newFolder("keys"))
        store = PhotoFileStore(File(tmp.root, "vaults/photos"))
        vault = PhotoVault(session, { store }, FakeImaging())
        manager = BackupManager(app, session, AlarmRuntime.get(app), vault, TestBackupKdf())
    }

    /** A readable fake image of [size] bytes (FakeImaging accepts anything starting with 1). */
    private fun image(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }.also { it[0] = 1 }

    private suspend fun import(bytes: ByteArray): PhotoEntity = (vault.import(bytes.copyOf(), null) as PhotoVault.ImportResult.Ok).photo

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
        val oldDeks = listOf(p1.dek.copyOf(), p2.dek.copyOf())
        val file = export()

        // Change everything, then restore.
        c.notes().deleteEverything(); c.notes().upsert(NoteEntity("n2", "Other", "gone", 3, 4))
        vault.delete(listOf(p1.id, p2.id))
        val q = import(image(20, 5))

        val r = restore(file) as RestoreResult.Success
        assertThat(r.notes).isEqualTo(1)
        assertThat(r.photos).isEqualTo(2)
        assertThat(notes()).containsExactly("n1" to "kept")
        val now = vault.observeAll().first()
        assertThat(now.map { it.id }).containsExactly(p1.id, p2.id)
        assertThat(vault.original(now.single { it.id == p1.id })).isEqualTo(big)
        assertThat(vault.original(now.single { it.id == p2.id })).isEqualTo(small)
        now.forEach { e -> oldDeks.forEach { assertThat(e.dek).isNotEqualTo(it) } }
        assertThat(liveFiles()).containsExactlyElementsIn(now.flatMap { listOf(it.fileId, "${it.fileId}.t") })
        assertThat(liveFiles()).doesNotContain(q.fileId)
        assertThat(stagingDir().exists()).isFalse()
    }

    @Test fun exportsAreRealFormatTwoWithoutKeysOrFileIds() = runTest {
        setUp()
        session.content().notes().upsert(NoteEntity("n1", "Title", "body", 1, 2))
        val p = import(image(10, 1))
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
        assertThat(json).doesNotContain(p.fileId)
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
        assertThat(vault.original(vault.observeAll().first().single())).isEqualTo(current)
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

    @Test fun anUnreadablePhotoInTheBackupChangesNothing() = runTest {
        setUp()
        // Written by hand: a well-formed format 2 file whose photo bytes are not an image.
        val payload = BackupPayload(
            content = ContentSnapshot(emptyList(), emptyList(), emptyList(), notes = listOf(NoteRecord("n1", "T", "from backup", 1, 2))),
            passwords = PasswordSnapshot(emptyList(), emptyList(), photos = listOf(PhotoRecord("x", "image/jpeg", 3, 1, 1, null, 0))),
        )
        val file = writeFile(2) { PayloadV2.write(it, payload) { byteArrayOf(7, 7, 7) } }
        session.content().notes().upsert(NoteEntity("n2", "Other", "current", 3, 4))
        val q = import(image(30, 9))
        val r = restore(file)
        assertThat(r).isEqualTo(RestoreResult.Failure("A photo in the backup could not be read. Nothing was changed."))
        assertThat(notes()).containsExactly("n2" to "current")
        assertThat(vault.observeAll().first().map { it.id }).containsExactly(q.id)
        assertThat(stagingDir().exists()).isFalse()
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
        assertThat(r.photos).isEqualTo(0)
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
}
