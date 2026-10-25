package kr.co.hanium.dreamup.walksafe.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.testing.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native HTTP acknowledgement loss, original client proof and synthetic per-family peer state. */
@RunWith(AndroidJUnit4::class)
class RefreshResponseLossDeviceTest {
    @Test
    fun refreshAcknowledgementLossCanRevokeOnlyItsOriginalFamilyIdempotently() {
        syntheticBoundaryContext()
        val family = UUID.randomUUID().toString().replace("-", "")
        val device = "native-refresh-${UUID.randomUUID()}"
        val rotated = AtomicBoolean(false)
        val revoked = AtomicBoolean(false)
        val peerOtherFamilyActive = AtomicBoolean(true)
        val replyCookie = "walksafe_field_session=" + "b".repeat(32) + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200"
        NativeLoopbackServer(listOf(NativeHttpReply(200, "{", mapOf("Set-Cookie" to replyCookie), declaredLength = 512),
            NativeHttpReply(204), NativeHttpReply(204)), inspect = { index, request ->
            assertEquals("/api/field-session", request.path)
            val body = JSONObject(request.body)
            assertEquals("refresh_token", body.getString("grant_type"))
            assertEquals(family, body.getString("family_id"))
            assertEquals(device, body.getString("device_id"))
            assertEquals(7L, body.getLong("rotation"))
            assertEquals(REFRESH, body.getString("refresh_token"))
            if (index == 0) {
                assertEquals("POST", request.method)
                assertTrue(rotated.compareAndSet(false, true))
            } else {
                assertEquals("DELETE", request.method)
                assertTrue(rotated.get())
                revoked.set(true)
                assertTrue("This loopback peer must not revoke another family", peerOtherFamilyActive.get())
            }
        }).use { peer ->
            val original = session(peer.baseUrl, device, family)
            val other = session(peer.baseUrl, "native-other-${UUID.randomUUID()}", UUID.randomUUID().toString().replace("-", ""))
            val pending = GatewayPendingRevocation("native-logout-${UUID.randomUUID()}", requireNotNull(original.versionOrNull), REFRESH)
            val client = GatewayFieldSessionClient(approvedAccountOrigin = peer.baseUrl, allowDebugAccountOrigin = true)
            val failure = assertThrows(GatewaySessionHttpException::class.java) { client.renew(original, ACTOR) }
            assertEquals(0, failure.statusCode)
            assertTrue("Only the two native truncated-response failure paths are allowed",
                failure.reason in setOf("gateway_refresh_outcome_unknown", "invalid_gateway_login_response"))
            assertFalse("An unknown refresh outcome cannot authorize requests", original.isUsableFor(ACTOR))
            assertEquals("gateway_refresh_not_allowed", assertThrows(GatewaySessionHttpException::class.java) {
                client.renew(original, ACTOR)
            }.reason)
            client.logout(pending)
            client.logout(pending)
            peer.finished()
            assertTrue(revoked.get())
            assertTrue(other.isUsableFor(ACTOR))
            assertTrue(peerOtherFamilyActive.get())
            assertEquals(3, peer.requests.size)
            assertEquals(peer.requests[1].body, peer.requests[2].body)
            assertEquals("Original proof must be preserved after rotation acknowledgement loss", peer.requests[0].body, peer.requests[1].body)
            assertTrue(peer.requests.drop(1).all { it.headers["cookie"] == null })
        }
    }

    private fun session(base: String, device: String, family: String) = GatewayFieldSession.longLivedSession(base,
        ACTOR, device, family, 7, "walksafe_field_session=" + "a".repeat(32), REFRESH,
        4_000_000_000_000L, 4_000_000_000_000L, 4_000_000_000_000L, accountGeneration = 7)

    private companion object {
        const val ACTOR = "44444444-4444-4444-8444-444444444444"
        val REFRESH = "r".repeat(64)
    }
}
