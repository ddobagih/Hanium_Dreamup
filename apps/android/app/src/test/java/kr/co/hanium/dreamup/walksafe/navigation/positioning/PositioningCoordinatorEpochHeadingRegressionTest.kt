package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PositioningCoordinatorEpochHeadingRegressionTest {
    @Test
    fun preEpochSourcesWithinTtlDoNotDirectCurrentOwnerStep() {
        val coordinator = coordinator()
        // Compare with the stored fix, not evaluate's constant-velocity forecast:
        // a step uses the existing, distinct motion-controlled covariance prediction.
        val before = requireNotNull(coordinator.evaluate(1_000L).filtered)
        val current = owner(actor = 2L)
        assertTrue(coordinator.beginUserMotionEpoch(current, 2_000L))

        val result = step(coordinator, input(sourceAt = 1_900L), receivedAt = 2_150L)

        assertNull("Epoch-old speed and magnetic samples are still within selector TTL", result.selectedHeading)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        val after = requireNotNull(result.filtered)
        assertEquals(before.localEnu, after.localEnu)
        assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
        assertEquals(2_100L, result.userMotion?.stepObservedAtElapsedRealtimeMs)
        assertEquals(current, result.userMotion?.owner)
    }

    @Test
    fun newOwnerFirstFreshHeadingDoesNotBlendPriorOwner() {
        val coordinator = coordinator()
        val first = step(coordinator, input(at = 2_000L, heading = 0.0))
        assertEquals(0.0, requireNotNull(first.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertTrue(coordinator.beginUserMotionEpoch(owner(actor = 2L), 2_050L))

        val result = step(coordinator, input(heading = 90.0))

        assertDirected(result, heading = 90.0)
        assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
        assertEquals(2_100L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
    }

    @Test
    fun epochOldSpeedCannotAuthorizeFreshMagneticHeading() {
        val coordinator = coordinator(boundary = 2_000L)

        val result = step(coordinator, input().copy(speed = WalkingSpeedObservation(0.3, 0.1, 1_900L)))

        assertCovarianceOnly(result)
    }

    @Test
    fun epochOldMagneticHeadingCannotUseFreshSpeed() {
        val coordinator = coordinator(boundary = 2_000L)

        val result = step(coordinator, input().copy(magneticTrueHeading = HeadingObservation(90.0, 5.0, 1_900L)))

        assertCovarianceOnly(result)
    }

    @Test
    fun epochOldGpsCourseCannotUseFreshSpeed() {
        val coordinator = coordinator(boundary = 2_000L)
        val heading = input(speedMps = 1.0).copy(
            gpsCourse = HeadingObservation(90.0, 5.0, 1_900L),
            magneticTrueHeading = null,
        )

        assertCovarianceOnly(step(coordinator, heading))
    }

    @Test
    fun epochOldGpsRotationBaselineDoesNotTransportFreshCourse() {
        val coordinator = coordinator(boundary = 2_000L)
        val heading = input(speedMps = 1.0).copy(
            gpsCourse = HeadingObservation(30.0, 5.0, 2_000L),
            magneticTrueHeadingAtGpsCourse = HeadingObservation(0.0, 5.0, 1_950L),
        )

        val result = step(coordinator, heading)

        assertDirected(result, heading = 30.0)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
        assertEquals(2_000L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertNull(result.selectedHeading?.rotationObservationAtElapsedRealtimeMs)
        assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
    }

    @Test
    fun magneticSourcesExactlyAtEpochBoundaryRemainUsable() {
        val coordinator = coordinator(boundary = 2_000L)

        val result = step(coordinator, input(sourceAt = 2_000L), receivedAt = 2_150L)

        assertDirected(result, heading = 90.0)
        assertEquals(2_000L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertEquals(2_100L, result.selectedHeading?.selectedAtElapsedRealtimeMs)
        assertEquals(2_100L, result.userMotion?.stepObservedAtElapsedRealtimeMs)
    }

    @Test
    fun gpsCourseAndRotationBaselineAtBoundaryRemainUsable() {
        val coordinator = coordinator(boundary = 2_000L)
        val heading = input(speedMps = 1.0).copy(
            gpsCourse = HeadingObservation(0.0, 5.0, 2_000L),
            magneticTrueHeadingAtGpsCourse = HeadingObservation(0.0, 5.0, 2_000L),
        )

        val result = step(coordinator, heading)

        assertDirected(result, heading = 90.0)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
        assertEquals(2_000L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertEquals(2_100L, result.selectedHeading?.rotationObservationAtElapsedRealtimeMs)
    }

    @Test
    fun sameOwnerSameBoundaryCallPreservesSmoothing() {
        val coordinator = coordinator()
        val control = coordinator()
        step(coordinator, input(at = 2_000L, heading = 0.0))
        step(control, input(at = 2_000L, heading = 0.0))

        assertTrue(coordinator.beginUserMotionEpoch(owner(), 1_000L))

        assertMatchesContinuingEpoch(step(control, input(heading = 20.0)), step(coordinator, input(heading = 20.0)))
    }

    @Test
    fun sameOwnerAdvancedBoundaryResetsSmoothing() {
        val coordinator = coordinator()
        step(coordinator, input(at = 2_000L, heading = 0.0))
        assertTrue(coordinator.beginUserMotionEpoch(owner(), 2_050L))

        val result = step(coordinator, input())

        assertDirected(result, heading = 90.0)
        assertEquals(5.0, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
        assertFalse(coordinator.isUserMotionInputCurrent(2_000L, owner()))
    }

    @Test
    fun firstOwnerAtZeroBoundaryResetsPreviouslyUnownedSmoothing() {
        val coordinator = initializedCoordinator()
        step(coordinator, input(at = 2_000L, heading = 0.0))
        assertTrue(coordinator.beginUserMotionEpoch(owner(), 0L))

        assertDirected(step(coordinator, input()), heading = 90.0)
        assertTrue(coordinator.isUserMotionInputCurrent(0L, owner()))
    }

    @Test
    fun sameOwnerZeroBoundaryCallPreservesSmoothing() {
        val coordinator = coordinator(boundary = 0L)
        val control = coordinator(boundary = 0L)
        step(coordinator, input(at = 2_000L, heading = 0.0))
        step(control, input(at = 2_000L, heading = 0.0))

        assertTrue(coordinator.beginUserMotionEpoch(owner(), 0L))

        assertMatchesContinuingEpoch(step(control, input(heading = 20.0)), step(coordinator, input(heading = 20.0)))
    }

    @Test
    fun rejectedOlderOwnerAndBoundaryLeaveSelectorStateIntact() {
        val current = owner(actor = 2L)
        val coordinator = coordinator(current, 1_500L)
        val control = coordinator(current, 1_500L)
        step(coordinator, input(at = 2_000L, heading = 0.0))
        step(control, input(at = 2_000L, heading = 0.0))

        assertFalse(coordinator.beginUserMotionEpoch(owner(), 2_050L))
        assertFalse(coordinator.beginUserMotionEpoch(current, 1_400L))
        assertEquals(current, coordinator.currentUserMotionOwner())

        assertMatchesContinuingEpoch(step(control, input(heading = 20.0)), step(coordinator, input(heading = 20.0)))
    }

    @Test
    fun rejectedNegativeBoundaryLeavesSelectorStateIntact() {
        val coordinator = coordinator()
        val control = coordinator()
        step(coordinator, input(at = 2_000L, heading = 0.0))
        step(control, input(at = 2_000L, heading = 0.0))

        assertFalse(coordinator.beginUserMotionEpoch(owner(actor = 2L), -1L))
        assertFalse(coordinator.beginUserMotionEpoch(owner(actor = 2L), Long.MIN_VALUE))
        assertEquals(owner(), coordinator.currentUserMotionOwner())

        assertMatchesContinuingEpoch(step(control, input(heading = 20.0)), step(coordinator, input(heading = 20.0)))
    }

    @Test
    fun oldOwnerAndOldGenerationStepsCannotMutateCurrentHeading() {
        val current = owner(actor = 2L)
        val coordinator = coordinator(current)
        val control = coordinator(current)
        step(coordinator, input(at = 2_000L, heading = 0.0))
        step(control, input(at = 2_000L, heading = 0.0))
        val generation = coordinator.currentInputGeneration()

        val oldOwner = step(coordinator, input(at = 2_050L), expectedOwner = owner())
        val oldGeneration = coordinator.observeStep(
            1, input(at = 2_050L), PdrStepQuality.HIGH,
            expectedMotionOwner = current, expectedGeneration = generation - 1L,
        )

        assertEquals(UserMotionStateReason.OWNER_MISMATCH, oldOwner.userMotion?.reason)
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH, oldGeneration.motionUpdate?.hardRejectReason)
        assertMatchesContinuingEpoch(step(control, input(heading = 20.0)), step(coordinator, input(heading = 20.0)))
        assertEquals(generation, coordinator.currentInputGeneration())
    }

    @Test
    fun epochChangePreservesAbsolutePositionAndCallbackGeneration() {
        val coordinator = coordinator()
        step(coordinator, input(at = 2_000L, heading = 0.0))
        val before = coordinator.evaluate(2_050L)
        val profile = coordinator.currentProfile()
        assertTrue(coordinator.beginUserMotionEpoch(owner(actor = 2L), 2_050L))

        val after = coordinator.evaluate(2_050L)

        assertEquals(before.filtered, after.filtered)
        assertEquals(before.quality, after.quality)
        assertEquals(before.confidence, after.confidence)
        assertEquals(before.inputGeneration, after.inputGeneration)
        assertEquals(profile, coordinator.currentProfile())
        assertNull(after.userMotion?.stepObservedAtElapsedRealtimeMs)
        assertTrue(after.absoluteArrivalConfirmable)
    }

    private fun assertDirected(result: PositioningSnapshot, heading: Double) {
        assertEquals(heading, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
    }

    private fun assertCovarianceOnly(result: PositioningSnapshot) {
        assertNull(result.selectedHeading)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    private fun assertMatchesContinuingEpoch(control: PositioningSnapshot, actual: PositioningSnapshot) {
        val heading = requireNotNull(actual.selectedHeading).headingDegreesTrueNorth
        assertTrue("The continuing epoch must retain its existing smoothing", heading > 0.0 && heading < 20.0)
        assertEquals(control.selectedHeading, actual.selectedHeading)
        assertEquals(control.selectedHeadingAccuracyDegrees, actual.selectedHeadingAccuracyDegrees)
        assertEquals(control.filtered, actual.filtered)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, actual.motionUpdate?.disposition)
    }

    private fun coordinator(currentOwner: UserMotionStateOwner = owner(), boundary: Long = 1_000L) =
        initializedCoordinator().also { assertTrue(it.beginUserMotionEpoch(currentOwner, boundary)) }

    private fun initializedCoordinator() = PositioningCoordinator().also {
        val fix = it.observeGnss(GnssPositionObservation(
            latitude = 37.5, longitude = 127.0, horizontalAccuracyM = 2.0, elapsedRealtimeMs = 1_000L,
        ))
        assertEquals(GnssObservationDisposition.INITIALIZED, fix.gnssUpdate?.disposition)
    }

    private fun owner(actor: Long = 1L) = UserMotionStateOwner(
        actor, WalkRuntimeEpoch("walk-$actor", 0L), "boot-1", "process-1",
    )

    private fun input(at: Long = 2_100L, sourceAt: Long = at, heading: Double = 90.0, speedMps: Double = 0.3) =
        PedestrianHeadingInput(
            nowElapsedRealtimeMs = at,
            speed = WalkingSpeedObservation(speedMps, 0.1, sourceAt),
            gpsCourse = null,
            magneticTrueHeading = HeadingObservation(heading, 5.0, sourceAt),
            phoneForwardMounted = true,
        )

    private fun step(
        coordinator: PositioningCoordinator,
        heading: PedestrianHeadingInput,
        receivedAt: Long = heading.nowElapsedRealtimeMs,
        expectedOwner: UserMotionStateOwner? = coordinator.currentUserMotionOwner(),
    ) = coordinator.observeStep(
        1, heading, PdrStepQuality.HIGH,
        receivedAtElapsedRealtimeMs = receivedAt, expectedMotionOwner = expectedOwner,
    )
}
