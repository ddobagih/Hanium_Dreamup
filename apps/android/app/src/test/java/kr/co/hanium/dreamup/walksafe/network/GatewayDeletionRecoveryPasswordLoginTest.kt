package kr.co.hanium.dreamup.walksafe.network

import java.io.IOException
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDeletionRecoveryPasswordLoginTest {
    @Test
    fun passwordRecoveryUsesOnlyRecoveryPayloadAndVerifiesTheSameCookie() {
        val transport = Transport()
        val session = login(transport)

        val payload = JSONObject(transport.postedBody!!)
        assertEquals(setOf("grant_type", "email", "password", "remember_me", "purpose"), payload.keys().asSequence().toSet())
        assertEquals("password", payload.getString("grant_type"))
        assertEquals(EMAIL, payload.getString("email"))
        assertEquals(PASSWORD, payload.getString("password"))
        assertFalse(payload.getBoolean("remember_me"))
        assertEquals(SCOPE, payload.getString("purpose"))
        assertEquals("$ORIGIN/api/field-session", transport.postedUrl)
        assertEquals(transport.postedUrl, transport.statusUrl)
        assertEquals(mapOf("Cookie" to "walksafe_field_session=${cookie()}"), transport.statusHeaders)
        assertEquals(transport.statusHeaders, session.requestHeaders(NOW))
        assertEquals(ACTOR, session.actorId)
        assertEquals(5L, session.accountGenerationOrNull)
        assertEquals(GatewaySessionScope.ACCOUNT_DELETION_RECOVERY, session.sessionScope)
        assertTrue(session.deviceId.startsWith("deletion-recovery-"))
        assertNull(session.familyId)
        assertNull(session.rotation)
        assertFalse(session.isLongLived)
        assertFalse(session.isBackendAccountDeviceBound)
        assertNull(session.persistenceSnapshotOrNull())
        assertNull(session.backendDevicePersistenceSnapshotOrNull(NOW))
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun unknownExpectedGenerationKeepsTheGenerationFromTheVerifiedV6Cookie() {
        val session = login(Transport(value = cookie(generation = "6")), generation = null)
        assertEquals(6L, session.accountGenerationOrNull)
    }

    @Test
    fun invalidInputAndUnapprovedOriginMakeNoNetworkRequests() {
        val attempts = listOf<(GatewayFieldSessionClient) -> Unit>(
            { it.loginDeletionRecoveryWithPassword(ORIGIN, "invalid", PASSWORD, ACTOR, nowEpochMs = NOW) },
            { it.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, "short", ACTOR, nowEpochMs = NOW) },
            { it.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, "named-actor", nowEpochMs = NOW) },
            { it.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, " $ACTOR", nowEpochMs = NOW) },
            { it.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, ACTOR, 0L, NOW) },
            { it.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, ACTOR, MAX_ACCOUNT_GENERATION + 1L, NOW) },
            { it.loginDeletionRecoveryWithPassword("https://other.example", EMAIL, PASSWORD, ACTOR, nowEpochMs = NOW) },
        )
        for (attempt in attempts) {
            val transport = Transport()
            assertThrows(GatewaySessionHttpException::class.java) { attempt(client(transport)) }
            assertEquals(0, transport.postCount)
            assertEquals(0, transport.getCount)
            assertEquals(0, transport.deleteCount)
        }
    }

    @Test
    fun loginRequiresExactRecoveryScopeAndDisposesAnIssuedCookieOnMismatch() {
        for (body in listOf("{}", """{"session_scope":"general"}""", """{"session_scope":true}""",
            """{"session_scope":"$SCOPE","session_mode":"long_lived"}""")) {
            val transport = Transport(loginBody = body)
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(0, transport.getCount)
            assertEquals(1, transport.deleteCount)
        }
    }

    @Test
    fun rejectsOtherActorsGenerationsVersionsAndNoncanonicalV6Fields() {
        val valid = cookie().split('.')
        val changes = listOf(
            0 to "v5", 0 to "v7", 1 to encode(OTHER_ACTOR), 1 to (encode(ACTOR) + "="),
            2 to "6", 2 to "05", 2 to "0", 2 to (MAX_ACCOUNT_GENERATION + 1L).toString(),
            3 to "0", 3 to "+1", 3 to "01", 3 to (MAX_ACCOUNT_GENERATION + 1L).toString(),
            4 to "general", 5 to "1000", 5 to "01060", 5 to Long.MAX_VALUE.toString(),
            6 to "short", 7 to "A".repeat(42), 7 to ("A".repeat(42) + "="),
        )
        val invalid = changes.map { (index, replacement) -> valid.toMutableList().also { it[index] = replacement }.joinToString(".") } +
            listOf(cookie() + ".extra", cookie() + ".", "legacy-session-cookie-value",
                listOf("v7", encode(ACTOR), "5", "7", encode("device-example-0001"), "general", "1060",
                    "s".repeat(43), "A".repeat(43)).joinToString("."))
        for (value in invalid) {
            val transport = Transport(value = value)
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(0, transport.getCount)
            assertEquals(1, transport.deleteCount)
        }
    }

    @Test
    fun sessionCookieWithoutMaxAgeIsAcceptedButCookiePolicyViolationsAreRejected() {
        assertTrue(login(Transport(attributes = "Path=/; HttpOnly; SameSite=Strict; Secure")).isUsableFor(ACTOR, NOW))
        for (attributes in listOf(
            "Path=/; HttpOnly; SameSite=Strict",
            "Path=/; SameSite=Strict; Secure",
            "Path=/; HttpOnly; SameSite=Lax; Secure",
            "Path=/; HttpOnly; SameSite=Strict; Secure; Domain=field.example",
            "Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=bad",
            "Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=060",
            "Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=60; Max-Age=120",
            "Path=/; HttpOnly; SameSite=Strict; SameSite=Lax; Secure",
        )) {
            val transport = Transport(attributes = attributes)
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(0, transport.getCount)
        }
    }

    @Test
    fun statusMustConfirmRequiredAuthenticatedActorAndRecoveryScopeWithoutInventedGeneration() {
        val bodies = listOf(
            """{"required":false,"authenticated":true,"actor_id":"$ACTOR","session_scope":"$SCOPE"}""",
            """{"required":true,"authenticated":false,"actor_id":"$ACTOR","session_scope":"$SCOPE"}""",
            """{"required":"true","authenticated":true,"actor_id":"$ACTOR","session_scope":"$SCOPE"}""",
            """{"required":true,"authenticated":true,"actor_id":"$OTHER_ACTOR","session_scope":"$SCOPE"}""",
            """{"required":true,"authenticated":true,"actor_id":"$ACTOR","session_scope":"general"}""",
            """{"required":true,"authenticated":true,"actor_id":"$ACTOR"}""",
            """{"required":true,"authenticated":true,"actor_id":"$ACTOR","session_scope":"$SCOPE","account_generation":5}""",
            "not-json",
        )
        for (body in bodies) {
            val transport = Transport(statusBody = body)
            assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
            assertEquals(1, transport.getCount)
            assertEquals(1, transport.deleteCount)
            assertEquals(transport.statusHeaders, transport.deletedHeaders)
        }
    }

    @Test
    fun statusFailureOrNetworkAmbiguityDisposesTheCookie() {
        for (status in listOf(201, 204, 401, 503)) {
            val http = Transport().apply { statusCode = status }
            assertEquals(status, assertThrows(GatewaySessionHttpException::class.java) { login(http) }.statusCode)
            assertEquals(1, http.deleteCount)
        }
        val network = Transport().apply { statusFailure = IOException("test connection loss") }
        assertThrows(IOException::class.java) { login(network) }
        assertEquals(1, network.deleteCount)
    }

    @Test
    fun expiryDuringEitherPostOrStatusCannotProduceAUsableSession() {
        for (expireDuringStatus in listOf(false, true)) {
            var nanos = 0L
            val transport = Transport().apply {
                if (expireDuringStatus) afterStatus = { nanos = 61_000_000_000L }
                else afterPost = { nanos = 61_000_000_000L }
            }
            assertThrows(GatewaySessionHttpException::class.java) {
                client(transport) { nanos }.loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, ACTOR, 5L, NOW)
            }
            assertEquals(if (expireDuringStatus) 1 else 0, transport.getCount)
            assertEquals(1, transport.deleteCount)
        }
    }

    @Test
    fun bestEffortLogoutFailureDoesNotReplaceTheBindingFailure() {
        val transport = Transport(statusBody = "{}").apply { deleteFailure = IOException("test logout loss") }
        val error = assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
        assertEquals("gateway_actor_binding_failed", error.reason)
        assertEquals(1, transport.deleteCount)
    }

    @Test
    fun passwordFailureKeepsTheServerStatusAndDoesNotTryStatusOrLogoutWithoutCookie() {
        val transport = Transport().apply { loginCode = 429; setCookie = null }
        val error = assertThrows(GatewaySessionHttpException::class.java) { login(transport) }
        assertEquals(429, error.statusCode)
        assertEquals("account_password_login_failed", error.reason)
        assertEquals(1, transport.postCount)
        assertEquals(0, transport.getCount)
        assertEquals(0, transport.deleteCount)
    }

    private fun login(transport: Transport, generation: Long? = 5L): GatewayFieldSession =
        client(transport).loginDeletionRecoveryWithPassword(ORIGIN, EMAIL, PASSWORD, ACTOR, generation, NOW)

    private fun client(transport: Transport, clock: () -> Long = { 0L }) = GatewayFieldSessionClient(
        transport, elapsedRealtimeNanos = clock, approvedAccountOrigin = ORIGIN, allowDebugAccountOrigin = false,
    )

    private class Transport(
        value: String = cookie(),
        attributes: String = "Path=/; HttpOnly; SameSite=Strict; Secure; Max-Age=60",
        private val loginBody: String = """{"session_scope":"$SCOPE"}""",
        private val statusBody: String = """{"required":true,"authenticated":true,"actor_id":"$ACTOR","session_scope":"$SCOPE"}""",
    ) : GatewaySessionTransport {
        var setCookie: String? = "walksafe_field_session=$value; $attributes"
        var loginCode = 200
        var statusCode = 200
        var postCount = 0
        var getCount = 0
        var deleteCount = 0
        var postedUrl: String? = null
        var postedBody: String? = null
        var statusUrl: String? = null
        var statusHeaders: Map<String, String>? = null
        var deletedHeaders: Map<String, String>? = null
        var statusFailure: Exception? = null
        var deleteFailure: Exception? = null
        var afterPost: () -> Unit = {}
        var afterStatus: () -> Unit = {}

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            postCount++
            postedUrl = url
            postedBody = body
            afterPost()
            return GatewayHttpResponse(loginCode, loginBody, setCookie?.let { mapOf("Set-Cookie" to it) } ?: emptyMap())
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
            getCount++
            statusUrl = url
            statusHeaders = headers
            statusFailure?.let { throw it }
            afterStatus()
            return GatewayHttpResponse(statusCode, statusBody)
        }

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse {
            deleteCount++
            deletedHeaders = headers
            deleteFailure?.let { throw it }
            return GatewayHttpResponse(204, "")
        }

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            throw AssertionError("RECOVERY 로그아웃에 일반 갱신 proof를 보내면 안 됩니다")
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val EMAIL = "walker@example.test"
        const val PASSWORD = "Valid-password-123!"
        const val ACTOR = "123e4567-e89b-42d3-a456-426614174000"
        const val OTHER_ACTOR = "223e4567-e89b-42d3-a456-426614174000"
        const val SCOPE = "account_deletion_recovery"
        const val NOW = 1_000_000L

        fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

        fun cookie(generation: String = "5"): String = listOf(
            "v6", encode(ACTOR), generation, "7", SCOPE, "1060", "s".repeat(43), "A".repeat(43),
        ).joinToString(".")
    }
}
