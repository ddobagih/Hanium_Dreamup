package kr.co.hanium.dreamup.walksafe.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWalkRestartRecoveryTest {
    @Test
    fun sameDeviceRestartEndsOnlyVerifiedOldLeaseAndStartsNewWalk() {
        val transport = recoveredTransport()
        val result = recover(transport) as GatewayWalkStartResult.Granted

        assertEquals(NEW_WALK, result.lease.walkId)
        assertEquals(8L, result.lease.fencingToken)
        assertEquals(listOf("start", "start", "end", "start"), transport.actions())
        assertEquals(listOf(NEW_WALK, OLD_WALK, OLD_WALK, NEW_WALK), transport.walks())
        val end = transport.commands[2]
        assertEquals("lease-0007", end.getString("lease_id"))
        assertEquals(7L, end.getLong("fencing_token"))
        assertTrue(transport.commands.none { it.has("confirmation") })
    }

    @Test
    fun newLeaseAndSameWalkIdempotentSuccessDoNotEnterRecovery() {
        for (resultName in listOf("ACQUIRED", "ALREADY_ACTIVE")) {
            val transport = RecordingTransport(lease(resultName, NEW_WALK, 8L))

            val result = recover(transport) as GatewayWalkStartResult.Granted

            assertEquals(resultName, result.lease.result)
            assertEquals(1, transport.commands.size)
        }
    }

    @Test
    fun otherDeviceConflictDoesNotProbeOrEndItsWalk() {
        val transport = RecordingTransport(conflict(deviceId = OTHER_DEVICE))

        val result = recover(transport) as GatewayWalkStartResult.Conflict

        assertEquals(OTHER_DEVICE, result.conflict.activeDeviceId)
        assertEquals(listOf("start"), transport.actions())
    }

    @Test
    fun sameDeviceDifferentLoginFamilyIsRejectedByOldWalkStartWithoutEnding() {
        // The gateway checks actor, device AND login family on the old-ID start.
        val transport = RecordingTransport(conflict(), conflict())
        val differentFamily = session(familyId = "g".repeat(32))

        val result = recover(transport, session = differentFamily)

        assertTrue(result is GatewayWalkStartResult.Conflict)
        assertEquals(listOf("start", "start"), transport.actions())
        assertEquals(listOf(NEW_WALK, OLD_WALK), transport.walks())
    }

    @Test
    fun sameWalkConflictFromDifferentFamilyDoesNotRetryIdenticalStart() {
        val transport = RecordingTransport(conflict(walkId = NEW_WALK))

        assertTrue(recover(transport) is GatewayWalkStartResult.Conflict)
        assertEquals(1, transport.commands.size)
    }

    @Test
    fun cancellationBeforeEachNetworkStepPreventsThatStep() {
        for ((staleCheck, expectedRequests) in listOf(1 to 0, 3 to 1, 5 to 2, 7 to 3)) {
            val transport = recoveredTransport()
            var checks = 0

            assertStale {
                recover(transport, isCurrent = { ++checks < staleCheck })
            }

            assertEquals("stale check $staleCheck", expectedRequests, transport.commands.size)
        }
    }

    @Test
    fun cancellationDuringEachResponseStopsFurtherRequestsAndLeasePublication() {
        for (cancelAfterRequest in 1..4) {
            var current = true
            val transport = recoveredTransport().apply {
                afterRequest = { count -> if (count == cancelAfterRequest) current = false }
            }

            assertStale { recover(transport, isCurrent = { current }) }

            assertEquals(cancelAfterRequest, transport.commands.size)
        }
    }

    @Test
    fun invalidatedLoginAfterOwnershipCheckCannotEndOldLease() {
        val session = session()
        val transport = recoveredTransport().apply {
            afterRequest = { count -> if (count == 2) session.invalidate() }
        }

        assertStale { recover(transport, session = session) }

        assertEquals(listOf("start", "start"), transport.actions())
    }

    @Test
    fun alreadyActiveWithRacingFenceIsReturnedAsConflictWithoutEnding() {
        val transport = RecordingTransport(conflict(), lease("ALREADY_ACTIVE", OLD_WALK, 9L))

        val result = recover(transport) as GatewayWalkStartResult.Conflict

        assertEquals(9L, result.conflict.fencingToken)
        assertEquals(listOf("start", "start"), transport.actions())
    }

    @Test
    fun expiryBeforeOwnershipProbeCleansNewlyAcquiredOldIdLease() {
        val transport = RecordingTransport(
            conflict(),
            lease("ACQUIRED", OLD_WALK, 8L),
            ended(fence = 8L),
            lease("ACQUIRED", NEW_WALK, 9L),
        )

        val result = recover(transport) as GatewayWalkStartResult.Granted

        assertEquals(NEW_WALK, result.lease.walkId)
        assertEquals(9L, result.lease.fencingToken)
        assertEquals(8L, transport.commands[2].getLong("fencing_token"))
        assertEquals("lease-0008", transport.commands[2].getString("lease_id"))
        assertEquals(listOf("start", "start", "end", "start"), transport.actions())
    }

    @Test
    fun acquiredOldIdWithoutIncreasingFenceIsNotEnded() {
        val transport = RecordingTransport(conflict(), lease("ACQUIRED", OLD_WALK, 7L))

        assertTrue(recover(transport) is GatewayWalkStartResult.Conflict)
        assertEquals(listOf("start", "start"), transport.actions())
    }

    @Test
    fun takeoverBeforeOwnershipProbePreservesNewOwner() {
        val transport = RecordingTransport(
            conflict(),
            conflict(walkId = "walk-taken", deviceId = OTHER_DEVICE, fence = 8L),
        )

        val result = recover(transport) as GatewayWalkStartResult.Conflict

        assertEquals("walk-taken", result.conflict.activeWalkId)
        assertEquals(listOf("start", "start"), transport.actions())
    }

    @Test
    fun takeoverDuringEndCasCannotEndNewOwnerAndDoesNotLoop() {
        val takeover = conflict(walkId = "walk-taken", deviceId = OTHER_DEVICE, fence = 8L)
        val transport = RecordingTransport(
            conflict(),
            lease("ALREADY_ACTIVE", OLD_WALK, 7L),
            takeover,
            takeover,
        )

        val result = recover(transport) as GatewayWalkStartResult.Conflict

        assertEquals(OTHER_DEVICE, result.conflict.activeDeviceId)
        assertEquals(7L, transport.commands[2].getLong("fencing_token"))
        assertEquals(listOf("start", "start", "end", "start"), transport.actions())
    }

    @Test
    fun expiryDuringEndCasAllowsOneNormalNewWalkStart() {
        val expiredConflict = JSONObject(conflict().responseBody)
            .put("active_walk_id", JSONObject.NULL)
            .put("active_device_id", JSONObject.NULL)
            .put("fencing_token", JSONObject.NULL)
            .put("lease_expires_at_epoch_ms", JSONObject.NULL)
        val transport = RecordingTransport(
            conflict(),
            lease("ALREADY_ACTIVE", OLD_WALK, 7L),
            GatewayHttpResponse(409, expiredConflict.toString()),
            lease("ACQUIRED", NEW_WALK, 8L),
        )

        val result = recover(transport) as GatewayWalkStartResult.Granted

        assertEquals(NEW_WALK, result.lease.walkId)
        assertEquals(4, transport.commands.size)
    }

    @Test
    fun unrelatedEndFailureDoesNotAttemptNewStart() {
        val transport = RecordingTransport(
            conflict(),
            lease("ALREADY_ACTIVE", OLD_WALK, 7L),
            GatewayHttpResponse(409, "{\"code\":\"request_id_conflict\"}"),
        )

        try {
            recover(transport)
            throw AssertionError("expected walk end rejection")
        } catch (error: GatewayWalkHttpException) {
            assertEquals("request_id_conflict", error.serverCode)
        }
        assertEquals(3, transport.commands.size)
    }

    @Test
    fun everyMutationUsesDistinctValidRequestIdIncludingMaximumLengthInitialId() {
        val transport = recoveredTransport()
        val requestId = "r".repeat(128)

        recover(transport, requestId = requestId)

        val ids = transport.commands.map { it.getString("request_id") }
        assertEquals(requestId, ids.first())
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}").matches(it) })
        assertTrue(transport.commands.all {
            it.getString("schema_version") == "walksafe.field-walk-command.v1"
        })
    }

    @Test
    fun newLeaseDeadlineUsesNewStartClockSample() {
        val transport = recoveredTransport()
        var now = 10_000L

        val result = recover(transport, nowElapsedMs = { now.also { now += 1_000L } })
            as GatewayWalkStartResult.Granted

        assertTrue(result.lease.isLocallyUsable(96_999L))
        assertFalse(result.lease.isLocallyUsable(97_000L))
    }

    private fun recover(
        transport: RecordingTransport,
        session: GatewayFieldSession = session(),
        requestId: String = "request-0001",
        nowElapsedMs: () -> Long = { 10_000L },
        isCurrent: () -> Boolean = { true },
    ): GatewayWalkStartResult = GatewayWalkSessionClient(transport).startWithOwnedWalkRecovery(
        session, NEW_WALK, requestId, nowElapsedMs, isCurrent,
    )

    private fun recoveredTransport(): RecordingTransport = RecordingTransport(
        conflict(),
        lease("ALREADY_ACTIVE", OLD_WALK, 7L),
        ended(),
        lease("ACQUIRED", NEW_WALK, 8L),
    )

    private fun session(familyId: String = "f".repeat(32)): GatewayFieldSession =
        GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = "actor-test",
            deviceId = DEVICE,
            familyId = familyId,
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

    private fun conflict(
        walkId: String = OLD_WALK,
        deviceId: String = DEVICE,
        fence: Long = 7L,
    ): GatewayHttpResponse = GatewayHttpResponse(
        409,
        JSONObject()
            .put("schema_version", RESPONSE_SCHEMA)
            .put("code", "walk_lease_conflict")
            .put("active_walk_id", walkId)
            .put("active_device_id", deviceId)
            .put("fencing_token", fence)
            .put("lease_expires_at_epoch_ms", 1_090_000L)
            .put("server_time_epoch_ms", 1_000_000L)
            .toString(),
    )

    private fun ended(fence: Long = 7L): GatewayHttpResponse = GatewayHttpResponse(
        200,
        JSONObject()
            .put("schema_version", RESPONSE_SCHEMA)
            .put("result", "ENDED")
            .put("walk_id", OLD_WALK)
            .put("lease_id", "lease-000$fence")
            .put("fencing_token", fence)
            .put("ended_at_epoch_ms", 1_000_100L)
            .put("server_time_epoch_ms", 1_000_100L)
            .toString(),
    )

    private fun assertStale(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected stale operation rejection")
        } catch (error: GatewayWalkHttpException) {
            assertEquals(0, error.statusCode)
            assertEquals("walk_start_operation_stale", error.reason)
        }
    }

    private class RecordingTransport(vararg responses: GatewayHttpResponse) : GatewaySessionTransport {
        private val responses = responses.toList()
        val commands = mutableListOf<JSONObject>()
        var afterRequest: (Int) -> Unit = {}

        fun actions(): List<String> = commands.map { it.getString("action") }
        fun walks(): List<String> = commands.map { it.getString("walk_id") }

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): GatewayHttpResponse {
            assertEquals("https://gateway.example.test/api/field-walk", url)
            assertEquals(setOf(GatewayFieldSession.COOKIE_HEADER), headers.keys)
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
