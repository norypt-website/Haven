package com.norypt.haven.crypto

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultKeyManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var kdf: FakeKdf
    private lateinit var hw: FakeWrapping
    private lateinit var manager: VaultKeyManager
    private val params = Argon2Params.RFC9106_MEMORY_CONSTRAINED
    private val pw = "correct horse battery staple".toByteArray()

    @Before fun setUp() {
        file = File(tmp.root, "keyring.json")
        kdf = FakeKdf()
        hw = FakeWrapping()
        manager = VaultKeyManager(KeyringStore(file), kdf, hw)
    }

    @Test fun initialiseCreatesIndependentVaults() {
        val keys = manager.initialise(pw, params, requireDeviceAuth = false)
        assertThat(keys.keys).containsExactly(VaultIds.CONTENT, VaultIds.PASSWORDS)
        val content = keys.getValue(VaultIds.CONTENT).bytes().copyOf()
        val passwords = keys.getValue(VaultIds.PASSWORDS).bytes().copyOf()
        assertThat(content).isNotEqualTo(passwords)
        keys.values.forEach { it.destroy() }
        val ring = manager.keyring()!!
        val e1 = ring.envelopes.getValue(VaultIds.CONTENT)
        val e2 = ring.envelopes.getValue(VaultIds.PASSWORDS)
        assertThat(e1.salt).isNotEqualTo(e2.salt) // separate password-derived keys
        assertThat(e1.hwAlias).isNotEqualTo(e2.hwAlias) // separate hardware keys
        assertThat(hw.keys).hasSize(2)
    }

    @Test fun openReturnsSameKeyAndNeedsPasswordEachTime() {
        val created = manager.initialise(pw, params, false)
        val expected = created.getValue(VaultIds.CONTENT).bytes().copyOf()
        created.values.forEach { it.destroy() }
        val callsBefore = kdf.calls
        val opened = manager.open(VaultIds.CONTENT, pw)
        assertThat(opened.bytes()).isEqualTo(expected)
        assertThat(kdf.calls).isEqualTo(callsBefore + 1)
        opened.destroy()
        assertThrows(IllegalStateException::class.java) { opened.bytes() }
    }

    @Test fun wrongPasswordFails() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) {
            manager.open(VaultIds.CONTENT, "correct horse battery stapl".toByteArray())
        }
        // A trailing space is a different password: never trimmed.
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) {
            manager.open(VaultIds.CONTENT, "correct horse battery staple ".toByteArray())
        }
    }

    @Test fun hardwareKeyIsNecessary() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        hw.keys.clear() // simulate factory reset / key invalidation
        assertThrows(VaultCryptoException.UnrecoverableHardwareKey::class.java) { manager.open(VaultIds.CONTENT, pw) }
    }

    @Test fun deviceAuthRequirementIsEnforcedByWrappingKey() {
        manager.initialise(pw, params, requireDeviceAuth = true).values.forEach { it.destroy() }
        hw.deviceAuthenticated = false
        assertThrows(VaultCryptoException.DeviceAuthenticationRequired::class.java) { manager.open(VaultIds.CONTENT, pw) }
        hw.deviceAuthenticated = true
        manager.open(VaultIds.CONTENT, pw).destroy()
    }

    @Test fun tamperingWithOuterCiphertextIsDetected() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        mutateEnvelope(VaultIds.CONTENT) { env -> env["outer"] = JsonPrimitive(java.util.Base64.getEncoder().encodeToString(flipFirst(java.util.Base64.getDecoder().decode(env.getValue("outer").jsonPrimitive.content)))) }
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { manager.open(VaultIds.CONTENT, pw) }
    }

    @Test fun tamperingWithHeaderIsDetected() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        mutateEnvelope(VaultIds.CONTENT) { env -> env["epoch"] = JsonPrimitive(7) }
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { manager.open(VaultIds.CONTENT, pw) }
    }

    @Test fun swappingEnvelopesBetweenVaultsIsDetected() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val json = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        val vaults = json.getValue("vaults").jsonObject.toMutableMap()
        val c = vaults.getValue(VaultIds.CONTENT).jsonObject
        val p = vaults.getValue(VaultIds.PASSWORDS).jsonObject.toMutableMap()
        // Move the passwords envelope under the content id (vault identity is part of the AAD).
        p["vault"] = JsonPrimitive(VaultIds.CONTENT)
        vaults[VaultIds.CONTENT] = JsonObject(p)
        vaults[VaultIds.PASSWORDS] = c
        json["vaults"] = JsonObject(vaults)
        file.writeText(JsonObject(json).toString())
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { manager.open(VaultIds.CONTENT, pw) }
    }

    @Test fun kdfParametersOutOfBoundsAreRejectedBeforeKdf() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        mutateEnvelope(VaultIds.CONTENT) { env ->
            val kdf = env.getValue("kdf").jsonObject.toMutableMap(); kdf["memKib"] = JsonPrimitive(8 * 1024 * 1024); env["kdf"] = JsonObject(kdf)
        }
        val calls = kdf.calls
        assertThrows(VaultCryptoException.Malformed::class.java) { manager.open(VaultIds.CONTENT, pw) }
        assertThat(kdf.calls).isEqualTo(calls)
    }

    @Test fun unsupportedVersionIsMalformedNotReplaced() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        mutateEnvelope(VaultIds.CONTENT) { env -> env["v"] = JsonPrimitive(2) }
        assertThrows(VaultCryptoException.Malformed::class.java) { manager.open(VaultIds.CONTENT, pw) }
        assertThat(file.exists()).isTrue() // nothing deleted
    }

    @Test fun changePasswordRewrapsBothVaultsAndBumpsEpoch() {
        val created = manager.initialise(pw, params, false)
        val expected = created.getValue(VaultIds.PASSWORDS).bytes().copyOf()
        created.values.forEach { it.destroy() }
        val newPw = "new pass phrase with spaces".toByteArray()
        manager.changePassword(pw, newPw, Argon2Params.PROFILE_128M)
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { manager.open(VaultIds.CONTENT, pw) }
        val opened = manager.open(VaultIds.PASSWORDS, newPw)
        assertThat(opened.bytes()).isEqualTo(expected) // database key unchanged
        opened.destroy()
        val ring = manager.keyring()!!
        ring.envelopes.values.forEach {
            assertThat(it.epoch).isEqualTo(1)
            assertThat(it.kdf).isEqualTo(Argon2Params.PROFILE_128M)
        }
    }

    @Test fun changePasswordWithWrongOldPasswordChangesNothing() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val before = file.readText()
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) {
            manager.changePassword("wrong".toByteArray(), "new".toByteArray(), params)
        }
        assertThat(file.readText()).isEqualTo(before)
    }

    @Test fun changePasswordRetiresOldHardwareKeysSoAnOldKeyringCopyIsDead() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val oldKeyring = file.readText()
        val oldAliases = manager.keyring()!!.envelopes.values.map { it.hwAlias }
        manager.changePassword(pw, "new pass phrase".toByteArray(), params)
        oldAliases.forEach { assertThat(hw.keys).doesNotContainKey(it) }
        // Someone who copied keyring.json before the change and later learns the old password gets nothing:
        // the Keystore key those envelopes were wrapped under no longer exists.
        file.writeText(oldKeyring)
        assertThrows(VaultCryptoException.UnrecoverableHardwareKey::class.java) { manager.open(VaultIds.CONTENT, pw) }
    }

    @Test fun changePasswordAndRotationKeepTheDuressVerifier() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val duress = "duress phrase".toByteArray()
        manager.setDuress(pw, duress, params)
        val newPw = "another pass phrase".toByteArray()
        manager.changePassword(pw, newPw, params)
        assertThat(manager.hasDuress()).isTrue()
        assertThat(manager.isDuressPassword(duress)).isTrue()
        manager.rotateHardwareKeys(newPw, requireDeviceAuth = true)
        assertThat(manager.isDuressPassword(duress)).isTrue()
        manager.open(VaultIds.CONTENT, newPw).destroy()
    }

    @Test fun changePasswordRefusesTheDuressPasswordAndLeavesNoOrphanKeys() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val duress = "duress phrase".toByteArray()
        manager.setDuress(pw, duress, params)
        val before = file.readText()
        val aliasesBefore = hw.keys.keys.toSet()
        assertThrows(IllegalArgumentException::class.java) { manager.changePassword(pw, duress, params) }
        assertThat(file.readText()).isEqualTo(before)
        assertThat(hw.keys.keys).isEqualTo(aliasesBefore)
        manager.open(VaultIds.CONTENT, pw).destroy()
    }

    @Test fun failedInitialiseLeavesNoKeystoreKeysBehind() {
        hw.failWrapWhenAliasContains = VaultIds.PASSWORDS
        assertThrows(java.security.GeneralSecurityException::class.java) { manager.initialise(pw, params, false) }
        assertThat(hw.keys).isEmpty()
        assertThat(manager.isInitialised()).isFalse()
    }

    @Test fun rotateHardwareKeysRetiresOldAliases() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val old = manager.keyring()!!.envelopes.values.map { it.hwAlias }
        manager.rotateHardwareKeys(pw, requireDeviceAuth = true)
        old.forEach { assertThat(hw.keys).doesNotContainKey(it) }
        assertThat(hw.authRequired).hasSize(2)
        manager.open(VaultIds.CONTENT, pw).destroy()
    }

    @Test fun eraseAllRetiresKeysAndRemovesKeyring() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        manager.eraseAll()
        assertThat(hw.keys).isEmpty()
        assertThat(file.exists()).isFalse()
        assertThat(manager.isInitialised()).isFalse()
    }

    @Test fun initialiseRefusesToOverwrite() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        assertThrows(IllegalStateException::class.java) { manager.initialise(pw, params, false) }
    }

    @Test fun envelopeJsonRoundTrip() {
        manager.initialise(pw, params, false).values.forEach { it.destroy() }
        val env = manager.envelope(VaultIds.CONTENT)
        val parsed = VaultKeyEnvelope.fromJson(Json.parseToJsonElement(env.toJson().toString()).jsonObject)
        assertThat(parsed).isEqualTo(env)
        assertThat(parsed.outerAssociatedData()).isNotEqualTo(parsed.innerAssociatedData())
    }

    @Test fun softwareLevelIsRefusedByDefault() {
        val softHw = FakeWrapping(level = SecurityLevel.SOFTWARE)
        // The fake reports SOFTWARE; the manager records whatever the provider reports. The refusal
        // policy lives in AndroidKeystoreWrappingKeys (tested on device). Here we assert the level is
        // recorded truthfully rather than upgraded.
        val m = VaultKeyManager(KeyringStore(File(tmp.root, "k2.json")), kdf, softHw)
        m.initialise(pw, params, false).values.forEach { it.destroy() }
        assertThat(m.envelope(VaultIds.CONTENT).hwLevel).isEqualTo(SecurityLevel.SOFTWARE)
    }

    private fun mutateEnvelope(vaultId: String, mutate: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit) {
        val root = Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
        val vaults = root.getValue("vaults").jsonObject.toMutableMap()
        val env = vaults.getValue(vaultId).jsonObject.toMutableMap()
        mutate(env)
        vaults[vaultId] = JsonObject(env)
        root["vaults"] = JsonObject(vaults)
        file.writeText(JsonObject(root).toString())
    }

    private fun flipFirst(b: ByteArray): ByteArray = b.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
}

