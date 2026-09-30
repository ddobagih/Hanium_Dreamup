package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S01-22: the long-lived session response and status must match the Gateway wire schema exactly
 * (field-long-session.ts sessionResponse = 9 keys, fieldLongSessionStatus = 11 keys + optional
 * capacity). String/decimal integers, missing or unexpected scope and extra fields are rejected;
 * the missing-scope compatibility branch stays limited to legacy (non long-lived) sessions.
 */
class GatewayLongLivedSessionStrictSchemaTest {
    @Before
    fun resetCapacity() {
        GatewayCapacityProcessState.resetForTests()
    }

    @After
    fun resetCapacityAfter() {
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun gatewayExactNineKeyLoginResponseIsAccepted() {
        val session = login(gatewaySessionBody())
        assertTrue(session.isUsableFor(ACTOR, NOW))
        assertEquals(0L, session.rotation)
        assertEquals(FAMILY, session.familyId)
    }

    @Test
    fun stringRotationIsRejected() {
        assertRejected(gatewaySessionBody().put("rotation", "0"))
    }

    @Test
    fun decimalRotationIsRejected() {
        assertRejectedText(decimal(gatewaySessionBody(), "rotation", "0"))
    }

    @Test
    fun stringExpiryIsRejected() {
        assertRejected(gatewaySessionBody().put("idle_expires_at_epoch_ms", IDLE.toString()))
    }

    @Test
    fun decimalExpiryIsRejected() {
        assertRejectedText(decimal(gatewaySessionBody(), "access_expires_at_epoch_ms", ACCESS.toString()))
    }

    @Test
    fun missingSessionScopeIsRejected() {
        val body = gatewaySessionBody()
        body.remove("session_scope")
        assertRejected(body)
    }

    @Test
    fun recoveryOrEmptySessionScopeIsRejected() {
        assertRejected(gatewaySessionBody().put("session_scope", "account_deletion_recovery"))
        assertRejected(gatewaySessionBody().put("session_scope", ""))
    }

    @Test
    fun extraFieldIsRejected() {
        assertRejected(gatewaySessionBody().put("session_mode", "long_lived"))
    }

    @Test
    fun numericIdentifierIsRejectedInsteadOfCoercedToText() {
        assertRejected(gatewaySessionBody().put("device_id", 1_234_567_890L))
    }

    @Test
    fun gatewayExactElevenKeyStatusIsReady() {
        assertEquals(GatewaySessionRevalidationStatus.READY, revalidate(gatewayStatusBody()).status)
    }

    @Test
    fun gatewayStatusWithOfficialCapacityFieldIsReady() {
        val body = gatewayStatusBody().put(
            "capacity",
            JSONObject()
                .put("version", 1)
                .put("observed_at", "2026-08-29T10:00:00Z")
                .put("expires_at", "2026-08-29T10:10:00Z")
                .put("level", "NORMAL")
                .put("reason", "STORAGE_UTILIZATION"),
        )
        assertEquals(GatewaySessionRevalidationStatus.READY, revalidate(body).status)
    }

    @Test
    fun longLivedStatusWithoutScopeIsNotReady() {
        val body = gatewayStatusBody()
        body.remove("session_scope")
        assertNotReady(body)
    }

    @Test
    fun longLivedStatusWithStringOrDecimalIntegersIsNotReady() {
        assertNotReady(gatewayStatusBody().put("rotation", "0"))
        assertNotReadyText(decimal(gatewayStatusBody(), "absolute_expires_at_epoch_ms", ABSOLUTE.toString()))
    }

    @Test
    fun longLivedStatusWithExtraFieldIsNotReady() {
        assertNotReady(gatewayStatusBody().put("refresh_token", "r".repeat(64)))
    }

    @Test
    fun legacyShortSessionKeepsMissingScopeCompatibility() {
        val legacy = GatewayFieldSession.legacyVerified(
            gatewayBaseUrl = ORIGIN,
            actorId = ACTOR,
            deviceId = DEVICE,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=legacy-access-token-0001",
            expiresAtEpochMs = NOW + 60_000L,
        )
        val transport = Transport(
            status = GatewayHttpResponse(200, """{"required":true,"authenticated":true,"actor_id":"$ACTOR"}"""),
        )
        val result = GatewayFieldSessionClient(transport).revalidate(legacy, ACTOR, NOW)
        assertEquals(GatewaySessionRevalidationStatus.READY, result.status)
    }

    private fun assertRejected(body: JSONObject) = assertRejectedText(body.toString())

    private fun assertRejectedText(body: String) {
        val error = assertThrows(GatewaySessionHttpException::class.java) { login(body) }
        assertEquals(0, error.statusCode)
    }

    private fun assertNotReady(body: JSONObject) = assertNotReadyText(body.toString())

    private fun assertNotReadyText(body: String) {
        val result = revalidate(body)
        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, result.status)
        assertEquals("gateway_actor_binding_unavailable", result.reason)
    }

