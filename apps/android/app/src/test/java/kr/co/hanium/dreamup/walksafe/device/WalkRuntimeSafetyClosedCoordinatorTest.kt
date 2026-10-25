package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyClosedCoordinatorTest {
    private val epoch = WalkRuntimeEpoch("closed-runtime", 1L)
    private val profile = ApprovedWalkRuntimeSafetyThresholdProfile("test-only", 1_000L, 100L)

    @Test
    fun closingRevokesFreshPermissionForQueuedReadsAndObservations() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(profile, stops::add)
        coordinator.beginEpoch(epoch)
        assertTrue(coordinator.observe(complete(1_000L)).safetyOutputsAllowed)

        coordinator.close()

        assertFalse(coordinator.evaluate(1_001L).safetyOutputsAllowed)
        assertFalse(coordinator.observe(complete(1_002L)).safetyOutputsAllowed)
        assertFalse(coordinator.evaluate(1_003L).safetyOutputsAllowed)
        assertTrue(stops.isEmpty())
        assertNull(coordinator.latchedStop())
    }

    @Test
    fun closedOwnerCannotBeginAnotherEpochOrLatchLateFailure() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(profile, stops::add)
        coordinator.beginEpoch(epoch)
        coordinator.close()

        val decision = coordinator.observe(complete(1_000L).copy(cameraTrusted = false))

        assertFalse(decision.safetyOutputsAllowed)
        assertTrue(decision.causes.isEmpty())
        assertNull(coordinator.latchedStop())
        assertFalse(coordinator.beginEpoch(WalkRuntimeEpoch("next", 2L)).safetyOutputsAllowed)
        assertFalse(coordinator.observe(complete(1_001L)).safetyOutputsAllowed)
        assertTrue(stops.isEmpty())
    }

    @Test
    fun queuedFailureCannotChangeTheRetiredOwnersOriginalStop() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(profile, stops::add)
        coordinator.beginEpoch(epoch)
        coordinator.observe(complete(1_000L).copy(cameraTrusted = false))
        val originalStop = coordinator.latchedStop()
        coordinator.close()

        val decision = coordinator.observe(complete(1_001L).copy(ttsTrusted = false))

        assertFalse(decision.safetyOutputsAllowed)
        assertEquals(originalStop?.causes, decision.causes)
        assertEquals(originalStop, coordinator.latchedStop())
        assertEquals(1, stops.size)
    }

    @Test
    fun closedUnusedCoordinatorRemainsUnavailable() {
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {}
        coordinator.close()

        assertFalse(coordinator.evaluate(1_000L).safetyOutputsAllowed)
        assertFalse(coordinator.observe(complete(1_000L)).safetyOutputsAllowed)
        assertFalse(coordinator.beginEpoch(epoch).safetyOutputsAllowed)
        assertNull(coordinator.latchedStop())
    }

    private fun complete(at: Long) = WalkRuntimeSafetyObservation(
        epoch = epoch,
        observedAtElapsedRealtimeMs = at,
        cameraTrusted = true,
        depthTrusted = true,
        gpsTrusted = true,
        riskTrusted = true,
        ttsTrusted = true,
        batteryCritical = false,
        storageCritical = false,
        thermalCritical = false,
        frameCapturedAtElapsedRealtimeMs = at,
        inferenceLatencyMs = 10L,
    )
}
