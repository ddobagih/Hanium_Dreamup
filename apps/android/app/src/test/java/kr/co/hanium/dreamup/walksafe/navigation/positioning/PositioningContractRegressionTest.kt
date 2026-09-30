package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssFrequencyClassifier
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssSatelliteSignal
import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct regressions for the positioning timing/uncertainty contracts reviewed in 09.txt. */
class PositioningContractRegressionTest {
    @Test
    fun callerSignalQualityWeightsMissingAccuracyFixOnDefaultSigma() {
        // The Android GNSS observer owns the signal feed; the coordinator must not replace its weight.
        val neutral = PositioningCoordinator().observeGnss(fix(1_000L, accuracyM = null))
        val weighted = PositioningCoordinator().observeGnss(
            fix(1_000L, accuracyM = null).copy(measurementNoiseMultiplier = 4.0),
        )

        assertEquals(50.0, requireNotNull(neutral.filtered).horizontalUncertaintyM, 1e-9)
        assertEquals(100.0, requireNotNull(weighted.filtered).horizontalUncertaintyM, 1e-9)
    }

    @Test
    fun callerAndCoordinatorSignalQualityAreAppliedOnceNotMultiplied() {
        val reported = PositioningCoordinator().observeGnss(
            fix(1_000L, accuracyM = 4.0).copy(measurementNoiseMultiplier = 4.0),
        )
        assertEquals(8.0, requireNotNull(reported.filtered).horizontalUncertaintyM, 1e-9)
        assertEquals(8.0, requireNotNull(reported.raw?.effectiveHorizontalAccuracyM), 1e-9)

        val both = PositioningCoordinator().also(::seedPoorSignalQuality)
        val internalOnly = both.observeGnss(fix(3_100L, accuracyM = 4.0))
        assertEquals(2.2, internalOnly.gnssQuality.measurementNoiseMultiplier, 1e-9)
        val stronger = PositioningCoordinator().also(::seedPoorSignalQuality).observeGnss(
            fix(3_100L, accuracyM = 4.0).copy(measurementNoiseMultiplier = 4.0),
        )
        // max(4.0, 2.2), never 4.0 * 2.2.
        assertEquals(8.0, requireNotNull(stronger.filtered).horizontalUncertaintyM, 1e-9)
    }

