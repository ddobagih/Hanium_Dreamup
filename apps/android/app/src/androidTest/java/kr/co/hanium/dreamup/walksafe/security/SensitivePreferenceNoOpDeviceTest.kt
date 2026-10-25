package kr.co.hanium.dreamup.walksafe.security

import android.content.Context
import java.security.KeyStore
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.testing.IsolatedBoundaryContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class SensitivePreferenceNoOpDeviceTest {
    @Test fun unchangedCommitPreservesCiphertextAndLostKeyBlocksRestoration() {
        val context = IsolatedBoundaryContext.create("sensitive-noop")
        val run = UUID.randomUUID().toString()
        val name = "walksafe_native_sensitive_$run"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.noop.$run.v", 1, setOf(1))
        val spec = SensitivePreferenceSpec("cipher", "blocked", "reset", name.toByteArray(), policy, setOf("value"))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            assertTrue(prefs.all.isEmpty())
            val store = AndroidSensitivePreferenceStore(prefs, spec)
            assertTrue(store.edit().putString("value", "synthetic-sensitive-value").commit())
            val encrypted = requireNotNull(prefs.getString("cipher", null))
            assertFalse(encrypted.contains("synthetic-sensitive-value"))
            repeat(100) { assertTrue(store.edit().putString("value", "synthetic-sensitive-value").commit()) }
            assertEquals("No-op must not reseal", encrypted, prefs.getString("cipher", null))
            assertTrue(store.edit().putString("value", "synthetic-sensitive-value").commitPersisted())
            assertNotEquals("Forced persistence must obtain a fresh native ciphertext acknowledgement",
                encrypted, prefs.getString("cipher", null))

            val current = AndroidSensitivePreferenceStore(prefs, spec)
            assertEquals("synthetic-sensitive-value", current.getString("value", null))
            assertFalse("Stale owner cannot claim a no-op success", store.edit().putString("value", "synthetic-sensitive-value").commit())
            keys.deleteEntry(policy.alias(1))
            val restored = AndroidSensitivePreferenceStore(prefs, spec)
            assertTrue(restored.isBlocked())
            assertFalse(restored.edit().putString("value", "replacement").commit())
            assertFalse(restored.edit().putString("value", "replacement").commitPersisted())
            assertFalse(keys.containsAlias(policy.alias(1)))
        } finally {
            check(context.deleteSharedPreferences(name))
            check(AndroidKeyStoreAead(policy).destroyKnownVersions())
            keys.deleteEntry(policy.alias(1) + ".generation")
            context.dispose()
        }
    }
}
