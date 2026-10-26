package kr.co.hanium.dreamup.walksafe.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SensitivePreferenceNoOpDeviceTest {
    /** Authenticated malformed plaintext, actual Android JSON/Keystore/preferences; isolated keys only. */
    @Test fun authenticatedSnapshotTypesAndBoundariesMatchTheHostContract() {
        val canonical = """{"snapshot_version":1,"values":{"consent":{"type":"boolean","value":true},"revision":{"type":"long","value":7},"label":{"type":"string","value":"한글"}}}"""
        val cases = linkedMapOf(
            "valid" to canonical, "whitespace" to " \r\n$canonical\t ",
            "long-min" to canonical.replace("\"value\":7", "\"value\":${Long.MIN_VALUE}"),
            "long-max" to canonical.replace("\"value\":7", "\"value\":${Long.MAX_VALUE}"),
            "extra-document" to (canonical + "{}"), "extra-text" to (canonical + "tail"),
            "unquoted" to canonical.replace("\"snapshot_version\"", "snapshot_version"),
            "trailing-comma" to (canonical.dropLast(1) + ",}"),
            "duplicate" to (canonical.dropLast(1) + ",\"snapshot_versio\\u006e\":1}"),
            "string-version" to canonical.replace("\"snapshot_version\":1", "\"snapshot_version\":\"1\""),
            "fraction-version" to canonical.replace("\"snapshot_version\":1", "\"snapshot_version\":1.5"),
            "string-boolean" to canonical.replace("\"value\":true", "\"value\":\"true\""),
            "string-long" to canonical.replace("\"value\":7", "\"value\":\"7\""),
            "fraction-long" to canonical.replace("\"value\":7", "\"value\":7.5"),
            "overflow-long" to canonical.replace("\"value\":7", "\"value\":9223372036854775808"),
            "number-string" to canonical.replace("\"value\":\"한글\"", "\"value\":123"),
            "unexpected-key" to (canonical.dropLast(1) + ",\"extra\":true}"),
            "invalid-utf8" to canonical,
        )
        for ((form, document) in cases) isolated(setOf("consent", "revision", "label")) { prefs, spec, crypto ->
            val bytes = if (form == "invalid-utf8") document.split("한글").let {
                it[0].toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + it[1].toByteArray()
            } else document.toByteArray()
            val limits = AeadLimits(65536, 65552, 98304)
            val envelope = (crypto.seal(bytes, spec.domainAad, limits) as AeadSealResult.Sealed).envelope
            assertTrue(prefs.edit().putString(spec.storageKey, envelope).commit())
            var opened: ByteArray? = null
            val observed = object : LocalAead by crypto {
                override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits) =
                    crypto.open(envelope, domainAad, limits).also { opened = (it as? AeadOpenResult.Opened)?.plaintext }
            }
            val store = AndroidSensitivePreferenceStore(prefs, spec, observed)
            val valid = form in setOf("valid", "whitespace", "long-min", "long-max")
            assertEquals("Native snapshot: $form", !valid, store.isBlocked())
            if (valid) {
                assertTrue(store.getBoolean("consent", false))
                assertEquals("한글", store.getString("label", null))
                assertEquals(when (form) { "long-min" -> Long.MIN_VALUE; "long-max" -> Long.MAX_VALUE; else -> 7L }, store.getLong("revision", 0L))
            } else {
                assertTrue(prefs.getBoolean(spec.blockedKey, false))
                assertTrue(store.keys().isEmpty())
                assertFalse(store.edit().putBoolean("consent", true).commit())
                assertTrue(AndroidSensitivePreferenceStore(prefs, spec).isBlocked())
            }
            assertTrue("Decoded bytes cleared: $form", requireNotNull(opened).all { it == 0.toByte() })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "NATIVE_PREFERENCE form=$form accepted=$valid actual_keystore=true actual_preferences=true\n")
            })
        }
    }

    @Test fun entryLimitPreservesLastCiphertextAndBlocksFurtherWrites() =
        isolated((0..256).map { "value_$it" }.toSet()) { prefs, spec, _ ->
            var store = AndroidSensitivePreferenceStore(prefs, spec)
            val editor = store.edit()
            repeat(256) { editor.putLong("value_$it", it.toLong()) }
            assertTrue(editor.commit())
            val before = prefs.getString(spec.storageKey, null)
            store = AndroidSensitivePreferenceStore(prefs, spec)
            assertFalse(store.isBlocked()); assertEquals(256, store.keys().size)
            assertFalse(store.edit().putLong("value_256", 256L).commit())
            assertTrue(store.isBlocked()); assertEquals(before, prefs.getString(spec.storageKey, null))
            assertTrue(AndroidSensitivePreferenceStore(prefs, spec).isBlocked())
        }

    private fun isolated(keys: Set<String>, action: (android.content.SharedPreferences, SensitivePreferenceSpec, AndroidKeyStoreAead) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val name = "device-sensitive-boundary-$id"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val policy = AeadKeyPolicy("walksafe.instrumentation.prefs.$id.v", 1, setOf(1))
        val spec = SensitivePreferenceSpec("cipher", "blocked", "reset", name.toByteArray(), policy, keys)
        val crypto = AndroidKeyStoreAead(policy)
        try { action(prefs, spec, crypto) }
        finally {
            assertTrue(context.deleteSharedPreferences(name))
            assertTrue(crypto.destroyKnownVersions())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
        }
    }

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
