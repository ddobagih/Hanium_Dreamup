package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PedestrianPositionEstimatorTest {
    @Test
    fun initializesAtGnssOriginAndRoundTripsCoordinates() {
        val estimator = PedestrianPositionEstimator()

        val update = estimator.observeGnss(observation(37.5547, 126.9706, 1_000L, 2.0))

        assertEquals(GnssObservationDisposition.INITIALIZED, update.disposition)
        assertEquals(1.0, requireNotNull(update.gateWeight), 0.0)
        val estimate = requireNotNull(update.estimate)
        assertEquals(37.5547, estimate.latitude, 1e-9)
        assertEquals(126.9706, estimate.longitude, 1e-9)
        assertEquals(PositionQuality.HIGH, estimate.quality)
    }

    @Test
    fun predictionIsNonMutatingAndIncreasesUncertainty() {
        val estimator = PedestrianPositionEstimator()
        val initial = requireNotNull(
            estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0)).estimate,
        )

        val predicted = requireNotNull(estimator.estimateAt(3_500L))
        val current = requireNotNull(estimator.estimateAt(1_000L))

        assertTrue(predicted.horizontalUncertaintyM > initial.horizontalUncertaintyM)
        assertEquals(initial.latitude, current.latitude, 1e-12)
        assertEquals(initial.longitude, current.longitude, 1e-12)
        assertEquals(1_000L, current.estimatedAtElapsedRealtimeMs)
    }

    @Test
    fun repeatedStationaryFixesReducePositionUncertaintyAndKeepCovarianceSymmetric() {
        val estimator = PedestrianPositionEstimator()
        val initial = requireNotNull(
            estimator.observeGnss(observation(37.0, 127.0, 1_000L, 5.0)).estimate,
        )

        var latest = initial
        for (second in 2L..8L) {
            latest = requireNotNull(
                estimator.observeGnss(observation(37.0, 127.0, second * 1_000L, 5.0)).estimate,
            )
        }

        assertTrue(latest.horizontalUncertaintyM < initial.horizontalUncertaintyM)
        val covariance = estimator.covarianceSnapshot()
        assertEquals(16, covariance.size)
        assertTrue(covariance.all(Double::isFinite))
        for (row in 0 until 4) {
            assertTrue(covariance[row * 4 + row] > 0.0)
            for (column in 0 until 4) {
                assertEquals(covariance[row * 4 + column], covariance[column * 4 + row], 1e-10)
            }
        }
    }

    @Test
    fun downweightsPlausibleGpsJumpInsteadOfRejectingIt() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 3.0))
        val jumpedLongitude = longitudeOffsetMeters(37.0, 127.0, 30.0)

        val update = estimator.observeGnss(observation(37.0, jumpedLongitude, 2_000L, 3.0))

        assertEquals(GnssObservationDisposition.DOWNWEIGHTED, update.disposition)
        assertTrue(requireNotNull(update.gateWeight) in 0.0..0.999999)
        val estimate = requireNotNull(update.estimate)
        val estimatedDisplacementM = longitudeDistanceMeters(37.0, 127.0, estimate.longitude)
        assertTrue(estimatedDisplacementM > 0.0)
        assertTrue(estimatedDisplacementM < 15.0)
    }

    @Test
    fun hardRejectsOnlyExtremeCorruptJumpWithoutMovingState() {
        val estimator = PedestrianPositionEstimator()
        val initial = requireNotNull(
            estimator.observeGnss(observation(37.0, 127.0, 1_000L, 3.0)).estimate,
        )
        val jumpedLongitude = longitudeOffsetMeters(37.0, 127.0, 2_000.0)

        val update = estimator.observeGnss(observation(37.0, jumpedLongitude, 2_000L, 3.0))

        assertEquals(GnssObservationDisposition.HARD_REJECTED, update.disposition)
        assertEquals(GnssHardRejectReason.CORRUPT_JUMP, update.hardRejectReason)
        val retained = requireNotNull(update.estimate)
        assertEquals(initial.latitude, retained.latitude, 1e-12)
        assertEquals(initial.longitude, retained.longitude, 1e-12)
    }

    @Test
    fun rejectsMalformedStaleAndOutOfOrderObservationsWithoutResettingState() {
        val estimator = PedestrianPositionEstimator()
        val initial = requireNotNull(
            estimator.observeGnss(observation(37.0, 127.0, 10_000L, 3.0)).estimate,
        )

        val mock = estimator.observeGnss(observation(37.0, 127.0, 11_000L, 3.0, mock = true))
        val invalid = estimator.observeGnss(observation(Double.NaN, 127.0, 11_000L, 3.0))
        val stale = estimator.observeGnss(
            observation(37.0, 127.0, 11_000L, 3.0, receivedAtElapsedRealtimeMs = 21_001L),
        )
        val outOfOrder = estimator.observeGnss(observation(37.0, 127.0, 10_000L, 3.0))

        assertEquals(GnssHardRejectReason.MOCK, mock.hardRejectReason)
        assertEquals(GnssHardRejectReason.INVALID_COORDINATE, invalid.hardRejectReason)
        assertEquals(GnssHardRejectReason.STALE, stale.hardRejectReason)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, outOfOrder.hardRejectReason)
        assertEquals(initial.latitude, requireNotNull(outOfOrder.estimate).latitude, 1e-12)
    }

    @Test
    fun becomesUnavailableAfterFreshnessWindowWithoutMutatingState() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 1.5))

        val staleEstimate = requireNotNull(estimator.estimateAt(11_001L))

        assertEquals(PositionQuality.UNAVAILABLE, staleEstimate.quality)
        assertEquals(1_000L, staleEstimate.lastInformativeGnssAtElapsedRealtimeMs)
    }

    @Test
    fun reinitializesAfterRetainedStateGap() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 3.0))
        val newLatitude = 37.01
        val newLongitude = 127.01

        val update = estimator.observeGnss(observation(newLatitude, newLongitude, 31_001L, 3.0))

        assertEquals(GnssObservationDisposition.REINITIALIZED, update.disposition)
        val estimate = requireNotNull(update.estimate)
        assertEquals(newLatitude, estimate.latitude, 1e-9)
        assertEquals(newLongitude, estimate.longitude, 1e-9)
        assertEquals(31_001L, estimate.lastInformativeGnssAtElapsedRealtimeMs)
    }

    @Test
    fun resetClearsEstimate() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 3.0))

        estimator.reset()

        assertNull(estimator.estimateAt(1_000L))
        assertTrue(estimator.covarianceSnapshot().isEmpty())
    }

    @Test
    fun twoEastboundStepsMovePositionByCalibratedDistance() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))

        val update = estimator.observePdrStep(
            PdrStepObservation(
                stepCount = 2,
                stepLengthM = 0.65,
                headingDegreesTrueNorth = 90.0,
                headingAccuracyDegrees = 5.0,
                quality = PdrStepQuality.HIGH,
                elapsedRealtimeMs = 2_000L,
            ),
        )

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, update.disposition)
        val estimate = requireNotNull(update.estimate)
        assertEquals(1.3, longitudeDistanceMeters(37.0, 127.0, estimate.longitude), 0.01)
        assertEquals(37.0, estimate.latitude, 1e-7)
    }

    @Test
    fun missingHeadingAndLowQualityStepOnlyIncreaseCovariance() {
        listOf(
            PdrStepObservation(
                stepCount = 2,
                stepLengthM = 0.65,
                headingDegreesTrueNorth = null,
                headingAccuracyDegrees = null,
                quality = PdrStepQuality.HIGH,
                elapsedRealtimeMs = 2_000L,
            ),
            PdrStepObservation(
                stepCount = 2,
                stepLengthM = 0.65,
                headingDegreesTrueNorth = 90.0,
                headingAccuracyDegrees = 5.0,
                quality = PdrStepQuality.LOW,
                elapsedRealtimeMs = 2_000L,
            ),
        ).forEach { step ->
            val estimator = PedestrianPositionEstimator()
            val initial = requireNotNull(
                estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0)).estimate,
            )

            val update = estimator.observePdrStep(step)

            assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, update.disposition)
            val estimate = requireNotNull(update.estimate)
            assertEquals(initial.latitude, estimate.latitude, 1e-12)
            assertEquals(initial.longitude, estimate.longitude, 1e-12)
            assertTrue(estimate.horizontalUncertaintyM > initial.horizontalUncertaintyM)
        }
    }

    @Test
    fun zuptReducesVelocityWithoutJumpingPosition() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))
        estimator.observeGnss(
            observation(37.0, longitudeOffsetMeters(37.0, 127.0, 1.0), 2_000L, 2.0),
        )
        val before = requireNotNull(estimator.estimateAt(2_000L))
        val beforeSpeed = kotlin.math.hypot(before.velocityEastMps, before.velocityNorthMps)

        val update = estimator.observeZupt(
            ZuptObservation(elapsedRealtimeMs = 2_000L, velocitySigmaMps = 0.05),
        )

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, update.disposition)
        val after = requireNotNull(update.estimate)
        val afterSpeed = kotlin.math.hypot(after.velocityEastMps, after.velocityNorthMps)
        assertEquals(before.latitude, after.latitude, 1e-12)
        assertEquals(before.longitude, after.longitude, 1e-12)
        assertTrue(afterSpeed < beforeSpeed)
    }

    @Test
    fun personalizedProcessSigmaChangesPredictedCovariance() {
        val narrow = PedestrianPositionEstimator()
        val wide = PedestrianPositionEstimator()
        assertTrue(narrow.setPedestrianDynamics(PedestrianDynamics(0.5)))
        assertTrue(wide.setPedestrianDynamics(PedestrianDynamics(3.0)))
        narrow.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))
        wide.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))

        val narrowPrediction = requireNotNull(narrow.estimateAt(3_000L))
        val widePrediction = requireNotNull(wide.estimateAt(3_000L))

        assertTrue(widePrediction.horizontalUncertaintyM > narrowPrediction.horizontalUncertaintyM)
        assertTrue(!wide.setPedestrianDynamics(PedestrianDynamics(Double.NaN)))
    }

    @Test
    fun pdrStepDoesNotAddPriorVelocityDisplacement() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))
        estimator.observeGnss(
            observation(37.0, longitudeOffsetMeters(37.0, 127.0, 2.0), 2_000L, 2.0),
        )
        val before = requireNotNull(estimator.estimateAt(2_000L))
        assertTrue(before.velocityEastMps > 0.0)

        val update = estimator.observePdrStep(
            PdrStepObservation(
                stepCount = 2,
                stepLengthM = 0.65,
                headingDegreesTrueNorth = 90.0,
                headingAccuracyDegrees = 5.0,
                quality = PdrStepQuality.HIGH,
                elapsedRealtimeMs = 3_000L,
            ),
        )

        val after = requireNotNull(update.estimate)
        assertEquals(
            1.3,
            longitudeDistanceMeters(37.0, before.longitude, after.longitude),
            0.01,
        )
    }

    @Test
    fun delayedZuptDoesNotPropagatePriorVelocityThroughStationaryDwell() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))
        estimator.observeGnss(
            observation(37.0, longitudeOffsetMeters(37.0, 127.0, 2.0), 2_000L, 2.0),
        )
        val before = requireNotNull(estimator.estimateAt(2_000L))
        assertTrue(before.velocityEastMps > 0.0)

        val update = estimator.observeZupt(
            ZuptObservation(elapsedRealtimeMs = 3_500L, velocitySigmaMps = 0.05),
        )

        val after = requireNotNull(update.estimate)
        assertEquals(before.latitude, after.latitude, 1e-12)
        assertEquals(before.longitude, after.longitude, 1e-12)
        assertTrue(abs(after.velocityEastMps) < abs(before.velocityEastMps))
        assertEquals(3_500L, after.estimatedAtElapsedRealtimeMs)
    }

    @Test
    fun averageWalkingSpeedScalesProcessNoise() {
        val slower = PedestrianPositionEstimator()
        val faster = PedestrianPositionEstimator()
        assertTrue(slower.setPedestrianDynamics(PedestrianDynamics(1.0, 0.6)))
        assertTrue(faster.setPedestrianDynamics(PedestrianDynamics(1.0, 1.8)))
        slower.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))
        faster.observeGnss(observation(37.0, 127.0, 1_000L, 2.0))

        val slowerPrediction = requireNotNull(slower.estimateAt(3_000L))
        val fasterPrediction = requireNotNull(faster.estimateAt(3_000L))

        assertTrue(fasterPrediction.horizontalUncertaintyM > slowerPrediction.horizontalUncertaintyM)
    }

    @Test
    fun rejectsPolarOriginThatCannotDefineEastAxis() {
        listOf(-90.0, 90.0).forEach { latitude ->
            val estimator = PedestrianPositionEstimator()

            val update = estimator.observeGnss(observation(latitude, 127.0, 1_000L, 2.0))

            assertEquals(GnssObservationDisposition.HARD_REJECTED, update.disposition)
            assertEquals(GnssHardRejectReason.INVALID_COORDINATE, update.hardRejectReason)
            assertNull(update.estimate)
        }
    }

    private fun observation(
        latitude: Double,
        longitude: Double,
        elapsedRealtimeMs: Long,
        accuracyM: Double,
        receivedAtElapsedRealtimeMs: Long = elapsedRealtimeMs,
        mock: Boolean = false,
    ) = GnssPositionObservation(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = accuracyM,
        elapsedRealtimeMs = elapsedRealtimeMs,
        receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs,
        mock = mock,
    )

    private fun longitudeOffsetMeters(latitude: Double, longitude: Double, meters: Double): Double =
        longitude + Math.toDegrees(meters / (EARTH_RADIUS_M * cos(Math.toRadians(latitude))))

    private fun longitudeDistanceMeters(latitude: Double, fromLongitude: Double, toLongitude: Double): Double =
        abs(Math.toRadians(toLongitude - fromLongitude) * EARTH_RADIUS_M * cos(Math.toRadians(latitude)))

    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
    }
}
