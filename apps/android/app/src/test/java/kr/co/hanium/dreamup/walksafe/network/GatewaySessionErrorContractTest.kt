package kr.co.hanium.dreamup.walksafe.network

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S01-20: every public field-session error code of the Gateway maps to one typed next action,
 * and the Android table equals the Gateway contract published in openapi.json. Adding a code
 * to the Gateway without this table (or changing a status or flag) fails here.
 */
class GatewaySessionErrorContractTest {
    @Before
    @After
    fun resetProcessState() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun androidTableIsExactlyTheGatewayOpenApiContract() {
        val openApi = JSONObject(File(GATEWAY_OPENAPI).readText(Charsets.UTF_8))
        val schema = openApi.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("FieldSessionError")
        val enumCodes = schema.getJSONObject("properties").getJSONObject("code")
            .getJSONArray("enum").let { array -> (0 until array.length()).map(array::getString) }
        assertEquals(enumCodes, GatewaySessionErrorContract.ENTRIES.keys.toList())
        val rows = schema.getJSONArray("x-walksafe-error-contract")
        val gateway = (0 until rows.length()).associate { index ->
            val row = rows.getJSONObject(index)
            val statuses = row.getJSONArray("statuses")
            row.getString("code") to GatewaySessionErrorContract.Entry(
                statuses = (0 until statuses.length()).map(statuses::getInt).toSet(),
                next = GatewaySessionNextAction.valueOf(row.getString("next_action")),
                retryable = row.opt("retryable") as? Boolean,
                reauthenticationRequired = row.opt("reauthentication_required") as? Boolean,
            )
        }
        assertEquals(gateway, GatewaySessionErrorContract.ENTRIES)
    }

    @Test
    fun everyRegisteredCodeKeepsItsCodeAndTypedActionOnRefresh() {
        for ((code, entry) in GatewaySessionErrorContract.ENTRIES) {
            val body = JSONObject().put("code", code)
            entry.retryable?.let { body.put("retryable", it) }
            entry.reauthenticationRequired?.let { body.put("reauthentication_required", it) }
            for (status in entry.statuses) {
                val error = refreshFailure(GatewayHttpResponse(status, body.toString()))
                assertEquals(code, entry.next, error.nextAction)
                assertEquals(code, code, error.serverCode)
                assertEquals(status, error.statusCode)
            }
        }
    }

    @Test
    fun nestedDetailCodesWithoutFlagsMapTheSameWay() {
        for ((code, entry) in GatewaySessionErrorContract.ENTRIES) {
            if (entry.retryable != null || entry.reauthenticationRequired != null) continue
            val body = JSONObject().put(
                "detail",
                JSONObject().put("code", code).put("message", "server text is not trusted"),
            )
            val error = refreshFailure(GatewayHttpResponse(entry.statuses.first(), body.toString()))
            assertEquals(code, entry.next, error.nextAction)
            assertEquals(code, error.serverCode)
        }
    }

