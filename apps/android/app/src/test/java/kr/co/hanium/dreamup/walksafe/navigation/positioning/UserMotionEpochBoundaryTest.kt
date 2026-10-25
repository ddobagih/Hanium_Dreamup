package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** ADD-MOTION-015/022: measurement times and sensor windows belong to one motion epoch. */
class UserMotionEpochBoundaryTest {
    @Test fun restartedCollectionCannotAdoptDelayedStepsMeasuredBeforeTheBoundary() {
        val coordinator = PositioningCoordinator()
        coordinator.observeStep(1, heading(4_900L), PdrStepQuality.HIGH)
        coordinator.reset()
        val current = owner()
        assertTrue(coordinator.beginUserMotionEpoch(current, 5_000L))
        // The restarted callback has the current collection generation, but an old measurement.
        coordinator.observeStep(1, heading(4_900L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 5_100L, expectedMotionOwner = current)
        val result = coordinator.currentUserMotion(5_100L)
        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, result.evidenceHealth)
        assertNull(result.stepObservedAtElapsedRealtimeMs)
    }

    @Test fun restartedCollectionCannotAdoptAWindowCrossingTheBoundary() {
        val coordinator = PositioningCoordinator()
        coordinator.reset()
        val current = owner()
        assertTrue(coordinator.beginUserMotionEpoch(current, 5_000L))
        // Envelope is after the boundary; its 4900..5100 window includes the previous walk.
        val result = coordinator.observeUserMotion(decision(5_100L), 5_100L,
            expectedMotionOwner = current)
        assertEquals(UserDeviceRotationState.UNKNOWN, result.deviceRotation)
        assertNull(result.motionObservedAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, result.evidenceHealth)
    }

