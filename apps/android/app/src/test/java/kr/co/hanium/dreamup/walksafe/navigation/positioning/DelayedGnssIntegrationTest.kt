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
        policy.observeMotion(PositionQuality.LOW, 5_000L)

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
    fun motionAndWeakGnssCannotCountTowardRecoveryButMotionDegradationClearsIt() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.LOW, 1_000L)
        policy.observe(PositionQuality.HIGH, 2_000L)
        val motion = policy.observeMotion(PositionQuality.HIGH, 2_500L)
        assertEquals(1, motion.consecutiveHighRecoverySamples)
        val weak = policy.observe(PositionQuality.HIGH, 2_600L, informativeGnss = false)
        assertEquals(1, weak.consecutiveHighRecoverySamples)
        assertTrue(weak.guidancePaused)
        policy.observeMotion(PositionQuality.LOW, 2_800L)
        assertEquals(1, policy.observe(PositionQuality.HIGH, 3_000L).consecutiveHighRecoverySamples)
    }

    @Test
    fun recoveryGapUsesMeasurementTimesRatherThanBatchedReceiptTimes() {
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.LOW, 0L)
        policy.observe(PositionQuality.HIGH, 1_000L, 8_000L)
        val second = policy.observe(PositionQuality.HIGH, 7_000L, 8_000L)
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
        coordinator.observeZupt(ZuptObservation(2_500L))

        val delayed = coordinator.observeGnss(fix(1_500L, receivedAtMs = 2_600L))
        assertNull(delayed.confidence.failureReason)
        assertEquals(GnssObservationDisposition.APPLIED, delayed.gnssUpdate?.disposition)
        assertEquals(1_500L, delayed.raw?.elapsedRealtimeMs)
        assertEquals(1_500L, delayed.filteredAtGnssMeasurement?.elapsedRealtimeMs)
        assertEquals(2_500L, delayed.filtered?.elapsedRealtimeMs)
        assertEquals(2, delayed.gnssUpdate?.replayedMotionEventCount)
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
