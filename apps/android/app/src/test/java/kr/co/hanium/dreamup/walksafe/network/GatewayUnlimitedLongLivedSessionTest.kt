package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Owner decision 2 (2026-09-29, policy amendment of FP-011): the Gateway may run the long-lived
 * login with `unlimited` idle / absolute expiry and then sends JSON null for both fields
 * (field-long-session.ts sessionResponse / fieldLongSessionStatus keep their exact key sets).
 * Android accepts only that explicit null, keeps the short access expiry, keeps rotating the
 * refresh value, and restores the login after an app restart without asking for a new login.
 */
class GatewayUnlimitedLongLivedSessionTest {
    @Before
    fun reset() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @After
    fun resetAfter() = reset()

    @Test
    fun nullIdleAndAbsoluteExpiryIsAnUnlimitedLogin() {
        val session = login(sessionBody())
        assertEquals(GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS, session.idleExpiresAtEpochMs)
        assertEquals(GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS, session.absoluteExpiresAtEpochMs)
        assertEquals(ACCESS, session.accessExpiresAtEpochMs)
        assertTrue(session.isUsableFor(ACTOR, NOW))
        // The access credential stays short; years later the login is still renewable.
        assertFalse(session.isUsableFor(ACTOR, TEN_YEARS_LATER))
        assertTrue(session.isRenewableFor(ACTOR, TEN_YEARS_LATER))
    }

    @Test
    fun finiteIdleUnderUnlimitedAbsoluteIsAccepted() {
        val session = login(sessionBody().put("idle_expires_at_epoch_ms", IDLE))
        assertEquals(IDLE, session.idleExpiresAtEpochMs)
        assertEquals(GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS, session.absoluteExpiresAtEpochMs)
    }

    @Test
    fun unlimitedIsOnlyTheExplicitNullAndKeepsTheOrdering() {
        // Unlimited idle under a finite absolute is an inverted pair.
        assertRejected(sessionBody().put("absolute_expires_at_epoch_ms", ABSOLUTE))
        // A numeric sentinel or an oversized finite value is not a disguised unlimited expiry.
        assertRejected(sessionBody().put("idle_expires_at_epoch_ms", Long.MAX_VALUE))
        assertRejected(
            sessionBody()
                .put("idle_expires_at_epoch_ms", IDLE)
                .put("absolute_expires_at_epoch_ms", Long.MAX_VALUE),
        )
        assertRejected(
            sessionBody()
                .put("idle_expires_at_epoch_ms", NOW + 91L * 86_400_000L)
                .put("absolute_expires_at_epoch_ms", NOW + 200L * 86_400_000L),
        )
        // The key set stays exact: a missing field is not unlimited.
        assertRejected(sessionBody().also { it.remove("idle_expires_at_epoch_ms") })
        // The access expiry is never unlimited.
        assertRejected(sessionBody().put("access_expires_at_epoch_ms", JSONObject.NULL))
        assertRejected(sessionBody().put("idle_expires_at_epoch_ms", "unlimited"))
    }