    @Test
    fun unregisteredCodesStatusesAndFlagsFailClosedToReauthenticate() {
        val cases = listOf(
            GatewayHttpResponse(503, """{"code":"field_session_new_private_marker","retryable":true}"""),
            GatewayHttpResponse(500, """{"code":"field_session_storage_busy","retryable":true}"""),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy"}"""),
            GatewayHttpResponse(503, """{"code":"field_session_storage_busy","retryable":"true"}"""),
            GatewayHttpResponse(
                503,
                """{"code":"field_session_storage_busy","retryable":true,"reauthentication_required":true}""",
            ),
            GatewayHttpResponse(401, """{"code":"invalid_refresh_token"}"""),
            GatewayHttpResponse(503, "not-json"),
            GatewayHttpResponse(503, ""),
        )
        for (response in cases) {
            val error = refreshFailure(response)
            assertEquals(response.responseBody, GatewaySessionNextAction.REAUTHENTICATE, error.nextAction)
        }
        assertNull(refreshFailure(cases.first()).serverCode)
    }

    @Test
    fun transportFailureAfterSendIsOutcomeUnknownNotRetrySafe() {
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(ThrowingTransport()).renew(session(), ACTOR_ID, nowEpochMs = NOW + 1L)
        }
        assertEquals("gateway_refresh_outcome_unknown", error.reason)
        assertEquals(GatewaySessionNextAction.OUTCOME_UNKNOWN, error.nextAction)
        assertEquals(
            GatewayRenewalFailureNotice.OUTCOME_UNKNOWN,
            GatewayRenewalFailureNotice.of(error),
        )
    }

    @Test
    fun revocationAndRetrySafeSetsAreTheDocumentedOnes() {
        val revoked = GatewaySessionErrorContract.ENTRIES
            .filterValues { it.next == GatewaySessionNextAction.SESSION_REVOKED }.keys
        assertEquals(
            setOf(
                "account_generation_inactive",
                "backend_account_binding_denied",
                "field_session_revoked",
                "refresh_token_reuse_detected",
            ),
            revoked,
        )
        assertTrue(
            GatewaySessionErrorContract.ENTRIES.values
                .filter { it.retryable == true }
                .all { it.next == GatewaySessionNextAction.RETRY_SAFE },
        )
        assertEquals(
            setOf(
                "field_session_storage_outcome_unknown",
                "gateway_client_closed",
                "gateway_internal_error",
                // S19-59: a handler cut at its whole-request deadline may still have committed.
                "gateway_request_deadline_exceeded",
            ),
            GatewaySessionErrorContract.ENTRIES
                .filterValues { it.next == GatewaySessionNextAction.OUTCOME_UNKNOWN }.keys,
        )
    }

    @Test
    fun remoteDeviceRevokeReauthenticationIsARegisteredReauthenticateRow() {
        // S19-4-F1: DELETE /api/field-session?device_id=<other device> outside the recent
        // login window. The code is kept and maps to REAUTHENTICATE, never SESSION_REVOKED.
        val body = JSONObject()
            .put("code", "field_session_reauthentication_required")
            .put("message", "x")
            .put("reauthentication_required", true)
            .toString()
        assertEquals(
            GatewaySessionNextAction.REAUTHENTICATE,
            GatewaySessionErrorContract.nextAction(401, body),
        )
        assertEquals(
            "field_session_reauthentication_required",
            GatewaySessionErrorContract.registeredCodeOrNull(body),
        )
        val error = refreshFailure(GatewayHttpResponse(401, body))
        assertEquals("field_session_reauthentication_required", error.serverCode)
        assertEquals(GatewaySessionNextAction.REAUTHENTICATE, error.nextAction)
        // Without the flag the row does not match and the client fails closed the same way.
        assertEquals(
            GatewaySessionNextAction.REAUTHENTICATE,
            GatewaySessionErrorContract.nextAction(
                401,
                JSONObject().put("code", "field_session_reauthentication_required").toString(),
            ),
        )
    }

    @Test
    fun dispatchAdapterAndRelayedBackendCodesHaveTheirTypedAction() {
        val expected = mapOf(
            "gateway_method_not_allowed" to (405 to GatewaySessionNextAction.REAUTHENTICATE),
            "gateway_invalid_request" to (400 to GatewaySessionNextAction.REAUTHENTICATE),
            "gateway_internal_error" to (500 to GatewaySessionNextAction.OUTCOME_UNKNOWN),
            "account_crypto_key_binding_missing" to (503 to GatewaySessionNextAction.RETRY_SAFE),
        )
        for ((code, statusAndNext) in expected) {
            val (status, next) = statusAndNext
            val body = JSONObject().put("code", code).toString()
            val error = refreshFailure(GatewayHttpResponse(status, body))
            assertEquals(code, next, error.nextAction)
            assertEquals(code, error.serverCode)
        }
    }

    private fun refreshFailure(response: GatewayHttpResponse): GatewaySessionHttpException {
        resetGatewayRefreshProofFenceForTests()
        return assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(FixedTransport(response)).renew(
                session(),
                ACTOR_ID,
                nowEpochMs = NOW + 1L,
            )
        }
    }

    private fun session() = GatewayFieldSession.longLivedSession(
        gatewayBaseUrl = "https://field.example",
        actorId = ACTOR_ID,
        deviceId = DEVICE_ID,
        familyId = "f".repeat(32),
        rotation = 0L,
        cookiePair = GatewayFieldSession.COOKIE_NAME +
            "=access-token-rotation-0000000000000000",
        refreshToken = "r".repeat(64),
        accessExpiresAtEpochMs = NOW + 60_000L,
        idleExpiresAtEpochMs = NOW + 120_000L,
        absoluteExpiresAtEpochMs = NOW + 240_000L,
    )

    private open class FixedTransport(
        private val response: GatewayHttpResponse,
    ) : GatewaySessionTransport {
        override fun postJson(url: String, body: String): GatewayHttpResponse = response
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            GatewayHttpResponse(200, "{}")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
    }

    private class ThrowingTransport : FixedTransport(GatewayHttpResponse(200, "{}")) {
        override fun postJson(url: String, body: String): GatewayHttpResponse =
            throw java.io.IOException("connection reset after send")
    }

    private companion object {
        // Gradle runs unit tests with apps/android/app as the working directory.
        const val GATEWAY_OPENAPI = "../../android-gateway/openapi.json"
        const val ACTOR_ID = "tester-01"
        const val DEVICE_ID = "device-installation-00000001"
        const val NOW = 1_000L
    }
}
