package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class GatewaySessionJsonBoundaryTest(private val form: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun forms() = listOf("canonical", "whitespace", "trailing-object", "trailing-text",
            "unquoted-key", "single-quotes", "trailing-comma", "escaped-duplicate").map { arrayOf(it) }
        private const val ACTOR = "123e4567-e89b-42d3-a456-426614174000"
        private const val DEVICE = "email-json-device-01"
        private const val NOW = 1_000_000L
        private const val ORIGIN = "https://gateway.example"
    }

    @Before fun reset() { resetGatewayRefreshProofFenceForTests(); GatewayCapacityProcessState.resetForTests() }
    @After fun cleanup() { resetGatewayRefreshProofFenceForTests(); GatewayCapacityProcessState.resetForTests() }

    @Test fun longLoginRequiresOneStrictDocument() {
        val transport = Transport().apply { response = response.copy(responseBody = mutate(response.responseBody)) }
        if (valid) assertTrue(login(transport).isUsableFor(ACTOR, NOW))
        else assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
    }

    @Test fun postLoginConfirmationCannotApproveMalformedStatus() {
        val transport = Transport().apply { status = status.copy(responseBody = mutate(status.responseBody)) }
        if (valid) assertTrue(login(transport).isUsableFor(ACTOR, NOW)) else {
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(1, transport.deletes)
        }
    }

    @Test fun renewalCannotReplaceSessionUsingMalformedReply() {
        val transport = Transport()
        val original = login(transport)
        transport.response = response(payload(1)).let { it.copy(responseBody = mutate(it.responseBody)) }
        if (valid) assertTrue(client(transport).renew(original, ACTOR, NOW).session.isUsableFor(ACTOR, NOW)) else {
            assertThrows(GatewaySessionHttpException::class.java) { client(transport).renew(original, ACTOR, NOW) }
            assertFalse(original.isUsableFor(ACTOR, NOW))
        }
    }

    @Test fun revalidationCannotReportReadyFromMalformedStatus() {
        val transport = Transport()
        val original = login(transport)
        transport.status = transport.status.copy(responseBody = mutate(transport.status.responseBody))
        val result = client(transport).revalidate(original, ACTOR, NOW)
        assertEquals(if (valid) GatewaySessionRevalidationStatus.READY else GatewaySessionRevalidationStatus.PENDING, result.status)
        if (!valid) assertEquals("gateway_status_malformed", result.reason)
    }

    @Test fun malformedBusyReplyCannotAuthorizeReuseOfRefreshProof() {
        val transport = Transport()
        val original = login(transport)
        transport.response = GatewayHttpResponse(503,
            mutate("{\"code\":\"field_session_storage_busy\",\"retryable\":true}", "code"),
            mapOf("Retry-After" to "1"))
        val error = assertThrows(GatewaySessionHttpException::class.java) { client(transport).renew(original, ACTOR, NOW) }
        assertEquals(503, error.statusCode)
        if (valid) assertNotNull(error.refreshRetryReservation) else assertNull(error.refreshRetryReservation)
        if (!valid) assertNull(error.serverCode)
    }

    @Test fun malformedErrorContractDoesNotAdvertiseSafeRetry() {
        val body = mutate("{\"code\":\"field_session_storage_busy\",\"retryable\":true}", "code")
        assertEquals(if (valid) GatewaySessionNextAction.RETRY_SAFE else GatewaySessionNextAction.REAUTHENTICATE,
            GatewaySessionErrorContract.nextAction(503, body))
    }

    private val valid get() = form in setOf("canonical", "whitespace")
    private fun mutate(value: String, key: String = "session_scope"): String = when (form) {
        "canonical" -> value
        "whitespace" -> " \r\n\t$value\t "
        "trailing-object" -> value + "{}"
        "trailing-text" -> value + " private-trailing-response"
        "unquoted-key" -> value.replace(JSONObject.quote(key) + ":", key + ":")
        "single-quotes" -> value.replace('"', '\'')
        "trailing-comma" -> value.dropLast(1) + ",}"
        "escaped-duplicate" -> value.dropLast(1) + ",\"" + key.dropLast(1) + "\\u0065\":" +
            JSONObject.quote(JSONObject(value).getString(key)) + "}"
        else -> error(form)
    }

    private fun payload(rotation: Int = 0) = JSONObject()
        .put("session_scope", "general").put("actor_id", ACTOR).put("device_id", DEVICE)
        .put("family_id", "f".repeat(32)).put("rotation", rotation).put("account_generation", 7L)
        .put("refresh_token", (if (rotation == 0) "r" else "s").repeat(64))
        .put("access_expires_at_epoch_ms", NOW + 900_000L)
        .put("idle_expires_at_epoch_ms", JSONObject.NULL).put("absolute_expires_at_epoch_ms", JSONObject.NULL)

    private fun response(json: JSONObject) = GatewayHttpResponse(200, json.toString(), mapOf("Set-Cookie" to
        "walksafe_field_session=v6.access-token-${json.getInt("rotation")}; Path=/; HttpOnly; SameSite=Strict; Max-Age=900; Secure"))

    private fun client(transport: Transport) = GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L }, allowDebugAccountOrigin = true)
    private fun login(transport: Transport) = client(transport).loginWithPassword(ORIGIN, "person@example.com",
        "correct horse battery", true, DEVICE, NOW, enableLongLivedSession = true)

    private inner class Transport : GatewaySessionTransport {
        var response = response(payload())
        var status = GatewayHttpResponse(200, payload().apply {
            remove("refresh_token"); put("required", true); put("authenticated", true); put("session_mode", "long_lived")
        }.toString())
        var deletes = 0
        override fun postJson(url: String, body: String) = response
        override fun get(url: String, headers: Map<String, String>) = status
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse { deletes++; return GatewayHttpResponse(204, "") }
        override fun deleteJson(url: String, body: String) = GatewayHttpResponse(204, "")
    }
}
