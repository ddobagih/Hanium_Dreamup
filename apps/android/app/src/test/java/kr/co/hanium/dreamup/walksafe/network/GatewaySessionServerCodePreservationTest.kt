package kr.co.hanium.dreamup.walksafe.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class GatewaySessionServerCodePreservationTest {
    @Before
    @After
    fun resetProcessState() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun refreshBusyCodeAndRetryAfterArePreservedWithoutAutomaticRetry() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 503,
                responseBody = """{"code":"field_session_storage_busy","retryable":true}""",
                headers = mapOf("Retry-After" to "1"),
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(503, error.statusCode)
        assertEquals("gateway_refresh_failed", error.reason)
        assertEquals("field_session_storage_busy", error.serverCode)
        assertEquals(1_000L, error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun nestedBusyCodeIsPreservedOnPasswordLoginWithoutFollowupRequests() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 503,
                responseBody = """{"detail":{"code":"field_session_storage_busy"}}""",
                headers = mapOf("Retry-After" to "1"),
            ),
        )
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).loginWithPassword(
                gatewayBaseUrl = "http://127.0.0.1:8081",
                email = "walker@example.test",
                password = "Valid-password-123!",
                rememberMe = false,
                deviceId = DEVICE_ID,
                nowEpochMs = NOW,
            )
        }

        assertEquals(503, error.statusCode)
        assertEquals("account_password_login_failed", error.reason)
        assertEquals("field_session_storage_busy", error.serverCode)
        assertEquals(1_000L, error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun unknownBusyLookingCodeStillRemainsPrivate() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 503,
                responseBody =
                    """{"code":"field_session_storage_busy_private_marker","retryable":true}""",
                headers = mapOf("Retry-After" to "1"),
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(503, error.statusCode)
        assertNull(error.serverCode)
        assertFalse(error.message.orEmpty().contains("private_marker"))
        assertEquals(1_000L, error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun explicitRevocationKeepsItsAuthenticationFailureCode() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 401,
                responseBody = """{"code":"field_session_revoked"}""",
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(401, error.statusCode)
        assertEquals("gateway_refresh_failed", error.reason)
        assertEquals("field_session_revoked", error.serverCode)
        assertNull(error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun invalidRetryAfterDoesNotDiscardTheKnownBusyCode() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 503,
                responseBody = """{"code":"field_session_storage_busy"}""",
                headers = mapOf("Retry-After" to "not-a-delay"),
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(503, error.statusCode)
        assertEquals("field_session_storage_busy", error.serverCode)
        assertNull(error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun preservingBusyCodeDoesNotReleaseTheConsumedRefreshProof() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 503,
                responseBody = """{"code":"field_session_storage_busy","retryable":true}""",
                headers = mapOf("Retry-After" to "1"),
            ),
        )
        val client = GatewayFieldSessionClient(transport)
        val source = session()
        val first = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(source, ACTOR_ID, nowEpochMs = NOW + 1L)
        }
        val second = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(source, ACTOR_ID, nowEpochMs = NOW + 2L)
        }

        assertEquals("field_session_storage_busy", first.serverCode)
        assertEquals(0, second.statusCode)
        assertNull(second.serverCode)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun nestedAuthenticationStaleCodeIsPreservedOnRefreshWithoutAutomaticRetry() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 401,
                responseBody = """{"detail":{"code":"account_authentication_stale"}}""",
                headers = mapOf("Cache-Control" to "no-store"),
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(401, error.statusCode)
        assertEquals("gateway_refresh_failed", error.reason)
        assertEquals("account_authentication_stale", error.serverCode)
        assertNull(error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun nestedAuthenticationStaleCodeIsPreservedOnPasswordLogin() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 401,
                responseBody = """{"detail":{"code":"account_authentication_stale"}}""",
                headers = mapOf("Cache-Control" to "no-store"),
            ),
        )
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).loginWithPassword(
                gatewayBaseUrl = "http://127.0.0.1:8081",
                email = "walker@example.test",
                password = "Valid-password-123!",
                rememberMe = false,
                deviceId = DEVICE_ID,
                nowEpochMs = NOW,
            )
        }

        assertEquals(401, error.statusCode)
        assertEquals("account_password_login_failed", error.reason)
        assertEquals("account_authentication_stale", error.serverCode)
        assertNull(error.retryAfterMs)
        assertNoFollowupRequests(transport)
    }

    @Test
    fun unknownAuthenticationStaleLookalikeRemainsPrivate() {
        val transport = ErrorTransport(
            GatewayHttpResponse(
                statusCode = 401,
                responseBody =
                    """{"detail":{"code":"account_authentication_stale_private_marker"}}""",
            ),
        )
        val error = refreshFailure(transport)

        assertEquals(401, error.statusCode)
        assertNull(error.serverCode)
        assertFalse(error.message.orEmpty().contains("private_marker"))
        assertNoFollowupRequests(transport)
    }

    private fun refreshFailure(transport: ErrorTransport): GatewaySessionHttpException =
        assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport).renew(
                session(),
                ACTOR_ID,
                nowEpochMs = NOW + 1L,
            )
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

    private fun assertNoFollowupRequests(transport: ErrorTransport) {
        assertEquals(1, transport.postCount)
        assertEquals(0, transport.getCount)
        assertEquals(0, transport.deleteCount)
    }

    private class ErrorTransport(
        private val response: GatewayHttpResponse,
    ) : GatewaySessionTransport {
        var postCount = 0
        var getCount = 0
        var deleteCount = 0

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            postCount += 1
            return response
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
            getCount += 1
            return GatewayHttpResponse(200, "{}")
        }

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse {
            deleteCount += 1
            return GatewayHttpResponse(204, "")
        }

        override fun deleteJson(url: String, body: String): GatewayHttpResponse {
            deleteCount += 1
            return GatewayHttpResponse(204, "")
        }
    }

    private companion object {
        const val ACTOR_ID = "tester-01"
        const val DEVICE_ID = "device-installation-00000001"
        const val NOW = 1_000L
    }
}
