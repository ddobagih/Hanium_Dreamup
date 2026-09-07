package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class PositioningCoordinatorTest {
    @Test
    fun acceptedFixesShareSessionEnuAndFilteredCoordinateRoundTrips() {
        val coordinator = PositioningCoordinator()
        val first = coordinator.observeGnss(fix(latitude = 37.5, longitude = 127.0, timeMs = 1_000L))

        assertEquals(0.0, requireNotNull(first.raw?.localEnu).eastM, 1e-9)
        assertEquals(0.0, requireNotNull(first.filtered).localEnu.eastM, 1e-8)
        assertEquals(37.5, first.filtered.coordinate.latitude, 1e-9)
        assertEquals(127.0, first.filtered.coordinate.longitude, 1e-9)

        val tenMetersLongitude = 10.0 / (6_378_137.0 * cos(Math.toRadians(37.5)) * Math.PI / 180.0)
        val second = coordinator.observeGnss(
            fix(latitude = 37.5, longitude = 127.0 + tenMetersLongitude, timeMs = 2_000L),
        )

        assertEquals(10.0, requireNotNull(second.raw?.localEnu).eastM, 0.02)
        assertTrue(requireNotNull(second.filtered).localEnu.eastM in 0.0..10.0)
        assertEquals(second.filtered.coordinate.latitude, second.gnssUpdate?.estimate?.latitude ?: 0.0, 1e-9)
        assertEquals(second.filtered.coordinate.longitude, second.gnssUpdate?.estimate?.longitude ?: 0.0, 1e-9)
    }

    @Test
    fun cn0RiskInflatesMeasurementNoiseWithoutHardRejectingFix() {
        val coordinator = PositioningCoordinator()
        repeat(3) { index ->
            coordinator.addGnssSignalEpoch(
                elapsedRealtimeNanos = (index + 1L) * 1_000_000_000L,
                signals = (1..4).map { svid ->
                    GnssSatelliteSignal(
                        constellation = 1,
                        svid = svid,
                        carrierFrequencyHz = 1_575_420_000.0,
                        cn0DbHz = 10.0,
                        usedInFix = true,
                    )
                },
            )
        }

        val snapshot = coordinator.observeGnss(fix(timeMs = 3_100L, accuracyM = 4.0))

        assertTrue(snapshot.gnssQuality.measurementNoiseMultiplier > 1.0)
        assertTrue(requireNotNull(snapshot.raw?.effectiveHorizontalAccuracyM) > 4.0)
        assertEquals(GnssObservationDisposition.INITIALIZED, snapshot.gnssUpdate?.disposition)
        assertNotNull(snapshot.filtered)
    }

    @Test
    fun profileHeadingStepAndZuptFlowThroughEstimator() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(timeMs = 1_000L))
        repeat(3) {
            coordinator.calibrateProfile(
                WalkingCalibrationSample(
                    distanceM = 8.0,
                    stepCount = 10,
                    durationMs = 8_000L,
                    trustedPositionSegment = true,
                ),
            )
        }
        assertTrue(coordinator.currentProfile().isCalibrated)

        val step = coordinator.observeStep(
            stepCount = 2,
            headingInput = PedestrianHeadingInput(
                nowElapsedRealtimeMs = 2_000L,
                speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
                gpsCourse = HeadingObservation(90.0, 5.0, 2_000L),
                magneticTrueHeading = null,
                phoneForwardMounted = false,
            ),
            quality = PdrStepQuality.HIGH,
        )

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, step.motionUpdate?.disposition)
        assertTrue(requireNotNull(step.filtered).localEnu.eastM > 1.0)
        val zupt = coordinator.observeZupt(ZuptObservation(elapsedRealtimeMs = 3_000L))
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, zupt.motionUpdate?.disposition)
        assertTrue(kotlin.math.abs(requireNotNull(zupt.filtered).velocityEastMps) < 0.1)
    }

    @Test
    fun invalidFirstFixDoesNotBecomeSessionOriginAndResetClearsState() {
        val coordinator = PositioningCoordinator()
        val invalid = coordinator.observeGnss(fix(latitude = Double.NaN, timeMs = 1_000L))
        assertNull(invalid.raw?.localEnu)
        assertNull(invalid.filtered)

        val accepted = coordinator.observeGnss(fix(latitude = 37.5, longitude = 127.0, timeMs = 2_000L))
        assertEquals(0.0, requireNotNull(accepted.raw?.localEnu).eastM, 1e-9)
        assertEquals(0.0, requireNotNull(accepted.raw?.localEnu).northM, 1e-9)

        coordinator.reset()
        val stepAfterReset = coordinator.observeStep(
            stepCount = 1,
            headingInput = PedestrianHeadingInput(
                nowElapsedRealtimeMs = 3_000L,
                speed = WalkingSpeedObservation(1.0, 0.1, 3_000L),
                gpsCourse = HeadingObservation(90.0, 5.0, 3_000L),
                magneticTrueHeading = null,
                phoneForwardMounted = false,
            ),
            quality = PdrStepQuality.HIGH,
        )
        assertNull(stepAfterReset.filtered)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, stepAfterReset.motionUpdate?.disposition)
        assertEquals(0, stepAfterReset.gnssQuality.epochCount)
    }

    @Test
    fun unavailableEstimatePausesGuidanceImmediately() {
        val coordinator = PositioningCoordinator()
        val snapshot = coordinator.observeGnss(fix(timeMs = 1_000L, accuracyM = 30.0))

        assertEquals(PositionQuality.UNAVAILABLE, snapshot.quality)
        assertEquals(PositionConfidenceTransition.DEGRADED, snapshot.confidence.transition)
        assertTrue(snapshot.confidence.guidancePaused)
        assertFalse(snapshot.confidence.announcementRequest?.repeated ?: true)
    }

    @Test
    fun motionOnlyHighEstimateDoesNotCountAsFreshHighGnssRecoveryEvidence() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(timeMs = 1_000L))
        val degraded = coordinator.observeGnss(
            fix(timeMs = 2_000L, receivedAtMs = 13_001L),
        )
        assertTrue(degraded.confidence.guidancePaused)

        val firstHighGnss = coordinator.observeGnss(fix(timeMs = 14_000L))
        assertEquals(PositionQuality.HIGH, firstHighGnss.quality)
        assertTrue(firstHighGnss.confidence.guidancePaused)

        val motionOnly = coordinator.observeStep(
            stepCount = 1,
            headingInput = PedestrianHeadingInput(
                nowElapsedRealtimeMs = 15_000L,
                speed = WalkingSpeedObservation(1.0, 0.1, 15_000L),
                gpsCourse = HeadingObservation(90.0, 5.0, 15_000L),
                magneticTrueHeading = null,
                phoneForwardMounted = false,
            ),
            quality = PdrStepQuality.HIGH,
        )
        assertEquals(PositionQuality.HIGH, motionOnly.quality)
        assertTrue(motionOnly.confidence.guidancePaused)

        val secondHighGnss = coordinator.observeGnss(fix(timeMs = 16_000L))
        assertEquals(PositionQuality.HIGH, secondHighGnss.quality)
        assertFalse(secondHighGnss.confidence.guidancePaused)
        assertEquals(PositionConfidenceTransition.RECOVERED, secondHighGnss.confidence.transition)
    }

    @Test
    fun mapsProfileAverageWalkingSpeedIntoEstimatorProcessNoise() {
        fun calibratedCoordinator(durationMs: Long): PositioningCoordinator =
            PositioningCoordinator().also { coordinator ->
                repeat(3) {
                    coordinator.calibrateProfile(
                        WalkingCalibrationSample(
                            distanceM = 8.0,
                            stepCount = 10,
                            durationMs = durationMs,
                            trustedPositionSegment = true,
                        ),
                    )
                }
            }

        val slower = calibratedCoordinator(durationMs = 10_000L)
        val faster = calibratedCoordinator(durationMs = 5_000L)
        assertTrue(
            faster.currentProfile().averageWalkingSpeedMps >
                slower.currentProfile().averageWalkingSpeedMps,
        )
        slower.observeGnss(fix(timeMs = 1_000L))
        faster.observeGnss(fix(timeMs = 1_000L))
        val headingUnavailable = PedestrianHeadingInput(
            nowElapsedRealtimeMs = 3_000L,
            speed = WalkingSpeedObservation(0.3, 0.1, 3_000L),
            gpsCourse = null,
            magneticTrueHeading = null,
            phoneForwardMounted = false,
        )

        val slowerStep = slower.observeStep(1, headingUnavailable, PdrStepQuality.LOW)
        val fasterStep = faster.observeStep(1, headingUnavailable, PdrStepQuality.LOW)

        assertTrue(
            requireNotNull(fasterStep.filtered).horizontalUncertaintyM >
                requireNotNull(slowerStep.filtered).horizontalUncertaintyM,
        )
    }

    @Test
    fun stepSnapshotExposesSelectedHeadingForRecording() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(timeMs = 1_000L))

        val snapshot = coordinator.observeStep(
            stepCount = 1,
            headingInput = PedestrianHeadingInput(
                nowElapsedRealtimeMs = 2_000L,
                speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
                gpsCourse = HeadingObservation(90.0, 5.0, 1_900L),
                magneticTrueHeading = null,
                phoneForwardMounted = false,
            ),
            quality = PdrStepQuality.HIGH,
        )

        val selectedHeading = requireNotNull(snapshot.selectedHeading)
        assertEquals(90.0, selectedHeading.headingDegreesTrueNorth, 1e-9)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, selectedHeading.source)
        assertEquals(2_000L, selectedHeading.selectedAtElapsedRealtimeMs)
        assertEquals(1_900L, selectedHeading.sourceObservationAtElapsedRealtimeMs)
        assertEquals(5.0, requireNotNull(snapshot.selectedHeadingAccuracyDegrees), 1e-9)
        assertEquals(PositioningMotionTrigger.STEP, snapshot.motionTrigger)
    }

    @Test
    fun gnssAndZuptSnapshotsDoNotReusePreviousStepHeading() {
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(timeMs = 1_000L))
        val step = coordinator.observeStep(
            stepCount = 1,
            headingInput = PedestrianHeadingInput(
                nowElapsedRealtimeMs = 2_000L,
                speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
                gpsCourse = HeadingObservation(90.0, 5.0, 2_000L),
                magneticTrueHeading = null,
                phoneForwardMounted = false,
            ),
            quality = PdrStepQuality.HIGH,
        )
        assertNotNull(step.selectedHeading)

        val gnss = coordinator.observeGnss(fix(timeMs = 3_000L))
        assertNull(gnss.selectedHeading)
        assertNull(gnss.selectedHeadingAccuracyDegrees)
        assertNull(gnss.motionTrigger)

        val zupt = coordinator.observeZupt(ZuptObservation(elapsedRealtimeMs = 4_000L))
        assertNull(zupt.selectedHeading)
        assertNull(zupt.selectedHeadingAccuracyDegrees)
        assertEquals(PositioningMotionTrigger.ZUPT, zupt.motionTrigger)
    }

    private fun fix(
        latitude: Double = 37.5,
        longitude: Double = 127.0,
        timeMs: Long,
        accuracyM: Double = 1.5,
        receivedAtMs: Long = timeMs,
    ) = GnssPositionObservation(
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyM = accuracyM,
        elapsedRealtimeMs = timeMs,
        receivedAtElapsedRealtimeMs = receivedAtMs,
    )
}
