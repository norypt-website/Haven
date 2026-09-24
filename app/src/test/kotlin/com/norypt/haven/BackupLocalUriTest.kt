package com.norypt.haven

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.norypt.haven.alarm.AlarmRuntime
import com.norypt.haven.backup.BackupManager
import com.norypt.haven.crypto.KeyringStore
import com.norypt.haven.crypto.VaultKeyManager
import com.norypt.haven.security.GuessThrottle
import com.norypt.haven.session.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupLocalUriTest {
    private fun manager(): BackupManager {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Test-only KDF: the native Argon2 binding cannot load on the JVM; the URI policy under test never derives keys anyway.
        val fakeKdf = object : com.norypt.haven.crypto.PasswordKeyDerivation {
            override fun deriveKey(password: ByteArray, salt: ByteArray, params: com.norypt.haven.crypto.Argon2Params, outputLength: Int): ByteArray = ByteArray(outputLength)
        }
        val keys = VaultKeyManager(KeyringStore(File(ctx.cacheDir, "k.json")), fakeKdf, com.norypt.haven.crypto.AndroidKeystoreWrappingKeys(true))
        val session = VaultSession(ctx, keys, AlarmRuntime.get(ctx), GuessThrottle()) {}
        return BackupManager(ctx, session, AlarmRuntime.get(ctx))
    }

    @Test fun onlyLocalDocumentProvidersAreAccepted() {
        val m = manager()
        assertThat(m.isLocalUri(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FHaven%2Fa.hvbk"))).isTrue()
        assertThat(m.isLocalUri(Uri.parse("content://com.android.providers.downloads.documents/document/1234"))).isTrue()
        assertThat(m.isLocalUri(Uri.parse("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded"))).isFalse()
        assertThat(m.isLocalUri(Uri.parse("content://com.microsoft.skydrive.content.StorageAccessProvider/document/x"))).isFalse()
        assertThat(m.isLocalUri(Uri.parse("content://com.dropbox.product.android.dbapp.document_provider.documents/document/x"))).isFalse()
        assertThat(m.isLocalUri(Uri.parse("file:///sdcard/Download/a.hvbk"))).isFalse()
        assertThat(m.isLocalUri(Uri.parse("https://example.com/a.hvbk"))).isFalse()
    }

    @Test fun restoreRefusesNonLocalUriBeforeTouchingAnything() = runBlocking {
        val m = manager()
        val r = m.restore(Uri.parse("content://com.google.android.apps.docs.storage/document/x"), "pw".toCharArray(), com.norypt.haven.backup.BackupKey.generate(), false)
        assertThat(r).isInstanceOf(com.norypt.haven.backup.RestoreResult.Failure::class.java)
        val insp = m.inspect(Uri.parse("content://com.google.android.apps.docs.storage/document/x"))
        assertThat(insp.isFailure).isTrue()
    }
}
