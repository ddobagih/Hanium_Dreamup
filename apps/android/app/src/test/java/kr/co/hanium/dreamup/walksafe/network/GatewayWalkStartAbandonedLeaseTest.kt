package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S03-22: a start the gateway granted after the operation became stale is never published, but
 * the lease this operation acquired for its own walk is handed back (without another request) so
 * the caller can end exactly that lease.
 */
class GatewayWalkStartAbandonedLeaseTest {
    @Test
    fun staleAfterOwnGrantHandsBackThatLeaseWithoutAnotherRequest() {
        val transport = RecordingTransport(lease("ACQUIRED", NEW_WALK, 8L))
        var checks = 0

        val error = stale { recover(transport, isCurrent = { ++checks < 2 }) }

        assertEquals(NEW_WALK, error.abandonedLease?.walkId)
        assertEquals("lease-0008", error.abandonedLease?.leaseId)
        assertEquals(8L, error.abandonedLease?.fencingToken)
        assertEquals(listOf("start"), transport.actions())
    }

    @Test
    fun staleAfterRecoveredNewWalkGrantHandsBackOnlyTheNewWalkLease() {
        val transport = RecordingTransport(
            conflict(),
            lease("ALREADY_ACTIVE", OLD_WALK, 7L),
            ended(),
            lease("ACQUIRED", NEW_WALK, 8L),
        )
        var checks = 0

        val error = stale { recover(transport, isCurrent = { ++checks < 8 }) }

        assertEquals(NEW_WALK, error.abandonedLease?.walkId)
        assertEquals("lease-0008", error.abandonedLease?.leaseId)
        assertEquals(listOf("start", "start", "end", "start"), transport.actions())
    }

    @Test
    fun staleAfterOldWalkOwnershipProbeDoesNotHandBackTheOldWalkLease() {
        val transport = RecordingTransport(conflict(), lease("ALREADY_ACTIVE", OLD_WALK, 7L))
        var checks = 0

        val error = stale { recover(transport, isCurrent = { ++checks < 4 }) }

        assertNull(error.abandonedLease)
        assertEquals(listOf("start", "start"), transport.actions())
    }

    @Test
    fun staleAfterConflictHasNoLeaseToRelease() {
        val transport = RecordingTransport(conflict(deviceId = OTHER_DEVICE))
        var checks = 0

        val error = stale { recover(transport, isCurrent = { ++checks < 2 }) }

        assertNull(error.abandonedLease)
        assertEquals(listOf("start"), transport.actions())
    }

    @Test
    fun loginInvalidatedDuringGrantStillReportsTheOwnLeaseForRelease() {
        val session = session()
        val transport = RecordingTransport(lease("ACQUIRED", NEW_WALK, 8L)).apply {
            afterRequest = { session.invalidate() }
        }

        val error = stale { recover(transport, session = session) }

        assertEquals(NEW_WALK, error.abandonedLease?.walkId)
        assertEquals(1, transport.commands.size)
    }

    private fun recover(
        transport: RecordingTransport,
        session: GatewayFieldSession = session(),
        isCurrent: () -> Boolean = { true },
    ): GatewayWalkStartResult = GatewayWalkSessionClient(transport).startWithOwnedWalkRecovery(
        session, NEW_WALK, "request-0001", { 10_000L }, isCurrent,
    )

    private fun stale(block: () -> Unit): GatewayWalkHttpException {
        try {
            block()
        } catch (error: GatewayWalkHttpException) {
            assertEquals(0, error.statusCode)
            assertEquals("walk_start_operation_stale", error.reason)
            return error
        }
        throw AssertionError("expected stale operation rejection")
    }

    private fun session(): GatewayFieldSession =
        GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = "actor-test",
            deviceId = DEVICE,
            familyId = "f".repeat(32),
            rotation = 1L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(32)}",
            refreshToken = "r".repeat(64),
            accessExpiresAtEpochMs = Long.MAX_VALUE,
            idleExpiresAtEpochMs = Long.MAX_VALUE,
            absoluteExpiresAtEpochMs = Long.MAX_VALUE,
        )

    private fun lease(result: String, walkId: String, fence: Long): GatewayHttpResponse =
        GatewayHttpResponse(
            if (result == "ACQUIRED") 201 else 200,
            JSONObject()
                .put("schema_version", RESPONSE_SCHEMA)
                .put("result", result)
                .put("walk_id", walkId)
                .put("lease_id", "lease-000$fence")
                .put("fencing_token", fence)
                .put("acquired_at_epoch_ms", 1_000_000L)
                .put("lease_expires_at_epoch_ms", 1_090_000L)
                .put("server_time_epoch_ms", 1_000_000L)
                .toString(),
        )

    private fun conflict(deviceId: String = DEVICE): GatewayHttpResponse = GatewayHttpResponse(
        409,
        JSONObject()
            .put("schema_version", RESPONSE_SCHEMA)
            .put("code", "walk_lease_conflict")
            .put("active_walk_id", OLD_WALK)
            .put("active_device_id", deviceId)
            .put("fencing_token", 7L)
            .put("lease_expires_at_epoch_ms", 1_090_000L)
            .put("server_time_epoch_ms", 1_000_000L)
            .toString(),
    )

    private fun ended(): GatewayHttpResponse = GatewayHttpResponse(
        200,
        JSONObject()
            .put("schema_version", RESPONSE_SCHEMA)
            .put("result", "ENDED")
            .put("walk_id", OLD_WALK)
            .put("lease_id", "lease-0007")
            .put("fencing_token", 7L)
            .put("ended_at_epoch_ms", 1_000_100L)
            .put("server_time_epoch_ms", 1_000_100L)
            .toString(),
    )

    private class RecordingTransport(vararg responses: GatewayHttpResponse) : GatewaySessionTransport {
        private val responses = responses.toList()
        val commands = mutableListOf<JSONObject>()
        var afterRequest: (Int) -> Unit = {}

        fun actions(): List<String> = commands.map { it.getString("action") }

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): GatewayHttpResponse {
            val response = responses[commands.size]
            commands += JSONObject(body)
            afterRequest(commands.size)
            return response
        }

        override fun postJson(url: String, body: String): GatewayHttpResponse =
            throw AssertionError("unauthenticated request")

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse =
            throw AssertionError("unexpected GET")

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse =
            throw AssertionError("unexpected DELETE")

        override fun deleteJson(url: String, body: String): GatewayHttpResponse =
            throw AssertionError("unexpected DELETE")
    }

    private companion object {
        const val OLD_WALK = "walk-before-restart"
        const val NEW_WALK = "walk-after-restart"
        const val DEVICE = "device-0001"
        const val OTHER_DEVICE = "device-0002"
        const val RESPONSE_SCHEMA = "walksafe.field-walk-response.v1"
    }
}
