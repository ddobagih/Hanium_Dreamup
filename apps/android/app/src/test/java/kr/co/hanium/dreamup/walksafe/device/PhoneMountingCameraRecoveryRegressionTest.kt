package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMountingCameraRecoveryRegressionTest {
    @Test
    fun pureCameraQualityFaultRecoversFromANewFrameWithTheSameOriginalMountingConfirmation() {
        recoverableReasons.forEach { reason ->
            val initial = assess(NOW_MS + 1L, camera(NOW_MS + 1L), initialState())
            assertTrue(initial.canStartOrResumeDetection)
            val fault = assess(NOW_MS + 2L, camera(NOW_MS + 2L, reason), initial.nextState)
            assertFalse(fault.canStartOrResumeDetection)
            assertFalse(fault.requiresSafetyStop)
            assertTrue(fault.spokenGuidanceKo.contains("자동"))
            assertFalse(fault.spokenGuidanceKo.contains("화면"))
            val restored = assess(NOW_MS + 3L, camera(NOW_MS + 3L), fault.nextState)

            assertEquals(reason.name, PhoneMountingStatus.SUITABLE, restored.status)
            assertTrue(restored.canStartOrResumeDetection)
            assertNull(restored.nextState.correctionRequiredSinceElapsedRealtimeMs)
            assertEquals(0, restored.nextState.failedRuntimeRetryAttempts)
            assertEquals(NOW_MS + 3L, restored.readinessEvidence.single { it.source == "phone_mounting:camera" }
                .observedAtElapsedRealtimeMs)
            assertEquals(NOW_MS, restored.readinessEvidence.single { it.source == "phone_mounting:user_confirmation" }
                .observedAtElapsedRealtimeMs)
        }
    }

    @Test
    fun cameraOnlyFaultCannotExhaustPhysicalMountingRetriesAndLatchAnAutomaticRecoveryStop() {
        var state = initialState()
        repeat(10) { index ->
            val time = NOW_MS + index + 1L
            val assessment = assess(time, camera(time, CameraFrameQualityReason.FRAME_UNAVAILABLE), state, retry = true)
            assertFalse(assessment.requiresSafetyStop)
            assertFalse(assessment.nextState.safetyStopRequired)
            state = assessment.nextState
        }
        val restored = assess(NOW_MS + 11L, camera(NOW_MS + 11L), state)
        assertTrue(restored.canStartOrResumeDetection)
    }

    @Test
    fun cameraOnlyFaultAlsoRecoversUsingTheOriginalSensorCheckRequest() {
        val checkRequest = PhoneMountingCheckRequest(EPOCH, 1L, NOW_MS)
        val fault = assess(
            NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE),
            initialState(), checkRequest = checkRequest,
        )
        val restored = assess(NOW_MS + 2L, camera(NOW_MS + 2L), fault.nextState, checkRequest = checkRequest)
        assertTrue(restored.canStartOrResumeDetection)
    }

    @Test
    fun firstExpirationOfANormalFrameRecoversOnlyWhenANewActualFramePasses() {
        val priorPass = camera(NOW_MS + 1L)
        val suitable = assess(NOW_MS + 1L, priorPass, initialState())
        assertTrue(suitable.canStartOrResumeDetection)
        val expiredAt = NOW_MS + 5_002L
        val expired = assess(expiredAt, priorPass, suitable.nextState)

        assertFalse(expired.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.CAMERA_EVIDENCE_STALE, expired.reason)
        assertEquals(NOW_MS + 1L, priorPass.observedAtElapsedRealtimeMs)
        val restored = assess(expiredAt + 1L, camera(expiredAt + 1L), expired.nextState)

        assertTrue(restored.canStartOrResumeDetection)
        assertNull(restored.nextState.correctionRequiredSinceElapsedRealtimeMs)
        assertEquals(NOW_MS, restored.readinessEvidence.single { it.source == "phone_mounting:user_confirmation" }
            .observedAtElapsedRealtimeMs)
        assertEquals(expiredAt + 1L, restored.readinessEvidence.single { it.source == "phone_mounting:camera" }
            .observedAtElapsedRealtimeMs)
    }

    @Test
    fun repeatedExpirationChecksCannotExhaustPhysicalRetriesOrRestoreTheExpiredFrame() {
        val priorPass = camera(NOW_MS + 1L)
        var state = assess(NOW_MS + 1L, priorPass, initialState()).nextState
        val checkRequest = PhoneMountingCheckRequest(EPOCH, 1L, NOW_MS)
        repeat(4) { index ->
            val checkedAt = NOW_MS + 5_002L + index * 3_000L
            val expired = assess(checkedAt, priorPass, state, retry = true, checkRequest = checkRequest)
            assertFalse(expired.canStartOrResumeDetection)
            assertFalse(expired.requiresSafetyStop)
            assertEquals(0, expired.nextState.failedRuntimeRetryAttempts)
            assertEquals(NOW_MS + 1L, priorPass.observedAtElapsedRealtimeMs)
            state = expired.nextState
        }
        val restoredAt = NOW_MS + 14_003L
        val restored = assess(restoredAt, camera(restoredAt), state, checkRequest = checkRequest)
        assertTrue(restored.canStartOrResumeDetection)
    }

    @Test
    fun expirationOfAnEarlierNormalFrameCannotClearAnActualMountAngleFault() {
        val priorPass = camera(NOW_MS + 1L)
        val suitable = assess(NOW_MS + 1L, priorPass, initialState())
        val physical = assess(
            NOW_MS + 2L, camera(NOW_MS + 2L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE),
            suitable.nextState,
        )
        val expiredAt = NOW_MS + 5_002L
        val expired = assess(expiredAt, priorPass, physical.nextState)
        assertFalse(expired.canStartOrResumeDetection)
        assertTrue(expired.nextState.requiresExplicitCorrection)
        val unconfirmed = assess(expiredAt + 1L, camera(expiredAt + 1L), expired.nextState)
        assertFalse(unconfirmed.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED, unconfirmed.reason)
    }

    @Test
    fun expirationOfTheSameRejectedCameraFrameKeepsTheSoftRestrictionUntilANewActualPass() {
        val missingFrame = camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE)
        val fault = assess(NOW_MS + 1L, missingFrame, initialState())
        val expiredAt = NOW_MS + 5_002L
        val expired = assess(expiredAt, missingFrame, fault.nextState)
        assertFalse(expired.canStartOrResumeDetection)
        assertFalse(expired.requiresSafetyStop)
        val restored = assess(expiredAt + 1L, camera(expiredAt + 1L), expired.nextState)
        assertTrue(restored.canStartOrResumeDetection)
    }

    @Test
    fun expirationOfAKnownPriorPassKeepsAnExistingCameraOnlyFaultRecoverable() {
        val priorPass = camera(NOW_MS + 1L)
        val suitable = assess(NOW_MS + 1L, priorPass, initialState())
        assertTrue(suitable.canStartOrResumeDetection)
        val fault = assess(
            NOW_MS + 2L, camera(NOW_MS + 2L, CameraFrameQualityReason.FRAME_UNAVAILABLE), suitable.nextState,
        )
        val expiredReceiptAt = NOW_MS + 5_002L
        val expired = assess(expiredReceiptAt, priorPass, fault.nextState)
        assertFalse(expired.canStartOrResumeDetection)
        assertFalse(expired.nextState.requiresExplicitCorrection)
        assertEquals(NOW_MS + 1L, priorPass.observedAtElapsedRealtimeMs)
        assertEquals(expiredReceiptAt, expired.nextState.correctionRequiredSinceElapsedRealtimeMs)
        val restored = assess(expiredReceiptAt + 1L, camera(expiredReceiptAt + 1L), expired.nextState)
        assertTrue(restored.canStartOrResumeDetection)
        assertEquals(expiredReceiptAt + 1L, restored.readinessEvidence.single { it.source == "phone_mounting:camera" }
            .observedAtElapsedRealtimeMs)
    }

    @Test
    fun cameraRecoveryRequiresEvidenceStrictlyAfterTheLatestRejectedFault() {
        val first = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), initialState())
        val later = assess(NOW_MS + 3L, camera(NOW_MS + 3L, CameraFrameQualityReason.FRAME_UNAVAILABLE), first.nextState)
        assertEquals(NOW_MS + 3L, later.nextState.correctionRequiredSinceElapsedRealtimeMs)
        val delayed = assess(NOW_MS + 3L, camera(NOW_MS + 2L), later.nextState)
        val sameTimestamp = assess(NOW_MS + 3L, camera(NOW_MS + 3L), later.nextState)
        assertFalse(delayed.canStartOrResumeDetection)
        assertFalse(sameTimestamp.canStartOrResumeDetection)
        val restored = assess(NOW_MS + 4L, camera(NOW_MS + 4L), later.nextState)
        assertTrue(restored.canStartOrResumeDetection)
    }

    @Test
    fun recheckingAPreFaultPassingFrameCannotExhaustTheSoftCameraRecoveryBudget() {
        val fault = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), initialState())
        var state = fault.nextState
        repeat(10) { index ->
            val waiting = assess(NOW_MS + index + 2L, camera(NOW_MS), state, retry = true)
            assertFalse(waiting.canStartOrResumeDetection)
            assertFalse(waiting.requiresSafetyStop)
            state = waiting.nextState
        }
        assertTrue(assess(NOW_MS + 12L, camera(NOW_MS + 12L), state).canStartOrResumeDetection)
    }

    @Test
    fun realMountAngleFaultRetainsManualCorrectionEvenIfACameraOnlyFaultFollowsIt() {
        val physical = assess(
            NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE),
            initialState(),
        )
        assertTrue(physical.spokenGuidanceKo.contains("장착 교정"))
        val cameraOnly = assess(
            NOW_MS + 2L, camera(NOW_MS + 2L, CameraFrameQualityReason.FRAME_UNAVAILABLE), physical.nextState,
        )
        val automatic = assess(NOW_MS + 3L, camera(NOW_MS + 3L), cameraOnly.nextState)
        assertFalse(automatic.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED, automatic.reason)
        val corrected = assess(
            NOW_MS + 4L, camera(NOW_MS + 4L), automatic.nextState,
            confirmation = CONFIRMATION.copy(confirmedAtElapsedRealtimeMs = NOW_MS + 3L, postFaultCorrectionConfirmed = true),
        )
        assertTrue(corrected.canStartOrResumeDetection)
    }

    @Test
    fun actualMountAngleFaultAfterACameraOnlyFaultRequiresExplicitCorrectionBeforeAFreshPass() {
        val soft = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), initialState())
        assertFalse(soft.nextState.requiresExplicitCorrection)
        val physical = assess(
            NOW_MS + 2L, camera(NOW_MS + 2L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE),
            soft.nextState,
        )
        assertTrue(physical.nextState.requiresExplicitCorrection)
        assertEquals(NOW_MS + 2L, physical.nextState.correctionRequiredSinceElapsedRealtimeMs)
        val unconfirmed = assess(NOW_MS + 3L, camera(NOW_MS + 3L), physical.nextState)
        assertFalse(unconfirmed.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED, unconfirmed.reason)
        assertTrue(assess(
            NOW_MS + 4L, camera(NOW_MS + 4L), unconfirmed.nextState,
            confirmation = CONFIRMATION.copy(confirmedAtElapsedRealtimeMs = NOW_MS + 3L, postFaultCorrectionConfirmed = true),
        ).canStartOrResumeDetection)
    }

    @Test
    fun aPhysicalRetryLimitAfterACameraOnlyFaultCannotRetainSoftRecoveryMetadata() {
        val oneRetryState = PhoneMountingPolicy.initialState(EPOCH, PROFILE.copy(maximumRuntimeRetryAttempts = 1))
        val soft = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), oneRetryState)
        assertFalse(soft.nextState.requiresExplicitCorrection)
        val physicalStop = assess(
            NOW_MS + 2L, camera(NOW_MS + 2L, CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE),
            soft.nextState, retry = true,
        )
        assertTrue(physicalStop.requiresSafetyStop)
        assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, physicalStop.reason)
        assertTrue(physicalStop.nextState.requiresExplicitCorrection)
        assertTrue(physicalStop.nextState.safetyStopRequired)
        val latePass = assess(NOW_MS + 3L, camera(NOW_MS + 3L), physicalStop.nextState)
        assertFalse(latePass.canStartOrResumeDetection)
        assertTrue(latePass.nextState.requiresExplicitCorrection)
    }

    @Test
    fun anExpiredPhysicalAngleAssessmentCannotBecomeASoftCameraRecoveryFault() {
        assertDelayedHardCameraAssessment(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)
    }

    @Test
    fun anExpiredInvalidMeasurementCannotBecomeASoftCameraRecoveryFault() {
        assertDelayedHardCameraAssessment(CameraFrameQualityReason.INVALID_MEASUREMENT)
    }

    private fun assertDelayedHardCameraAssessment(reason: CameraFrameQualityReason) {
        val soft = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), initialState())
        assertFalse(soft.nextState.requiresExplicitCorrection)
        val originalHardAssessment = camera(NOW_MS + 2L, reason)
        val delayedReceiptAt = NOW_MS + 5_003L
        val delayedHard = assess(delayedReceiptAt, originalHardAssessment, soft.nextState)
        assertFalse(delayedHard.canStartOrResumeDetection)
        assertTrue("${reason.name} cannot become a soft fault when its original measurement expires",
            delayedHard.nextState.requiresExplicitCorrection)
        assertEquals(delayedReceiptAt, delayedHard.nextState.correctionRequiredSinceElapsedRealtimeMs)
        val freshPass = assess(delayedReceiptAt + 1L, camera(delayedReceiptAt + 1L), delayedHard.nextState)
        assertFalse(freshPass.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED, freshPass.reason)
    }

    @Test
    fun invalidEvidenceAndProhibitedMountingNeverUseCameraOnlyAutomaticRecovery() {
        listOf(
            camera(NOW_MS + 1L, CameraFrameQualityReason.INVALID_MEASUREMENT),
            camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_STATE_UNKNOWN),
            camera(NOW_MS + 2L),
        ).forEach { invalid ->
            val fault = assess(NOW_MS + 1L, invalid, initialState())
            assertFalse(fault.canStartOrResumeDetection)
            val fresh = assess(NOW_MS + 3L, camera(NOW_MS + 3L), fault.nextState)
            assertFalse(fresh.canStartOrResumeDetection)
        }
        val pocket = assess(
            NOW_MS + 1L, camera(NOW_MS + 1L), initialState(),
            confirmation = CONFIRMATION.copy(method = PhoneMountingMethod.POCKET),
        )
        val unconfirmed = assess(NOW_MS + 2L, camera(NOW_MS + 2L), pocket.nextState)
        assertFalse(unconfirmed.canStartOrResumeDetection)
    }

    @Test
    fun wrongOwnerProfileAndStalePassRemainBlockedAfterACameraOnlyFault() {
        val fault = assess(NOW_MS + 1L, camera(NOW_MS + 1L, CameraFrameQualityReason.FRAME_UNAVAILABLE), initialState())
        listOf(
            camera(NOW_MS + 2L).copy(epoch = WalkRuntimeEpoch("other-walk", 1L)),
            camera(NOW_MS + 2L).copy(profileId = "other-camera"),
            camera(NOW_MS - 5_001L),
        ).forEach { invalid ->
            assertFalse(assess(NOW_MS + 2L, invalid, fault.nextState).canStartOrResumeDetection)
        }
    }

    private fun assess(
        time: Long,
        camera: CameraFrameQualityAssessment,
        state: PhoneMountingRuntimeState,
        retry: Boolean = false,
        confirmation: PhoneMountingUserConfirmation = CONFIRMATION,
        checkRequest: PhoneMountingCheckRequest? = null,
    ) = PhoneMountingPolicy.assess(
        phase = PhoneMountingAssessmentPhase.ACTIVE,
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = time,
        userConfirmation = confirmation,
        cameraFrameQuality = camera,
        previousState = state,
        runtimeRetryRequested = retry,
        checkRequest = checkRequest,
    )

    private fun initialState() = PhoneMountingPolicy.initialState(EPOCH, PROFILE)

    private fun camera(time: Long, reason: CameraFrameQualityReason = CameraFrameQualityReason.PASSED) =
        CameraFrameQualityAssessment(
            epoch = EPOCH,
            observedAtElapsedRealtimeMs = time,
            profileId = CAMERA_PROFILE,
            maximumEvidenceAgeMs = 5_000L,
            status = when (reason) {
                CameraFrameQualityReason.PASSED -> EnvironmentEvidenceStatus.PASS
                CameraFrameQualityReason.FRAME_UNAVAILABLE,
                CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
                CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
                CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE -> EnvironmentEvidenceStatus.FAIL
                else -> EnvironmentEvidenceStatus.UNKNOWN
            },
            reason = reason,
        )

    private companion object {
        const val NOW_MS = 10_000L
        const val CAMERA_PROFILE = "camera-field-approved-r001"
        val EPOCH = WalkRuntimeEpoch("stationary-walk", 1L)
        val PROFILE = ApprovedPhoneMountingProfile("mounting-field-approved-r001", CAMERA_PROFILE, 5_000L, 2, 60_000L)
        val CONFIRMATION = PhoneMountingUserConfirmation(EPOCH, NOW_MS, PhoneMountingMethod.CHEST_FORWARD, false)
        val recoverableReasons = listOf(
            CameraFrameQualityReason.FRAME_UNAVAILABLE,
            CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
        )
    }
}
