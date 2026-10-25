package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-035/048: receipt-time stillness cannot authorize an earlier motion-time ZUPT. */
class UserMotionZuptMeasurementAlignmentRegressionTest {
    @Test
    fun futureGnssCannotAuthorizeAnEarlierDelayedZeroVelocityUpdate() {
        val fixture = delayedMotion(speedAt = 3_100L)
        val state = fixture.estimator.snapshot(RECEIPT_AT)

        assertEquals(UserMovementState.STATIONARY, state.movement)
        assertEquals(UserDeviceRotationState.STABLE, state.deviceRotation)
        assertTrue(state.zeroVelocityUpdateAllowed)
        assertEquals(MOTION_AT, state.motionObservedAtElapsedRealtimeMs)
        assertEquals(3_100L, state.speedObservedAtElapsedRealtimeMs)
        val gnss = state.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }
        assertEquals(3_100L, gnss.measuredAtElapsedRealtimeMs)
        assertEquals(3_100L, gnss.receivedAtElapsedRealtimeMs)
        assertEquals(location(3_100L), gnss.locationEvidence)

        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
        assertEquals(state, fixture.estimator.peek(RECEIPT_AT))
    }

    @Test
    fun rejectedHistoricalClaimDoesNotConsumeTheSameGnssOrItsOverlappingMotionWindow() {
        val fixture = delayedMotion(speedAt = 3_100L)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))

        // The same GNSS fix now supports this later motion; no older GNSS fix is readmitted.
        fixture.estimator.observeMotion(fixture.detector.observe(quiet(3_100L)), RECEIPT_AT,
            expectedOwner = fixture.owner)
        assertTrue(fixture.estimator.claimZeroVelocityUpdate(3_100L, RECEIPT_AT, fixture.owner))
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(3_100L, RECEIPT_AT, fixture.owner))
    }

    @Test
    fun sameOrEarlierMeasuredGnssStillAuthorizesTheDelayedMotion() {
        for (at in listOf(2_999L, MOTION_AT)) {
            val fixture = delayedMotion(speedAt = at)
            assertTrue("GNSS measured at $at", fixture.estimator.claimZeroVelocityUpdate(
                MOTION_AT, RECEIPT_AT, fixture.owner))
            assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
        }
    }

    @Test
    fun aLaterReceiptDoesNotTurnAnAlignedOriginalGnssMeasurementIntoFutureEvidence() {
        val fixture = delayedMotion(speedAt = MOTION_AT, speedReceiptAt = 3_120L)
        val ref = fixture.estimator.peek(RECEIPT_AT).evidenceRefs
            .single { it.source == UserMotionEvidenceSource.GNSS_FIX }

        assertEquals(MOTION_AT, ref.measuredAtElapsedRealtimeMs)
        assertEquals(3_120L, ref.receivedAtElapsedRealtimeMs)
        assertTrue(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
    }

    @Test
    fun evenTheNextMeasurementMillisecondCannotSupportTheEarlierZupt() {
        val fixture = delayedMotion(speedAt = MOTION_AT + 1L)
        assertTrue(fixture.estimator.peek(RECEIPT_AT).zeroVelocityUpdateAllowed)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
    }

    @Test
    fun movingOrMissingGroundSpeedStillCannotAuthorizeZeroVelocity() {
        for (speed in listOf(1.2, null)) {
            val fixture = delayedMotion(speedAt = MOTION_AT, speedMps = speed)
            val state = fixture.estimator.peek(RECEIPT_AT)
            assertEquals(if (speed == null) UserMovementState.UNKNOWN else UserMovementState.MOVING,
                state.movement)
            assertFalse(state.zeroVelocityUpdateAllowed)
            assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
        }
    }

    @Test
    fun wrongOwnerAndMismatchedMotionTimeDoNotConsumeAnOtherwiseValidClaim() {
        val fixture = delayedMotion(speedAt = MOTION_AT)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, owner(2L)))
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT - 1L, RECEIPT_AT, fixture.owner))
        assertTrue(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
    }

    @Test
    fun sameGnssAndOverlappingAcceptedWindowsStillCannotGenerateRepeatedZupt() {
        val fixture = delayedMotion(speedAt = MOTION_AT)
        assertTrue(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, RECEIPT_AT, fixture.owner))
        fixture.estimator.observeMotion(fixture.detector.observe(quiet(3_100L)), RECEIPT_AT,
            expectedOwner = fixture.owner)
        fixture.estimator.observeSpeed(speed(3_100L), location(3_100L), RECEIPT_AT,
            expectedOwner = fixture.owner)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(3_100L, RECEIPT_AT, fixture.owner))
        fixture.estimator.observeMotion(fixture.detector.observe(quiet(3_300L)), 3_300L,
            expectedOwner = fixture.owner)
        assertTrue(fixture.estimator.claimZeroVelocityUpdate(3_300L, 3_300L, fixture.owner))
    }

    @Test
    fun expiredMotionCannotRecoverWithoutANewObservation() {
        val fixture = delayedMotion(speedAt = MOTION_AT)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, 3_251L, fixture.owner))
        assertEquals(UserMovementState.UNKNOWN, fixture.estimator.peek(3_251L).movement)
        assertFalse(fixture.estimator.claimZeroVelocityUpdate(MOTION_AT, 3_400L, fixture.owner))
    }

    @Test
    fun actualCoordinatorConsumerRejectsHistoricalZuptWhilePreservingCurrentStillness() {
        val coordinator = PositioningCoordinator()
        val detector = PedestrianStationaryDetector()
        val owner = owner()
        assertTrue(coordinator.beginUserMotionEpoch(owner, 1_000L))
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 1_000L, 1_000L))
        coordinator.observeUserSpeed(speed(1_000L, 8.0), location(1_000L), 1_000L,
            expectedMotionOwner = owner)
        for (at in 1_200L..2_900L step 100L) {
            coordinator.observeUserMotion(detector.observe(quiet(at)), at, expectedMotionOwner = owner)
        }
        val delayed = detector.observe(quiet(MOTION_AT))
        assertTrue(delayed.shouldApplyZeroVelocityUpdate)
        coordinator.observeUserSpeed(speed(3_100L), location(3_100L), 3_100L,
            expectedMotionOwner = owner)
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 2.0, 3_100L, 3_100L))
        val state = coordinator.observeUserMotion(delayed, RECEIPT_AT, expectedMotionOwner = owner)
        assertEquals(UserMovementState.STATIONARY, state.movement)
        assertTrue(state.zeroVelocityUpdateAllowed)

        val rejected = coordinator.observeZupt(ZuptObservation(MOTION_AT), RECEIPT_AT,
            expectedMotionOwner = owner)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, rejected.motionUpdate?.disposition)
        assertEquals(PedestrianMotionHardRejectReason.MOTION_NOT_CONFIRMED, rejected.motionUpdate?.hardRejectReason)
        assertEquals(UserMovementState.STATIONARY, rejected.userMotion?.movement)

        coordinator.observeUserMotion(detector.observe(quiet(3_100L)), RECEIPT_AT,
            expectedMotionOwner = owner)
        val aligned = coordinator.observeZupt(ZuptObservation(3_100L), RECEIPT_AT,
            expectedMotionOwner = owner)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, aligned.motionUpdate?.disposition)
    }

    private fun delayedMotion(speedAt: Long, speedReceiptAt: Long = speedAt,
        speedMps: Double? = 0.0): Fixture {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        val owner = owner()
        assertTrue(estimator.beginEpoch(owner, 1_000L))
        estimator.observeSpeed(speed(1_000L, 8.0), location(1_000L), 1_000L, expectedOwner = owner)
        for (at in 1_200L..2_900L step 100L) {
            estimator.observeMotion(detector.observe(quiet(at)), at, expectedOwner = owner)
        }
        assertEquals(UserMovementState.MOVING, estimator.peek(2_900L).movement)
        val delayed = detector.observe(quiet(MOTION_AT))
        assertTrue(delayed.accepted)
        assertTrue(delayed.shouldApplyZeroVelocityUpdate)
        estimator.observeSpeed(speedMps?.let { speed(speedAt, it) }, location(speedAt), speedReceiptAt,
            expectedOwner = owner)
        estimator.observeMotion(delayed, RECEIPT_AT, expectedOwner = owner)
        return Fixture(estimator, detector, owner)
    }

    private fun quiet(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun speed(at: Long, value: Double = 0.0) = WalkingSpeedObservation(value, 0.1, at)

    private fun location(at: Long) = UserMotionLocationEvidence(at, 2.0, false,
        latitude = 37.5, longitude = 127.0, measurementElapsedRealtimeNanos = at * 1_000_000L)

    private fun owner(actor: Long = 1L) = UserMotionStateOwner(actor,
        WalkRuntimeEpoch("walk-$actor", 0L), null, "process-1")

    private data class Fixture(val estimator: UserMotionStateEstimator,
        val detector: PedestrianStationaryDetector, val owner: UserMotionStateOwner)

    private companion object {
        const val MOTION_AT = 3_000L
        const val RECEIPT_AT = 3_150L
    }
}
