package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** ADD-MOTION-005: supplied device-body relation is independent of physical motion evidence. */
class UserMotionMountObservationRegressionTest {
    @Test fun supportedMountReachesCoordinatorCurrentPeekAndPositioningOutput() {
        val coordinator = PositioningCoordinator()
        val owner = owner()
        assertTrue(coordinator.beginUserMotionEpoch(owner, 1_000L))
        assertTrue(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            1_000L, 1_010L, expectedMotionOwner = owner))
        val current = coordinator.currentUserMotion(1_010L)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, current.mount)
        assertEquals(current, coordinator.peekUserMotion(1_010L))
        assertEquals(current, coordinator.evaluate(1_010L).userMotion)
        assertEquals(owner, current.owner)
        assertEquals(UserMovementState.UNKNOWN, current.movement)
        assertEquals(UserLocomotionState.UNKNOWN, current.locomotion)
        assertEquals(UserDeviceRotationState.UNKNOWN, current.deviceRotation)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, current.evidenceHealth)
        assertTrue(current.evidenceRefs.isEmpty())
        assertNull(current.expiresAtElapsedRealtimeMs)
        assertFalse(current.zeroVelocityUpdateAllowed)
    }

    @Test fun explicitUnsupportedAndUnknownClearSupportEvenInTheSameMillisecond() {
        val estimator = UserMotionStateEstimator()
        for (mount in listOf(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, UserDeviceBodyRelationState.UNKNOWN)) {
            assertTrue(estimator.observeMount(mount, 1_000L, 1_000L))
            assertEquals(mount, estimator.peek(1_000L).mount)
            assertEquals(UserMovementState.UNKNOWN, estimator.peek(1_000L).movement)
            assertFalse(estimator.peek(1_000L).zeroVelocityUpdateAllowed)
        }
    }

    @Test fun mountInputDoesNotPublishOrChangeAnyMovingEvidence() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_000L, 1_000L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_500L, 1_500L)
        val before = estimator.snapshot(1_500L)
        assertEquals(UserMovementState.MOVING, before.movement)
        assertEquals(UserLocomotionState.WALKING, before.locomotion)
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 2_000L, 2_000L))
        // Input is newer, but it never advances the navigation publication watermark.
        assertEquals(before, estimator.snapshot(1_500L))
        val supported = estimator.peek(2_000L)
        assertEquals(before.stateSinceElapsedRealtimeMs, supported.stateSinceElapsedRealtimeMs)
        assertEquals(before.evidenceRefs, supported.evidenceRefs)
        assertEquals(before.expiresAtElapsedRealtimeMs, supported.expiresAtElapsedRealtimeMs)
        assertEquals(UserLocomotionState.WALKING, supported.locomotion)
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 2_000L, 2_000L))
        assertEquals(supported.copy(mount = UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED),
            estimator.peek(2_000L))
    }

    @Test fun explicitMountDoesNotKeepExpiredPhysicalEvidenceAlive() {
        val estimator = UserMotionStateEstimator()
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_000L, 1_000L)
        val before = estimator.peek(2_900L)
        assertEquals(UserMovementState.MOVING, before.movement)
        assertEquals(3_000L, before.expiresAtElapsedRealtimeMs)
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 2_900L, 2_900L))
        assertEquals(before.copy(mount = UserDeviceBodyRelationState.SUPPORTED_MOUNT), estimator.peek(2_900L))
        val expired = estimator.snapshot(3_001L)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, expired.mount)
        assertEquals(UserMovementState.UNKNOWN, expired.movement)
        assertEquals(UserMotionEvidenceHealth.EXPIRED, expired.evidenceHealth)
        assertNull(expired.stepObservedAtElapsedRealtimeMs)
        assertNull(expired.expiresAtElapsedRealtimeMs)
        assertFalse(expired.zeroVelocityUpdateAllowed)
    }

    @Test fun mountNeitherChangesStationaryStatisticsNorCreatesOrRestoresAZuptClaim() {
        val estimator = UserMotionStateEstimator()
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
            val sample = PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
                accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
                gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
            estimator.observeMotion(detector.observe(sample), at)
            estimator.observeSpeed(WalkingSpeedObservation(0.0, 0.1, at),
                UserMotionLocationEvidence(at, 2.0, false), at)
        }
        val before = estimator.snapshot(3_000L)
        assertEquals(UserMovementState.STATIONARY, before.movement)
        assertTrue(before.zeroVelocityUpdateAllowed)
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 3_000L, 3_000L))
        assertEquals(before.copy(mount = UserDeviceBodyRelationState.SUPPORTED_MOUNT), estimator.peek(3_000L))
        assertTrue(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 3_000L, 3_000L))
        assertFalse(estimator.claimZeroVelocityUpdate(3_000L, 3_000L))
        assertEquals(before.copy(mount = UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED), estimator.peek(3_000L))
    }

    @Test fun rejectedOwnerGenerationAndFutureInputsDoNotConsumeAValidRetry() {
        val estimator = UserMotionStateEstimator()
        val owner = owner()
        estimator.beginEpoch(owner, 1_000L)
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 9_000L, 9_000L,
            expectedOwner = owner(walk = "old-walk")))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 9_000L, 9_000L,
            expectedGeneration = 99L, expectedOwner = owner))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_101L, 1_100L,
            expectedOwner = owner))
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_101L, 1_101L,
            expectedOwner = owner))
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, estimator.peek(1_101L).mount)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.rejectedOwnerSnapshot(1_101L).mount)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.rejectedGenerationSnapshot(1_101L).mount)
    }

    @Test fun negativeAndPreEpochInputsCannotSupplyAMountRelation() {
        val estimator = UserMotionStateEstimator()
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, -1L, 0L))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 0L, -1L))
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.peek(0L).mount)
        val owner = owner()
        assertTrue(estimator.beginEpoch(owner, 1_000L))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 999L, 1_100L,
            expectedOwner = owner))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_000L, 1_100L,
            expectedOwner = null))
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_000L, 1_100L,
            expectedOwner = owner))
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, estimator.peek(1_100L).mount)
    }

    @Test fun lateObservationAndRegressingReceiptCannotOverwriteOrConsumeLaterInput() {
        val estimator = UserMotionStateEstimator()
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_100L, 1_300L))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.UNKNOWN, 1_000L, 9_000L))
        assertFalse(estimator.observeMount(UserDeviceBodyRelationState.UNKNOWN, 1_101L, 1_200L))
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, estimator.peek(1_300L).mount)
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, 1_101L, 1_300L))
        assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, estimator.peek(1_300L).mount)
    }

    @Test fun epochAdvanceClearsMountAndRejectsThePreviousOwner() {
        val coordinator = PositioningCoordinator()
        val old = owner()
        val current = owner(walk = "walk-2")
        assertTrue(coordinator.beginUserMotionEpoch(old, 1_000L))
        assertTrue(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            1_100L, 1_100L, expectedMotionOwner = old))
        assertTrue(coordinator.beginUserMotionEpoch(old, 1_000L))
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, coordinator.currentUserMotion(1_100L).mount)
        assertTrue(coordinator.beginUserMotionEpoch(current, 2_000L))
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, coordinator.currentUserMotion(2_000L).mount)
        assertFalse(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            9_000L, 9_000L, expectedMotionOwner = old))
        assertTrue(coordinator.observeUserMount(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED,
            2_100L, 2_100L, expectedMotionOwner = current))
        assertEquals(UserDeviceBodyRelationState.UNSUPPORTED_OR_CHANGED, coordinator.evaluate(2_100L).userMotion?.mount)
        assertTrue(coordinator.beginUserMotionEpoch(current, 2_200L))
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, coordinator.currentUserMotion(2_200L).mount)
    }

    @Test fun collectionResetAndProcessRestartNeverRestoreThePreviousMount() {
        val coordinator = PositioningCoordinator()
        val generation = coordinator.currentInputGeneration()
        val old = owner()
        assertTrue(coordinator.beginUserMotionEpoch(old, 9_000L))
        assertTrue(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            9_100L, 9_100L, expectedMotionOwner = old))
        coordinator.reset()
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, coordinator.currentUserMotion(0L).mount)
        val current = owner(process = "process-2")
        assertTrue(coordinator.beginUserMotionEpoch(current, 100L))
        assertFalse(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            200L, 200L, expectedGeneration = generation, expectedMotionOwner = current))
        assertFalse(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            9_200L, 9_200L, expectedMotionOwner = old))
        assertTrue(coordinator.observeUserMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT,
            200L, 200L, expectedMotionOwner = current))
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, coordinator.evaluate(200L).userMotion?.mount)
    }

    @Test fun diagnosticAndInvalidTimeReadsDoNotAdoptAFutureMount() {
        val estimator = UserMotionStateEstimator()
        assertTrue(estimator.observeMount(UserDeviceBodyRelationState.SUPPORTED_MOUNT, 1_000L, 1_000L))
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.peek(999L).mount)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.snapshot(-1L).mount)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, estimator.snapshot(1_000L).mount)
        assertEquals(UserDeviceBodyRelationState.UNKNOWN, estimator.snapshot(999L).mount)
        assertEquals(UserDeviceBodyRelationState.SUPPORTED_MOUNT, estimator.peek(1_000L).mount)
        assertFalse(estimator.claimZeroVelocityUpdate(1_000L, 1_000L))
    }

    private fun owner(walk: String = "walk-1", process: String = "process-1") =
        UserMotionStateOwner(1L, WalkRuntimeEpoch(walk, 0L), "boot-1", process)
}
