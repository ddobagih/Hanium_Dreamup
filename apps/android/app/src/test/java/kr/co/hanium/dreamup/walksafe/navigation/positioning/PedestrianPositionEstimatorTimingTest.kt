package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianPositionEstimatorTimingTest {
    @Test
    fun lateGnssAfterStepAndZuptMatchesMeasurementTimeOrder() {
        val ordered = initialized()
        val delayed = initialized()
        val fix = gnss(2_000L)
        val step = step(2_500L)
        val stop = ZuptObservation(2_700L)
        val atMeasurement = requireNotNull(ordered.observeGnss(fix).estimate)
        ordered.observePdrStep(step)
        ordered.observeZupt(stop)
        delayed.observePdrStep(step)
        delayed.observeZupt(stop)

        val result = delayed.observeGnss(fix.copy(receivedAtElapsedRealtimeMs = 2_800L))

        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, result.disposition)
        assertEquivalent(ordered, delayed, 2_700L)
        assertEquals(ordered.estimateAt(2_700L), result.estimate)
        assertEquals(atMeasurement, result.estimateAtGnssMeasurement)
        assertEquals(2_000L, requireNotNull(result.estimate).lastInformativeGnssAtElapsedRealtimeMs)
        assertEquals(2_700L, requireNotNull(result.estimate).estimatedAtElapsedRealtimeMs)
        assertEquals(2, result.replayedMotionEventCount)
        assertEquals(700L, result.measurementLagMs)
        assertNull(delayed.estimateAt(2_000L))
    }

    @Test
    fun equalTimeGnssIsAppliedBeforeBothMotionEvents() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observeGnss(gnss(2_000L))
        listOf(ordered, delayed).forEach {
            it.observePdrStep(step(2_000L))
            it.observeZupt(ZuptObservation(2_000L))
        }

        val result = delayed.observeGnss(gnss(2_000L, receivedAt = 2_100L))

        assertEquals(GnssObservationDisposition.APPLIED, result.disposition)
        assertEquals(2, result.replayedMotionEventCount)
        assertEquals(0L, result.measurementLagMs)
        assertEquivalent(ordered, delayed, 2_000L)
    }

    @Test
    fun successiveLateFixesReuseOnlyMotionAfterEachMeasurement() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observeGnss(gnss(1_500L))
        ordered.observePdrStep(step(2_000L))
        ordered.observeGnss(gnss(2_500L, longitude = 127.00003))
        ordered.observePdrStep(step(3_000L))
        ordered.observeZupt(ZuptObservation(3_500L))
        delayed.observePdrStep(step(2_000L))
        delayed.observePdrStep(step(3_000L))
        delayed.observeZupt(ZuptObservation(3_500L))

        val first = delayed.observeGnss(gnss(1_500L, receivedAt = 3_600L))
        val second = delayed.observeGnss(gnss(2_500L, receivedAt = 3_700L, longitude = 127.00003))

        assertEquals(3, first.replayedMotionEventCount)
        assertEquals(2, second.replayedMotionEventCount)
        assertEquivalent(ordered, delayed, 3_500L)
    }

    @Test
    fun replayPreservesStepAndZuptDuplicateGuardsAndDoesNotDoubleCountDisplacement() {
        val estimator = initialized()
        estimator.observePdrStep(step(2_000L))
        estimator.observeZupt(ZuptObservation(2_500L))
        val update = estimator.observeGnss(gnss(1_500L, receivedAt = 2_600L))
        val before = update.estimate
        val covariance = estimator.covarianceSnapshot()

        val duplicateStep = estimator.observePdrStep(step(2_000L))
        val duplicateZupt = estimator.observeZupt(ZuptObservation(2_500L))
        val duplicateGnss = estimator.observeGnss(gnss(1_500L, receivedAt = 2_700L))

        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, duplicateStep.hardRejectReason)
        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, duplicateZupt.hardRejectReason)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, duplicateGnss.hardRejectReason)
        assertEquals(before, duplicateGnss.estimate)
        assertArrayEquals(covariance, estimator.covarianceSnapshot(), 0.0)
        val measurement = requireNotNull(update.estimateAtGnssMeasurement)
        val expectedStepLongitude = Math.toDegrees(0.65 / (6_371_000.0 * kotlin.math.cos(Math.toRadians(37.0))))
        assertEquals(expectedStepLongitude, requireNotNull(before).longitude - measurement.longitude, 1e-12)
    }

    @Test
    fun olderGnssThanLastAcceptedFixRemainsOutOfOrder() {
        val estimator = initialized()
        estimator.observeGnss(gnss(2_000L))
        estimator.observePdrStep(step(3_000L))
        val before = estimator.estimateAt(3_000L)

        val result = estimator.observeGnss(gnss(1_500L, receivedAt = 3_100L))

        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, result.hardRejectReason)
        assertEquals(before, result.estimate)
        assertEquals(0, result.replayedMotionEventCount)
    }

    @Test
    fun lagBoundaryIsInclusiveAndOlderFixCannotMutateState() {
        val accepted = initialized()
        val rejected = initialized()
        accepted.observePdrStep(step(7_000L))
        rejected.observePdrStep(step(7_001L))
        val before = rejected.estimateAt(7_001L)
        val covariance = rejected.covarianceSnapshot()

        val inside = accepted.observeGnss(gnss(2_000L, receivedAt = 7_100L))
        val outside = rejected.observeGnss(gnss(2_000L, receivedAt = 7_100L))

        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, inside.disposition)
        assertEquals(5_000L, inside.measurementLagMs)
        assertEquals(GnssHardRejectReason.REPLAY_WINDOW_EXCEEDED, outside.hardRejectReason)
        assertEquals(5_001L, outside.measurementLagMs)
        assertEquals(before, outside.estimate)
        assertArrayEquals(covariance, rejected.covarianceSnapshot(), 0.0)
    }

    @Test
    fun retainedEventLimitRejectsMissingHistoryButStillAcceptsFixInsideRetainedHistory() {
        val estimator = initialized(PedestrianPositionEstimatorConfig(maximumRetainedMotionEvents = 2))
        listOf(2_000L, 3_000L, 4_000L).forEach { estimator.observePdrStep(step(it)) }
        val before = estimator.estimateAt(4_000L)

        val evicted = estimator.observeGnss(gnss(1_500L, receivedAt = 4_100L))
        val retained = estimator.observeGnss(gnss(2_500L, receivedAt = 4_200L))

        assertEquals(GnssHardRejectReason.REPLAY_WINDOW_EXCEEDED, evicted.hardRejectReason)
        assertEquals(before, evicted.estimate)
        assertEquals(2, retained.replayedMotionEventCount)
        val ordered = initialized()
        ordered.observePdrStep(step(2_000L))
        ordered.observeGnss(gnss(2_500L))
        ordered.observePdrStep(step(3_000L))
        ordered.observePdrStep(step(4_000L))
        assertEquivalent(ordered, estimator, 4_000L)
    }

    @Test
    fun partiallyEvictedEqualTimeMotionCannotBeReplayedOutOfOrder() {
        val estimator = initialized(PedestrianPositionEstimatorConfig(maximumRetainedMotionEvents = 1))
        estimator.observePdrStep(step(2_000L))
        estimator.observeZupt(ZuptObservation(2_000L))
        val before = estimator.estimateAt(2_000L)

        val result = estimator.observeGnss(gnss(2_000L, receivedAt = 2_100L))

        assertEquals(GnssHardRejectReason.REPLAY_WINDOW_EXCEEDED, result.hardRejectReason)
        assertEquals(before, result.estimate)
    }

    @Test
    fun staleAndInvalidCallbackTimesAreRejectedBeforeRewind() {
        val estimator = initialized()
        estimator.observePdrStep(step(3_000L))
        val before = estimator.estimateAt(3_000L)

        val stale = estimator.observeGnss(gnss(2_000L, receivedAt = 12_001L))
        val invalid = estimator.observeGnss(gnss(2_000L, receivedAt = 1_999L))

        assertEquals(GnssHardRejectReason.STALE, stale.hardRejectReason)
        assertEquals(GnssHardRejectReason.INVALID_TIME, invalid.hardRejectReason)
        assertEquals(before, estimator.estimateAt(3_000L))
        assertEquals(1, estimator.observeGnss(gnss(2_000L, receivedAt = 3_100L)).replayedMotionEventCount)
    }

    @Test
    fun rejectedLateCorruptOrNumericalFixRollsBackHistoryCovarianceAndDynamics() {
        listOf(
            gnss(2_000L, receivedAt = 3_600L, longitude = 127.1) to GnssHardRejectReason.CORRUPT_JUMP,
            gnss(2_000L, receivedAt = 3_600L).copy(horizontalAccuracyM = Double.MAX_VALUE) to
                GnssHardRejectReason.NUMERICAL_FAILURE,
        ).forEach { (badFix, reason) ->
            val estimator = initialized()
            estimator.setPedestrianDynamics(PedestrianDynamics(0.8, 0.9))
            estimator.observePdrStep(step(3_000L))
            estimator.setPedestrianDynamics(PedestrianDynamics(2.0, 1.5))
            estimator.observeZupt(ZuptObservation(3_500L))
            estimator.setPedestrianDynamics(PedestrianDynamics(3.0, 1.8))
            val before = estimator.estimateAt(3_500L)
            val predictionBefore = estimator.estimateAt(4_000L)
            val covarianceBefore = estimator.covarianceSnapshot()

            val rejected = estimator.observeGnss(badFix)

            assertEquals(reason, rejected.hardRejectReason)
            assertEquals(before, rejected.estimate)
            assertEquals(predictionBefore, estimator.estimateAt(4_000L))
            assertArrayEquals(covarianceBefore, estimator.covarianceSnapshot(), 0.0)
            assertEquals(2, estimator.observeGnss(gnss(2_000L, receivedAt = 3_700L)).replayedMotionEventCount)
        }
    }

    @Test
    fun replayUsesOriginalIntervalDynamicsAndRestoresCurrentProfileAfterward() {
        val ordered = initialized()
        val delayed = initialized()
        listOf(ordered, delayed).forEach { it.setPedestrianDynamics(PedestrianDynamics(0.8, 0.9)) }
        ordered.observeGnss(gnss(2_000L))
        listOf(ordered, delayed).forEach {
            it.observePdrStep(step(2_500L))
            it.setPedestrianDynamics(PedestrianDynamics(2.0, 1.5))
            it.observeZupt(ZuptObservation(3_000L))
            it.setPedestrianDynamics(PedestrianDynamics(3.0, 1.8))
        }

        delayed.observeGnss(gnss(2_000L, receivedAt = 3_100L))

        assertEquivalent(ordered, delayed, 3_000L)
        assertEquals(ordered.estimateAt(4_000L), delayed.estimateAt(4_000L))
    }

    @Test
    fun unresolvedStepReplayPreservesCovarianceOnlyBehavior() {
        val ordered = initialized()
        val delayed = initialized()
        ordered.observeGnss(gnss(2_000L))
        val unresolved = step(2_500L).copy(headingDegreesTrueNorth = null)
        listOf(ordered, delayed).forEach {
            assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, it.observePdrStep(unresolved).disposition)
        }

        val update = delayed.observeGnss(gnss(2_000L, receivedAt = 2_600L))

        assertEquivalent(ordered, delayed, 2_500L)
        assertEquals(requireNotNull(update.estimateAtGnssMeasurement).longitude, requireNotNull(update.estimate).longitude, 0.0)
    }

    @Test
    fun weakLateGnssDoesNotRefreshInformativeGnssTime() {
        val estimator = initialized()
        estimator.observePdrStep(step(2_500L))

        val update = estimator.observeGnss(gnss(2_000L, receivedAt = 2_600L, longitude = 127.004))

        assertEquals(GnssObservationDisposition.DOWNWEIGHTED, update.disposition)
        assertTrue(requireNotNull(update.gateWeight) < 0.25)
        assertEquals(1_000L, requireNotNull(update.estimate).lastInformativeGnssAtElapsedRealtimeMs)
        assertEquals(2_000L, requireNotNull(update.estimateAtGnssMeasurement).estimatedAtElapsedRealtimeMs)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER,
            estimator.observeGnss(gnss(2_000L, receivedAt = 2_700L)).hardRejectReason)
    }

    @Test
    fun resetClearsReplayHistoryAndTimestampGuards() {
        val estimator = initialized()
        estimator.observePdrStep(step(3_000L))
        estimator.observeZupt(ZuptObservation(3_500L))
        estimator.observeGnss(gnss(2_000L, receivedAt = 3_600L))

        estimator.reset()

        assertNull(estimator.estimateAt(3_500L))
        assertEquals(GnssObservationDisposition.INITIALIZED, estimator.observeGnss(gnss(500L)).disposition)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, estimator.observePdrStep(step(600L)).disposition)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, estimator.observeZupt(ZuptObservation(700L)).disposition)
        val result = estimator.observeGnss(gnss(550L, receivedAt = 800L))
        assertEquals(2, result.replayedMotionEventCount)
        assertEquals(700L, requireNotNull(result.estimate).estimatedAtElapsedRealtimeMs)
    }

    @Test
    fun reinitializationDiscardsOldMotionHistoryAndPreservesNewFrame() {
        val estimator = initialized()
        estimator.observePdrStep(step(2_000L))
        val fresh = gnss(32_001L, longitude = 128.0)

        val reinitialized = estimator.observeGnss(fresh)
        estimator.observePdrStep(step(33_000L))
        val next = fresh.copy(elapsedRealtimeMs = 32_500L, receivedAtElapsedRealtimeMs = 33_100L)
        val update = estimator.observeGnss(next)

        assertEquals(GnssObservationDisposition.REINITIALIZED, reinitialized.disposition)
        assertEquals(0, reinitialized.replayedMotionEventCount)
        assertEquals(1, update.replayedMotionEventCount)
        val ordered = PedestrianPositionEstimator()
        ordered.observeGnss(fresh)
        ordered.observeGnss(next)
        ordered.observePdrStep(step(33_000L))
        assertEquivalent(ordered, estimator, 33_000L)
    }

    private fun initialized(config: PedestrianPositionEstimatorConfig = PedestrianPositionEstimatorConfig()) =
        PedestrianPositionEstimator(config).also {
            it.observeGnss(gnss(1_000L, longitude = 127.0))
        }

    private fun gnss(time: Long, receivedAt: Long = time, longitude: Double = 127.00002) =
        GnssPositionObservation(37.0, longitude, 3.0, time, receivedAt)

    private fun step(time: Long) = PdrStepObservation(
        stepCount = 1,
        stepLengthM = 0.65,
        headingDegreesTrueNorth = 90.0,
        headingAccuracyDegrees = 5.0,
        quality = PdrStepQuality.HIGH,
        elapsedRealtimeMs = time,
    )

    private fun assertEquivalent(expected: PedestrianPositionEstimator, actual: PedestrianPositionEstimator, time: Long) {
        assertEquals(expected.estimateAt(time), actual.estimateAt(time))
        assertArrayEquals(expected.covarianceSnapshot(), actual.covarianceSnapshot(), 1e-12)
    }
}