    @Test fun newWalkClearsAllMotionEvidenceWithoutChangingTheCollectionOrFilteredPosition() {
        val coordinator = PositioningCoordinator()
        val first = owner(walk = "walk-1")
        coordinator.beginUserMotionEpoch(first, 0L)
        coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, 1_000L))
        feedQuiet(coordinator, first)
        assertTrue(coordinator.currentUserMotion(3_000L).zeroVelocityUpdateAllowed)
        val before = coordinator.evaluate(3_000L)
        val collection = coordinator.currentInputGeneration()
        val next = owner(walk = "walk-2")
        assertTrue(coordinator.beginUserMotionEpoch(next, 3_050L))
        val after = coordinator.evaluate(3_050L, collection)
        val motion = requireNotNull(after.userMotion)
        assertEquals(collection, coordinator.currentInputGeneration())
        assertEquals(collection, motion.generation)
        assertEquals(before.filtered?.coordinate, after.filtered?.coordinate)
        assertNotNull(after.filtered)
        assertEquals(next, motion.owner)
        assertEquals(UserMovementState.UNKNOWN, motion.movement)
        assertEquals(UserDeviceRotationState.UNKNOWN, motion.deviceRotation)
        assertEquals(UserLocomotionState.UNKNOWN, motion.locomotion)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, motion.evidenceHealth)
        assertNull(motion.motionObservedAtElapsedRealtimeMs)
        assertNull(motion.speedObservedAtElapsedRealtimeMs)
        assertNull(motion.stepObservedAtElapsedRealtimeMs)
        assertNull(motion.stationaryCandidateSinceElapsedRealtimeMs)
        assertNull(motion.expiresAtElapsedRealtimeMs)
        assertFalse(motion.zeroVelocityUpdateAllowed)
        val walking = coordinator.observeStep(1, heading(3_100L).copy(
            speed = WalkingSpeedObservation(0.3, 0.1, 3_100L),
            magneticTrueHeading = HeadingObservation(90.0, 5.0, 3_100L), phoneForwardMounted = true), PdrStepQuality.HIGH,
            expectedGeneration = collection, expectedMotionOwner = next)
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, walking.motionUpdate?.disposition)
        assertEquals(UserMovementState.MOVING, walking.userMotion?.movement)
        assertTrue(walking.relativeMotionEstimable)
    }

    @Test fun oldOwnerCannotFeedAnyMotionSourceOrConsumeTheNewOwnersOrder() {
        val coordinator = PositioningCoordinator()
        val old = owner(walk = "walk-1")
        val current = owner(walk = "walk-2")
        coordinator.beginUserMotionEpoch(old, 0L)
        coordinator.beginUserMotionEpoch(current, 5_000L)
        coordinator.observeUserSpeed(WalkingSpeedObservation(2.0, 0.1, 9_000L), location(9_000L), 9_000L,
            expectedMotionOwner = old)
        val refused = coordinator.observeUserMotion(decision(9_000L), 9_000L, expectedMotionOwner = old)
        assertEquals(UserMotionStateReason.OWNER_MISMATCH, refused.reason)
        assertEquals(current, refused.owner)
        val step = coordinator.observeStep(2, heading(9_000L), PdrStepQuality.HIGH,
            expectedMotionOwner = old)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, step.motionUpdate?.disposition)
        val zupt = coordinator.observeZupt(ZuptObservation(elapsedRealtimeMs = 9_000L), expectedMotionOwner = old)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, zupt.motionUpdate?.disposition)
        val empty = coordinator.currentUserMotion(5_100L)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, empty.evidenceHealth)
        assertNull(empty.motionObservedAtElapsedRealtimeMs)
        speed(coordinator, current, 5_100L, 1.2)
        assertEquals(UserMovementState.MOVING, coordinator.currentUserMotion(5_100L).movement)
    }

    @Test fun crossingEitherSensorWindowIsRejectedBeforeItsWatermarkIsConsumed() {
        val coordinator = PositioningCoordinator()
        val current = owner()
        coordinator.beginUserMotionEpoch(current, 5_000L)
        val crossing = PedestrianMotionWindowCoverage(5, 4_900L, 5_200L, 50L)
        val sample = sample(5_200L)
        coordinator.observeUserMotion(decision(sample.copy(accelerationWindow = crossing)), 5_200L,
            expectedMotionOwner = current)
        coordinator.observeUserMotion(decision(sample.copy(gyroscopeWindow = crossing)), 5_200L,
            expectedMotionOwner = current)
        assertNull(coordinator.peekUserMotion(5_200L).motionObservedAtElapsedRealtimeMs)
        val accepted = coordinator.observeUserMotion(decision(sample), 5_200L, expectedMotionOwner = current)
        assertEquals(5_200L, accepted.motionObservedAtElapsedRealtimeMs)
        assertEquals(UserDeviceRotationState.STABLE, accepted.deviceRotation)
    }

    @Test fun oldSpeedAndStepTimesAreRejectedButTheBoundaryItselfIsAccepted() {
        val coordinator = PositioningCoordinator()
        val current = owner()
        coordinator.beginUserMotionEpoch(current, 5_000L)
        speed(coordinator, current, 4_999L, 1.2, receivedAt = 5_100L)
        coordinator.observeStep(1, heading(4_999L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 5_100L, expectedMotionOwner = current)
        val initial = coordinator.currentUserMotion(5_100L)
        assertNull(initial.speedObservedAtElapsedRealtimeMs)
        assertNull(initial.stepObservedAtElapsedRealtimeMs)
        speed(coordinator, current, 5_000L, 1.2, receivedAt = 5_100L)
        coordinator.observeStep(1, heading(5_000L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 5_100L, expectedMotionOwner = current)
        val accepted = coordinator.currentUserMotion(5_100L)
        assertEquals(5_000L, accepted.speedObservedAtElapsedRealtimeMs)
        assertEquals(5_000L, accepted.stepObservedAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, accepted.movement)
    }

    @Test fun staleStationaryDecisionNeedsANewQuietDwellBeforeZupt() {
        val coordinator = PositioningCoordinator()
        val current = owner()
        coordinator.beginUserMotionEpoch(current, 5_000L)
        // A detector may retain STATIONARY internally; the owner keeps its own post-boundary dwell.
        speed(coordinator, current, 5_200L, 0.0)
        var state = coordinator.observeUserMotion(decision(5_200L), 5_200L, expectedMotionOwner = current)
        assertEquals(UserMovementState.UNKNOWN, state.movement)
        assertFalse(state.zeroVelocityUpdateAllowed)
        for (at in 5_300L..6_700L step 100L) {
            speed(coordinator, current, at, 0.0)
            state = coordinator.observeUserMotion(decision(at), at, expectedMotionOwner = current)
        }
        assertEquals(UserMovementState.STATIONARY, state.movement)
        assertEquals(5_200L, state.stationaryCandidateSinceElapsedRealtimeMs)
        assertTrue(state.zeroVelocityUpdateAllowed)
    }

    @Test fun actorAndRecoveryChangesClearWalkingAndRejectPreviousOwners() {
        val estimator = UserMotionStateEstimator()
        val first = owner(actor = 1L)
        estimator.beginEpoch(first, 1_000L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_000L, 1_000L, expectedOwner = first)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_100L, 1_100L, expectedOwner = first)
        assertEquals(UserLocomotionState.WALKING, estimator.snapshot(1_100L).locomotion)
        val actor = owner(actor = 2L)
        assertTrue(estimator.beginEpoch(actor, 1_150L))
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, estimator.snapshot(1_150L).evidenceHealth)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_200L, 1_200L, expectedOwner = first)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(1_200L).movement)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_300L, 1_300L, expectedOwner = actor)
        val recovered = owner(actor = 2L, recovery = 1L)
        assertTrue(estimator.beginEpoch(recovered, 1_350L))
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(1_350L).movement)
        assertFalse(estimator.beginEpoch(actor, 1_400L))
    }

    @Test fun newBootAllowsANewMonotonicDomainAndDoesNotCarryPublicationOrSourceOrder() {
        val estimator = UserMotionStateEstimator()
        val old = owner(boot = "boot-count:10")
        estimator.beginEpoch(old, 9_000L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 9_100L, 9_100L, expectedOwner = old)
        assertEquals(UserMovementState.MOVING, estimator.snapshot(9_100L).movement)
        val boot = owner(boot = "boot-count:11", process = "process-2")
        assertTrue(estimator.beginEpoch(boot, 100L))
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, estimator.snapshot(100L).evidenceHealth)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 200L, 200L, expectedOwner = old)
        assertEquals(UserMovementState.UNKNOWN, estimator.snapshot(200L).movement)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 200L, 200L, expectedOwner = boot)
        assertEquals(UserMovementState.MOVING, estimator.snapshot(200L).movement)
    }

    @Test fun unavailableBootIdentityIsExplicitAndProcessRestartStartsWarmingUp() {
        val estimator = UserMotionStateEstimator()
        val old = owner(boot = null, process = "process-1")
        estimator.beginEpoch(old, 1_000L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 1_100L, 1_100L, expectedOwner = old)
        estimator.snapshot(1_100L)
        val process = owner(boot = null, process = "process-2")
        assertTrue(estimator.beginEpoch(process, 1_200L))
        val snapshot = estimator.snapshot(1_200L)
        assertNull(snapshot.owner?.bootId)
        assertEquals("process-2", snapshot.owner?.processInstanceId)
        assertEquals(UserMovementState.UNKNOWN, snapshot.movement)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, snapshot.evidenceHealth)
        // A new process creates a new estimator; its first binding also starts empty.
        val restarted = UserMotionStateEstimator()
        restarted.beginEpoch(process, 1_200L)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, restarted.snapshot(1_200L).evidenceHealth)
    }

    @Test fun repeatedBindingIsIdempotentAndRegressiveBoundaryDoesNotEraseFreshEvidence() {
        val estimator = UserMotionStateEstimator()
        val current = owner()
        estimator.beginEpoch(current, 5_000L)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 5_200L, 5_200L, expectedOwner = current)
        assertTrue(estimator.beginEpoch(current, 5_000L))
        assertFalse(estimator.beginEpoch(current, 4_999L))
        assertFalse(estimator.beginEpoch(owner(actor = 0L), 5_300L))
        assertEquals(UserMovementState.MOVING, estimator.snapshot(5_200L).movement)
        assertFalse(estimator.beginEpoch(current, -1L))
        assertEquals(current, estimator.currentOwner())
    }

    @Test fun sourceOrderingIsIndependentWithinOneOwnerAndWindowsCannotGoBackwardsOrIntoTheFuture() {
        val coordinator = PositioningCoordinator()
        val current = owner()
        coordinator.beginUserMotionEpoch(current, 5_000L)
        coordinator.observeUserMotion(decision(5_500L), 5_600L, expectedMotionOwner = current)
        speed(coordinator, current, 5_200L, 1.2, receivedAt = 5_600L)
        assertEquals(UserMovementState.MOVING, coordinator.currentUserMotion(5_600L).movement)
        val future = PedestrianMotionWindowCoverage(5, 5_400L, 5_700L, 50L)
        val backwards = PedestrianMotionWindowCoverage(5, 5_100L, 5_400L, 50L)
        coordinator.observeUserMotion(decision(sample(5_600L).copy(gyroscopeWindow = future)), 5_600L,
            expectedMotionOwner = current)
        coordinator.observeUserMotion(decision(sample(5_600L).copy(accelerationWindow = backwards)), 5_600L,
            expectedMotionOwner = current)
        assertEquals(5_500L, coordinator.peekUserMotion(5_600L).motionObservedAtElapsedRealtimeMs)
        val accepted = coordinator.observeUserMotion(decision(5_600L), 5_600L, expectedMotionOwner = current)
        assertEquals(5_600L, accepted.motionObservedAtElapsedRealtimeMs)
    }

    @Test fun collectionResetStillRejectsOldGenerationAndNewGenerationAcceptsBoundInputs() {
        val coordinator = PositioningCoordinator()
        val oldGeneration = coordinator.currentInputGeneration()
        val current = owner()
        coordinator.beginUserMotionEpoch(current, 1_000L)
        coordinator.reset()
        coordinator.beginUserMotionEpoch(current, 2_000L)
        coordinator.observeUserSpeed(WalkingSpeedObservation(2.0, 0.1, 2_200L), location(2_200L), 2_200L,
            expectedGeneration = oldGeneration, expectedMotionOwner = current)
        coordinator.observeStep(1, heading(2_200L), PdrStepQuality.HIGH,
            expectedGeneration = oldGeneration, expectedMotionOwner = current)
        val rejected = coordinator.observeUserMotion(decision(2_200L), 2_200L,
            expectedGeneration = oldGeneration, expectedMotionOwner = current)
        assertEquals(UserMotionStateReason.GENERATION_MISMATCH, rejected.reason)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, coordinator.currentUserMotion(2_200L).evidenceHealth)
        speed(coordinator, current, 2_200L, 1.2)
        val accepted = coordinator.currentUserMotion(2_200L)
        assertEquals(oldGeneration + 1L, accepted.generation)
        assertEquals(UserMovementState.MOVING, accepted.movement)
    }

    @Test fun evaluationBeforeTheBoundaryIsInvalidWithoutPublishingOrConsumingNewEvidence() {
        val estimator = UserMotionStateEstimator()
        val current = owner()
        estimator.beginEpoch(current, 5_000L)
        assertEquals(UserMotionStateReason.INVALID_TIME, estimator.snapshot(4_999L).reason)
        assertEquals(UserMotionStateReason.INVALID_TIME, estimator.peek(4_999L).reason)
        estimator.observeSteps(1, PdrStepQuality.HIGH, 5_000L, 5_000L, expectedOwner = current)
        assertEquals(UserMovementState.MOVING, estimator.snapshot(5_000L).movement)
    }

    @Test fun rawGpsProducerAdmissionRejectsThePreviousBoundaryWithoutBlockingNewFixesOnTheSameCollection() {
        val coordinator = PositioningCoordinator()
        val old = owner(walk = "walk-1")
        val current = owner(walk = "walk-2")
        coordinator.beginUserMotionEpoch(old, 1_000L)
        val collection = coordinator.currentInputGeneration()
        coordinator.beginUserMotionEpoch(current, 5_000L)
        assertFalse(coordinator.isUserMotionInputCurrent(4_999L, current))
        assertFalse(coordinator.isUserMotionInputCurrent(5_100L, old))
        assertFalse(coordinator.isUserMotionInputCurrent(5_100L, null))
        assertTrue(coordinator.isUserMotionInputCurrent(5_000L, current))
        assertTrue(coordinator.isUserMotionInputCurrent(5_100L, current))
        speed(coordinator, current, 5_100L, 1.2)
        assertEquals(collection, coordinator.currentInputGeneration())
        assertEquals(UserMovementState.MOVING, coordinator.currentUserMotion(5_100L).movement)
    }

    @Test fun nullBootProcessChangeAcceptsANewOriginWithoutRestoringOldLiveEvidence() {
        val coordinator = PositioningCoordinator()
        val persisted = PersistedPedestrianMotionProfile(0.7, 1.0, 0.64, 12L)
        assertTrue(coordinator.restoreProfile(persisted))
        val profile = coordinator.currentProfile()
        val collection = coordinator.currentInputGeneration()
        val old = owner(boot = null, process = "process-1")
        assertTrue(coordinator.beginUserMotionEpoch(old, 9_000L))
        for (at in 9_200L..11_200L step 100L) {
            coordinator.observeUserMotion(decision(at), at, expectedMotionOwner = old)
            speed(coordinator, old, at, 0.0)
        }
        val previous = coordinator.currentUserMotion(11_200L)
        assertEquals(UserMovementState.STATIONARY, previous.movement)
        assertTrue(previous.zeroVelocityUpdateAllowed)
        assertNotNull(previous.stationaryCandidateSinceElapsedRealtimeMs)
        assertNotNull(previous.expiresAtElapsedRealtimeMs)
        assertTrue(previous.evidenceRefs.any { it.source == UserMotionEvidenceSource.GNSS_FIX })
        assertTrue(previous.evidenceRefs.any { it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW })
        assertTrue(previous.evidenceRefs.any { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW })

        val current = owner(boot = null, process = "process-2")
        assertTrue(coordinator.beginUserMotionEpoch(current, 100L))
        val empty = coordinator.currentUserMotion(100L)
        assertEquals(current, empty.owner)
        assertNull(empty.owner?.bootId)
        assertEquals(UserMovementState.UNKNOWN, empty.movement)
        assertEquals(UserLocomotionState.UNKNOWN, empty.locomotion)
        assertEquals(UserDeviceRotationState.UNKNOWN, empty.deviceRotation)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, empty.evidenceHealth)
        assertNull(empty.motionObservedAtElapsedRealtimeMs)
        assertNull(empty.speedObservedAtElapsedRealtimeMs)
        assertNull(empty.stepObservedAtElapsedRealtimeMs)
        assertNull(empty.stationaryCandidateSinceElapsedRealtimeMs)
        assertNull(empty.expiresAtElapsedRealtimeMs)
        assertFalse(empty.zeroVelocityUpdateAllowed)
        assertTrue(empty.evidenceRefs.isEmpty())
        assertFalse(coordinator.isUserMotionInputCurrent(200L, old))
        assertTrue(coordinator.isUserMotionInputCurrent(200L, current))

        val refusedStep = coordinator.observeStep(1, heading(200L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 250L, expectedMotionOwner = old)
        assertEquals(PedestrianMotionUpdateDisposition.HARD_REJECTED, refusedStep.motionUpdate?.disposition)
        assertEquals(UserMotionStateReason.OWNER_MISMATCH, refusedStep.userMotion?.reason)
        speed(coordinator, old, 300L, 0.0, receivedAt = 350L)
        val refusedMotion = coordinator.observeUserMotion(decision(300L), 350L, expectedMotionOwner = old)
        assertEquals(UserMotionStateReason.OWNER_MISMATCH, refusedMotion.reason)
        val stillEmpty = coordinator.peekUserMotion(350L)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, stillEmpty.evidenceHealth)
        assertTrue(stillEmpty.evidenceRefs.isEmpty())
        assertNull(stillEmpty.motionObservedAtElapsedRealtimeMs)
        assertNull(stillEmpty.speedObservedAtElapsedRealtimeMs)
        assertNull(stillEmpty.stepObservedAtElapsedRealtimeMs)

        // Identical source/receipt times are valid for the new owner after wrong-owner attempts.
        speed(coordinator, current, 300L, 0.0, receivedAt = 350L)
        val acceptedMotion = coordinator.observeUserMotion(decision(300L), 350L, expectedMotionOwner = current)
        assertEquals(300L, acceptedMotion.motionObservedAtElapsedRealtimeMs)
        assertEquals(300L, acceptedMotion.speedObservedAtElapsedRealtimeMs)
        assertEquals(UserDeviceRotationState.STABLE, acceptedMotion.deviceRotation)
        coordinator.observeStep(1, heading(400L), PdrStepQuality.HIGH,
            receivedAtElapsedRealtimeMs = 450L, expectedMotionOwner = current)
        val walking = coordinator.currentUserMotion(450L)
        assertEquals(current, walking.owner)
        assertEquals(400L, walking.stepObservedAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, walking.movement)
        assertEquals(collection, walking.generation)
        assertEquals(collection, coordinator.currentInputGeneration())
        assertEquals(profile, coordinator.currentProfile())
        assertEquals(persisted, coordinator.snapshotProfileForPersistence())
    }

    @Test fun changedBootAcceptsANewOriginEvenWhenProcessTokenIsUnchanged() {
        val estimator = UserMotionStateEstimator()
        val old = owner(boot = "boot-count:10", process = "process-1")
        assertTrue(estimator.beginEpoch(old, 9_000L))
        estimator.observeSteps(1, PdrStepQuality.HIGH, 9_100L, 9_100L, expectedOwner = old)
        val previous = estimator.snapshot(9_100L)
        assertEquals(UserMovementState.MOVING, previous.movement)
        assertEquals(9_100L, previous.stepObservedAtElapsedRealtimeMs)
        assertFalse(previous.evidenceRefs.isEmpty())

        // Keep the process token fixed to isolate boot identity at the public domain API.
        val boot = old.copy(bootId = "boot-count:11")
        assertEquals(old.processInstanceId, boot.processInstanceId)
        assertTrue(estimator.beginEpoch(boot, 100L))
        val empty = estimator.snapshot(100L)
        assertEquals(boot, empty.owner)
        assertEquals(UserMovementState.UNKNOWN, empty.movement)
        assertEquals(UserLocomotionState.UNKNOWN, empty.locomotion)
        assertEquals(UserDeviceRotationState.UNKNOWN, empty.deviceRotation)
        assertEquals(UserMotionEvidenceHealth.WARMING_UP, empty.evidenceHealth)
        assertNull(empty.motionObservedAtElapsedRealtimeMs)
        assertNull(empty.speedObservedAtElapsedRealtimeMs)
        assertNull(empty.stepObservedAtElapsedRealtimeMs)
        assertNull(empty.stationaryCandidateSinceElapsedRealtimeMs)
        assertNull(empty.expiresAtElapsedRealtimeMs)
        assertFalse(empty.zeroVelocityUpdateAllowed)
        assertTrue(empty.evidenceRefs.isEmpty())
        assertEquals(previous.generation, empty.generation)
        assertFalse(estimator.ownsInput(200L, old))
        assertTrue(estimator.ownsInput(200L, boot))

        estimator.observeSteps(1, PdrStepQuality.HIGH, 200L, 250L, expectedOwner = old)
        val refused = estimator.snapshot(250L)
        assertEquals(UserMovementState.UNKNOWN, refused.movement)
        assertNull(refused.stepObservedAtElapsedRealtimeMs)
        assertTrue(refused.evidenceRefs.isEmpty())
        estimator.observeSteps(1, PdrStepQuality.HIGH, 200L, 250L, expectedOwner = boot)
        val accepted = estimator.snapshot(250L)
        assertEquals(boot, accepted.owner)
        assertEquals(UserMovementState.MOVING, accepted.movement)
        assertEquals(200L, accepted.stepObservedAtElapsedRealtimeMs)
        assertEquals(previous.generation, accepted.generation)
    }

    private fun owner(actor: Long = 1L, walk: String = "walk-1", recovery: Long = 0L,
        boot: String? = "boot-count:10", process: String = "process-1") =
        UserMotionStateOwner(actor, WalkRuntimeEpoch(walk, recovery), boot, process)

    private fun heading(at: Long) = PedestrianHeadingInput(
        nowElapsedRealtimeMs = at, speed = null, gpsCourse = null,
        magneticTrueHeading = null, phoneForwardMounted = false)

    private fun sample(at: Long): PedestrianMotionSample {
        val window = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L)
        return PedestrianMotionSample(at, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.05, accelerationWindow = window, gyroscopeWindow = window)
    }

    private fun decision(at: Long) = decision(sample(at))
    private fun decision(sample: PedestrianMotionSample) = PedestrianStationaryDecision(
        PedestrianStationaryState.STATIONARY, PedestrianStationaryState.STATIONARY,
        accepted = true, sample = sample)

    private fun location(at: Long) = UserMotionLocationEvidence(at, 2.0, false)
    private fun speed(coordinator: PositioningCoordinator, owner: UserMotionStateOwner,
        at: Long, value: Double, receivedAt: Long = at) = coordinator.observeUserSpeed(
        WalkingSpeedObservation(value, 0.1, at), location(at), receivedAt, expectedMotionOwner = owner)

    private fun feedQuiet(coordinator: PositioningCoordinator, owner: UserMotionStateOwner) {
        val detector = PedestrianStationaryDetector()
        for (at in 1_000L..3_000L step 100L) {
            coordinator.observeUserMotion(detector.observe(sample(at)), at, expectedMotionOwner = owner)
            speed(coordinator, owner, at, 0.0)
        }
    }
}
