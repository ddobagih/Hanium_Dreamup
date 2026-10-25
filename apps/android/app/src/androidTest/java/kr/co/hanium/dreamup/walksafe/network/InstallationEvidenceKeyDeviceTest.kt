package kr.co.hanium.dreamup.walksafe.network

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.testing.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallationEvidenceKeyDeviceTest {
    @Test
    fun signatureSurvivesProcessRestartAndLostKeyIsNotRecreated() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("evidencePhase") ?: "roundtrip"
        require(phase in setOf("write", "read", "roundtrip"))
        val run = args.getString("evidenceToken") ?: UUID.randomUUID().toString().also { require(phase == "roundtrip") }
        val context = IsolatedBoundaryContext.create("evidence", run, fresh = phase != "read")
        val installation = "native-evidence-$run"
        val alias = alias(installation)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keys = AndroidInstallationEvidenceKey(context)
        val journal = DeletionBoundaryData.journal(ORIGIN, installation)
        val digest = "a".repeat(64)
        val marker = File(context.root, "writer.json")
        var retain = false
        try {
            if (phase != "read") {
                assertFalse("Fresh installation must not reuse an existing key", keyStore.containsAlias(alias))
                val publicKey = keys.publicKey(installation, ORIGIN, ACTOR, 7)
                assertFalse("Preparation is not registration acknowledgement", keys.isRegistered(installation, ORIGIN, ACTOR, 7))
                keys.markRegistered(installation, ORIGIN, ACTOR, 7)
                assertTrue(keys.isRegistered(installation, ORIGIN, ACTOR, 7))
                assertFalse(keys.isRegistered(installation, ORIGIN, ACTOR, 8))
                assertFalse(keys.isRegistered(installation, ORIGIN, OTHER_ACTOR, 7))
                assertFalse(keys.isRegistered(installation, "https://another-native-probe.example.test", ACTOR, 7))
                val signature = requireNotNull(keys.signIfPresent(journal, digest, hash(ACTOR)))
                marker.writeText(JSONObject().put("pid", Process.myPid()).put("public", publicKey).put("signature", signature).toString())
                if (phase == "write") { retain = true; return }
            }
            val saved = JSONObject(marker.readText())
            if (phase == "read") {
                assertTrue(saved.getInt("pid") > 0)
                assertNotEquals("Read phase needs a new Android process", saved.getInt("pid"), Process.myPid())
            }
            assertEquals(saved.getString("public"), keys.publicKey(installation, ORIGIN, ACTOR, 7))
            assertTrue(keys.isRegistered(installation, ORIGIN, ACTOR, 7))
            val signature = requireNotNull(keys.signIfPresent(journal, digest, hash(ACTOR)))
            assertEquals("Retry must preserve the exact cached signature bytes", saved.getString("signature"), signature)
            val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(saved.getString("public"))))
            assertTrue(verifyInstallationEvidenceSignature(public, digest, signature))
            assertFalse(verifyInstallationEvidenceSignature(public, "b".repeat(64), signature))
            val privateKey = requireNotNull(keyStore.getKey(alias, null))
            assertNull("Android private signing key must not be exportable", privateKey.encoded)
            assertThrows(IllegalStateException::class.java) { keys.signIfPresent(journal.copy(accountGeneration = 8), digest, hash(ACTOR)) }
            assertThrows(IllegalStateException::class.java) { keys.signIfPresent(journal, digest, hash(OTHER_ACTOR)) }
            keyStore.deleteEntry(alias)
            assertThrows(IllegalStateException::class.java) { keys.publicKey(installation, ORIGIN, ACTOR, 7) }
            assertThrows(IllegalStateException::class.java) { keys.signIfPresent(journal, digest, hash(ACTOR)) }
            assertFalse("Loss must never trigger implicit replacement", keyStore.containsAlias(alias))
        } finally {
            if (!retain) {
                keyStore.deleteEntry(alias)
                context.dispose()
            }
        }
    }

    @Test
    fun authenticatedRegistrationRequiresExactNativeAcknowledgementAndCurrentOwner() {
        val context = IsolatedBoundaryContext.create("key-registration")
        val installation = "native-installation-${UUID.randomUUID()}"
        val keys = AndroidInstallationEvidenceKey(context)
        try {
            NativeLoopbackServer(listOf(NativeHttpReply(204))).use { peer ->
                val session = generalSession(peer.baseUrl, installation)
                val confirmation = confirmed(installation)
                AndroidIntegratedConsentClient(keys::publicKey, keys::markRegistered)
                    .registerInstallationEvidenceKeyCall(session, confirmation) { true }.execute()
                peer.finished()
                assertTrue(keys.isRegistered(installation, peer.baseUrl, ACTOR, 7))
                val request = peer.requests.single()
                assertEquals("PUT", request.method)
                assertEquals("/privacy/rights?control=installation-evidence-key", request.path)
                assertEquals(confirmation.controlSecret, request.headers[CONSENT_CONTROL_SECRET_HEADER])
                assertNotNull(request.headers["cookie"])
                val body = JSONObject(request.body)
                assertEquals(setOf("installation_id", "public_key"), body.keys().asSequence().toSet())
                assertEquals(installation, body.getString("installation_id"))
                assertTrue(validInstallationEvidencePublicKey(body.getString("public_key")))
            }
            NativeLoopbackServer(listOf(NativeHttpReply(200, "{}"))).use { peer ->
                assertThrows(IntegratedConsentHttpException::class.java) {
                    AndroidIntegratedConsentClient(keys::publicKey, keys::markRegistered)
                        .registerInstallationEvidenceKeyCall(generalSession(peer.baseUrl, installation, generation = 8), confirmed(installation)) { true }.execute()
                }
                peer.finished()
                assertFalse(keys.isRegistered(installation, peer.baseUrl, ACTOR, 8))
            }
            NativeLoopbackServer(emptyList()).use { peer ->
                assertThrows(java.util.concurrent.CancellationException::class.java) {
                    AndroidIntegratedConsentClient(keys::publicKey, keys::markRegistered)
                        .registerInstallationEvidenceKeyCall(generalSession(peer.baseUrl, installation), confirmed(installation)) { false }.execute()
                }
                peer.finished()
                assertTrue(peer.requests.isEmpty())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias(installation)) }
            context.dispose()
        }
    }

    private fun generalSession(base: String, installation: String, generation: Long = 7) = GatewayFieldSession.longLivedSession(base,
        ACTOR, installation, UUID.randomUUID().toString().replace("-", ""), 1,
        "walksafe_field_session=" + "a".repeat(32), "r".repeat(64), 4_000_000_000_000L,
        4_000_000_000_000L, 4_000_000_000_000L, accountGeneration = generation)

    private fun confirmed(installation: String) = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
        INTEGRATED_CONSENT_POLICY_VERSION, installation, "synthetic_consent_key_0001", IntegratedConsentItemVersions(),
        1, 1, IntegratedConsentSelections(true, true, true, true), "2026-10-10T00:00:00.000Z",
        "a".repeat(64), "b".repeat(64), "d".repeat(64))

    private fun alias(installation: String) = "walkmate.deletion-evidence." + hash(installation)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ORIGIN = "https://native-evidence.example.test"
        const val ACTOR = "44444444-4444-4444-8444-444444444444"
        const val OTHER_ACTOR = "55555555-5555-4555-8555-555555555555"
    }
}
