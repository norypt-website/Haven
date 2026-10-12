package com.norypt.haven.photos

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import com.norypt.haven.session.VaultSession
import com.norypt.haven.session.unlockedTestSession
import org.junit.rules.TemporaryFolder
import java.io.File

/** Image decoding stand-in: bytes starting with 1 are a 40x30 JPEG; anything else is unreadable. */
class FakeImaging : PhotoImaging {
    /** Runs inside [thumbnail], in the middle of an import. */
    var duringThumbnail: () -> Unit = {}
    /** Runs inside [decode], after the thumbnail was decrypted. */
    var duringDecode: () -> Unit = {}
    var decodes: Boolean = false
    override fun probe(bytes: ByteArray) = if (bytes.isNotEmpty() && bytes[0] == 1.toByte()) Probe(40, 30, "image/jpeg") else null
    override fun thumbnail(bytes: ByteArray): ByteArray { duringThumbnail(); return byteArrayOf(9, 9) }
    override fun decode(bytes: ByteArray, maxSide: Int): ImageBitmap? { duringDecode(); return if (decodes) android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() else null }
}

const val TEST_PASSWORD = "test password"

/** A real session with an unlocked file-backed keeper and a photo vault under [tmp]; only image decoding is faked. */
fun newTestVault(
    tmp: TemporaryFolder,
    imaging: PhotoImaging = FakeImaging(),
    onKeeperClosed: () -> Unit = {},
): Triple<PhotoVault, PhotoFileStore, VaultSession> {
    val session = unlockedTestSession(ApplicationProvider.getApplicationContext(), tmp.newFolder(), password = TEST_PASSWORD, onKeeperClosed = onKeeperClosed)
    val store = PhotoFileStore(File(tmp.root, "vaults/photos"))
    return Triple(PhotoVault(session, { store }, imaging), store, session)
}
