package kr.co.hanium.dreamup.walksafe.network

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S01-16 pin (current behaviour, no policy change): `remember_me` on the password login is a
 * short, server-bounded (<= 12 h) cookie session. It carries no refresh value, cannot be renewed
 * and a restored copy never outlives the server expiry, so it must not be described or wired as
 * the FP-011 long-lived login. Whether an app reopen may restore it at all is the pending
 * FP-011-DETAIL-01 interim decision and is intentionally not asserted here.
 */
class GatewayPasswordLoginIsNotLongLivedLoginTest {
    @Before
    fun reset() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun rememberMeRequiresAnExplicitMaxAgeCookie() {
        val transport = transport(maxAge = null)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            login(transport, rememberMe = true)
        }
        assertEquals("invalid_gateway_cookie", error.reason)
        assertEquals(0, transport.getCount)
    }

    @Test
    fun rememberMeCookieLongerThanTwelveHoursIsRejected() {
        val transport = transport(maxAge = TWELVE_HOURS_S + 1L)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            login(transport, rememberMe = true)
        }
        assertEquals("invalid_gateway_cookie", error.reason)
    }

    @Test
    fun rememberMeSessionHasNoRefreshValueAndCannotBeRenewed() {
        val transport = transport(maxAge = TWELVE_HOURS_S)
        val session = login(transport, rememberMe = true)
        assertTrue(session.isBackendAccountDeviceBound)
        assertFalse(session.isLongLived)
        assertFalse(session.isRenewableFor(ACTOR_ID, NOW))
        assertNull("no long-lived refresh persistence", session.persistenceSnapshotOrNull())
        assertEquals(EXPIRES_AT_S * 1_000L, session.accessExpiresAtEpochMs)
        assertEquals(session.accessExpiresAtEpochMs, session.absoluteExpiresAtEpochMs)
        val posts = transport.postCount
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, approvedAccountOrigin = ORIGIN, allowDebugAccountOrigin = false)
                .renew(session, ACTOR_ID, NOW)
        }
        assertEquals("gateway_refresh_not_allowed", error.reason)
        assertEquals("renew must not send anything for a password session", posts, transport.postCount)
    }

    @Test
    fun restoredPasswordSessionNeverOutlivesTheServerExpiry() {
        val session = login(transport(maxAge = TWELVE_HOURS_S), rememberMe = true)
        val snapshot = assertNotNullAndGet(session.backendDevicePersistenceSnapshotOrNull(NOW))
        val expiresAt = EXPIRES_AT_S * 1_000L
        val justBefore = GatewayFieldSession.restoreBackendAccountDevice(
            snapshot, ORIGIN, ACTOR_ID, DEVICE_ID, nowEpochMs = expiresAt - 1L,
        )
        assertNotNull(justBefore)
        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, justBefore!!.verificationState)
        assertFalse("restored copy needs server revalidation first", justBefore.isUsableFor(ACTOR_ID, expiresAt - 1L))
        assertNull(
            GatewayFieldSession.restoreBackendAccountDevice(
                snapshot, ORIGIN, ACTOR_ID, DEVICE_ID, nowEpochMs = expiresAt,
            ),
        )
    }

    @Test
    fun rememberMeOffKeepsTheSameServerBoundInsteadOfALongerOne() {
        val session = login(transport(maxAge = null), rememberMe = false)
        assertFalse(session.isLongLived)
        assertEquals(EXPIRES_AT_S * 1_000L, session.accessExpiresAtEpochMs)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun login(transport: Transport, rememberMe: Boolean): GatewayFieldSession =
        GatewayFieldSessionClient(
            transport,
            elapsedRealtimeNanos = { 0L },
            approvedAccountOrigin = ORIGIN,
            allowDebugAccountOrigin = false,
        ).loginWithPassword(
            gatewayBaseUrl = ORIGIN,
            email = "person@example.com",
            password = "correct horse battery",
            rememberMe = rememberMe,
            deviceId = DEVICE_ID,
            nowEpochMs = NOW,
        )

    private fun transport(maxAge: Long?): Transport {
        val cookie = listOf(
            "v7", b64(ACTOR_ID), "7", "3", b64(DEVICE_ID), "general",
            EXPIRES_AT_S.toString(), "i".repeat(43), "s".repeat(43),
        ).joinToString(".")
        val attributes = "Path=/; HttpOnly; SameSite=Strict; Secure" +
            (maxAge?.let { "; Max-Age=$it" } ?: "")
        return Transport(
            post = GatewayHttpResponse(
                200,
                """{"session_scope":"general"}""",
                mapOf("Set-Cookie" to "${GatewayFieldSession.COOKIE_NAME}=$cookie; $attributes"),
            ),
            get = GatewayHttpResponse(
                200,
                """{"required":true,"authenticated":true,"actor_id":"$ACTOR_ID","session_scope":"general"}""",
            ),
        )
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private class Transport(
        private val post: GatewayHttpResponse,
        private val get: GatewayHttpResponse,
    ) : GatewaySessionTransport {
        var postCount = 0
        var getCount = 0
        override fun postJson(url: String, body: String): GatewayHttpResponse {
            postCount += 1
            return post
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
            getCount += 1
            return get
        }
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            GatewayHttpResponse(204, "")
    }

    private companion object {
        const val ORIGIN = "https://gateway.example"
        const val ACTOR_ID = "123e4567-e89b-42d3-a456-426614174000"
        const val DEVICE_ID = "android-device-account-01"
        const val NOW = 1_000_000L
        const val TWELVE_HOURS_S = 12L * 60L * 60L
        const val EXPIRES_AT_S = NOW / 1_000L + TWELVE_HOURS_S
    }
}
