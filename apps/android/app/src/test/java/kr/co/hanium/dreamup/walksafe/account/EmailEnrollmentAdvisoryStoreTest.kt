package kr.co.hanium.dreamup.walksafe.account

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.LocalAeadKeyProvider
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceStorage
import kr.co.hanium.dreamup.walksafe.security.VersionedLocalAead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailEnrollmentAdvisoryStoreTest {
    @Test
    fun encryptedAdvisorySurvivesOtpCleanupAndRestartAndOnlyItsOwnerCanClearIt() {
        val storage = MemoryStorage()
        val keys = Keys()
        val store = store(storage, keys)
        assertTrue(store.markCreationPending(OWNER))
        assertTrue(store.hasUnconfirmedCreation(OWNER))
        assertFalse(storage.strings.values.any { it.contains(OWNER) })
        assertTrue(store.clear()) // OTP cleanup must not erase an uncertain account result.
        val restarted = store(storage, keys)
        assertTrue(restarted.hasUnconfirmedCreation(OWNER))
        assertFalse(restarted.hasUnconfirmedCreation(OTHER_OWNER))
        assertFalse(restarted.clearUnconfirmedCreation(OTHER_OWNER))
        assertTrue(restarted.hasUnconfirmedCreation(OWNER))
        assertTrue(restarted.clearUnconfirmedCreation(OWNER))
        assertFalse(restarted.hasUnconfirmedCreation(OWNER))
    }

    @Test
    fun failedDurableWriteAndStaleStoreCannotCreateOrClearAnAdvisory() {
        val storage = MemoryStorage()
        val keys = Keys()
        val stale = store(storage, keys)
        assertTrue(stale.markCreationPending(OWNER))
        val current = store(storage, keys)
        assertFalse(stale.clearUnconfirmedCreation(OWNER))
        assertTrue(current.hasUnconfirmedCreation(OWNER))
        storage.commitAllowed = false
        assertFalse(current.clearUnconfirmedCreation(OWNER))
        assertFalse(current.markCreationPending(OTHER_OWNER))
    }

    private fun store(storage: MemoryStorage, keys: Keys) = AndroidEmailEnrollmentStore(
        storage = storage,
        aead = VersionedLocalAead(AeadKeyPolicy("walksafe.user.email_enrollment.aead.v", 1, setOf(1)), keys),
    )

    private class MemoryStorage : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        var commitAllowed = true
        override fun contains(key: String) = key in strings || key in booleans
        override fun keys(): Set<String> = strings.keys + booleans.keys
        override fun getRaw(key: String): Any? = strings[key] ?: booleans[key]
        override fun getString(key: String) = strings[key]
        override fun getBoolean(key: String, defaultValue: Boolean) = booleans[key] ?: defaultValue
        override fun commit(strings: Map<String, String>, booleans: Map<String, Boolean>, removals: Set<String>): Boolean {
            if (!commitAllowed) return false
            removals.forEach { this.strings.remove(it); this.booleans.remove(it) }
            this.strings.putAll(strings); this.booleans.putAll(booleans)
            return true
        }
    }

    private class Keys : LocalAeadKeyProvider {
        private val values = mutableMapOf<String, SecretKey>()
        override fun getExisting(alias: String): SecretKey? = values[alias]
        override fun getOrCreate(alias: String): SecretKey = values.getOrPut(alias) {
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
        override fun delete(alias: String): Boolean { values.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
    }

    private companion object {
        val OWNER = "a".repeat(64)
        val OTHER_OWNER = "b".repeat(64)
    }
}
