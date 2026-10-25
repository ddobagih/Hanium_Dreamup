package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** ADD-MOTION-018: current opposing source facts remain diagnostic; classification is unchanged. */
class UserMotionSupportConflictRegressionTest {
    @Test
    fun aFreshStationaryGnssFixAndFreshStepKeepTheirConflictWithoutChangingStepPriority() {
        val coordinator = conflictingInputs()
        val result = coordinator.currentUserMotion(1_250L)

        assertStepMovement(result, 3_100L)
        assertEquals(setOf(UserMotionEvidenceSource.GNSS_FIX, UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE),
            result.evidenceRefs.map { it.source }.toSet())
        assertEquals(WalkingSpeedObservation(0.0, 0.1, 1_000L),
            result.evidenceRefs.single { it.source == UserMotionEvidenceSource.GNSS_FIX }.speedObservation)
        assertEquals(1_100L, result.stepObservedAtElapsedRealtimeMs)
        // Use existing enum.name, so the baseline compiles without a future enum member or DTO.
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED.name, CONFLICT_REASON), reasonNames(result))
    }

    @Test
    fun currentConflictDoesNotDependOnWhichSourceCallbackArrivesFirst() {
        val speedFirst = conflictingInputs().currentUserMotion(1_250L)
        val stepFirstCoordinator = PositioningCoordinator()
        step(stepFirstCoordinator, 1_100L, 1_200L)
        stationaryGnss(stepFirstCoordinator, 1_000L, 1_220L)
        val stepFirst = stepFirstCoordinator.currentUserMotion(1_250L)

        assertStepMovement(speedFirst, 3_100L)
        assertStepMovement(stepFirst, 3_100L)
        assertEquals(speedFirst.reason, stepFirst.reason)
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED.name, CONFLICT_REASON), reasonNames(stepFirst))
        assertEquals(reasonNames(speedFirst), reasonNames(stepFirst))
    }

    @Test
    fun aStationaryGnssFixAloneDoesNotApproveBodyStillnessOrInventAConflict() {
        val coordinator = PositioningCoordinator()
        stationaryGnss(coordinator, 1_000L, 1_100L)
        val result = coordinator.currentUserMotion(1_250L)

        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.INSUFFICIENT_EVIDENCE, result.reason)
        assertEquals(setOf(UserMotionStateReason.INSUFFICIENT_EVIDENCE.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.GNSS_FIX, result.evidenceRefs.single().source)
        assertFalse(result.zeroVelocityUpdateAllowed)
        assertNull(result.expiresAtElapsedRealtimeMs)
    }

    @Test
    fun anAcceptedStepAloneDoesNotInventGroundSpeedOrAConflict() {
        val coordinator = PositioningCoordinator()
        step(coordinator, 1_100L, 1_200L)
        val result = coordinator.currentUserMotion(1_250L)

        assertStepMovement(result, 3_100L)
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, result.evidenceRefs.single().source)
        assertNull(result.speedObservedAtElapsedRealtimeMs)
    }

    @Test
    fun anExpiredGnssFixCannotKeepAConflictAliveOrRenewTheStepDeadline() {
        val coordinator = conflictingInputs()
        val first = coordinator.currentUserMotion(1_250L)
        val result = coordinator.currentUserMotion(3_001L)

        assertStepMovement(result, 3_100L)
        assertEquals(first.expiresAtElapsedRealtimeMs, result.expiresAtElapsedRealtimeMs)
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, result.evidenceRefs.single().source)
        assertNull(result.speedObservedAtElapsedRealtimeMs)
        assertFalse(CONFLICT_REASON in reasonNames(result))
    }

    @Test
    fun aRejectedMockGnssFixCannotCreateAConflictWithAnAcceptedStep() {
        val coordinator = PositioningCoordinator()
        stationaryGnss(coordinator, 1_000L, 1_100L, isMock = true)
        step(coordinator, 1_100L, 1_200L)
        val result = coordinator.currentUserMotion(1_250L)

        assertStepMovement(result, 3_100L)
        assertEquals(setOf(UserMotionStateReason.STEP_OBSERVED.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, result.evidenceRefs.single().source)
        assertNull(result.speedObservedAtElapsedRealtimeMs)
    }

    @Test
    fun aRejectedLowQualityStepCannotCreateAConflictOrApproveMovement() {
        val coordinator = PositioningCoordinator()
        stationaryGnss(coordinator, 1_000L, 1_100L)
        step(coordinator, 1_100L, 1_200L, PdrStepQuality.LOW)
        val result = coordinator.currentUserMotion(1_250L)

        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.INSUFFICIENT_EVIDENCE, result.reason)
        assertEquals(setOf(UserMotionStateReason.INSUFFICIENT_EVIDENCE.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.GNSS_FIX, result.evidenceRefs.single().source)
        assertNull(result.stepObservedAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    @Test
    fun anExpiredStepCannotApproveBodyMovementOrConflictWithAFreshStationaryGnssFix() {
        val coordinator = PositioningCoordinator()
        step(coordinator, 1_000L, 1_100L)
        stationaryGnss(coordinator, 2_900L, 2_950L)
        val result = coordinator.currentUserMotion(3_001L)

        assertEquals(UserMovementState.UNKNOWN, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.INSUFFICIENT_EVIDENCE, result.reason)
        assertEquals(setOf(UserMotionStateReason.INSUFFICIENT_EVIDENCE.name), reasonNames(result))
        assertEquals(UserMotionEvidenceSource.GNSS_FIX, result.evidenceRefs.single().source)
        assertNull(result.stepObservedAtElapsedRealtimeMs)
        assertNull(result.expiresAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    private fun conflictingInputs() = PositioningCoordinator().also { coordinator ->
        stationaryGnss(coordinator, 1_000L, 1_100L)
        step(coordinator, 1_100L, 1_200L)
    }

    private fun stationaryGnss(coordinator: PositioningCoordinator, measuredAtMs: Long,
        receivedAtMs: Long, isMock: Boolean = false) {
        coordinator.observeUserSpeed(
            WalkingSpeedObservation(0.0, 0.1, measuredAtMs),
            UserMotionLocationEvidence(measuredAtMs, 2.0, isMock, 11L),
            receivedAtMs,
        )
    }

    private fun step(coordinator: PositioningCoordinator, measuredAtMs: Long, receivedAtMs: Long,
        quality: PdrStepQuality = PdrStepQuality.HIGH) {
        coordinator.observeStep(1, PedestrianHeadingInput(
            nowElapsedRealtimeMs = measuredAtMs, speed = null, gpsCourse = null,
            magneticTrueHeading = null, phoneForwardMounted = false,
        ), quality, receivedAtElapsedRealtimeMs = receivedAtMs)
    }

    private fun assertStepMovement(result: UserMotionStateSnapshot, expiresAtMs: Long) {
        assertEquals(UserMovementState.MOVING, result.movement)
        assertEquals(UserLocomotionState.UNKNOWN, result.locomotion)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, result.reason)
        assertEquals(expiresAtMs, result.expiresAtElapsedRealtimeMs)
        assertFalse(result.zeroVelocityUpdateAllowed)
    }

    private fun reasonNames(result: UserMotionStateSnapshot) = result.reasonCodes.map { it.name }.toSet()

    private companion object {
        const val CONFLICT_REASON = "STEP_WITH_STATIONARY_GNSS"
    }
}
