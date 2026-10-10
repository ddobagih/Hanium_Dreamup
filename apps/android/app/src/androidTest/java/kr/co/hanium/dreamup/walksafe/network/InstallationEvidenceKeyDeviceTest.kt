package kr.co.hanium.dreamup.walksafe.network

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** S1617-52: real Keystore and durable signature cache, isolated synthetic installation only.
 * No server evidence receipt or application-wide deletion reset is asserted by this test.
 */
class InstallationEvidenceKeyDeviceTest {
    @Test fun signatureSurvivesProcessRestartAndLostKeyIsNotRecreated() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("evidencePhase") ?: "roundtrip"
        require(phase in setOf("write", "read", "roundtrip"))
        val token = args.getString("evidenceToken") ?: UUID.randomUUID().toString()
        require(token.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installation = "instrumentation-evidence-$token"
        val alias = "walkmate.deletion-evidence." + hash(installation)
        val digest = "a".repeat(64)
        val cache = File(context.noBackupFilesDir, "deletion-signature-" + hash("$installation:$digest"))
        val marker = File(context.cacheDir, "evidence-probe-$token")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = AndroidInstallationEvidenceKey(context)
        var keep = false
        try {
            if (phase != "read") {
                assertFalse("Use a fresh test token", store.containsAlias(alias))
                assertNull(key.signIfPresent(installation, digest))
                val publicKey = key.publicKey(installation)
                val signature = requireNotNull(key.signIfPresent(installation, digest))
                assertNull("Private key must not be exportable", store.getKey(alias, null).encoded)
                assertTrue(cache.isFile)
                marker.writeText(listOf(Process.myPid().toString(), publicKey, signature).joinToString("\n"))
            }
            if (phase == "write") { keep = true; return }
            val saved = marker.readLines()
            if (phase == "read") assertNotEquals(saved[0].toInt(), Process.myPid())
            assertEquals(saved[1], key.publicKey(installation))
            assertEquals("Retry must preserve the identical signature bytes", saved[2], key.signIfPresent(installation, digest))
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(saved[1])))
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(("walksafe/device-deletion-evidence-signature/v1\u0000" + digest).toByteArray(Charsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(saved[2]))
            })
            // Corruption must fail closed, never silently replace the durable retry evidence.
            cache.writeText("A".repeat(96))
            assertThrows(Exception::class.java) { key.signIfPresent(installation, digest) }
            assertEquals("A".repeat(96), cache.readText())
            store.deleteEntry(alias)
            assertNull(key.signIfPresent(installation, digest))
            assertFalse(store.containsAlias(alias))
        } finally {
            if (!keep) {
                store.deleteEntry(alias)
                listOf(cache, File(cache.path + ".bak"), File(cache.path + ".new"), marker)
                    .forEach { if (it.exists()) assertTrue(it.delete()) }
            }
        }
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
