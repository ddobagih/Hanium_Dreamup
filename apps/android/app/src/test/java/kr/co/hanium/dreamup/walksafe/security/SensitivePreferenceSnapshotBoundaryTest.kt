package kr.co.hanium.dreamup.walksafe.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SensitivePreferenceSnapshotBoundaryTest(private val form: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun forms() = listOf("valid", "whitespace", "long-min", "long-max", "trailing-object",
            "trailing-text", "unquoted-key", "trailing-comma", "duplicate-key", "string-version",
            "fraction-version", "string-boolean", "string-long", "fraction-long", "overflow-long",
            "number-string", "invalid-utf8", "unexpected-key").map { arrayOf(it) }
    }

    private val spec = SensitivePreferenceSpec("ciphertext", "blocked", "reset",
        "test|snapshot|schema=1".toByteArray(), AeadKeyPolicy("test.snapshot.v", 1, setOf(1)),
        exactKeys = setOf("consent", "revision", "label"))

    @Test fun authenticatedSnapshotMustKeepExactTypesAndDocumentBoundaries() {
        val storage = Storage()
        val crypto = VersionedLocalAead(spec.keyPolicy, Keys())
        val plain = fixture()
        val limits = AeadLimits(65536, 65552, 98304)
        val envelope = (crypto.seal(plain, spec.domainAad, limits) as AeadSealResult.Sealed).envelope
        storage.data[spec.storageKey] = envelope
        // This is an authenticated payload/schema check, not ciphertext tampering.
        assertArrayEquals(plain, (crypto.open(envelope, spec.domainAad, limits) as AeadOpenResult.Opened).plaintext)
        var openedBytes: ByteArray? = null
        val observed = object : LocalAead by crypto {
            override fun open(envelope: String, domainAad: ByteArray, limits: AeadLimits): AeadOpenResult =
                crypto.open(envelope, domainAad, limits).also { openedBytes = (it as? AeadOpenResult.Opened)?.plaintext }
        }
        val store = AndroidSensitivePreferenceStore(storage, spec, observed)
        if (form in setOf("valid", "whitespace", "long-min", "long-max")) {
            assertFalse(store.isBlocked())
            assertEquals(setOf("consent", "revision", "label"), store.keys())
            assertTrue(store.getBoolean("consent", false))
            assertEquals(when (form) { "long-min" -> Long.MIN_VALUE; "long-max" -> Long.MAX_VALUE; else -> 7L }, store.getLong("revision", 0L))
            assertEquals("한글", store.getString("label", null))
        } else {
            assertTrue("ambiguous authenticated snapshot accepted: $form", store.isBlocked())
            assertTrue(store.keys().isEmpty())
            assertFalse(store.getBoolean("consent", false))
            assertNull(store.getString("label", null))
            assertEquals(true, storage.data[spec.blockedKey])
            assertFalse(store.edit().putBoolean("consent", true).commit())
        }
        assertTrue(requireNotNull(openedBytes).all { it == 0.toByte() })
    }

    private fun fixture(): ByteArray {
        val root = JSONObject().put("snapshot_version", 1).put("values", JSONObject()
            .put("consent", JSONObject().put("type", "boolean").put("value", true))
            .put("revision", JSONObject().put("type", "long").put("value", 7L))
            .put("label", JSONObject().put("type", "string").put("value", "한글")))
        val values = root.getJSONObject("values")
        when (form) {
            "long-min" -> values.getJSONObject("revision").put("value", Long.MIN_VALUE)
            "long-max" -> values.getJSONObject("revision").put("value", Long.MAX_VALUE)
            "string-version" -> root.put("snapshot_version", "1")
            "fraction-version" -> root.put("snapshot_version", 1.5)
            "string-boolean" -> values.getJSONObject("consent").put("value", "true")
            "string-long" -> values.getJSONObject("revision").put("value", "7")
            "fraction-long" -> values.getJSONObject("revision").put("value", 7.5)
            "overflow-long" -> values.getJSONObject("revision").put("value", java.math.BigInteger("9223372036854775808"))
            "number-string" -> values.getJSONObject("label").put("value", 123)
            "unexpected-key" -> root.put("extra", true)
        }
        val original = root.toString()
        if (form == "invalid-utf8") {
            val split = original.split("한글")
            return split[0].toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + split[1].toByteArray()
        }
        return when (form) {
            "whitespace" -> " \t\r\n$original\n "
            "trailing-object" -> original + "{}"
            "trailing-text" -> original + "unexpected"
            "unquoted-key" -> original.replace("\"snapshot_version\":", "snapshot_version:")
            "trailing-comma" -> original.dropLast(1) + ",}"
            "duplicate-key" -> original.dropLast(1) + ",\"snapshot_versio\\u006e\":1}"
            else -> original
        }.toByteArray()
    }

    private class Storage : SensitivePreferenceStorage {
        override val processIdentity: Any = this
        val data = mutableMapOf<String, Any>()
        override fun contains(key: String) = key in data
        override fun keys() = data.keys.toSet()
        override fun getRaw(key: String) = data[key]
        override fun getString(key: String) = data[key] as? String
        override fun getBoolean(key: String, defaultValue: Boolean) = data[key] as? Boolean ?: defaultValue
        override fun commit(strings: Map<String, String>, booleans: Map<String, Boolean>, removals: Set<String>): Boolean {
            removals.forEach(data::remove); data.putAll(strings); data.putAll(booleans); return true
        }
    }
    private class Keys : LocalAeadKeyProvider {
        private val keys = mutableMapOf<String, SecretKey>()
        override fun getOrCreate(alias: String) = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun getExisting(alias: String) = keys[alias]
        override fun delete(alias: String): Boolean { keys.remove(alias); return true }
        override fun createFreshAfterVerifiedPurge(alias: String) = getOrCreate(alias)
    }
}