    /** org.json serializes 1.0 as 1, so decimal integers are written as raw JSON text. */
    private fun decimal(body: JSONObject, key: String, integer: String): String {
        val text = body.toString()
        val original = "\"$key\":$integer"
        check(text.contains(original)) { "fixture key $key missing" }
        return text.replace(original, "$original.0")
    }

    private fun login(body: JSONObject): GatewayFieldSession = login(body.toString())

    private fun login(body: String): GatewayFieldSession {
        val transport = Transport(
            login = GatewayHttpResponse(
                statusCode = 200,
                responseBody = body,
                headers = mapOf(
                    "Set-Cookie" to
                        "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_TOKEN; Path=/; HttpOnly; SameSite=Strict; Max-Age=60; Secure",
                ),
            ),
        )
        return GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L }).login(
            gatewayBaseUrl = ORIGIN,
            actorId = ACTOR,
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE,
        )
    }

    private fun revalidate(status: JSONObject): GatewaySessionRevalidation = revalidate(status.toString())

    private fun revalidate(status: String): GatewaySessionRevalidation {
        val session = GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = ORIGIN,
            actorId = ACTOR,
            deviceId = DEVICE,
            familyId = FAMILY,
            rotation = 0L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_TOKEN",
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochMs = ACCESS,
            idleExpiresAtEpochMs = IDLE,
            absoluteExpiresAtEpochMs = ABSOLUTE,
        )
        val transport = Transport(status = GatewayHttpResponse(200, status))
        return GatewayFieldSessionClient(transport).revalidate(session, ACTOR, NOW)
    }

    /** Mirrors apps/android-gateway/src/field-long-session.ts sessionResponse (9 keys). */
    private fun gatewaySessionBody(): JSONObject = JSONObject()
        .put("session_scope", "general")
        .put("actor_id", ACTOR)
        .put("device_id", DEVICE)
        .put("family_id", FAMILY)
        .put("rotation", 0L)
        .put("refresh_token", REFRESH_TOKEN)
        .put("access_expires_at_epoch_ms", ACCESS)
        .put("idle_expires_at_epoch_ms", IDLE)
        .put("absolute_expires_at_epoch_ms", ABSOLUTE)

    /** Mirrors apps/android-gateway/src/field-long-session.ts fieldLongSessionStatus (11 keys). */
    private fun gatewayStatusBody(): JSONObject = JSONObject()
        .put("required", true)
        .put("authenticated", true)
        .put("actor_id", ACTOR)
        .put("session_scope", "general")
        .put("session_mode", "long_lived")
        .put("device_id", DEVICE)
        .put("family_id", FAMILY)
        .put("rotation", 0L)
        .put("access_expires_at_epoch_ms", ACCESS)
        .put("idle_expires_at_epoch_ms", IDLE)
        .put("absolute_expires_at_epoch_ms", ABSOLUTE)

    private class Transport(
        private val login: GatewayHttpResponse? = null,
        private val status: GatewayHttpResponse? = null,
    ) : GatewaySessionTransport {
        override fun postJson(url: String, body: String): GatewayHttpResponse =
            login ?: error("unexpected POST")

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            status ?: error("unexpected GET")

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            GatewayHttpResponse(204, "")

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR = "tester-01"
        const val DEVICE = "android-device-strict-01"
        const val FAMILY = "ffffffffffffffffffffffffffffffff"
        const val ACCOUNT_TOKEN = "field-account-token-strict-000001"
        const val ACCESS_TOKEN = "access-token-strict-00000000000001"
        val REFRESH_TOKEN = "r".repeat(64)
        const val NOW = 1_000L
        const val ACCESS = 61_000L
        const val IDLE = 121_000L
        const val ABSOLUTE = 241_000L
    }
}
