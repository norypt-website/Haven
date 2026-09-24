package com.norypt.haven.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented: exercises the real Android Keystore and the real Argon2id binding.
 * On emulators the reported level is typically TRUSTED_ENVIRONMENT or SOFTWARE; the test records
 * it rather than asserting hardware, except that it asserts the level is NEVER upgraded.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun keystoreWrapUnwrapRoundTripAndAadBinding() {
        val hw = AndroidKeystoreWrappingKeys(allowSoftwareKeys = true)
        val alias = "haven.test.${System.nanoTime()}"
        val created = hw.create(alias, requireDeviceAuth = false)
        assertEquals(created.level, hw.securityLevelOf(alias))
        val secret = ByteArray(32) { it.toByte() }
        val blob = hw.wrap(alias, secret, "aad-1".toByteArray())
        assertArrayEquals(secret, hw.unwrap(alias, blob, "aad-1".toByteArray()))
        assertThrows(VaultCryptoException.Malformed::class.java) { hw.unwrap(alias, blob, "aad-2".toByteArray()) }
        hw.delete(alias)
        assertThrows(VaultCryptoException.UnrecoverableHardwareKey::class.java) { hw.unwrap(alias, blob, "aad-1".toByteArray()) }
    }

    @Test fun releasePolicyRefusesSoftwareKeys() {
        val strict = AndroidKeystoreWrappingKeys(allowSoftwareKeys = false)
        val alias = "haven.test.strict.${System.nanoTime()}"
        val result = runCatching { strict.create(alias, false) }
        result.onSuccess { assertTrue(it.level == SecurityLevel.STRONGBOX || it.level == SecurityLevel.TRUSTED_ENVIRONMENT); strict.delete(alias) }
        result.onFailure { assertTrue(it is VaultCryptoException.HardwareKeyUnavailable) }
    }

    @Test fun endToEndWithRealArgon2AndKeystore() {
        val dir = File(context.noBackupFilesDir, "test-vaults").apply { mkdirs() }
        val store = KeyringStore(File(dir, "keyring-${System.nanoTime()}.json"))
        val manager = VaultKeyManager(store, Argon2idKeyDerivation(), AndroidKeystoreWrappingKeys(allowSoftwareKeys = true))
        val pw = PasswordNormalizer.toBytes("test pass phrase".toCharArray())
        val t0 = System.nanoTime()
        val keys = manager.initialise(pw, Argon2Params.RFC9106_MEMORY_CONSTRAINED, requireDeviceAuth = false)
        val ms = (System.nanoTime() - t0) / 1_000_000
        android.util.Log.i("HavenTest", "initialise (2x Argon2id 64MiB) took $ms ms; level=${keys.values.first().hwLevel}")
        val expected = keys.getValue(VaultIds.CONTENT).bytes().copyOf()
        keys.values.forEach { it.destroy() }
        val opened = manager.open(VaultIds.CONTENT, pw)
        assertArrayEquals(expected, opened.bytes())
        opened.destroy()
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { manager.open(VaultIds.CONTENT, "wrong".toByteArray()) }
        manager.eraseAll()
    }
}
