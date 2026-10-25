package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual camera policy -> same-source runtime gate -> mounting latch, with raw FAIL retained. */
class PhoneMountingBoundaryRecheckTest {
    private val epoch = WalkRuntimeEpoch("mount-boundary", 1L)
    private val cameraProfile = ApprovedCameraFrameQualityProfile("camera", .2, .1, 45.0, -45.0, 45.0, 5_000L)
    private val mountProfile = ApprovedPhoneMountingProfile("mount", "camera", 5_000L, 2, 60_000L)
    private val confirmation = PhoneMountingUserConfirmation(epoch, 900L, PhoneMountingMethod.CHEST_FORWARD, false)

    @Test fun coldFortySixDegreesCannotBorrowAnAbsentPassingHistory() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        val raw = camera(1_000L, 46.0)
        assertEquals(EnvironmentEvidenceStatus.FAIL, raw.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY, raw.reason)
        val selected = gate.observe(raw.asMeasuredEnvironmentEvidence(), 1_000L)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
        assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        val fault = mount(raw.copy(reason = CameraFrameQualityReason.valueOf(selected.detail)), initial())
        assertTrue(fault.nextState.requiresExplicitCorrection)
        assertFalse(mount(camera(1_100L), fault.nextState).canStartOrResumeDetection)
    }

    @Test fun aBriefSmallBoundaryExcessKeepsTheFrameFailedAndRecoversWithoutManualCorrection() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        val passing = camera(1_000L)
        val suitable = mount(passing, initial())
        assertTrue(suitable.canStartOrResumeDetection)
        gate.observe(passing.asMeasuredEnvironmentEvidence(), 1_000L)
        val raw = camera(1_100L, 46.0)
        val selected = gate.observe(raw.asMeasuredEnvironmentEvidence(), 1_100L)
        assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
        assertEquals(1_100L, selected.observedAtElapsedRealtimeMs)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name, selected.detail)
        assertEquals(raw.asMeasuredEnvironmentEvidence(), gate.latestRawObservation())
        val pending = mount(raw.copy(reason = CameraFrameQualityReason.valueOf(selected.detail)), suitable.nextState)
        assertFalse("a currently failed frame cannot start current camera risk output", pending.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.CAMERA_ANGLE_RECHECK, pending.reason)
        assertFalse(pending.nextState.requiresExplicitCorrection)
        val recovered = camera(1_300L)
        assertEquals(EnvironmentEvidenceStatus.PASS, gate.observe(recovered.asMeasuredEnvironmentEvidence(), 1_300L).status)
        assertTrue(mount(recovered, pending.nextState).canStartOrResumeDetection)
    }

    @Test fun rawNearBoundaryReasonAloneDoesNotAuthorizeAutomaticMountingRecovery() {
        val suitable = mount(camera(1_000L), initial())
        val raw = camera(1_100L, 46.0)
        val fault = mount(raw, suitable.nextState)
        assertTrue(fault.nextState.requiresExplicitCorrection)
        assertFalse(mount(camera(1_200L), fault.nextState).canStartOrResumeDetection)
    }

    @Test fun repeatedNearBoundaryFramesUseOneOriginalDeadlineThenBecomeHardWithoutRestart() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
        var state = mount(camera(1_000L), initial()).nextState
        for (now in 1_100L..5_500L step 100L) {
            val raw = camera(now, 46.0)
            val selected = gate.observe(raw.asMeasuredEnvironmentEvidence(), now)
            assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name, selected.detail)
            assertEquals(5_600L, gate.pendingFailureDeadlineElapsedRealtimeMs())
            val pending = mount(raw.copy(reason = CameraFrameQualityReason.valueOf(selected.detail)), state)
            assertFalse(pending.nextState.requiresExplicitCorrection)
            state = pending.nextState
        }
        for (now in listOf(5_600L, 5_700L, 5_800L)) {
            val raw = camera(now, 46.0)
            val selected = gate.observe(raw.asMeasuredEnvironmentEvidence(), now)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
            val hard = mount(raw.copy(reason = CameraFrameQualityReason.valueOf(selected.detail)), state)
            assertTrue(hard.nextState.requiresExplicitCorrection)
            state = hard.nextState
        }
        assertFalse("sustained angle failure retains the explicit correction requirement", mount(camera(5_900L), state).canStartOrResumeDetection)
    }

    @Test fun aNinetyDegreeDeskPoseAndMoreThanFiveDegreesExcessAreImmediatelyHard() {
        for (pitch in listOf(-90.0, 90.0, -50.01, 50.01)) {
            val gate = RuntimeMeasuredQualityGate(4_500L)
            gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
            val raw = camera(1_100L, pitch)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE, raw.reason)
            assertEquals(raw.asMeasuredEnvironmentEvidence(), gate.observe(raw.asMeasuredEnvironmentEvidence(), 1_100L))
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
            assertTrue(mount(raw, initial()).nextState.requiresExplicitCorrection)
        }
    }

    @Test fun positiveAndNegativeNearBoundaryEdgesRemainFailedWhileRechecking() {
        for (pitch in listOf(-50.0, -45.01, 45.01, 50.0)) {
            val gate = RuntimeMeasuredQualityGate(4_500L)
            gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
            val selected = gate.observe(camera(1_100L, pitch).asMeasuredEnvironmentEvidence(), 1_100L)
            assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name, selected.detail)
        }
    }

    @Test fun expiredPassingHistoryCannotQualifyABoundaryRecheck() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
        val selected = gate.observe(camera(6_001L, 46.0).asMeasuredEnvironmentEvidence(), 6_001L)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun epochAndProfileReplacementCannotBorrowThePreviousOwnerPass() {
        val raw = camera(1_100L, 46.0).asMeasuredEnvironmentEvidence()
        for (changed in listOf(raw.copy(epoch = WalkRuntimeEpoch("new-walk", 1L)),
            raw.copy(epoch = epoch.copy(recoveryGeneration = 2L)), raw.copy(measurementProfileId = "new-camera"))) {
            val gate = RuntimeMeasuredQualityGate(4_500L)
            gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, gate.observe(changed, 1_100L).detail)
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }

    @Test fun aPermissionOrUntrustedFrameFailureRetiresTheHistoryBeforeTheNextBoundarySample() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        val pass = camera(1_000L).asMeasuredEnvironmentEvidence()
        gate.observe(pass, 1_000L)
        val permissionLost = pass.copy(observedAtElapsedRealtimeMs = 1_050L,
            status = EnvironmentEvidenceStatus.UNKNOWN, detail = "CAMERA_PERMISSION_MISSING")
        assertEquals(permissionLost, gate.observe(permissionLost, 1_050L))
        val selected = gate.observe(camera(1_100L, 46.0).asMeasuredEnvironmentEvidence(), 1_100L)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun timestampInvalidityAndAConflictAtThePassingTimestampRemainHard() {
        for (rawAt in listOf(1_000L, 1_101L)) {
            val gate = RuntimeMeasuredQualityGate(4_500L)
            val pass = camera(1_000L).asMeasuredEnvironmentEvidence()
            gate.observe(pass, 1_000L)
            val raw = pass.copy(observedAtElapsedRealtimeMs = rawAt, status = EnvironmentEvidenceStatus.FAIL,
                detail = CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY.name)
            val selected = gate.observe(raw, 1_100L)
            assertEquals(EnvironmentEvidenceStatus.FAIL, selected.status)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, selected.detail)
            assertEquals(rawAt, selected.observedAtElapsedRealtimeMs)
            assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        }
    }

    @Test fun aPendingMarkerSeededIntoAColdGateIsRequalifiedAndCannotActAsItsOwnPass() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        val marker = camera(1_100L, 46.0).asMeasuredEnvironmentEvidence().copy(
            detail = CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name)
        val cold = gate.observe(marker, 1_100L)
        assertEquals(EnvironmentEvidenceStatus.FAIL, cold.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, cold.detail)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
        gate.reset()
        val mislabeled = gate.observe(marker.copy(status = EnvironmentEvidenceStatus.PASS), 1_100L)
        assertEquals(EnvironmentEvidenceStatus.FAIL, mislabeled.status)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, mislabeled.detail)
    }

    @Test fun refeedingAnAlreadyNormalizedAssessmentCannotRestartTheBoundaryDeadline() {
        val gate = RuntimeMeasuredQualityGate(4_500L)
        gate.observe(camera(1_000L).asMeasuredEnvironmentEvidence(), 1_000L)
        val pending = gate.observe(camera(1_100L, 46.0).asMeasuredEnvironmentEvidence(), 1_100L)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name, pending.detail)
        val repeated = gate.observe(pending.copy(observedAtElapsedRealtimeMs = 2_000L), 2_000L)
        assertEquals(EnvironmentEvidenceStatus.FAIL, repeated.status)
        assertEquals(5_600L, gate.pendingFailureDeadlineElapsedRealtimeMs())
        val hard = gate.observe(pending.copy(observedAtElapsedRealtimeMs = 5_600L), 5_600L)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name, hard.detail)
        assertNull(gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    private fun camera(at: Long, pitch: Double = 44.0) = CameraFrameQualityPolicy.assess(epoch, at,
        CameraFrameQualityObservation(epoch, at, true, .6, 0.0, 0.0, pitch), cameraProfile)
    private fun initial() = PhoneMountingPolicy.initialState(epoch, mountProfile)
    private fun mount(camera: CameraFrameQualityAssessment, state: PhoneMountingRuntimeState) = PhoneMountingPolicy.assess(
        PhoneMountingAssessmentPhase.ACTIVE, epoch, requireNotNull(camera.observedAtElapsedRealtimeMs), confirmation, camera, state)
}
