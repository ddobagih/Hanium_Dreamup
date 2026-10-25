package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.testing.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic v2 protocol peer, native signature/cache and HTTP; never runs an actual local purge. */
@RunWith(AndroidJUnit4::class)
class DeletionEvidenceRecoveryDeviceTest {
    @Test
    fun unreadableAcknowledgementRetriesTheSameSignedEvidenceBytes() {
        val context = IsolatedBoundaryContext.create("deletion-evidence")
        val installation = "native-deletion-${UUID.randomUUID()}"
        val keys = AndroidInstallationEvidenceKey(context)
        val actorHash = hash(ACTOR)
        try {
            NativeLoopbackServer(listOf(NativeHttpReply(200, "{", declaredLength = 512),
                NativeHttpReply(200, DeletionBoundaryData.statusBody()))).use { peer ->
                keys.publicKey(installation, peer.baseUrl, ACTOR, 7)
                val journal = DeletionBoundaryData.journal(peer.baseUrl, installation)
                val unsigned = DeviceDeletionEvidence(DeletionBoundaryData.requestId, DeletionBoundaryData.tombstone,
                    DeletionBoundaryData.receipt, installation, "native-device-evidence-0001", 1, 1, "DELETED",
                    "2026-10-10T00:00:10Z", "0".repeat(64))
                val evidence = unsigned.copy(evidenceSha256 = deviceDeletionEvidenceSha256(unsigned))
                val client = AndroidPrivacyDeletionClient(signEvidence = { bound, digest -> keys.signIfPresent(bound, digest, actorHash) })
                val failure = try {
                    client.submitDeviceDeletionEvidenceCall(journal, peer.baseUrl, SECRET, evidence).execute()
                    null
                } catch (error: Exception) { error }
                assertTrue("A truncated native acknowledgement must fail as I/O or strict protocol parsing; failure_class=${failure?.javaClass?.name ?: "none"}",
                    failure is java.io.IOException || failure is AccountDeletionProtocolException)
                val result = client.submitDeviceDeletionEvidenceCall(journal, peer.baseUrl, SECRET, evidence).execute()
                assertEquals(DeletionBoundaryData.requestId, result.requestId)
                assertEquals(7L, result.accountGeneration)
                peer.finished()
                assertEquals(2, peer.requests.size)
                val first = peer.requests[0]
                val retry = peer.requests[1]
                assertEquals("Retry uses the same cached native signature and complete request bytes", first.body, retry.body)
                assertEquals("POST", first.method)
                assertEquals("/privacy/account-deletions/${journal.requestId}/device-evidence", first.path)
                assertEquals(SECRET, first.headers[ACCOUNT_DELETION_ACCESS_SECRET_HEADER])
                assertNull("Capability evidence must not carry a general session cookie", first.headers["cookie"])
                val body = JSONObject(first.body)
                assertEquals(evidence.evidenceSha256, body.getString("evidence_sha256"))
                val signature = body.getString("installation_signature")
                val public = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .getCertificate("walkmate.deletion-evidence." + hash(installation)).publicKey
                assertTrue(verifyInstallationEvidenceSignature(public, evidence.evidenceSha256, signature))
                assertEquals("Synthetic HTTP status is not proof of actual local deletion", AccountDeletionPhase.IN_PROGRESS, result.derivedPhase())
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("walkmate.deletion-evidence." + hash(installation)) }
            context.dispose()
        }
    }

    @Test
    fun recoveryScopedReplayAcceptsExistingRequestAndRejectsGeneralScope() {
        syntheticBoundaryContext()
        val installation = "native-recovery-${UUID.randomUUID()}"
        NativeLoopbackServer(listOf(NativeHttpReply(200, DeletionBoundaryData.statusBody()),
            NativeHttpReply(404, "{\"detail\":{\"code\":\"account_deletion_not_found\"}}"))).use { peer ->
            val general = GatewayFieldSession.legacyVerified(peer.baseUrl, ACTOR, installation,
                "walksafe_field_session=" + "a".repeat(32), 4_000_000_000_000L)
            val recovery = GatewayFieldSession.legacyVerified(peer.baseUrl, ACTOR, installation,
                "walksafe_field_session=" + "b".repeat(32), 4_000_000_000_000L,
                sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
            val client = AndroidPrivacyDeletionClient()
            assertThrows(IllegalArgumentException::class.java) {
                client.recoverDeletionRequestAcceptOrReplayCall(peer.baseUrl, peer.baseUrl, general, installation,
                    SECRET, DeletionBoundaryData.requestId)
            }
            assertEquals(DeletionBoundaryData.requestId,
                client.recoverDeletionRequestAcceptOrReplayCall(peer.baseUrl, peer.baseUrl, recovery, installation,
                    SECRET, DeletionBoundaryData.requestId).execute().requestId)
            assertThrows(AccountDeletionReauthenticationRequiredException::class.java) {
                client.recoverDeletionRequestAcceptOrReplayCall(peer.baseUrl, peer.baseUrl, recovery, installation,
                    SECRET, DeletionBoundaryData.requestId).execute()
            }
            peer.finished()
            assertEquals(2, peer.requests.size)
            assertEquals(peer.requests[0].body, peer.requests[1].body)
            assertTrue(peer.requests.all { it.method == "POST" && it.headers["cookie"] != null })
        }
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val ACTOR = "44444444-4444-4444-8444-444444444444"
        val SECRET = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { 0x64.toByte() })
    }
}
