package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.cos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianPositionEstimatorVelocityGuardTest {
    @Test
    fun directedTurnStopsOldAxisExtrapolationWithoutInventingZeroVelocity() {
        val estimator = movingEstimator()
        val before = requireNotNull(estimator.estimateAt(2_000L))
        val covarianceBefore = estimator.covarianceSnapshot()

        val update = estimator.observePdrStep(step(3_000L))
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, update.disposition)
        val turned = requireNotNull(update.estimate)
        assertTrue(turned.latitude > before.latitude)
        assertEquals(before.longitude, turned.longitude, 1e-12)
        assertEquals(before.velocityEastMps, turned.velocityEastMps, 0.0)
        assertTrue(turned.velocityEastMps > 0.0)
        val covarianceAfter = estimator.covarianceSnapshot()
        assertTrue(covarianceAfter[10] >= covarianceBefore[10])
        assertTrue(covarianceAfter[15] >= covarianceBefore[15])

        val future = requireNotNull(estimator.estimateAt(4_000L))
        assertSameMean(turned, future)
        assertTrue(future.horizontalUncertaintyM > turned.horizontalUncertaintyM)
    }

    @Test
    fun alignedDirectedStepAlsoCannotDoubleCountPriorVelocity() {
        val estimator = movingEstimator()
        val before = requireNotNull(estimator.estimateAt(2_000L))
        val after = requireNotNull(estimator.observePdrStep(step(3_000L, 90.0)).estimate)

        assertTrue(after.longitude > before.longitude)
        assertSameMean(after, requireNotNull(estimator.estimateAt(4_000L)))
    }

    @Test
    fun noStepAndUnresolvedStepPreserveExistingPredictionBehavior() {
        val normal = movingEstimator()
        assertTrue(
            requireNotNull(normal.estimateAt(4_000L)).longitude >
                requireNotNull(normal.estimateAt(2_000L)).longitude,
        )

        val unresolved = movingEstimator()
        val update = unresolved.observePdrStep(step(3_000L).copy(
            headingDegreesTrueNorth = null,
            headingAccuracyDegrees = null,
        ))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, update.disposition)
        assertTrue(
            requireNotNull(unresolved.estimateAt(4_000L)).longitude >
                requireNotNull(update.estimate).longitude,
        )
    }

    @Test
    fun duplicateOrRejectedStepCannotChangeTheVelocityGuard() {
        val estimator = movingEstimator()
        val accepted = requireNotNull(estimator.observePdrStep(step(3_000L)).estimate)
        val duplicate = estimator.observePdrStep(step(3_000L, 90.0))
        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, duplicate.hardRejectReason)
        val invalid = estimator.observePdrStep(step(4_000L).copy(stepCount = 0))
        assertEquals(PedestrianMotionHardRejectReason.INVALID_STEP, invalid.hardRejectReason)

        assertSameMean(accepted, requireNotNull(estimator.estimateAt(4_000L)))
    }

    @Test
    fun sameTimestampGnssReplayPreservesDirectedStepGuardAndCovariance() {
        val ordered = movingEstimator()
        val delayed = movingEstimator()
        val fix = gnss(3_000L, eastM = 4.0)
        ordered.observeGnss(fix)
        val orderedStep = requireNotNull(ordered.observePdrStep(step(3_000L)).estimate)
        delayed.observePdrStep(step(3_000L))

        val replayed = delayed.observeGnss(fix.copy(receivedAtElapsedRealtimeMs = 3_500L))
        assertEquals(1, replayed.replayedMotionEventCount)
        assertSameMean(orderedStep, requireNotNull(replayed.estimate))
        assertSameMean(orderedStep, requireNotNull(delayed.estimateAt(4_000L)))
        assertArrayEquals(ordered.covarianceSnapshot(), delayed.covarianceSnapshot(), 1e-9)
    }

    @Test
    fun newerInformativeGnssRestoresExistingVelocityPrediction() {
        val estimator = movingEstimator()
        val turned = requireNotNull(estimator.observePdrStep(step(3_000L)).estimate)
        val update = estimator.observeGnss(GnssPositionObservation(
            latitude = turned.latitude,
            longitude = turned.longitude + longitudeOffset(2.0),
            horizontalAccuracyM = 2.0,
            elapsedRealtimeMs = 4_000L,
        ))

        assertEquals(GnssObservationDisposition.APPLIED, update.disposition)
        assertEquals(1.0, requireNotNull(update.gateWeight), 0.0)
        assertTrue(
            requireNotNull(estimator.estimateAt(5_000L)).longitude >
                requireNotNull(update.estimate).longitude,
        )
    }

    @Test
    fun failedDelayedGnssRestoresSuppressedExtrapolation() {
        val estimator = movingEstimator()
        val turned = requireNotNull(estimator.observePdrStep(step(3_000L)).estimate)
        val covarianceBefore = estimator.covarianceSnapshot()

        val rejected = estimator.observeGnss(GnssPositionObservation(
            latitude = 0.0,
            longitude = 0.0,
            horizontalAccuracyM = 2.0,
            elapsedRealtimeMs = 2_500L,
            receivedAtElapsedRealtimeMs = 3_500L,
        ))
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, rejected.hardRejectReason)
        assertSameMean(turned, requireNotNull(estimator.estimateAt(4_000L)))
        assertArrayEquals(covarianceBefore, estimator.covarianceSnapshot(), 0.0)
    }

    @Test
    fun resetAndStateReinitializationClearTheOldStepGuard() {
        listOf(false, true).forEach { explicitReset ->
            val estimator = movingEstimator()
            estimator.observePdrStep(step(3_000L))
            if (explicitReset) estimator.reset()
            val startAt = if (explicitReset) 1_000L else 40_000L
            val initialized = estimator.observeGnss(gnss(startAt, eastM = 0.0))
            assertEquals(
                if (explicitReset) GnssObservationDisposition.INITIALIZED else GnssObservationDisposition.REINITIALIZED,
                initialized.disposition,
            )
            val moving = requireNotNull(estimator.observeGnss(gnss(startAt + 1_000L, eastM = 2.0)).estimate)

            assertTrue(requireNotNull(estimator.estimateAt(startAt + 2_000L)).longitude > moving.longitude)
        }
    }

    @Test
    fun replayFromAlreadyGuardedCheckpointMatchesInOrderState() {
        val ordered = movingEstimator()
        val delayed = movingEstimator()
        ordered.observePdrStep(step(3_000L))
        delayed.observePdrStep(step(3_000L))
        val fix = gnss(3_500L, eastM = 4.0, northM = 0.75)
        ordered.observeGnss(fix)
        val orderedStep = requireNotNull(ordered.observePdrStep(step(4_000L)).estimate)
        delayed.observePdrStep(step(4_000L))

        val replayed = delayed.observeGnss(fix.copy(receivedAtElapsedRealtimeMs = 4_500L))
        assertEquals(1, replayed.replayedMotionEventCount)
        assertSameMean(orderedStep, requireNotNull(replayed.estimate))
        assertSameMean(orderedStep, requireNotNull(delayed.estimateAt(5_000L)))
        assertArrayEquals(ordered.covarianceSnapshot(), delayed.covarianceSnapshot(), 1e-9)
    }

    private fun movingEstimator() = PedestrianPositionEstimator().also {
        it.observeGnss(gnss(1_000L, eastM = 0.0))
        val moving = requireNotNull(it.observeGnss(gnss(2_000L, eastM = 2.0)).estimate)
        assertTrue(moving.velocityEastMps > 0.0)
    }

    private fun step(atMs: Long, heading: Double = 0.0) = PdrStepObservation(
        stepCount = 1,
        stepLengthM = 0.75,
        headingDegreesTrueNorth = heading,
        headingAccuracyDegrees = 5.0,
        quality = PdrStepQuality.HIGH,
        elapsedRealtimeMs = atMs,
    )

    private fun gnss(atMs: Long, eastM: Double, northM: Double = 0.0) = GnssPositionObservation(
        latitude = 37.0 + Math.toDegrees(northM / EARTH_RADIUS_M),
        longitude = 127.0 + longitudeOffset(eastM),
        horizontalAccuracyM = 2.0,
        elapsedRealtimeMs = atMs,
    )

    private fun longitudeOffset(meters: Double) =
        Math.toDegrees(meters / (EARTH_RADIUS_M * cos(Math.toRadians(37.0))))

    private fun assertSameMean(expected: EstimatedLocation, actual: EstimatedLocation) {
        assertEquals(expected.latitude, actual.latitude, 1e-12)
        assertEquals(expected.longitude, actual.longitude, 1e-12)
        assertEquals(expected.velocityEastMps, actual.velocityEastMps, 1e-12)
        assertEquals(expected.velocityNorthMps, actual.velocityNorthMps, 1e-12)
    }

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
