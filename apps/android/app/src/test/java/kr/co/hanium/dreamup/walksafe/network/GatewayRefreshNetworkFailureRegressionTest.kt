package kr.co.hanium.dreamup.walksafe.network

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class GatewayRefreshNetworkFailureRegressionTest {
    @Before
    fun resetProofFence() = resetGatewayRefreshProofFenceForTests()

    @Test
    fun connectionFailureBecomesUnknownOutcomeAndInvalidatesTheLease() {
        assertUnknownOutcome(ConnectException("synthetic connection failure"))
    }

    @Test
    fun responseTimeoutBecomesUnknownOutcomeAndInvalidatesTheLease() {
        assertUnknownOutcome(SocketTimeoutException("synthetic response timeout"))
    }

    @Test
    fun responseStreamDisconnectBecomesUnknownOutcomeAndInvalidatesTheLease() {
        assertUnknownOutcome(EOFException("synthetic truncated response"))
    }

    @Test
    fun otherTransportIoFailureBecomesUnknownOutcomeAndInvalidatesTheLease() {
        assertUnknownOutcome(IOException("synthetic transport IO failure"))
    }

    @Test
    fun runtimeTransportFailureKeepsTheExistingUnknownOutcomeContract() {
        assertUnknownOutcome(IllegalStateException("synthetic transport failure"))
    }

    @Test
    fun ambiguousIoFailureCannotReuseTheSameRefreshProofAfterRestoration() {
        val original = session()
        val persisted = requireNotNull(original.persistenceSnapshotOrNull())
        val transport = FailingTransport(SocketTimeoutException("synthetic response timeout"))
        val client = client(transport)
        assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(original, ACTOR, NOW)
        }
        val restored = requireNotNull(GatewayFieldSession.restore(
            persisted, ORIGIN, ACTOR, nowEpochMs = NOW,
        ))
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(restored, ACTOR, NOW)
        }
        assertEquals("gateway_refresh_proof_already_attempted", error.reason)
        assertEquals(1, transport.posts)
    }

    private fun assertUnknownOutcome(failure: Exception) {
        val original = session()
        val transport = FailingTransport(failure)
        val client = client(transport)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(original, ACTOR, NOW)
        }
        assertEquals("gateway_refresh_outcome_unknown", error.reason)
        assertEquals(0, error.statusCode)
        assertNull(error.serverCode)
        assertEquals(1, transport.posts)
        assertFalse(original.isUsableFor(ACTOR, NOW))
        assertFalse(original.isRenewableFor(ACTOR, NOW))
        assertNull(original.persistenceSnapshotOrNull())
        assertThrows(IllegalStateException::class.java) { original.requestHeaders(NOW) }
        val repeat = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(original, ACTOR, NOW)
        }
        assertEquals("gateway_refresh_not_allowed", repeat.reason)
        assertEquals(1, transport.posts)
    }

    private fun client(transport: GatewaySessionTransport) = GatewayFieldSessionClient(
        transport = transport,
        approvedAccountOrigin = ORIGIN,
        allowDebugAccountOrigin = false,
    )

    private fun session() = GatewayFieldSession.longLivedSession(
        gatewayBaseUrl = ORIGIN,
        actorId = ACTOR,
        deviceId = "device-installation-00000001",
        familyId = "f".repeat(32),
        rotation = 0L,
        cookiePair = "walksafe_field_session=access-token-rotation-0000000000000000",
        refreshToken = "r".repeat(64),
        accessExpiresAtEpochMs = NOW + 60_000L,
        idleExpiresAtEpochMs = NOW + 120_000L,
        absoluteExpiresAtEpochMs = NOW + 240_000L,
    )

    private class FailingTransport(private val failure: Exception) : GatewaySessionTransport {
        var posts = 0
        override fun postJson(url: String, body: String): GatewayHttpResponse {
            assertEquals("refresh_token", JSONObject(body).getString("grant_type"))
            posts += 1
            throw failure
        }
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("refresh must not fall back to GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            error("client does not perform recovery revocation itself")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            error("client does not perform recovery revocation itself")
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR = "tester-01"
        const val NOW = 1_000L
    }
}