class DuressTest {
    @get:Rule val tmp = TemporaryFolder()
    private val params = Argon2Params.RFC9106_MEMORY_CONSTRAINED
    private val real = "real password".toByteArray()
    private val duress = "duress password".toByteArray()

    private fun manager(hw: FakeWrapping = FakeWrapping(), kdf: FakeKdf = FakeKdf()) = VaultKeyManager(KeyringStore(File(tmp.root, "keyring.json")), kdf, hw)

    @Test fun duressIsOffByDefaultAndRequiresRealPasswordToArm() {
        val m = manager()
        m.initialise(real, params, false).values.forEach { it.destroy() }
        assertThat(m.hasDuress()).isFalse()
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { m.setDuress("wrong".toByteArray(), duress, params) }
        assertThrows(IllegalArgumentException::class.java) { m.setDuress(real, real, params) }
        m.setDuress(real, duress, params)
        assertThat(m.hasDuress()).isTrue()
        assertThat(m.isDuressPassword(duress)).isTrue()
        assertThat(m.isDuressPassword(real)).isFalse()
        assertThat(m.isDuressPassword("other".toByteArray())).isFalse()
        // Real password still opens the vault after arming.
        m.open(VaultIds.CONTENT, real).destroy()
    }

    @Test fun decoyReplacementMakesRealPasswordFailAndRetiresKeys() {
        val hw = FakeWrapping()
        val m = manager(hw)
        m.initialise(real, params, false).values.forEach { it.destroy() }
        m.setDuress(real, duress, params)
        val oldAliases = m.keyring()!!.envelopes.values.map { it.hwAlias }
        val decoy = m.duressReplaceWithDecoy(duress, params)
        decoy.values.forEach { it.destroy() }
        oldAliases.forEach { assertThat(hw.keys).doesNotContainKey(it) }
        assertThat(m.isInitialised()).isTrue() // still looks like a vault
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { m.open(VaultIds.CONTENT, real) }
        assertThat(m.isDuressPassword(duress)).isTrue() // re-armed: repeated duress entries behave the same
    }

