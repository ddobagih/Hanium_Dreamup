package kr.co.hanium.dreamup.walksafe.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SensitivePreferenceNoOpDeviceTest {
    @Test fun unchangedCommitPreservesCiphertextAndLostKeyBlocksRestoration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "device-sensitive-probe-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.prefs.${UUID.randomUUID()}.v", 1, setOf(1))
        val spec = SensitivePreferenceSpec("cipher", "blocked", "reset", "probe".toByteArray(), policy, setOf("value"))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            val store = AndroidSensitivePreferenceStore(prefs, spec)
            assertTrue(store.edit().putString("value", "synthetic-sensitive-value").commit())
            val encrypted = prefs.getString("cipher", null)
            assertNotNull(encrypted)
            assertFalse(encrypted!!.contains("synthetic-sensitive-value"))
            repeat(100) { assertTrue(store.edit().putString("value", "synthetic-sensitive-value").commit()) }
            assertEquals("No-op commits must not reseal", encrypted, prefs.getString("cipher", null))
            keys.deleteEntry(policy.alias(1))
            val restored = AndroidSensitivePreferenceStore(prefs, spec)
            assertTrue(restored.isBlocked())
            assertFalse(restored.edit().putString("value", "replacement").commit())
            assertFalse(keys.containsAlias(policy.alias(1)))
        } finally {
            context.deleteSharedPreferences(name)
            keys.deleteEntry(policy.alias(1)); keys.deleteEntry(policy.alias(1) + ".generation")
        }
    }
}