    @Test
    fun statusWithNullExpiryMatchesAnUnlimitedSessionOnly() {
        val unlimited = login(sessionBody())
        assertEquals(GatewaySessionRevalidationStatus.READY, revalidate(unlimited, statusBody()).status)
        val numeric = statusBody()
            .put("idle_expires_at_epoch_ms", IDLE)
            .put("absolute_expires_at_epoch_ms", ABSOLUTE)
        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, revalidate(unlimited, numeric).status)
        val finite = login(
            sessionBody()
                .put("idle_expires_at_epoch_ms", IDLE)
                .put("absolute_expires_at_epoch_ms", ABSOLUTE),
        )
        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, revalidate(finite, statusBody()).status)
        assertEquals(GatewaySessionRevalidationStatus.READY, revalidate(finite, numeric).status)
    }

    @Test
    fun renewalYearsLaterRotatesAndKeepsTheUnlimitedExpiry() {
        val session = login(sessionBody())
        val later = TEN_YEARS_LATER
        val renewedBody = sessionBody()
            .put("rotation", 1L)
            .put("refresh_token", "s".repeat(64))
            .put("access_expires_at_epoch_ms", later + 60_000L)
        val transport = Transport(
            login = GatewayHttpResponse(200, renewedBody.toString(), mapOf("Set-Cookie" to cookie("renewed"))),
        )
        val renewal = GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { 0L })
            .renew(session, ACTOR, later)
        assertEquals(1L, renewal.session.rotation)
        assertEquals(GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS, renewal.session.absoluteExpiresAtEpochMs)
        assertTrue(renewal.session.isUsableFor(ACTOR, later))
        val sent = JSONObject(requireNotNull(transport.lastPost))
        assertEquals("refresh_token", sent.getString("grant_type"))
        assertEquals(0L, sent.getLong("rotation"))
        // A server that suddenly sends a finite absolute expiry for the same family is refused.
        val second = login(sessionBody().put("refresh_token", "t".repeat(64)))
        val changedAbsolute = Transport(
            login = GatewayHttpResponse(
                200,
                renewedBody.put("absolute_expires_at_epoch_ms", later + 86_400_000L).toString(),
                mapOf("Set-Cookie" to cookie("renewed2")),
            ),
        )
        assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(changedAbsolute, elapsedRealtimeNanos = { 0L })
                .renew(second, ACTOR, later)
        }
    }

    @Test
    fun persistedUnlimitedSessionRestoresYearsLaterWithoutANewLogin() {
        val session = login(sessionBody())
        val snapshot = requireNotNull(session.persistenceSnapshotOrNull())
        val restored = GatewayFieldSession.restore(
            snapshot = snapshot,
            expectedGatewayBaseUrl = ORIGIN,
            expectedActorId = ACTOR,
            expectedDeviceId = DEVICE,
            nowEpochMs = TEN_YEARS_LATER,
        )
        assertNotNull(restored)
        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored!!.verificationState)
        assertTrue(restored.isRenewableFor(ACTOR, TEN_YEARS_LATER))
        // A finite persisted session with an out-of-bound idle expiry still fails closed.
        val finite = GatewayFieldSessionPersistence(
            gatewayBaseUrl = ORIGIN,
            actorId = ACTOR,
            accessCookiePair = cookie("finite").substringBefore(';'),
            refreshToken = REFRESH_TOKEN,
            deviceId = DEVICE,
            familyId = FAMILY,
            rotation = 0L,
            accessExpiresAtEpochMs = ACCESS,
            idleExpiresAtEpochMs = NOW + 91L * 86_400_000L,
            absoluteExpiresAtEpochMs = NOW + 200L * 86_400_000L,
        )
        assertNull(GatewayFieldSession.restore(finite, ORIGIN, ACTOR, DEVICE, NOW))
    }

    private fun assertRejected(body: JSONObject) {
        val error = assertThrows(GatewaySessionHttpException::class.java) { login(body) }
        assertEquals(0, error.statusCode)
    }

    private fun login(body: JSONObject): GatewayFieldSession {
        val transport = Transport(
            login = GatewayHttpResponse(200, body.toString(), mapOf("Set-Cookie" to cookie("login"))),
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

    private fun revalidate(session: GatewayFieldSession, status: JSONObject): GatewaySessionRevalidation =
        GatewayFieldSessionClient(Transport(status = GatewayHttpResponse(200, status.toString())))
            .revalidate(session, ACTOR, NOW)

    private fun cookie(label: String) =
        "${GatewayFieldSession.COOKIE_NAME}=access-token-unlimited-$label-0000001; " +
            "Path=/; HttpOnly; SameSite=Strict; Max-Age=60; Secure"

    /** field-long-session.ts sessionResponse (9 keys) of an `unlimited` Gateway. */
    private fun sessionBody(): JSONObject = JSONObject()
        .put("session_scope", "general")
        .put("actor_id", ACTOR)
        .put("device_id", DEVICE)
        .put("family_id", FAMILY)
        .put("rotation", 0L)
        .put("refresh_token", REFRESH_TOKEN)
        .put("access_expires_at_epoch_ms", ACCESS)
        .put("idle_expires_at_epoch_ms", JSONObject.NULL)
        .put("absolute_expires_at_epoch_ms", JSONObject.NULL)

    /** field-long-session.ts fieldLongSessionStatus (11 keys) of an `unlimited` Gateway. */
    private fun statusBody(): JSONObject = JSONObject()
        .put("required", true)
        .put("authenticated", true)
        .put("actor_id", ACTOR)
        .put("session_scope", "general")
        .put("session_mode", "long_lived")
        .put("device_id", DEVICE)
        .put("family_id", FAMILY)
        .put("rotation", 0L)
        .put("access_expires_at_epoch_ms", ACCESS)
        .put("idle_expires_at_epoch_ms", JSONObject.NULL)
        .put("absolute_expires_at_epoch_ms", JSONObject.NULL)

    private class Transport(
        private val login: GatewayHttpResponse? = null,
        private val status: GatewayHttpResponse? = null,
    ) : GatewaySessionTransport {
        var lastPost: String? = null

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            lastPost = body
            return login ?: error("unexpected POST")
        }

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
        const val DEVICE = "android-device-unlimited-01"
        const val FAMILY = "uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu"
        const val ACCOUNT_TOKEN = "field-account-token-unlimited-0001"
        val REFRESH_TOKEN = "r".repeat(64)
        const val NOW = 1_000L
        const val ACCESS = 61_000L
        const val IDLE = 121_000L
        const val ABSOLUTE = 241_000L
        const val TEN_YEARS_LATER = NOW + 10L * 365L * 86_400_000L
    }
}