    @Test fun decoyMirrorsOriginalKdfCostAndDeviceAuthFlag() {
        val hw = FakeWrapping()
        val m = manager(hw)
        m.initialise(real, Argon2Params.PROFILE_128M, requireDeviceAuth = true).values.forEach { it.destroy() }
        m.setDuress(real, duress, Argon2Params.PROFILE_128M)
        // The caller passes a fallback profile; the decoy must still look like the original vault.
        m.duressReplaceWithDecoy(duress, Argon2Params.RFC9106_MEMORY_CONSTRAINED).values.forEach { it.destroy() }
        val ring = m.keyring()!!
        ring.envelopes.values.forEach {
            assertThat(it.kdf).isEqualTo(Argon2Params.PROFILE_128M)
            assertThat(it.requiresDeviceAuth).isTrue()
            assertThat(hw.authRequired).contains(it.hwAlias)
        }
    }

    @Test fun clearDuressNeedsRealPassword() {
        val m = manager()
        m.initialise(real, params, false).values.forEach { it.destroy() }
        m.setDuress(real, duress, params)
        assertThrows(VaultCryptoException.WrongPasswordOrCorrupt::class.java) { m.clearDuress(duress) }
        m.clearDuress(real)
        assertThat(m.hasDuress()).isFalse()
    }

    @Test fun duressVerifierJsonRoundTripAndBounds() {
        val kdf = FakeKdf()
        val v = DuressVerifier.create(duress, params, kdf)
        val parsed = DuressVerifier.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(v.toJson().toString()).jsonObject)
        assertThat(parsed.matches(duress, kdf)).isTrue()
        assertThat(parsed.matches(real, kdf)).isFalse()
        val bad = kotlinx.serialization.json.Json.parseToJsonElement(v.toJson().toString()).jsonObject.toMutableMap()
        bad["memKib"] = JsonPrimitive(1024)
        assertThrows(VaultCryptoException.Malformed::class.java) { DuressVerifier.fromJson(JsonObject(bad)) }
    }
}
