package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayWalkSessionTest {
    @Test
    fun authenticatedStartUsesExactSchemaAndStrictLeaseBinding() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(201, leaseBody("ACQUIRED", "walk-0001", 4L))
        }
        val session = session()

        val result = GatewayWalkSessionClient(transport).start(
            session = session,
            walkId = "walk-0001",
            requestId = "request-0001",
            localNowElapsedMs = 10_000L,
        )

        assertTrue(result is GatewayWalkStartResult.Granted)
        assertEquals(
            setOf(GatewayFieldSession.COOKIE_HEADER),
            transport.authenticatedHeaders.keys,
        )
        val command = JSONObject(transport.body)
        assertEquals(
            setOf("schema_version", "request_id", "action", "walk_id"),
            command.keySet(),
        )
        assertEquals("walksafe.field-walk-command.v1", command.getString("schema_version"))
        assertEquals("start", command.getString("action"))
        val lease = (result as GatewayWalkStartResult.Granted).lease
        assertEquals(4L, lease.fencingToken)
        assertTrue(lease.isLocallyUsable(94_999L))
        assertFalse(lease.isLocallyUsable(95_000L))
        assertFalse(lease.toString().contains("lease-0001"))
    }

    @Test
    fun startAcceptsAlreadyActiveAndRejectsContractExternalActive() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(
                200,
                leaseBody("ALREADY_ACTIVE", "walk-0001", 4L),
            )
        }
        val client = GatewayWalkSessionClient(transport)

        assertTrue(
            client.start(
                session(),
                "walk-0001",
                "request-0001",
                10_000L,
            ) is GatewayWalkStartResult.Granted,
        )

        transport.next = GatewayHttpResponse(
            200,
            leaseBody("ACTIVE", "walk-0001", 4L),
        )
        assertRejected {
            client.start(
                session(),
                "walk-0001",
                "request-0002",
                10_000L,
            )
        }
    }

    @Test
    fun takeoverAndRenewRejectContractExternalActive() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(409, conflictBody())
        }
        val client = GatewayWalkSessionClient(transport)
        val conflict = (
            client.start(
                session(),
                "walk-0001",
                "request-0001",
                10_000L,
            ) as GatewayWalkStartResult.Conflict
        ).conflict

        transport.next = GatewayHttpResponse(
            200,
            leaseBody("ACTIVE", "walk-0001", 8L),
        )
        assertRejected {
            client.takeover(
                session(),
                "walk-0001",
                "request-0002",
                conflict,
                10_001L,
            )
        }

        transport.next = GatewayHttpResponse(
            200,
            leaseBody("ACTIVE", "walk-0001", 4L),
        )
        assertRejected {
            client.renew(
                session(),
                lease(localDeadline = 50_000L),
                "request-0003",
                10_002L,
            )
        }
    }

    @Test
    fun requestIdConflictNeverEntersVoiceTakeoverPath() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(
                409,
                JSONObject()
                    .put("schema_version", "walksafe.field-walk-response.v1")
                    .put("code", "request_id_conflict")
                    .toString(),
            )
        }

        val failure = runCatching {
            GatewayWalkSessionClient(transport).start(
                session(),
                "walk-0001",
                "request-0001",
                10_000L,
            )
        }.exceptionOrNull()

        assertTrue(failure is GatewayWalkHttpException)
        assertEquals(
            "request_id_conflict",
            (failure as GatewayWalkHttpException).serverCode,
        )
    }

    @Test
    fun extraLeaseFieldAndNonIntegerFenceAreRejected() {
        val extra = JSONObject(leaseBody("ACQUIRED", "walk-0001", 4L))
            .put("unexpected", true)
            .toString()
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(201, extra)
        }
        assertRejected {
            GatewayWalkSessionClient(transport).start(
                session(),
                "walk-0001",
                "request-0001",
                10_000L,
            )
        }

        transport.next = GatewayHttpResponse(
            201,
            JSONObject(leaseBody("ACQUIRED", "walk-0001", 4L))
                .put("fencing_token", 4.5)
                .toString(),
        )
        assertRejected {
            GatewayWalkSessionClient(transport).start(
                session(),
                "walk-0001",
                "request-0002",
                10_000L,
            )
        }
    }

    @Test
    fun conflictRequiresVoiceConfirmedTakeoverAndAdvancesFence() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(409, conflictBody())
        }
        val client = GatewayWalkSessionClient(transport)
        val start = client.start(
            session(),
            "walk-0001",
            "request-0001",
            10_000L,
        )
        assertTrue(start is GatewayWalkStartResult.Conflict)
        val conflict = (start as GatewayWalkStartResult.Conflict).conflict

        transport.next = GatewayHttpResponse(
            200,
            leaseBody("TAKEN_OVER", "walk-0001", 8L),
        )
        val lease = client.takeover(
            session(),
            "walk-0001",
            "request-0002",
            conflict,
            10_001L,
        )

        val command = JSONObject(transport.body)
        assertEquals("takeover", command.getString("action"))
        assertEquals("voice_confirmed", command.getString("confirmation"))
        assertEquals(7L, command.getLong("expected_fencing_token"))
        assertEquals(8L, lease.fencingToken)
    }

    @Test
    fun controllerRejectsStaleCallbackAndExpiresConservatively() {
        var now = 10_000L
        var request = 0
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-${++request}".padEnd(16, '0') },
            elapsedClock = { now },
        )
        val firstEpoch = WalkRuntimeEpoch("walk-0001", 0L)
        val stale = requireNotNull(controller.beginStart(firstEpoch))
        controller.cancel(stale)
        val current = requireNotNull(controller.beginStart(firstEpoch))
        val lease = lease(localDeadline = 95_000L)

        assertEquals(
            GatewayWalkAuthorityCompletion.STALE,
            controller.completeStart(stale, GatewayWalkStartResult.Granted(lease)),
        )
        assertEquals(
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            controller.completeStart(current, GatewayWalkStartResult.Granted(lease)),
        )
        assertNotNull(controller.activeLeaseOrNull(firstEpoch))

        now = 95_000L
        assertNull(controller.activeLeaseOrNull(firstEpoch))
    }

    @Test
    fun delayedStartCallbackCannotActivateAnExpiredParsedLease() {
        var now = 10_000L
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-delay-01" },
            elapsedClock = { now },
        )
        val epoch = WalkRuntimeEpoch("walk-0001", 0L)
        val start = requireNotNull(controller.beginStart(epoch))
        val parsedLease = lease(localDeadline = 20_000L)

        now = 20_000L
        assertEquals(
            GatewayWalkAuthorityCompletion.INVALID,
            controller.completeStart(
                start,
                GatewayWalkStartResult.Granted(parsedLease),
            ),
        )
        assertNull(controller.activeLeaseOrNull(epoch))
    }

    @Test
    fun delayedTakeoverCallbackCannotActivateAnExpiredParsedLease() {
        var now = 10_000L
        var request = 0
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-${++request}".padEnd(16, '0') },
            elapsedClock = { now },
        )
        val epoch = WalkRuntimeEpoch("walk-0001", 0L)
        val start = requireNotNull(controller.beginStart(epoch))
        assertEquals(
            GatewayWalkAuthorityCompletion.CONFLICT,
            controller.completeStart(
                start,
                GatewayWalkStartResult.Conflict(
                    GatewayWalkConflict(
                        activeWalkId = "walk-active",
                        activeDeviceId = "device-active",
                        fencingToken = 7L,
                        leaseExpiresAtEpochMs = 1_090_000L,
                        serverTimeEpochMs = 1_000_000L,
                        localDeadlineElapsedMs = 50_000L,
                    ),
                ),
            ),
        )
        val takeover = requireNotNull(controller.beginTakeover(epoch))
        val parsedLease = lease(localDeadline = 20_000L).copy(
            result = "TAKEN_OVER",
            fencingToken = 8L,
        )

        now = 20_000L
        assertEquals(
            GatewayWalkAuthorityCompletion.INVALID,
            controller.completeTakeover(takeover, parsedLease),
        )
        assertNull(controller.activeLeaseOrNull(epoch))
    }

    @Test
    fun renewKeepsFenceAndEpochRebindRejectsOldOperation() {
        var request = 0
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-${++request}".padEnd(16, '0') },
            elapsedClock = { 10_000L },
        )
        val firstEpoch = WalkRuntimeEpoch("walk-0001", 0L)
        val start = requireNotNull(controller.beginStart(firstEpoch))
        assertEquals(
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            controller.completeStart(
                start,
                GatewayWalkStartResult.Granted(lease(localDeadline = 50_000L)),
            ),
        )
        val renewal = requireNotNull(controller.beginRenew(firstEpoch))
        val pausedEpoch = firstEpoch.copy(recoveryGeneration = 1L)

        assertTrue(controller.rebindEpoch(firstEpoch, pausedEpoch))
        assertFalse(controller.isCurrent(renewal))
        assertNotNull(controller.activeLeaseOrNull(pausedEpoch))
        assertNull(controller.activeLeaseOrNull(firstEpoch))
    }

    @Test
    fun shortLeaseSchedulesRenewBeforeItsConservativeDeadline() {
        var now = 10_000L
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-short-01" },
            elapsedClock = { now },
        )
        val epoch = WalkRuntimeEpoch("walk-0001", 0L)
        val start = requireNotNull(controller.beginStart(epoch))
        assertEquals(
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            controller.completeStart(
                start,
                GatewayWalkStartResult.Granted(lease(localDeadline = 20_000L)),
            ),
        )

        assertEquals(
            5_000L,
            requireNotNull(controller.nextRenewDelayMs(epoch, 30_000L)),
        )
        now = 20_000L
        assertNull(controller.nextRenewDelayMs(epoch, 30_000L))
    }

    @Test
    fun lateRenewSuccessAfterSourceExpiryFailsClosed() {
        var now = 10_000L
        val controller = GatewayWalkAuthorityController(
            requestIdFactory = { "request-late-0001" },
            elapsedClock = { now },
        )
        val epoch = WalkRuntimeEpoch("walk-0001", 0L)
        val start = requireNotNull(controller.beginStart(epoch))
        assertEquals(
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            controller.completeStart(
                start,
                GatewayWalkStartResult.Granted(lease(localDeadline = 20_000L)),
            ),
        )
        val renewal = requireNotNull(controller.beginRenew(epoch))

        now = 20_000L
        assertEquals(
            GatewayWalkAuthorityCompletion.INVALID,
            controller.currentOperationStatus(renewal),
        )
        assertFalse(controller.isCurrent(renewal))
        assertEquals(
            GatewayWalkAuthorityCompletion.INVALID,
            controller.completeRenew(
                renewal,
                lease(localDeadline = 40_000L),
            ),
        )
        assertNull(controller.activeLeaseOrNull(epoch))
    }

    @Test
    fun endResponseIsExactAndBoundToTheSourceLease() {
        val transport = FakeTransport().apply {
            next = GatewayHttpResponse(
                200,
                JSONObject()
                    .put("schema_version", "walksafe.field-walk-response.v1")
                    .put("result", "ENDED")
                    .put("walk_id", "walk-0001")
                    .put("lease_id", "lease-0001")
                    .put("fencing_token", 4L)
                    .put("ended_at_epoch_ms", 1_010_000L)
                    .put("server_time_epoch_ms", 1_010_001L)
                    .toString(),
            )
        }

        val ended = GatewayWalkSessionClient(transport).end(
            session(),
            lease(localDeadline = 50_000L),
            "request-end-0001",
        )

        assertEquals("walk-0001", ended.walkId)
        assertEquals("end", JSONObject(transport.body).getString("action"))
    }

    @Test
    fun takeoverConfirmationAcceptsOnlyExplicitYesOrNo() {
        assertEquals(
            GatewayWalkTakeoverConfirmation.YES,
            GatewayWalkTakeoverConfirmation.fromRecognizedText("예"),
        )
        assertEquals(
            GatewayWalkTakeoverConfirmation.YES,
            GatewayWalkTakeoverConfirmation.fromRecognizedText("네"),
        )
        assertEquals(
            GatewayWalkTakeoverConfirmation.NO,
            GatewayWalkTakeoverConfirmation.fromRecognizedText("아니요"),
        )
        assertEquals(
            GatewayWalkTakeoverConfirmation.UNRECOGNIZED,
            GatewayWalkTakeoverConfirmation.fromRecognizedText("시작"),
        )
    }

    private fun session(): GatewayFieldSession =
        GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = "actor-test",
            deviceId = "device-0001",
            familyId = "f".repeat(32),
            rotation = 1L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(32)}",
            refreshToken = "r".repeat(64),
            accessExpiresAtEpochMs = Long.MAX_VALUE,
            idleExpiresAtEpochMs = Long.MAX_VALUE,
            absoluteExpiresAtEpochMs = Long.MAX_VALUE,
        )

    private fun lease(localDeadline: Long): GatewayWalkLease =
        GatewayWalkLease(
            result = "ACQUIRED",
            actorId = "actor-test",
            deviceId = "device-0001",
            walkId = "walk-0001",
            leaseId = "lease-0001",
            fencingToken = 4L,
            acquiredAtEpochMs = 1_000_000L,
            leaseExpiresAtEpochMs = 1_090_000L,
            serverTimeEpochMs = 1_000_000L,
            localDeadlineElapsedMs = localDeadline,
        )

    private fun leaseBody(result: String, walkId: String, fence: Long): String =
        JSONObject()
            .put("schema_version", "walksafe.field-walk-response.v1")
            .put("result", result)
            .put("walk_id", walkId)
            .put("lease_id", "lease-0001")
            .put("fencing_token", fence)
            .put("acquired_at_epoch_ms", 1_000_000L)
            .put("lease_expires_at_epoch_ms", 1_090_000L)
            .put("server_time_epoch_ms", 1_000_000L)
            .toString()

    private fun conflictBody(): String =
        JSONObject()
            .put("schema_version", "walksafe.field-walk-response.v1")
            .put("code", "walk_lease_conflict")
            .put("active_walk_id", "walk-active")
            .put("active_device_id", "device-active")
            .put("fencing_token", 7L)
            .put("lease_expires_at_epoch_ms", 1_090_000L)
            .put("server_time_epoch_ms", 1_000_000L)
            .toString()

    private fun JSONObject.keySet(): Set<String> = buildSet {
        val iterator = keys()
        while (iterator.hasNext()) add(iterator.next())
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private class FakeTransport : GatewaySessionTransport {
        lateinit var next: GatewayHttpResponse
        var authenticatedHeaders: Map<String, String> = emptyMap()
        var body: String = ""

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            this.body = body
            return next
        }

        override fun postJson(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): GatewayHttpResponse {
            authenticatedHeaders = headers
            this.body = body
            return next
        }

        override fun get(
            url: String,
            headers: Map<String, String>,
        ): GatewayHttpResponse = next

        override fun delete(
            url: String,
            headers: Map<String, String>,
        ): GatewayHttpResponse = next

        override fun deleteJson(url: String, body: String): GatewayHttpResponse = next
    }
}