    @Test
    fun invalidCallerSignalWeightIsRejectedWithoutCreatingState() {
        val coordinator = PositioningCoordinator()
        for (multiplier in listOf(Double.NaN, 0.5, Double.POSITIVE_INFINITY)) {
            val rejected = coordinator.observeGnss(fix(1_000L).copy(measurementNoiseMultiplier = multiplier))
            assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate?.disposition)
            assertEquals(GnssHardRejectReason.INVALID_ACCURACY, rejected.gnssUpdate?.hardRejectReason)
            assertNull(rejected.filtered)
        }
        assertEquals(GnssObservationDisposition.INITIALIZED, coordinator.observeGnss(fix(1_000L)).gnssUpdate?.disposition)
    }

    @Test
    fun rejectedFutureGnssDoesNotAdvanceOrExpireSignalQualityState() {
        val coordinator = PositioningCoordinator().also(::seedPoorSignalQuality)
        val rejected = coordinator.observeGnss(fix(90_000L, receivedAtMs = 3_100L))
        assertEquals(GnssHardRejectReason.INVALID_TIME, rejected.gnssUpdate?.hardRejectReason)

        val next = coordinator.addGnssSignalEpoch(4 * SECOND_NANOS, poorSignals())
        assertEquals(4 * SECOND_NANOS, next.latestEpochAtNanos)
        assertEquals(4, next.epochCount)
        assertFalse(next.isStale)
        assertEquals(2.2, coordinator.observeGnss(fix(3_500L)).gnssQuality.measurementNoiseMultiplier, 1e-9)
    }

    @Test
    fun zuptMeasuredBetweenGnssMeasurementAndReceiptIsNotAnInvalidTime() {
        // 09.txt example: GNSS 1,000/1,100 ms and ZUPT 1,050/1,150 ms, shifted by 2 s for dwell.
        val policy = PositionConfidencePolicy()
        policy.observe(PositionQuality.HIGH, 3_000L, 3_100L)
        assertNull(policy.observeMotion(PositionQuality.HIGH, 3_050L, 3_150L).failureReason)
        // The former measurement-time-as-now call is the counterexample.
        assertEquals(PositionConfidenceFailureReason.INVALID_TIME, PositionConfidencePolicy().also {
            it.observe(PositionQuality.HIGH, 3_000L, 3_100L)
        }.evaluate(3_050L).failureReason)

        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(fix(3_000L, receivedAtMs = 3_100L))
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_050L step 50L) {
            coordinator.observeUserMotion(detector.observe(quietSample(at)), at)
        }
        coordinator.observeUserSpeed(
            WalkingSpeedObservation(0.0, 0.1, 3_050L),
            UserMotionLocationEvidence(3_050L, 2.0, false),
            3_050L,
        )
        val zupt = coordinator.observeZupt(ZuptObservation(3_050L, receivedAtElapsedRealtimeMs = 3_150L))

        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, zupt.motionUpdate?.disposition)
        assertNull(zupt.confidence.failureReason)
        assertFalse(zupt.confidence.guidancePaused)
    }

    @Test
    fun deliveredConfidenceAnnouncementIsNotReturnedAgainFromCoordinatorReads() {
        val coordinator = PositioningCoordinator()
        val degraded = coordinator.observeGnss(fix(1_000L, accuracyM = 30.0))
        val request = requireNotNull(degraded.confidence.announcementRequest)

        assertTrue(coordinator.commitAnnouncementDelivered(request.token, 1_000L))
        assertFalse(coordinator.commitAnnouncementDelivered(request.token, 1_000L))
        assertNull(coordinator.evaluate(1_000L).confidence.announcementRequest)
        val rejectedMotion = coordinator.observeStep(1, heading(1_200L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 1_200L, stepLengthSigmaM = Double.NaN)
        assertNull(rejectedMotion.confidence.announcementRequest)
        assertNull(coordinator.evaluate(1_300L).confidence.announcementRequest)
    }

    @Test
    fun fiveSecondReplayWindowRetainsFourHertzStepsAndOneHertzZupts() {
        val estimator = PedestrianPositionEstimator()
        estimator.observeGnss(fix(1_000L))
        var events = 0
        for (at in 1_250L..6_000L step 250L) {
            val step = PdrStepObservation(1, 0.65, headingDegreesTrueNorth = 90.0,
                headingAccuracyDegrees = 5.0, quality = PdrStepQuality.HIGH, elapsedRealtimeMs = at)
            assertEquals(PedestrianMotionUpdateDisposition.APPLIED, estimator.observePdrStep(step).disposition)
            events += 1
            if (at % 1_000L == 0L) {
                assertEquals(PedestrianMotionUpdateDisposition.APPLIED,
                    estimator.observeZupt(ZuptObservation(at + 100L)).disposition)
                events += 1
            }
        }
        assertTrue(events > 20)

        val delayed = estimator.observeGnss(fix(1_101L, receivedAtMs = 6_100L))

        assertNotEquals(GnssObservationDisposition.HARD_REJECTED, delayed.disposition)
        assertEquals(events, delayed.replayedMotionEventCount)
    }

    @Test
    fun quietSampleAfterTenSecondsWithoutObservationsDoesNotBecomeStationary() {
        // 09.txt reproduction: quiet at 1,000 ms, no samples for 10 s, quiet again at 11,000 ms.
        val detector = PedestrianStationaryDetector()
        assertEquals(PedestrianStationaryState.CANDIDATE, detector.observe(quietSample(1_000L)).state)

        val afterGap = detector.observe(quietSample(11_000L))

        assertEquals(PedestrianStationaryState.CANDIDATE, afterGap.state)
        assertFalse(afterGap.shouldApplyZeroVelocityUpdate)
        assertEquals(11_000L, afterGap.candidateSinceElapsedRealtimeMs)
    }

    @Test
    fun singleSampleWindowIsInsufficientRatherThanQuiet() {
        val detector = PedestrianStationaryDetector()
        val single = quietSample(1_000L).let {
            it.copy(
                accelerationWindow = PedestrianMotionWindowCoverage(1, 1_000L, 1_000L, 0L),
                gyroscopeWindow = PedestrianMotionWindowCoverage(1, 1_000L, 1_000L, 0L),
            )
        }
        assertEquals(PedestrianStationaryState.UNKNOWN, detector.observe(single).state)
    }

    @Test
    fun newCoordinatorForAnotherActorStartsFromTheDefaultProfile() {
        val actorA = PositioningCoordinator()
        repeat(3) {
            actorA.calibrateProfile(WalkingCalibrationSample(8.0, 10, 8_000L, trustedPositionSegment = true))
        }
        assertTrue(actorA.currentProfile().isCalibrated)
        actorA.reset()
        // Same actor, new walk: reset keeps the calibrated profile.
        assertTrue(actorA.currentProfile().isCalibrated)

        val actorB = PositioningCoordinator()
        assertFalse(actorB.currentProfile().isCalibrated)
        assertEquals(PedestrianMotionProfileConfig().defaultStepLengthM, actorB.currentProfile().stepLengthM, 0.0)
        assertNull(actorB.snapshotProfileForPersistence())
        assertNotNull(actorA.snapshotProfileForPersistence())
    }

    @Test
    fun sessionFrameNormalizesLongitudeAcrossTheAntimeridianLikeTheEstimator() {
        // 09.txt S09-52: the estimator normalizes longitude differences; the session ENU frame must too.
        val coordinator = PositioningCoordinator()
        coordinator.observeGnss(GnssPositionObservation(37.5, 179.99995, 1.5, 1_000L))
        val crossed = coordinator.observeGnss(GnssPositionObservation(37.5, -179.99995, 1.5, 2_000L))

        // S09-52: the session frame now uses the shared LocalGeodesy radius (formerly its own 6 378 137 m).
        val expectedEastM = 0.0001 * PI / 180.0 * LocalGeodesy.EARTH_RADIUS_M * cos(37.5 * PI / 180.0)
        val raw = requireNotNull(crossed.raw?.localEnu)
        assertEquals(expectedEastM, raw.eastM, 1e-6)
        val filtered = requireNotNull(crossed.filtered)
        assertTrue(filtered.localEnu.eastM > 0.0 && filtered.localEnu.eastM <= expectedEastM + 1e-6)
        assertTrue(filtered.coordinate.longitude in -180.0..180.0)
    }

    @Test
    fun highCorridorFitWithHundredMetreInputErrorStillLeavesPositionGuidancePaused() {
        // 09.txt S09-44: 10 m beside a straight route, 100 m input error, no competing branch.
        val route = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0, 127.002))
        val offset = RoutePoint(37.0 + 10.0 / 111_320.0, 127.001)
        val match = RouteCorridorMatcher().match("straight", route, FilteredRoutePosition(offset, 100.0, 1_000L))
        assertEquals(RouteMatchQuality.HIGH, match.quality)

        val position = PositioningCoordinator().observeGnss(
            GnssPositionObservation(offset.latitude, offset.longitude, 100.0, 1_000L),
        )
        // The corridor fit is independent evidence; the position gate keeps precise guidance paused.
        assertEquals(PositionQuality.UNAVAILABLE, position.quality)
        assertTrue(position.confidence.guidancePaused)
    }

    private fun seedPoorSignalQuality(coordinator: PositioningCoordinator) {
        for (second in 1L..3L) coordinator.addGnssSignalEpoch(second * SECOND_NANOS, poorSignals())
    }

    private fun poorSignals() = (1..4).map { svid ->
        GnssSatelliteSignal(
            constellation = 1,
            svid = svid,
            carrierFrequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ,
            cn0DbHz = 13.0,
            usedInFix = true,
        )
    }

    private fun quietSample(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun heading(at: Long) = PedestrianHeadingInput(at, WalkingSpeedObservation(1.0, 0.1, at),
        HeadingObservation(90.0, 5.0, at), null, false)

    private fun fix(at: Long, accuracyM: Double? = 1.5, receivedAtMs: Long = at) =
        GnssPositionObservation(37.5, 127.0, accuracyM, at, receivedAtMs)

    private companion object {
        const val SECOND_NANOS = 1_000_000_000L
    }
}
