package kr.co.hanium.dreamup.walksafe.session

import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class WalkSessionLifecycleFp012Test {
    @Test
    fun localStartRemainsReadyUntilTheExactServerLeaseIsAccepted() {
        val lifecycle = WalkSessionLifecycle(
            sessionIdFactory = { "walk-0001" },
            clock = { 1_000L },
        )
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        val readiness = ready(lifecycle.snapshot().epoch)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        val authority = GatewayWalkAuthorityController(
            requestIdFactory = { "request-00000001" },
            elapsedClock = { 10_000L },
        )
        val operation = requireNotNull(authority.beginStart(token.epoch))

        assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
        assertEquals(
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            authority.completeStart(
                operation,
                GatewayWalkStartResult.Granted(lease()),
            ),
        )

        lifecycle.handle(WalkSessionEvent.StartRequested(token))

        assertNotNull(authority.activeLeaseOrNull(lifecycle.snapshot().epoch))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
    }

    @Test
    fun backgroundGenerationRebindKeepsLeaseButRejectsTheOldEpoch() {
        val lifecycle = WalkSessionLifecycle(
            sessionIdFactory = { "walk-0001" },
            clock = { 1_000L },
        )
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(
            WalkSessionEvent.InitialCheckCompleted(
                ready(lifecycle.snapshot().epoch),
            ),
        )
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        val authority = GatewayWalkAuthorityController(
            requestIdFactory = { "request-00000001" },
            elapsedClock = { 10_000L },
        )
        val operation = requireNotNull(authority.beginStart(token.epoch))
        authority.completeStart(operation, GatewayWalkStartResult.Granted(lease()))
        lifecycle.handle(WalkSessionEvent.StartRequested(token))
        val activeEpoch = lifecycle.snapshot().epoch

        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        val pausedEpoch = lifecycle.snapshot().epoch
        authority.rebindEpoch(activeEpoch, pausedEpoch)

        assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
        assertEquals(null, authority.activeLeaseOrNull(activeEpoch))
        assertNotNull(authority.activeLeaseOrNull(pausedEpoch))
    }

    private fun ready(epoch: WalkRuntimeEpoch): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(
            action = WalkSessionAction.START_WALK,
            mode = WalkSessionMode.FULL,
        )
        val collector = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtEpochMs = 1_000L)
    }

    private fun lease(): GatewayWalkLease =
        GatewayWalkLease(
            result = "ACQUIRED",
            actorId = "actor-test",
            deviceId = "device-0001",
            walkId = "walk-0001",
            leaseId = "lease-0001",
            fencingToken = 3L,
            acquiredAtEpochMs = 1_000_000L,
            leaseExpiresAtEpochMs = 1_090_000L,
            serverTimeEpochMs = 1_000_000L,
            localDeadlineElapsedMs = 95_000L,
        )
}
