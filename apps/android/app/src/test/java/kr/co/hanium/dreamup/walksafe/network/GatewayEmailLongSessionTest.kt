package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class GatewayEmailLongSessionTest {
    @Before fun resetProofs() = resetGatewayRefreshProofFenceForTests()
    @After fun clearProofs() = resetGatewayRefreshProofFenceForTests()
    private val actor = "123e4567-e89b-42d3-a456-426614174000"
    private val device = "email-long-device-01"
    private val origin = "https://gateway.example"
    private val now = 1_000_000L

    private class Transport : GatewaySessionTransport {
        var response = GatewayHttpResponse(500, "{}")
        var status = GatewayHttpResponse(500, "{}")
        var body = ""
        var deletes = 0
        override fun postJson(url: String, body: String): GatewayHttpResponse {
            this.body = body
            return response
        }
        override fun get(url: String, headers: Map<String, String>) = status
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse {
            deletes++
            return GatewayHttpResponse(204, "")
        }
        override fun deleteJson(url: String, body: String) = GatewayHttpResponse(204, "")
    }

    private fun payload(at: Long = now, rotation: Int = 0, generation: Any = 7L) = JSONObject()
        .put("session_scope", "general").put("actor_id", actor).put("device_id", device)
        .put("family_id", "f".repeat(32)).put("rotation", rotation)
        .put("account_generation", generation).put("refresh_token", (if (rotation == 0) "r" else "s").repeat(64))
        .put("access_expires_at_epoch_ms", at + 900_000L)
        .put("idle_expires_at_epoch_ms", JSONObject.NULL)
        .put("absolute_expires_at_epoch_ms", JSONObject.NULL)

    private fun response(json: JSONObject) = GatewayHttpResponse(200, json.toString(),
        mapOf("Set-Cookie" to "walksafe_field_session=v6.access-token-${json.getInt("rotation")}; Path=/; HttpOnly; SameSite=Strict; Max-Age=900; Secure"))

    private fun status(json: JSONObject) = JSONObject(json.toString()).apply {
        remove("refresh_token")
        put("required", true); put("authenticated", true); put("session_mode", "long_lived")
    }

    private fun login(transport: Transport) = GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L })
        .loginWithPassword(origin, "person@example.com", "correct horse battery", true, device, now,
            enableLongLivedSession = true)

    @Test fun emailLongLoginUsesOptInAndPreservesBindingBeyondTwelveHours() {
        val json = payload()
        val transport = Transport().apply { response = response(json); status = GatewayHttpResponse(200, status(json).toString()) }
        val session = login(transport)
        assertEquals("long_lived", JSONObject(transport.body).getString("session_mode"))
        assertTrue(session.isLongLived)
        assertEquals(7L, session.lease.backendAccountGeneration)
        val later = now + 13 * 60 * 60 * 1000L
        val restored = requireNotNull(GatewayFieldSession.restore(requireNotNull(session.persistenceSnapshotOrNull()),
            origin, actor, device, later))
        assertFalse(restored.isUsableFor(actor, later))
        assertTrue(restored.isRenewableFor(actor, later))
        assertEquals(7L, restored.lease.backendAccountGeneration)
        assertThrows(IllegalStateException::class.java) { restored.requestHeaders(later) }
        transport.response = response(payload(later, 1))
        val renewed = GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L }).renew(restored, actor, later).session
        assertTrue(renewed.isUsableFor(actor, later))
        assertEquals(7L, renewed.lease.backendAccountGeneration)
        assertEquals(1L, renewed.rotation)
    }

    @Test fun malformedOrMissingGenerationCannotCreateEmailLongSession() {
        listOf(payload(generation = "7"), payload(generation = 7.0), payload(generation = 0L),
            payload().apply { remove("account_generation") }).forEach { json ->
            val transport = Transport().apply { response = response(json) }
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
        }
    }

    @Test fun statusGenerationOrDeviceMismatchRevokesNewLogin() {
        listOf(status(payload()).put("account_generation", 8L),
            status(payload()).put("device_id", "another-device-01")).forEach { wrong ->
            val transport = Transport().apply { response = response(payload()); status = GatewayHttpResponse(200, wrong.toString()) }
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(1, transport.deletes)
        }
    }

    @Test fun refreshCannotChangeOrDropAccountGeneration() {
        listOf(payload(now + 1000L, 1, 8L), payload(now + 1000L, 1).apply { remove("account_generation") }).forEach { wrong ->
            val transport = Transport().apply { response = response(payload()); status = GatewayHttpResponse(200, status(payload()).toString()) }
            resetGatewayRefreshProofFenceForTests()
            val original = login(transport)
            transport.response = response(wrong)
            assertThrows(GatewaySessionHttpException::class.java) {
                GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L }).renew(original, actor, now + 1000L)
            }
            assertFalse(original.isUsableFor(actor, now + 1000L))
        }
    }

    @Test fun longLoginRequiresRememberMeAndRestoreRequiresSameOriginActorAndDevice() {
        val transport = Transport().apply { response = response(payload()); status = GatewayHttpResponse(200, status(payload()).toString()) }
        assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).loginWithPassword(origin, "person@example.com", "correct horse battery", false, device, now, true)
        }
        val snapshot = requireNotNull(login(transport).persistenceSnapshotOrNull())
        assertNull(GatewayFieldSession.restore(snapshot, "https://another.example", actor, device, now))
        assertNull(GatewayFieldSession.restore(snapshot, origin, "123e4567-e89b-42d3-a456-426614174001", device, now))
        assertNull(GatewayFieldSession.restore(snapshot, origin, actor, "another-device-01", now))
    }
}
