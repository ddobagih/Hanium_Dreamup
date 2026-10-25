package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMountingNativeCorrectionRegressionTest {
    @Test
    fun originalSensorRequestAllowsAValidExplicitCorrectionFollowedByANewCameraFrame() {
        val corrected = assess(
            nowMs = 10_003L,
            cameraAtMs = 10_003L,
            confirmation = confirmation(10_002L),
        )

        assertEquals(PhoneMountingStatus.SUITABLE, corrected.status)
        assertTrue(corrected.canStartOrResumeDetection)
        assertEquals(0, corrected.nextState.failedRuntimeRetryAttempts)
    }

    @Test
    fun originalSensorRequestCannotBorrowInvalidOrUnconfirmedExplicitCorrections() {
        val invalidConfirmations = listOf(
            confirmation(10_002L).copy(epoch = WalkRuntimeEpoch("other-walk", 1L)),
            confirmation(10_002L).copy(method = PhoneMountingMethod.HANDHELD),
            confirmation(10_002L).copy(method = PhoneMountingMethod.POCKET),
            confirmation(10_002L).copy(method = PhoneMountingMethod.UNKNOWN),
            confirmation(10_004L),
            confirmation(10_002L).copy(postFaultCorrectionConfirmed = false),
            confirmation(10_001L),
        )
        invalidConfirmations.forEach { invalid ->
            val blocked = assess(10_003L, 10_003L, invalid)
            assertFalse("$invalid must not authorize the correction", blocked.canStartOrResumeDetection)
            assertTrue(blocked.nextState.requiresExplicitCorrection)
        }
    }

    @Test
    fun originalSensorRequestCannotBorrowAnExpiredExplicitCorrection() {
        val blocked = assess(
            nowMs = 70_003L,
            cameraAtMs = 70_003L,
            confirmation = confirmation(10_002L),
        )

        assertFalse(blocked.canStartOrResumeDetection)
        assertTrue(blocked.nextState.requiresExplicitCorrection)
    }

    @Test
    fun cameraMustFollowTheLaterOfTheSensorRequestAndExplicitCorrection() {
        val requestBeforeConfirmation = REQUEST.copy(requestId = 2L, requestedAtElapsedRealtimeMs = 10_002L)
        val newerConfirmation = confirmation(10_004L)
        listOf(10_003L, 10_004L).forEach { cameraAt ->
            assertFalse(assess(10_006L, cameraAt, newerConfirmation, requestBeforeConfirmation)
                .canStartOrResumeDetection)
        }
        assertTrue(assess(10_006L, 10_005L, newerConfirmation, requestBeforeConfirmation)
            .canStartOrResumeDetection)

        val requestAfterConfirmation = REQUEST.copy(requestId = 3L, requestedAtElapsedRealtimeMs = 10_004L)
        val earlierConfirmation = confirmation(10_002L)
        listOf(10_003L, 10_004L).forEach { cameraAt ->
            assertFalse(assess(10_006L, cameraAt, earlierConfirmation, requestAfterConfirmation)
                .canStartOrResumeDetection)
        }
        assertTrue(assess(10_006L, 10_005L, earlierConfirmation, requestAfterConfirmation)
            .canStartOrResumeDetection)
    }

    @Test
    fun validNewSensorRequestCanStillAuthorizeRecoveryWithoutAnExplicitConfirmation() {
        val newerRequest = REQUEST.copy(requestId = 2L, requestedAtElapsedRealtimeMs = 10_002L)
        val restored = assess(10_003L, 10_003L, null, newerRequest)

        assertTrue(restored.canStartOrResumeDetection)
    }

    private fun assess(
        nowMs: Long,
        cameraAtMs: Long,
        confirmation: PhoneMountingUserConfirmation?,
        checkRequest: PhoneMountingCheckRequest = REQUEST,
    ): PhoneMountingAssessment {
        val fault = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = 10_001L,
            userConfirmation = null,
            cameraFrameQuality = camera(10_001L).copy(
                status = EnvironmentEvidenceStatus.FAIL,
                reason = CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE,
            ),
            previousState = PhoneMountingPolicy.initialState(EPOCH, PROFILE),
            checkRequest = REQUEST,
        )
        assertTrue(fault.nextState.requiresExplicitCorrection)
        return PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = nowMs,
            userConfirmation = confirmation,
            cameraFrameQuality = camera(cameraAtMs),
            previousState = fault.nextState,
            runtimeRetryRequested = true,
            checkRequest = checkRequest,
        )
    }

    private fun confirmation(atMs: Long) = PhoneMountingUserConfirmation(
        EPOCH, atMs, PhoneMountingMethod.CHEST_FORWARD, postFaultCorrectionConfirmed = true,
    )

    private fun camera(atMs: Long) = CameraFrameQualityAssessment(
        EPOCH, atMs, "camera", 5_000L, EnvironmentEvidenceStatus.PASS, CameraFrameQualityReason.PASSED,
    )

    private companion object {
        val EPOCH = WalkRuntimeEpoch("native-walk", 1L)
        val PROFILE = ApprovedPhoneMountingProfile("mounting", "camera", 5_000L, 2, 60_000L)
        val REQUEST = PhoneMountingCheckRequest(EPOCH, 1L, 10_000L)
    }
}
