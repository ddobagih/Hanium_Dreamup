package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianStationaryDetectorTest {
    private val config = PedestrianStationaryDetectorConfig(
        stationaryDwellMs = 1_000L,
        missingGyroscopeDwellMs = 2_000L,
    )

    @Test
    fun listeningPauseBecomesStationaryOnlyAfterDwell() {
        val detector = PedestrianStationaryDetector(config)

        val candidate = detector.observe(quietSample(1_000L))
        for (at in 1_200L..1_800L step 200L) detector.observe(quietSample(at))
        val beforeDwell = detector.observe(quietSample(1_999L))
        val stationary = detector.observe(quietSample(2_000L))

        assertEquals(PedestrianStationaryState.CANDIDATE, candidate.state)
        assertEquals(PedestrianStationaryState.CANDIDATE, beforeDwell.state)
        assertFalse(beforeDwell.shouldApplyZeroVelocityUpdate)
        assertEquals(PedestrianStationaryState.STATIONARY, stationary.state)
        assertTrue(stationary.shouldApplyZeroVelocityUpdate)
    }

    @Test
    fun caneSweepImmediatelyReleasesStationaryState() {
        val detector = stationaryDetector()

        val sweep = detector.observe(
            quietSample(2_100L).copy(
                gyroscopeRmsRadPerSecond = 0.12,
                gyroscopePeakRadPerSecond = 0.70,
            ),
        )

        assertEquals(PedestrianStationaryState.MOVING, sweep.state)
        assertTrue(sweep.stateChanged)
        assertFalse(sweep.shouldApplyZeroVelocityUpdate)
    }

    @Test
    fun slowWalkingDoesNotBecomeStationaryBetweenSparseSteps() {
        val detector = PedestrianStationaryDetector(config)

        detector.observe(movingSample(1_000L, stepDetected = true))
        val betweenSteps = detector.observe(movingSample(1_500L))
        val stillBetweenSteps = detector.observe(movingSample(2_000L))
        val nextStep = detector.observe(movingSample(2_500L, stepDetected = true))

        assertEquals(PedestrianStationaryState.MOVING, betweenSteps.state)
        assertEquals(PedestrianStationaryState.MOVING, stillBetweenSteps.state)
        assertEquals(PedestrianStationaryState.MOVING, nextStep.state)
    }

    @Test
    fun newStepImmediatelyReleasesStationaryState() {
        val detector = stationaryDetector()

        val step = detector.observe(quietSample(2_100L).copy(stepDetected = true))

        assertEquals(PedestrianStationaryState.MOVING, step.state)
        assertFalse(step.shouldApplyZeroVelocityUpdate)
    }

    @Test
    fun noStepAloneNeverEntersStationaryState() {
        val detector = PedestrianStationaryDetector(config)

        detector.observe(PedestrianMotionSample(elapsedRealtimeMs = 1_000L))
        val muchLater = detector.observe(PedestrianMotionSample(elapsedRealtimeMs = 10_000L))

        assertEquals(PedestrianStationaryState.UNKNOWN, muchLater.state)
        assertFalse(muchLater.shouldApplyZeroVelocityUpdate)
    }

    @Test
    fun missingGyroscopeRequiresLongerDwellAsWeakEvidence() {
        val detector = PedestrianStationaryDetector(config)

        detector.observe(quietSample(1_000L, includeGyroscope = false))
        for (at in 1_200L..1_800L step 200L) detector.observe(quietSample(at, includeGyroscope = false))
        val normalDwellElapsed = detector.observe(quietSample(2_000L, includeGyroscope = false))
        for (at in 2_200L..2_800L step 200L) detector.observe(quietSample(at, includeGyroscope = false))
        val extendedDwellElapsed = detector.observe(quietSample(3_000L, includeGyroscope = false))

        assertEquals(PedestrianStationaryState.CANDIDATE, normalDwellElapsed.state)
        assertFalse(normalDwellElapsed.shouldApplyZeroVelocityUpdate)
        assertEquals(PedestrianStationaryState.STATIONARY, extendedDwellElapsed.state)
        assertTrue(extendedDwellElapsed.shouldApplyZeroVelocityUpdate)
    }

    @Test
    fun outOfOrderSampleIsRejectedWithoutChangingCandidateDwell() {
        val detector = PedestrianStationaryDetector(config)
        detector.observe(quietSample(1_000L))

        val rejected = detector.observe(movingSample(900L, stepDetected = true))
        for (at in 1_200L..1_800L step 200L) detector.observe(quietSample(at))
        val completed = detector.observe(quietSample(2_000L))

        assertFalse(rejected.accepted)
        assertEquals(PedestrianMotionSampleRejectionReason.OUT_OF_ORDER, rejected.rejectionReason)
        assertEquals(PedestrianStationaryState.CANDIDATE, rejected.state)
        assertEquals(PedestrianStationaryState.STATIONARY, completed.state)
    }

    @Test
    fun resetClearsStationaryStateAndTimestampHistory() {
        val detector = stationaryDetector()

        detector.reset()
        val restarted = detector.observe(quietSample(1_000L))

        assertEquals(PedestrianStationaryState.CANDIDATE, restarted.state)
        assertEquals(1_000L, restarted.candidateSinceElapsedRealtimeMs)
        assertNull(restarted.rejectionReason)
        assertFalse(restarted.shouldApplyZeroVelocityUpdate)
    }

    private fun stationaryDetector() = PedestrianStationaryDetector(config).also { detector ->
        for (at in 1_000L..2_000L step 200L) detector.observe(quietSample(at))
        assertEquals(PedestrianStationaryState.STATIONARY, detector.currentState())
    }

    private fun quietSample(
        elapsedRealtimeMs: Long,
        includeGyroscope: Boolean = true,
    ) = PedestrianMotionSample(
        elapsedRealtimeMs = elapsedRealtimeMs,
        accelerationMagnitudeMeanMps2 = 9.81,
        accelerationMagnitudeVarianceMps4 = 0.02,
        gyroscopeRmsRadPerSecond = if (includeGyroscope) 0.02 else null,
        gyroscopePeakRadPerSecond = if (includeGyroscope) 0.05 else null,
        accelerationWindow = PedestrianMotionWindowCoverage(5, elapsedRealtimeMs - 200L, elapsedRealtimeMs, 50L),
        gyroscopeWindow = if (includeGyroscope) PedestrianMotionWindowCoverage(5, elapsedRealtimeMs - 200L, elapsedRealtimeMs, 50L) else null,
    )

    private fun movingSample(
        elapsedRealtimeMs: Long,
        stepDetected: Boolean = false,
    ) = PedestrianMotionSample(
        elapsedRealtimeMs = elapsedRealtimeMs,
        stepDetected = stepDetected,
        accelerationMagnitudeMeanMps2 = 9.81,
        accelerationMagnitudeVarianceMps4 = 0.30,
        gyroscopeRmsRadPerSecond = 0.10,
        gyroscopePeakRadPerSecond = 0.22,
    )
}
