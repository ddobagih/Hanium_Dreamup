package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

class WalkRuntimeSafetyFreshnessTest {
    private val epoch = WalkRuntimeEpoch("current", 2L)
    private val profile = ApprovedWalkRuntimeSafetyThresholdProfile("test-fixture-only", 100L, 200L, 100L)

    @Test fun emptyEpochAndPartialEvidenceNeverAuthorizeOutput() {
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {}
        assertFalse(coordinator.beginEpoch(epoch).safetyOutputsAllowed)
        assertFalse(coordinator.observe(WalkRuntimeSafetyObservation(epoch, 1_000L, cameraTrusted = true)).safetyOutputsAllowed)
        assertFalse(coordinator.evaluate(1_001L).safetyOutputsAllowed)
    }

    @Test fun noObservationExpiresAndClockRollbackCannotResurrectPermission() {
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {}
        coordinator.beginEpoch(epoch)
        assertTrue(coordinator.observe(complete(1_000L)).safetyOutputsAllowed)
        assertTrue(coordinator.evaluate(1_100L).safetyOutputsAllowed)
        assertFalse(coordinator.evaluate(1_101L).safetyOutputsAllowed)
        assertFalse(coordinator.evaluate(1_000L).safetyOutputsAllowed)
        assertFalse(coordinator.observe(complete(1_000L)).safetyOutputsAllowed)
        assertTrue(coordinator.observe(complete(1_200L)).safetyOutputsAllowed)
    }

    @Test fun sourcesMayArriveInDifferentOrderButOneSourceCannotOverwriteNewerEvidence() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(profile, stops::add)
        coordinator.beginEpoch(epoch)
        coordinator.observe(complete(1_000L).copy(gpsTrusted = null))
        assertTrue(coordinator.observe(WalkRuntimeSafetyObservation(epoch, 990L, gpsTrusted = true,
            receivedAtElapsedRealtimeMs = 1_000L)).safetyOutputsAllowed)
        val oldCameraFailure = coordinator.observe(WalkRuntimeSafetyObservation(epoch, 999L, cameraTrusted = false,
            receivedAtElapsedRealtimeMs = 1_001L))
        assertTrue(oldCameraFailure.safetyOutputsAllowed)
        assertTrue(stops.isEmpty())
        assertFalse(coordinator.observe(WalkRuntimeSafetyObservation(epoch, 1_002L, cameraTrusted = false)).safetyOutputsAllowed)
        coordinator.observe(WalkRuntimeSafetyObservation(epoch, 1_001L, cameraTrusted = true,
            receivedAtElapsedRealtimeMs = 1_003L))
        assertEquals(1, stops.size)
        assertFalse(coordinator.evaluate(1_004L).safetyOutputsAllowed)
    }

    @Test fun oldEpochCannotStopOrSupplyEvidenceToNewWalk() {
        val stops = mutableListOf<WalkRuntimeSafetyStop>()
        val coordinator = WalkRuntimeSafetyCoordinator(profile, stops::add)
        coordinator.beginEpoch(epoch)
        coordinator.observe(complete(1_000L))
        val next = WalkRuntimeEpoch("next", 3L)
        coordinator.beginEpoch(next)
        val ignored = coordinator.observe(complete(1_001L).copy(cameraTrusted = false))
        assertEquals(WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION, ignored.disposition)
        assertTrue(stops.isEmpty())
        assertFalse(coordinator.evaluate(1_002L).safetyOutputsAllowed)
        assertTrue(coordinator.observe(complete(1_003L).copy(epoch = next)).safetyOutputsAllowed)
    }

    @Test fun invalidFutureObservationDoesNotPassEvenWithFreshFlags() {
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {}
        coordinator.beginEpoch(epoch)
        val decision = coordinator.observe(complete(10_000L).copy(receivedAtElapsedRealtimeMs = 1_000L))
        assertFalse(decision.safetyOutputsAllowed)
        assertTrue(WalkRuntimeSafetyStopCause.INVALID_TIME in decision.causes)
    }

    @Test fun repackagingAFrameDoesNotExtendItsOriginalDeadline() {
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {}
        coordinator.beginEpoch(epoch)
        coordinator.observe(complete(1_000L))
        assertFalse(coordinator.observe(complete(1_150L).copy(frameCapturedAtElapsedRealtimeMs = 1_000L)).safetyOutputsAllowed)
    }

    @Test fun failedStopCallbackRemainsPendingAndRetriesOnlyForItsEpoch() {
        var attempts = 0
        val coordinator = WalkRuntimeSafetyCoordinator(profile) {
            attempts++
            if (attempts == 1) throw IllegalStateException("fixture cleanup failure")
        }
        coordinator.beginEpoch(epoch)
        assertFalse(coordinator.observe(complete(1_000L).copy(cameraTrusted = false)).safetyOutputsAllowed)
        assertEquals(1, attempts)
        assertFalse(coordinator.evaluate(1_001L).safetyOutputsAllowed)
        coordinator.evaluate(1_002L)
        assertEquals(2, attempts)
        coordinator.beginEpoch(WalkRuntimeEpoch("next", 3L))
        coordinator.evaluate(1_003L)
        assertEquals(2, attempts)
    }

    private fun complete(at: Long) = WalkRuntimeSafetyObservation(epoch, at,
        cameraTrusted = true, depthTrusted = true, gpsTrusted = true, riskTrusted = true,
        ttsTrusted = true, batteryCritical = false, storageCritical = false, thermalCritical = false,
        frameCapturedAtElapsedRealtimeMs = at, inferenceLatencyMs = 20L)
}
