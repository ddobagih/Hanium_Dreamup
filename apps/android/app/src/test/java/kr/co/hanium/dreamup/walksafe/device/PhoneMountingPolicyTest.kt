package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMountingPolicyTest {
    @Test
    fun productionHasNoUnapprovedMountingProfile() {
        assertNull(PhoneMountingPolicy.productionProfile)
    }

    @Test
    fun onlyChestAndNecklaceForwardMountsAreCandidates() {
        listOf(
            PhoneMountingMethod.CHEST_FORWARD,
            PhoneMountingMethod.NECKLACE_FORWARD,
        ).forEach { method ->
            val assessment = assess(
                confirmation = confirmation(method = method),
                camera = camera(),
            )

            assertEquals(PhoneMountingStatus.SUITABLE, assessment.status)
            assertTrue(assessment.canStartOrResumeDetection)
            assertFalse(assessment.mustSuppressDetectionOutput)
        }

        listOf(
            PhoneMountingMethod.HANDHELD,
            PhoneMountingMethod.POCKET,
            PhoneMountingMethod.UNKNOWN,
        ).forEach { method ->
            val assessment = assess(
                confirmation = confirmation(method = method),
                camera = camera(),
            )

            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
            assertEquals(PhoneMountingReason.PROHIBITED_MOUNTING_METHOD, assessment.reason)
            assertFalse(assessment.canStartOrResumeDetection)
            assertTrue(assessment.mustSuppressDetectionOutput)
        }
    }

    @Test
    fun userConfirmationDoesNotReplaceAnApprovedProfile() {
        val assessment = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = NOW_MS,
            userConfirmation = confirmation(),
            cameraFrameQuality = camera(),
            previousState = PhoneMountingPolicy.initialState(EPOCH, approvedProfile = null),
        )

        assertEquals(PhoneMountingStatus.UNUSABLE, assessment.status)
        assertEquals(PhoneMountingReason.PROFILE_NOT_APPROVED, assessment.reason)
        assertFalse(assessment.retryAllowed)
        assertTrue(assessment.requiresSafetyStop)
    }

    @Test
    fun preflightFailureBlocksStartWithoutEnteringRuntimeCorrectionOrSafetyStop() {
        val oneRetryProfile = PROFILE.copy(maximumRuntimeRetryAttempts = 1)
        val assessment = assess(
            nowMs = NOW_MS + 1L,
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            confirmation = confirmation(),
            camera = camera(
                observedAtMs = NOW_MS + 1L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = PhoneMountingPolicy.initialState(EPOCH, oneRetryProfile),
        )

        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
        assertEquals(PhoneMountingReason.CAMERA_QUALITY_FAILED, assessment.reason)
        assertFalse(assessment.requiresSafetyStop)
        assertNull(assessment.nextState.correctionRequiredSinceElapsedRealtimeMs)
        assertEquals(0, assessment.nextState.failedRuntimeRetryAttempts)
        assertFalse(assessment.nextState.safetyStopRequired)
    }

    @Test
    fun approvedProfileRequiresAtLeastOneRuntimeRetry() {
        assertThrows(IllegalArgumentException::class.java) {
            PROFILE.copy(maximumRuntimeRetryAttempts = 0)
        }
    }

    @Test
    fun preflightCannotConsumeARuntimeRetry() {
        assertThrows(IllegalArgumentException::class.java) {
            assess(
                phase = PhoneMountingAssessmentPhase.PREFLIGHT,
                confirmation = confirmation(),
                camera = camera(status = EnvironmentEvidenceStatus.FAIL),
                runtimeRetryRequested = true,
            )
        }
    }

    @Test
    fun missingStaleAndWrongEpochConfirmationFailClosed() {
        val cases = listOf(
            null to PhoneMountingReason.USER_CONFIRMATION_MISSING,
            confirmation(epoch = OTHER_EPOCH) to
                PhoneMountingReason.USER_CONFIRMATION_EPOCH_MISMATCH,
            confirmation(confirmedAtMs = NOW_MS - MAX_AGE_MS - 1L) to
                PhoneMountingReason.USER_CONFIRMATION_STALE,
            confirmation(confirmedAtMs = NOW_MS + 1L) to
                PhoneMountingReason.USER_CONFIRMATION_STALE,
        )

        cases.forEach { (confirmation, reason) ->
            val assessment = assess(confirmation = confirmation, camera = camera())

            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
            assertEquals(reason, assessment.reason)
        }
    }

    @Test
    fun cameraAssessmentMustBeFreshAndMatchEpochAndApprovedProfile() {
        val cases = listOf(
            null to PhoneMountingReason.CAMERA_EVIDENCE_MISSING,
            camera(epoch = OTHER_EPOCH) to PhoneMountingReason.CAMERA_EPOCH_MISMATCH,
            camera(profileId = "other-camera-profile") to
                PhoneMountingReason.CAMERA_PROFILE_MISMATCH,
            camera(observedAtMs = NOW_MS - MAX_AGE_MS - 1L) to
                PhoneMountingReason.CAMERA_EVIDENCE_STALE,
            camera(observedAtMs = NOW_MS + 1L) to
                PhoneMountingReason.CAMERA_EVIDENCE_STALE,
        )

        cases.forEach { (camera, reason) ->
            val assessment = assess(confirmation = confirmation(), camera = camera)

            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
            assertEquals(reason, assessment.reason)
        }
    }

    @Test
    fun preflightRequiresACameraFrameStrictlyAfterMountingConfirmation() {
        listOf(NOW_MS - 1L, NOW_MS).forEach { cameraObservedAtMs ->
            val blocked = assess(
                nowMs = NOW_MS + 1L,
                phase = PhoneMountingAssessmentPhase.PREFLIGHT,
                confirmation = confirmation(confirmedAtMs = NOW_MS),
                camera = camera(observedAtMs = cameraObservedAtMs),
            )

            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, blocked.status)
            assertEquals(
                PhoneMountingReason.POST_CONFIRMATION_CAMERA_EVIDENCE_REQUIRED,
                blocked.reason,
            )
        }
        val ready = assess(
            nowMs = NOW_MS + 1L,
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            confirmation = confirmation(confirmedAtMs = NOW_MS),
            camera = camera(observedAtMs = NOW_MS + 1L),
        )

        assertEquals(PhoneMountingStatus.SUITABLE, ready.status)
    }

    @Test
    fun cameraQualityAssessmentIsConsumedWithoutDuplicatingItsThresholds() {
        val failed = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val unknown = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.UNKNOWN),
        )

        assertEquals(PhoneMountingReason.CAMERA_QUALITY_FAILED, failed.reason)
        assertEquals(PhoneMountingReason.CAMERA_QUALITY_UNKNOWN, unknown.reason)
        assertTrue(failed.retryAllowed)
        assertTrue(unknown.retryAllowed)
    }

    @Test
    fun inconsistentPassingCameraAssessmentFailsClosed() {
        val inconsistent = camera().copy(
            reason = CameraFrameQualityReason.MISSING_OBSERVATION,
        )

        val assessment = assess(
            confirmation = confirmation(),
            camera = inconsistent,
        )

        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
        assertEquals(PhoneMountingReason.CAMERA_QUALITY_UNKNOWN, assessment.reason)
    }

    @Test
    fun runtimeStateFromAnotherEpochCannotBeReused() {
        val assessment = assess(
            confirmation = confirmation(),
            camera = camera(),
            previousState = PhoneMountingPolicy.initialState(OTHER_EPOCH, PROFILE),
        )

        assertEquals(PhoneMountingStatus.UNUSABLE, assessment.status)
        assertEquals(PhoneMountingReason.STATE_EPOCH_MISMATCH, assessment.reason)
        assertTrue(assessment.nextState.safetyStopRequired)
    }

    @Test
    fun passingEvidenceAfterAQualityFaultDoesNotResumeAutomatically() {
        val fault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val automaticResume = assess(
            nowMs = NOW_MS + 1L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 1L),
            camera = camera(observedAtMs = NOW_MS + 1L),
            previousState = fault.nextState,
        )
        val oldCameraAfterConfirmation = assess(
            nowMs = NOW_MS + 1L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 1L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS),
            previousState = fault.nextState,
        )
        val explicitRecovery = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 1L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS + 2L),
            previousState = fault.nextState,
        )

        assertEquals(
            PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED,
            automaticResume.reason,
        )
        assertEquals(
            PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
            oldCameraAfterConfirmation.reason,
        )
        assertEquals(PhoneMountingStatus.SUITABLE, explicitRecovery.status)
        assertTrue(explicitRecovery.canStartOrResumeDetection)
    }

    @Test
    fun postFaultCameraEvidenceMustFollowTheExplicitCorrectionConfirmation() {
        val fault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val confirmationAfterCamera = confirmation(
            confirmedAtMs = NOW_MS + 2L,
            postFaultCorrectionConfirmed = true,
        )
        val preConfirmationCamera = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmationAfterCamera,
            camera = camera(observedAtMs = NOW_MS + 1L),
            previousState = fault.nextState,
        )
        val postConfirmationCamera = assess(
            nowMs = NOW_MS + 3L,
            confirmation = confirmationAfterCamera,
            camera = camera(observedAtMs = NOW_MS + 3L),
            previousState = preConfirmationCamera.nextState,
        )

        assertEquals(
            PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
            preConfirmationCamera.reason,
        )
        assertFalse(preConfirmationCamera.canStartOrResumeDetection)
        assertEquals(PhoneMountingStatus.SUITABLE, postConfirmationCamera.status)
    }

    @Test
    fun cameraEvidenceAtTheExactCorrectionConfirmationTimeIsNotPostConfirmationEvidence() {
        val fault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val confirmedAtMs = NOW_MS + 1L
        val assessment = assess(
            nowMs = confirmedAtMs,
            confirmation = confirmation(
                confirmedAtMs = confirmedAtMs,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = confirmedAtMs),
            previousState = fault.nextState,
        )

        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
        assertEquals(
            PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
            assessment.reason,
        )
    }

    @Test
    fun approvedProfileBoundsFailedRuntimeRetriesAndLatchesSafetyStop() {
        val initialFault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val firstFailedRetry = assess(
            nowMs = NOW_MS + 1L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 1L),
            camera = camera(
                observedAtMs = NOW_MS + 1L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = initialFault.nextState,
            runtimeRetryRequested = true,
        )
        val secondFailedRetry = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 2L),
            camera = camera(
                observedAtMs = NOW_MS + 2L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = firstFailedRetry.nextState,
            runtimeRetryRequested = true,
        )
        val latePassingEvidence = assess(
            nowMs = NOW_MS + 3L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 3L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS + 3L),
            previousState = secondFailedRetry.nextState,
        )

        assertEquals(1, firstFailedRetry.nextState.failedRuntimeRetryAttempts)
        assertTrue(firstFailedRetry.retryAllowed)
        assertEquals(PhoneMountingStatus.UNUSABLE, secondFailedRetry.status)
        assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, secondFailedRetry.reason)
        assertTrue(secondFailedRetry.requiresSafetyStop)
        assertEquals(PhoneMountingReason.SAFETY_STOP_LATCHED, latePassingEvidence.reason)
        assertFalse(latePassingEvidence.canStartOrResumeDetection)
    }

    @Test
    fun oneRetryProfileConsumesItsBudgetOnlyWhenThePostConfirmationFrameIsAssessed() {
        val oneRetryProfile = PROFILE.copy(maximumRuntimeRetryAttempts = 1)
        val fault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
            previousState = PhoneMountingPolicy.initialState(EPOCH, oneRetryProfile),
        )
        val postFaultConfirmation = confirmation(
            confirmedAtMs = NOW_MS + 1L,
            postFaultCorrectionConfirmed = true,
        )
        val recovered = assess(
            nowMs = NOW_MS + 2L,
            confirmation = postFaultConfirmation,
            camera = camera(observedAtMs = NOW_MS + 2L),
            previousState = fault.nextState,
            runtimeRetryRequested = true,
        )
        val failedRetry = assess(
            nowMs = NOW_MS + 2L,
            confirmation = postFaultConfirmation,
            camera = camera(
                observedAtMs = NOW_MS + 2L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = fault.nextState,
            runtimeRetryRequested = true,
        )

        assertEquals(PhoneMountingStatus.SUITABLE, recovered.status)
        assertEquals(0, recovered.nextState.failedRuntimeRetryAttempts)
        assertEquals(PhoneMountingStatus.UNUSABLE, failedRetry.status)
        assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, failedRetry.reason)
        assertEquals(1, failedRetry.nextState.failedRuntimeRetryAttempts)
    }

    @Test
    fun aLatePassFromBeforeTheLatestFaultCannotRestoreDetection() {
        val initialFault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val laterFault = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 2L),
            camera = camera(
                observedAtMs = NOW_MS + 2L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = initialFault.nextState,
            runtimeRetryRequested = true,
        )
        val lateEarlierPass = assess(
            nowMs = NOW_MS + 3L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 3L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS + 1L),
            previousState = laterFault.nextState,
        )

        assertEquals(
            PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
            lateEarlierPass.reason,
        )
        assertFalse(lateEarlierPass.canStartOrResumeDetection)
    }

    @Test
    fun explicitRetriesWithoutFreshPostFaultCameraEvidenceAreBounded() {
        val initialFault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val firstRetry = assess(
            nowMs = NOW_MS + 1L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 1L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS),
            previousState = initialFault.nextState,
            runtimeRetryRequested = true,
        )
        val secondRetry = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmation(
                confirmedAtMs = NOW_MS + 2L,
                postFaultCorrectionConfirmed = true,
            ),
            camera = camera(observedAtMs = NOW_MS),
            previousState = firstRetry.nextState,
            runtimeRetryRequested = true,
        )

        assertEquals(
            PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
            firstRetry.reason,
        )
        assertEquals(1, firstRetry.nextState.failedRuntimeRetryAttempts)
        assertEquals(PhoneMountingStatus.UNUSABLE, secondRetry.status)
        assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, secondRetry.reason)
        assertTrue(secondRetry.requiresSafetyStop)
    }

    @Test
    fun explicitRetriesWithoutPostFaultConfirmationAreBounded() {
        val initialFault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val firstRetry = assess(
            nowMs = NOW_MS + 1L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 1L),
            camera = camera(observedAtMs = NOW_MS + 1L),
            previousState = initialFault.nextState,
            runtimeRetryRequested = true,
        )
        val secondRetry = assess(
            nowMs = NOW_MS + 2L,
            confirmation = confirmation(confirmedAtMs = NOW_MS + 2L),
            camera = camera(observedAtMs = NOW_MS + 2L),
            previousState = firstRetry.nextState,
            runtimeRetryRequested = true,
        )

        assertEquals(
            PhoneMountingReason.POST_FAULT_CONFIRMATION_REQUIRED,
            firstRetry.reason,
        )
        assertEquals(1, firstRetry.nextState.failedRuntimeRetryAttempts)
        assertEquals(PhoneMountingStatus.UNUSABLE, secondRetry.status)
        assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, secondRetry.reason)
    }

    @Test
    fun lateFailureCannotMoveTheFaultWatermarkBackward() {
        val initialFault = assess(
            confirmation = confirmation(),
            camera = camera(status = EnvironmentEvidenceStatus.FAIL),
        )
        val lateFailure = assess(
            nowMs = NOW_MS - 1L,
            confirmation = confirmation(confirmedAtMs = NOW_MS - 1L),
            camera = camera(
                observedAtMs = NOW_MS - 1L,
                status = EnvironmentEvidenceStatus.FAIL,
            ),
            previousState = initialFault.nextState,
        )

        assertEquals(
            initialFault.nextState.correctionRequiredSinceElapsedRealtimeMs,
            lateFailure.nextState.correctionRequiredSinceElapsedRealtimeMs,
        )
    }

    @Test
    fun everyOutcomeProvidesAccessibleKoreanReasonAndAction() {
        PhoneMountingReason.entries.forEach { reason ->
            assertTrue(reason.accessibleReasonKo.isNotBlank())
            assertTrue(reason.accessibleActionKo.isNotBlank())
            assertTrue(reason.accessibleReasonKo.any { it in '가'..'힣' })
            assertTrue(reason.accessibleActionKo.any { it in '가'..'힣' })
        }
    }

    private fun assess(
        nowMs: Long = NOW_MS,
        phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.ACTIVE,
        confirmation: PhoneMountingUserConfirmation?,
        camera: CameraFrameQualityAssessment?,
        previousState: PhoneMountingRuntimeState =
            PhoneMountingPolicy.initialState(EPOCH, PROFILE),
        runtimeRetryRequested: Boolean = false,
    ) = PhoneMountingPolicy.assess(
        phase = phase,
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = nowMs,
        userConfirmation = confirmation,
        cameraFrameQuality = camera,
        previousState = previousState,
        runtimeRetryRequested = runtimeRetryRequested,
    )

    private fun confirmation(
        epoch: WalkRuntimeEpoch = EPOCH,
        confirmedAtMs: Long = NOW_MS,
        method: PhoneMountingMethod = PhoneMountingMethod.CHEST_FORWARD,
        postFaultCorrectionConfirmed: Boolean = false,
    ) = PhoneMountingUserConfirmation(
        epoch = epoch,
        confirmedAtElapsedRealtimeMs = confirmedAtMs,
        method = method,
        postFaultCorrectionConfirmed = postFaultCorrectionConfirmed,
    )

    private fun camera(
        epoch: WalkRuntimeEpoch = EPOCH,
        observedAtMs: Long = NOW_MS,
        profileId: String? = CAMERA_PROFILE_ID,
        status: EnvironmentEvidenceStatus = EnvironmentEvidenceStatus.PASS,
    ) = CameraFrameQualityAssessment(
        epoch = epoch,
        observedAtElapsedRealtimeMs = observedAtMs,
        profileId = profileId,
        maximumEvidenceAgeMs = MAX_AGE_MS,
        status = status,
        reason = when (status) {
            EnvironmentEvidenceStatus.PASS -> CameraFrameQualityReason.PASSED
            EnvironmentEvidenceStatus.FAIL ->
                CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE

            EnvironmentEvidenceStatus.UNKNOWN ->
                CameraFrameQualityReason.MISSING_OBSERVATION
        },
    )

    private companion object {
        const val NOW_MS = 10_000L
        const val MAX_AGE_MS = 2_000L
        const val CAMERA_PROFILE_ID = "camera-field-approved-r001"
        val EPOCH = WalkRuntimeEpoch("walk-1", 1L)
        val OTHER_EPOCH = WalkRuntimeEpoch("walk-2", 1L)
        val PROFILE = ApprovedPhoneMountingProfile(
            profileId = "mounting-field-approved-r001",
            cameraFrameQualityProfileId = CAMERA_PROFILE_ID,
            maximumEvidenceAgeMs = MAX_AGE_MS,
            maximumRuntimeRetryAttempts = 2,
        )
    }
}
