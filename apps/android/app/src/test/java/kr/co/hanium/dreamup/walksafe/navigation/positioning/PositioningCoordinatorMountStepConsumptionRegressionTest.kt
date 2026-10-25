package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** ADD-MOTION-011/037: an explicit unsupported relation constrains the existing mounted input. */
class PositioningCoordinatorMountStepConsumptionRegressionTest {
    @Test fun unsupportedMountCannotDirectAMagneticSingleStep() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        val before = requireNotNull(coordinator.evaluate(1_500L).filtered)
        val result = step(coordinator)
        val state = requireNotNull(result.userMotion)
        assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, state.mount)
        assertEquals(UserMovementState.MOVING, state.movement)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, state.reason)
        val ref = state.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(2_000L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(2_000L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(StepEventSource.STEP_DETECTOR, ref.stepEvidence?.source)
        assertEquals(StepEventConfidence.HIGH, ref.stepEvidence?.confidence)
        assertEquals(1, ref.stepEvidence?.stepCount)
        assertFalse(state.zeroVelocityUpdateAllowed)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertNull(result.selectedHeading)
        val after = requireNotNull(result.filtered)
        assertEquals(before.localEnu, after.localEnu)
        assertEquals(before.coordinate, after.coordinate)
        assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
    }

    @Test fun unsupportedRelationAtTheMeasurementBoundaryAlsoWithholdsMagneticDirection() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 2_000L))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, step(coordinator).motionUpdate?.disposition)
    }

    @Test fun aFutureRelationDoesNotRetroactivelyRestrictAnEarlierDelayedStep() {
        val coordinator = coordinator()
        val control = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 2_001L, 2_050L))
        val result = step(coordinator, receipt = 2_100L)
        val expected = step(control, receipt = 2_100L)
        assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, result.userMotion?.mount)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
        assertEquals(expected.selectedHeading, result.selectedHeading)
    }

    @Test fun explicitUnknownPreservesTheLegacyDeclaredMountedInput() {
        val coordinator = coordinator()
        val control = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNKNOWN, 1_600L))
        val result = step(coordinator)
        val expected = step(control)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, result.userMotion?.mount)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
    }

    @Test fun rejectedRelationInputsCannotConsumeTheValidUnsupportedRetry() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_400L))
        assertFalse(coordinator.observeUserMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED,
            9_000L, 9_000L, expectedMotionOwner = OWNER.copy(walkEpoch = WalkRuntimeEpoch("old", 0L))))
        assertFalse(coordinator.observeUserMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED,
            9_000L, 9_000L, expectedGeneration = 99L, expectedMotionOwner = OWNER))
        assertFalse(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_501L, 1_500L))
        assertFalse(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 999L, 1_500L))
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, step(coordinator).motionUpdate?.disposition)
    }

    @Test fun aNewEpochDoesNotInheritThePreviousUnsupportedRelation() {
        val coordinator = coordinator()
        val control = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        val next = OWNER.copy(walkEpoch = WalkRuntimeEpoch("walk-2", 0L))
        assertTrue(coordinator.beginUserMotionEpoch(next, 1_600L))
        assertTrue(control.beginUserMotionEpoch(next, 1_600L))
        val result = step(coordinator, owner = next)
        val expected = step(control, owner = next)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, result.userMotion?.mount)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
    }

    @Test fun supportedAndMissingRelationsKeepExistingMountedCalculations() {
        val coordinator = coordinator()
        val control = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_500L))
        val result = step(coordinator)
        val expected = step(control)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, result.userMotion?.mount)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, expected.userMotion?.mount)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
        assertEquals(expected.selectedHeading, result.selectedHeading)
    }

    @Test fun unsupportedMountStillAllowsIndependentFreshGpsTravelCourse() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        val before = requireNotNull(coordinator.evaluate(1_500L).filtered)
        val input = magneticInput().copy(speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
            gpsCourse = HeadingObservation(0.0, 5.0, 2_000L))
        val result = step(coordinator, input = input)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
        assertEquals(0.0, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertEquals(2_000L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertNull(result.selectedHeading?.rotationObservationAtElapsedRealtimeMs)
        val after = requireNotNull(result.filtered)
        assertEquals(before.localEnu.eastM, after.localEnu.eastM, 1e-9)
        assertTrue(after.localEnu.northM > before.localEnu.northM)
    }

    @Test fun unsupportedMountCannotTransportGpsCourseByPhoneRotation() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        val input = magneticInput().copy(speed = WalkingSpeedObservation(1.0, 0.1, 2_000L),
            gpsCourse = HeadingObservation(0.0, 5.0, 1_800L),
            magneticTrueHeadingAtGpsCourse = HeadingObservation(0.0, 5.0, 1_800L))
        val result = step(coordinator, input = input)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
        assertEquals(0.0, requireNotNull(result.selectedHeading).headingDegreesTrueNorth, 1e-9)
        assertEquals(1_800L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertNull(result.selectedHeading?.rotationObservationAtElapsedRealtimeMs)
    }

    @Test fun supportedMountAloneCreatesNeitherDirectionNorGroundStillness() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_500L))
        val before = coordinator.currentUserMotion(1_500L)
        assertEquals(UserMovementState.UNKNOWN, before.movement)
        assertFalse(before.zeroVelocityUpdateAllowed)
        val result = step(coordinator, input = magneticInput().copy(
            magneticTrueHeading = null, phoneForwardMounted = false))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertNull(result.selectedHeading)
        assertFalse(requireNotNull(result.userMotion).zeroVelocityUpdateAllowed)
    }

    @Test fun withholdingDirectionStillConsumesOnlyOneOriginalStep() {
        val coordinator = coordinator()
        assertTrue(mount(coordinator, UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_500L))
        val first = step(coordinator)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, first.motionUpdate?.disposition)
        val duplicate = step(coordinator)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, duplicate.motionUpdate?.disposition)
        assertEquals(PedestrianMotionHardRejectReason.OUT_OF_ORDER, duplicate.motionUpdate?.hardRejectReason)
        assertEquals(first.filtered, duplicate.filtered)
    }

    private fun coordinator() = PositioningCoordinator().also {
        assertTrue(it.beginUserMotionEpoch(OWNER, 1_000L))
        assertEquals(GnssObservationDisposition.INITIALIZED,
            it.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, 1_000L)).gnssUpdate?.disposition)
    }

    private fun mount(coordinator: PositioningCoordinator, mount: UserDeviceBodyRelationState,
        observed: Long, receipt: Long = observed) = coordinator.observeUserMount(mount, observed, receipt,
            expectedMotionOwner = OWNER)

    private fun magneticInput() = PedestrianHeadingInput(2_000L, WalkingSpeedObservation(0.4, 0.1, 2_000L),
        null, HeadingObservation(90.0, 5.0, 2_000L), true)

    private fun step(coordinator: PositioningCoordinator, input: PedestrianHeadingInput = magneticInput(),
        receipt: Long = 2_000L, owner: UserMotionStateOwner = OWNER) = coordinator.observeStep(
            1, input, PdrStepQuality.HIGH, receivedAtElapsedRealtimeMs = receipt, expectedMotionOwner = owner,
            stepEventSource = StepEventSource.STEP_DETECTOR, stepEventConfidence = StepEventConfidence.HIGH)

    private companion object {
        val OWNER = UserMotionStateOwner(1L, WalkRuntimeEpoch("walk-1", 0L), "boot-1", "process-1")
    }
}
