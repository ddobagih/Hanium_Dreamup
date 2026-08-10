package kr.co.hanium.dreamup.walksafe.network

import android.content.SharedPreferences
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGatewaySessionCryptoLifecycleTest {
    @Test
    fun confirmedDeletionCreatesANewInstallGenerationWithoutFailClosedMarkers() {
        val fixture = Fixture("happy")
        val before = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(before)

        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())

        val after = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(after)
        assertNotEquals(before, after)
        assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
    }

    @Test
    fun logoutKeyResetPreservesTheInstallIdentifierAndRemainsReusable() {
        val fixture = Fixture("logout-happy")
        val installationId = fixture.store().getOrCreateInstallDeviceId()
        assertNotNull(installationId)

        assertTrue(fixture.store().purgeDisabled())

        assertTrue(fixture.store().getOrCreateInstallDeviceId() == installationId)
        assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
    }

    @Test
    fun restartReconcilesEveryInstallationKeyResetCrashBoundary() {
        repeat(4) { crashBoundary ->
            val fixture = Fixture("crash$crashBoundary")
            assertNotNull(fixture.store().getOrCreateInstallDeviceId())
            fixture.preferences.edit()
                .remove(INSTALL_ID_PREF_KEY)
                .remove(FAIL_CLOSED_PREF_KEY)
                .putString(KEY_RESET_PENDING_PREF_KEY, "INSTALLATION")
                .commit()
            if (crashBoundary >= 1) {
                assertTrue(fixture.sessionAead.destroyKnownVersions())
                assertTrue(fixture.legacyAead.destroyKnownVersions())
                assertTrue(fixture.installAead.destroyKnownVersions())
            }
            if (crashBoundary >= 2) {
                assertTrue(fixture.sessionAead.createFreshAfterVerifiedPurge())
            }
            if (crashBoundary >= 3) {
                assertTrue(fixture.installAead.createFreshAfterVerifiedPurge())
            }

            assertNotNull(fixture.store().getOrCreateInstallDeviceId())
            assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
            assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        }
    }

    @Test
    fun restartReconcilesEveryLogoutSessionKeyResetCrashBoundary() {
        repeat(4) { crashBoundary ->
            val fixture = Fixture("logout$crashBoundary")
            val installationId = fixture.store().getOrCreateInstallDeviceId()
            assertNotNull(installationId)
            fixture.preferences.edit()
                .remove(FAIL_CLOSED_PREF_KEY)
                .putString(KEY_RESET_PENDING_PREF_KEY, "SESSION")
                .commit()
            if (crashBoundary >= 1) {
                assertTrue(fixture.sessionAead.destroyKnownVersions())
                assertTrue(fixture.legacyAead.destroyKnownVersions())
            }
            if (crashBoundary >= 2) {
                assertTrue(fixture.sessionAead.createFreshAfterVerifiedPurge())
            }
            if (crashBoundary >= 3) {
                assertTrue(fixture.preferences.edit().remove(KEY_RESET_PENDING_PREF_KEY).commit())
            }

            assertTrue(fixture.store().getOrCreateInstallDeviceId() == installationId)
            assertFalse(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
            assertFalse(fixture.preferences.contains(FAIL_CLOSED_PREF_KEY))
        }
    }

    @Test
    fun ciphertextAndMissingKeysRemainFailClosedAcrossStoreInstances() {
        val fixture = Fixture("loss")
        assertNotNull(fixture.store().getOrCreateInstallDeviceId())
        fixture.provider.loseAllKeysAndTombstones()

        assertNull(fixture.store().getOrCreateInstallDeviceId())

        assertTrue(fixture.preferences.getBoolean(FAIL_CLOSED_PREF_KEY, false))
        assertFalse(fixture.provider.hasKey(fixture.installAlias))
        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())
    }

    @Test
    fun wrongTypeKeyResetMarkerFailsClosedWithoutCreatingKeys() {
        val fixture = Fixture("wrong-type-reset-marker")
        fixture.preferences.edit()
            .putBoolean(KEY_RESET_PENDING_PREF_KEY, true)
            .commit()

        assertNull(fixture.store().getOrCreateInstallDeviceId())

        assertTrue(fixture.preferences.contains(KEY_RESET_PENDING_PREF_KEY))
        assertFalse(fixture.provider.hasKey(fixture.installAlias))
        assertTrue(fixture.store().resetInstallationAfterConfirmedAccountDeletion())
    }

    private class Fixture(suffix: String) {
        val preferences = FakeSharedPreferences()
        val provider = FakeKeyProvider()
        private val sessionPolicy = policy("walksafe.test.gateway.$suffix.session.v")
        private val installPolicy = policy("walksafe.test.gateway.$suffix.install.v")
        private val legacyPolicy = policy("walksafe.test.gateway.$suffix.legacy.v")
        val sessionAead = VersionedLocalAead(sessionPolicy, provider)
        val installAead = VersionedLocalAead(installPolicy, provider)
        val legacyAead = VersionedLocalAead(legacyPolicy, provider)
        val installAlias = installPolicy.alias(1)

        fun store() = AndroidGatewaySessionStore(
            preferences = preferences,
            sessionAead = sessionAead,
            installIdAead = installAead,
            legacySessionAead = legacyAead,
        )

        private fun policy(prefix: String) = AeadKeyPolicy(
            aliasPrefix = prefix,
            currentVersion = 1,
            readableVersions = setOf(1),
        )
    }

    private class FakeKeyProvider : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        private val tombstones = mutableSetOf<String>()
        private val observed = mutableSetOf<String>()

        override fun getOrCreate(alias: String): SecretKey? {
            observed += alias
            keys[alias]?.let { return it }
            if (alias in tombstones) return null
            tombstones += alias
            return newKey().also { keys[alias] = it }
        }

        override fun getExisting(alias: String): SecretKey? {
            observed += alias
            return keys[alias]
        }

        override fun delete(alias: String): Boolean {
            observed += alias
            tombstones += alias
            keys.remove(alias)
            return true
        }

        override fun createFreshAfterVerifiedPurge(alias: String): SecretKey {
            observed += alias
            tombstones += alias
            return keys.getOrPut(alias, ::newKey)
        }

        fun loseAllKeysAndTombstones() {
            keys.clear()
            tombstones.clear()
        }

        fun hasKey(alias: String): Boolean = alias in keys

        private fun newKey(): SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                updates[key] = value
            }
            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { updates[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removals.forEach(values::remove)
                updates.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return true
            }
            override fun apply() = commit().let { Unit }
        }
    }

    private companion object {
        const val INSTALL_ID_PREF_KEY = "gateway_install_id_encrypted_v1"
        const val FAIL_CLOSED_PREF_KEY = "gateway_login_bundle_fail_closed_v3"
        const val KEY_RESET_PENDING_PREF_KEY = "gateway_key_reset_pending_v1"
    }
}
