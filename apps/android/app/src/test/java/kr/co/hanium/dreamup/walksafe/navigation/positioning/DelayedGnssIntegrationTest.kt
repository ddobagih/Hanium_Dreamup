package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedGnssIntegrationTest {
    @Test
    fun delayedGnssRecoveryUsesTwoDistinctMeasurementsEvenInOneCallbackBatch() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, 1_000L)
        policy.observeMotion(PositionQuality.UNAVAILABLE, 2_000L)
        assertTrue(policy.evaluate(5_000L).guidancePaused)

        val first = policy.observe(PositionQuality.HIGH, 4_000L, 5_100L)
        assertNull(first.failureReason)
        assertEquals(1, first.consecutiveHighRecoverySamples)
        assertTrue(first.guidancePaused)

        val second = policy.observe(PositionQuality.HIGH, 4_500L, 5_100L)
        assertNull(second.failureReason)
        assertFalse(second.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, second.transition)
    }

    @Test
    fun motionAndWeakGnssCannotCountTowardRecoveryButMeasuredFailureClearsIt() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.UNAVAILABLE, 0L)
        assertTrue(policy.evaluate(3_000L).guidancePaused)
        assertEquals(1, policy.observe(PositionQuality.HIGH, 4_000L).consecutiveHighRecoverySamples)
        val motion = policy.observeMotion(PositionQuality.HIGH, 4_500L)
        assertEquals(1, motion.consecutiveHighRecoverySamples)
        val weak = policy.observe(PositionQuality.HIGH, 4_600L, informativeGnss = false)
        assertEquals(1, weak.consecutiveHighRecoverySamples)
        assertTrue(weak.guidancePaused)
        assertEquals(1, policy.observeMotion(PositionQuality.UNAVAILABLE, 4_700L).consecutiveHighRecoverySamples)
        policy.observe(PositionQuality.UNAVAILABLE, 4_800L)
        assertEquals(1, policy.observe(PositionQuality.HIGH, 5_000L).consecutiveHighRecoverySamples)
    }

    @Test
    fun recoveryGapUsesMeasurementTimesRatherThanBatchedReceiptTimes() {
        // A shorter configured recovery gap isolates continuity from the 10 s sample-age gate.
        val policy = PositionConfidencePolicy(PositionConfidencePolicyConfig(maximumHighRecoverySampleGapMs = 5_000L))
        policy.observe(PositionQuality.UNAVAILABLE, 0L)
        assertTrue(policy.evaluate(3_000L).guidancePaused)
        val first = policy.observe(PositionQuality.HIGH, 4_000L, 11_000L)
        assertNull(first.failureReason)
        assertEquals(1, first.consecutiveHighRecoverySamples)
        val second = policy.observe(PositionQuality.HIGH, 10_000L, 11_000L)
        assertNull(second.failureReason)
        assertEquals(1, second.consecutiveHighRecoverySamples)
        assertTrue(second.guidancePaused)
    }

    @Test
    fun duplicateGnssStillFailsClosedAfterMotionAndResetClearsBothWatermarks() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, 1_000L)
        policy.observeMotion(PositionQuality.LOW, 3_000L)
        policy.observe(PositionQuality.HIGH, 2_000L, 3_100L)
        val duplicate = policy.observe(PositionQuality.HIGH, 2_000L, 3_200L)
        assertEquals(PositionConfidenceFailureReason.OUT_OF_ORDER, duplicate.failureReason)
        assertTrue(duplicate.guidancePaused)
        policy.reset()
        assertNull(policy.observeMotion(PositionQuality.LOW, 100L).failureReason)
        assertNull(policy.observe(PositionQuality.HIGH, 50L, 110L).failureReason)
    }

    @Test
    fun coordinatorSeparatesMeasurementTraceFromReplayedLiveState() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        coordinator.observeStep(
            stepCount = 1,
            headingInput = heading(2_000L),
            quality = PdrStepQuality.HIGH,
        )
        val unsupportedZupt = coordinator.observeZupt(ZuptObservation(2_500L))
        assertEquals(PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED,
            unsupportedZupt.motionUpdate?.hardRejectReason)

        val delayed = coordinator.observeGnss(fix(1_500L, receivedAtMs = 2_600L))
        assertNull(delayed.confidence.failureReason)
        assertEquals(GnssObservationDisposition.APPLIED, delayed.gnssUpdate?.disposition)
        assertEquals(1_500L, delayed.raw?.elapsedRealtimeMs)
        assertEquals(1_500L, delayed.filteredAtGnssMeasurement?.elapsedRealtimeMs)
        assertEquals(2_000L, delayed.filtered?.elapsedRealtimeMs)
        assertEquals(1, delayed.gnssUpdate?.replayedMotionEventCount)
        assertNotNull(delayed.filteredAtGnssMeasurement?.localEnu)
        assertTrue(requireNotNull(delayed.filtered).coordinate.longitude >
            requireNotNull(delayed.filteredAtGnssMeasurement).coordinate.longitude)
    }

    @Test
    fun coordinatorMotionDegradationDoesNotBlockAnOlderUsefulGnssMeasurement() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(1_000L))
        val motion = coordinator.observeStep(
            stepCount = 4,
            headingInput = heading(5_000L),
            quality = PdrStepQuality.LOW,
        )
        assertTrue(motion.quality != PositionQuality.HIGH)
        val delayed = coordinator.observeGnss(fix(4_500L, receivedAtMs = 5_100L))
        assertNull(delayed.confidence.failureReason)
        assertEquals(5_000L, delayed.filtered?.elapsedRealtimeMs)
    }

    private fun fix(timeMs: Long, receivedAtMs: Long = timeMs) = GnssPositionObservation(
        latitude = 37.0, longitude = 127.0, horizontalAccuracyM = 2.0,
        elapsedRealtimeMs = timeMs, receivedAtElapsedRealtimeMs = receivedAtMs,
    )

    private fun heading(timeMs: Long) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = timeMs,
        speed = WalkingSpeedObservation(1.0, 0.1, timeMs),
        gpsCourse = HeadingObservation(90.0, 5.0, timeMs),
        magneticTrueHeading = null, phoneForwardMounted = false,
    )
}
