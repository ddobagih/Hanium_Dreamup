package kr.co.hanium.dreamup.walksafe.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSensitivePreferenceStoreTest {
    private val spec = SensitivePreferenceSpec(
        storageKey = "sensitive_snapshot_v1",
        blockedKey = "sensitive_snapshot_blocked_v1",
        resetPendingKey = "sensitive_snapshot_reset_pending_v1",
        domainAad = "kr.co.hanium.dreamup.walksafe|USER|prefs|privacy|schema=1".toByteArray(),
        keyPolicy = AeadKeyPolicy(
            aliasPrefix = "walksafe.user.privacy.aead.v",
            currentVersion = 1,
            readableVersions = setOf(1),
        ),
        exactKeys = setOf("control_secret", "revision", "consent"),
        keyPrefixes = setOf("profile_"),
    )

    @Test
    fun storesTheWholeLogicalMapAsOneCiphertextSnapshot() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val store = newStore(storage, provider)

        assertTrue(
            store.edit()
                .putString("control_secret", "secret-plain-value")
                .putLong("revision", 7L)
                .putBoolean("consent", true)
                .commit(),
        )

        val raw = storage.strings.getValue(spec.storageKey)
        assertFalse(raw.contains("secret-plain-value"))
        assertFalse(raw.contains("control_secret"))
        assertTrue(raw.contains("\"envelope_version\":1"))
        val restored = newStore(storage, provider)
        assertFalse(restored.isBlocked())
        assertEquals("secret-plain-value", restored.getString("control_secret", null))
        assertEquals(7L, restored.getLong("revision", 0L))
        assertTrue(restored.getBoolean("consent", false))
    }

    @Test
    fun plaintextLegacyValueIsRemovedAndBlocksTheDomain() {
        val storage = FakeStorage().apply {
            strings["control_secret"] = "legacy-plaintext"
        }
        val store = newStore(storage, FakeKeyProvider())

        assertTrue(store.isBlocked())
        assertFalse(storage.strings.containsKey("control_secret"))
        assertEquals(true, storage.booleans[spec.blockedKey])
        assertFalse(store.edit().putString("control_secret", "replacement").commit())
    }

    @Test
    fun tamperAndMissingKeyRemainBlockedInsteadOfBecomingMissing() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val store = newStore(storage, provider)
        assertTrue(store.edit().putString("control_secret", "secret").commit())
        val envelope = storage.strings.getValue(spec.storageKey)
        val offset = envelope.indexOf("\"ciphertext\":\"") + "\"ciphertext\":\"".length
        storage.strings[spec.storageKey] = envelope.replaceRange(
            offset,
            offset + 1,
            if (envelope[offset] == 'A') "B" else "A",
        )

        val tampered = newStore(storage, provider)
        assertTrue(tampered.isBlocked())
        assertNull(tampered.getString("control_secret", null))

        storage.booleans.remove(spec.blockedKey)
        storage.strings[spec.storageKey] = envelope
        provider.keys.clear()
        val createsBeforeRestore = provider.createCalls
        val missingKey = newStore(storage, provider)
        assertTrue(missingKey.isBlocked())
        assertEquals(createsBeforeRestore, provider.createCalls)
    }

    @Test
    fun explicitDeletionDestroysTheKeyAndClearsCiphertextAndFence() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val store = newStore(storage, provider)
        assertTrue(store.edit().putString("profile_actor", "private-profile").commit())
        assertTrue(provider.keys.isNotEmpty())

        assertTrue(store.destroyAndClear())

        assertTrue(provider.keys.isNotEmpty())
        assertFalse(storage.contains(spec.storageKey))
        assertFalse(storage.contains(spec.blockedKey))
        assertFalse(store.isBlocked())
        assertTrue(store.edit().putString("control_secret", "new-secret").commit())
    }

    @Test
    fun restartReconcilesEverySensitiveKeyResetBoundary() {
        repeat(3) { crashBoundary ->
            val storage = FakeStorage()
            val provider = FakeKeyProvider()
            val store = newStore(storage, provider)
            assertTrue(store.edit().putString("control_secret", "old-secret").commit())
            assertTrue(
                storage.commit(
                    strings = mapOf(spec.resetPendingKey to "RESET_PENDING_V1"),
                    removals = setOf(spec.storageKey, spec.blockedKey, "control_secret"),
                ),
            )
            if (crashBoundary >= 1) {
                assertTrue(VersionedLocalAead(spec.keyPolicy, provider).destroyKnownVersions())
            }
            if (crashBoundary >= 2) {
                assertTrue(
                    VersionedLocalAead(spec.keyPolicy, provider)
                        .createFreshAfterVerifiedPurge(),
                )
            }

            val restarted = newStore(storage, provider)

            assertFalse(restarted.isBlocked())
            assertFalse(storage.contains(spec.resetPendingKey))
            assertFalse(storage.contains(spec.storageKey))
            assertTrue(restarted.edit().putString("control_secret", "fresh-secret").commit())
        }
    }

    @Test
    fun wrongTypeResetMarkerFailsClosedWithoutKeyCreation() {
        val storage = FakeStorage().apply {
            booleans[spec.resetPendingKey] = true
        }
        val provider = FakeKeyProvider()

        val store = newStore(storage, provider)

        assertTrue(store.isBlocked())
        assertEquals(0, provider.createCalls)
        assertTrue(storage.contains(spec.resetPendingKey))
    }

    @Test
    fun wrongTypeResetMarkerInvalidatesExistingProcessLocalInstances() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val stale = newStore(storage, provider)
        assertTrue(stale.edit().putString("control_secret", "old-account-secret").commit())
        val oldEnvelope = storage.strings.getValue(spec.storageKey)
        storage.booleans[spec.resetPendingKey] = true

        assertTrue(stale.isBlocked())
        assertNull(stale.getString("control_secret", null))
        assertFalse(stale.edit().putLong("revision", 102L).commit())
        assertEquals(oldEnvelope, storage.strings[spec.storageKey])
        val observer = newStore(storage, provider)
        assertTrue(observer.isBlocked())
        assertTrue(observer.destroyAndClear())
        assertTrue(stale.isBlocked())
        assertNull(stale.getString("control_secret", null))
        assertFalse(stale.edit().putLong("revision", 103L).commit())
        val fresh = newStore(storage, provider)
        assertTrue(fresh.edit().putString("control_secret", "fresh-account-secret").commit())
        assertEquals("fresh-account-secret", fresh.getString("control_secret", null))
    }

    @Test
    fun keyEnumerationFailureIsFailClosedForInitializeAndReset() {
        val storage = FakeStorage().apply {
            strings["control_secret"] = "legacy-secret"
            keyEnumerationSucceeds = false
        }
        val provider = FakeKeyProvider()
        val store = newStore(storage, provider)

        assertTrue(store.isBlocked())
        assertFalse(store.destroyAndClear())
        assertTrue(storage.strings.containsKey("control_secret"))
        assertEquals(0, provider.createCalls)
    }

    @Test
    fun staleInstanceCannotResealOldValuesAfterAnotherInstanceResets() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val stale = newStore(storage, provider)
        assertTrue(
            stale.edit()
                .putString("control_secret", "old-account-secret")
                .putLong("revision", 41L)
                .commit(),
        )
        val resetter = newStore(storage, provider)
        assertEquals("old-account-secret", resetter.getString("control_secret", null))

        assertTrue(resetter.destroyAndClear())

        assertTrue(stale.isBlocked())
        assertFalse(stale.edit().putLong("revision", 42L).commit())
        val restarted = newStore(storage, provider)
        assertNull(restarted.getString("control_secret", null))
        assertEquals(0L, restarted.getLong("revision", 0L))
        assertTrue(restarted.edit().putString("control_secret", "fresh-account-secret").commit())
    }

    @Test
    fun pendingMarkerClearFailureInvalidatesStaleInstancesBeforeFreshKeyCreation() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val stale = newStore(storage, provider)
        assertTrue(stale.edit().putString("control_secret", "old-account-secret").commit())
        val resetter = newStore(storage, provider)
        storage.failResetMarkerClearOnce = true

        assertFalse(resetter.destroyAndClear())

        assertTrue(storage.contains(spec.resetPendingKey))
        assertFalse(storage.contains(spec.storageKey))
        assertFalse(stale.edit().putLong("revision", 99L).commit())
        assertFalse(storage.contains(spec.storageKey))
        assertTrue(resetter.destroyAndClear())
        val restarted = newStore(storage, provider)
        assertNull(restarted.getString("control_secret", null))
    }

    @Test
    fun stagingResetMarkerInvalidatesStaleInstanceBeforeStorageCommitReturns() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val stale = newStore(storage, provider)
        assertTrue(stale.edit().putString("control_secret", "old-account-secret").commit())
        val resetter = newStore(storage, provider)
        var staleCommitResult: Boolean? = null
        storage.afterResetMarkerStaged = {
            staleCommitResult = stale.edit().putLong("revision", 99L).commit()
        }

        assertTrue(resetter.destroyAndClear())

        assertEquals(false, staleCommitResult)
        assertFalse(stale.edit().putLong("revision", 100L).commit())
        assertFalse(storage.contains(spec.storageKey))
    }

    @Test
    fun failedResetMarkerStageBlocksNewProcessLocalInstancesFromOldSnapshot() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val oldStore = newStore(storage, provider)
        assertTrue(oldStore.edit().putString("control_secret", "old-account-secret").commit())
        val resetter = newStore(storage, provider)
        storage.failResetMarkerStageOnce = true

        assertFalse(resetter.destroyAndClear())

        val newInstance = newStore(storage, provider)
        assertTrue(newInstance.isBlocked())
        assertNull(newInstance.getString("control_secret", null))
        assertFalse(newInstance.edit().putLong("revision", 101L).commit())
    }

    @Test
    fun newerProcessOwnerRejectsLateWholeSnapshotCommitFromOldInstance() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val old = newStore(storage, provider)
        assertTrue(old.edit().putString("control_secret", "old-secret").commit())
        val lateEditor = old.edit().putLong("revision", 1L)
        val current = newStore(storage, provider)
        assertTrue(
            current.edit()
                .putString("control_secret", "current-secret")
                .putLong("revision", 2L)
                .commit(),
        )
        val expectedStrings = storage.strings.toMap()
        val expectedBooleans = storage.booleans.toMap()

        assertFalse(lateEditor.commit())
        old.failClosed()
        assertFalse(old.destroyAndClear())
        assertTrue(old.isBlocked())
        assertEquals(expectedStrings, storage.strings)
        assertEquals(expectedBooleans, storage.booleans)
        val readback = newStore(storage, provider)
        assertEquals("current-secret", readback.getString("control_secret", null))
        assertEquals(2L, readback.getLong("revision", 0L))
    }

    @Test
    fun ownerEpochsAreIndependentAcrossSensitiveStorageScopes() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val primary = newStore(storage, provider)
        assertTrue(primary.edit().putLong("revision", 1L).commit())
        val secondarySpec = SensitivePreferenceSpec(
            storageKey = "secondary_snapshot_v1",
            blockedKey = "secondary_snapshot_blocked_v1",
            resetPendingKey = "secondary_snapshot_reset_pending_v1",
            domainAad =
                "kr.co.hanium.dreamup.walksafe|USER|prefs|secondary|schema=1"
                    .toByteArray(),
            keyPolicy = AeadKeyPolicy(
                aliasPrefix = "walksafe.user.secondary.aead.v",
                currentVersion = 1,
                readableVersions = setOf(1),
            ),
            exactKeys = setOf("secondary_secret"),
        )
        val secondary = AndroidSensitivePreferenceStore(
            storage = storage,
            spec = secondarySpec,
            aead = VersionedLocalAead(secondarySpec.keyPolicy, provider),
        )

        assertTrue(secondary.edit().putString("secondary_secret", "secondary").commit())
        assertTrue(primary.edit().putLong("revision", 2L).commit())
        assertEquals(2L, primary.getLong("revision", 0L))
        assertEquals("secondary", secondary.getString("secondary_secret", null))
    }

    @Test
    fun oldCommitBeforeNewOwnerClaimIsVisibleToTheNewSnapshot() {
        val storage = FakeStorage()
        val provider = FakeKeyProvider()
        val old = newStore(storage, provider)
        assertTrue(
            old.edit()
                .putString("control_secret", "committed-before-rotation")
                .putLong("revision", 7L)
                .commit(),
        )

        val current = newStore(storage, provider)

        assertEquals(
            "committed-before-rotation",
            current.getString("control_secret", null),
        )
        assertEquals(7L, current.getLong("revision", 0L))
        assertFalse(old.edit().putLong("revision", 8L).commit())
    }

    private fun newStore(
        storage: FakeStorage,
        provider: FakeKeyProvider,
    ): AndroidSensitivePreferenceStore = AndroidSensitivePreferenceStore(
        storage = storage,
        spec = spec,
        aead = VersionedLocalAead(spec.keyPolicy, provider),
    )

    private class FakeStorage : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        var commitsSucceed = true
        var keyEnumerationSucceeds = true
        var failResetMarkerClearOnce = false
        var failResetMarkerStageOnce = false
        var afterResetMarkerStaged: (() -> Unit)? = null

        override fun contains(key: String): Boolean = key in strings || key in booleans
        override fun keys(): Set<String>? =
            if (keyEnumerationSucceeds) strings.keys + booleans.keys else null
        override fun getRaw(key: String): Any? = strings[key] ?: booleans[key]
        override fun getString(key: String): String? = strings[key]
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue

        override fun commit(
            strings: Map<String, String>,
            booleans: Map<String, Boolean>,
            removals: Set<String>,
        ): Boolean {
            if (!commitsSucceed) return false
            if (
                failResetMarkerStageOnce &&
                strings["sensitive_snapshot_reset_pending_v1"] == "RESET_PENDING_V1"
            ) {
                failResetMarkerStageOnce = false
                return false
            }
            if (failResetMarkerClearOnce && "sensitive_snapshot_reset_pending_v1" in removals) {
                failResetMarkerClearOnce = false
                return false
            }
            removals.forEach {
                this.strings.remove(it)
                this.booleans.remove(it)
            }
            this.strings.putAll(strings)
            this.booleans.putAll(booleans)
            if (strings["sensitive_snapshot_reset_pending_v1"] == "RESET_PENDING_V1") {
                afterResetMarkerStaged?.also { callback ->
                    afterResetMarkerStaged = null
                    callback()
                }
            }
            return true
        }
    }

    private class FakeKeyProvider : LocalAeadKeyProvider {
        val keys = mutableMapOf<String, SecretKey>()
        private val generationTombstones = mutableSetOf<String>()
        var createCalls = 0

        override fun getOrCreate(alias: String): SecretKey? {
            createCalls += 1
            if (alias !in keys && alias in generationTombstones) return null
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }

        override fun getExisting(alias: String): SecretKey? = keys[alias]

        override fun delete(alias: String): Boolean {
            generationTombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
            generationTombstones += alias
            return keys.getOrPut(alias) {
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            }
        }
    }
}
