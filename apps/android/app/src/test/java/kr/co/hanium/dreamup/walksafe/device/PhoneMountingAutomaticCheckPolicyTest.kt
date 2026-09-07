package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMountingAutomaticCheckPolicyTest {
    @Test
    fun explicitCheckNeedsNoInventedMountingMethod() {
        val result = assess(camera = camera(10_100L))
        assertTrue(result.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.SENSOR_CHECK_PASSED, result.reason)
    }

    @Test
    fun observationBeforeOrAtRequestCannotAuthorizeStart() {
        for (observedAt in listOf(9_999L, 10_000L)) {
            val result = assess(camera = camera(observedAt))
            assertFalse(result.canStartOrResumeDetection)
            assertEquals(PhoneMountingReason.POST_CHECK_CAMERA_EVIDENCE_REQUIRED, result.reason)
        }
    }

    @Test
    fun failedOrUnknownMeasurementsAreNeverPromoted() {
        for (status in listOf(EnvironmentEvidenceStatus.FAIL, EnvironmentEvidenceStatus.UNKNOWN)) {
            val observation = camera(10_100L, status)
            val result = assess(camera = observation)
            assertFalse(result.canStartOrResumeDetection)
            assertEquals(status, observation.status)
        }
    }

    @Test
    fun invalidOrWrongEpochRequestsCannotAuthorizeStart() {
        for (request in listOf(
            REQUEST.copy(epoch = WalkRuntimeEpoch("other-fixture", 1L)),
            REQUEST.copy(requestId = 0L),
            REQUEST.copy(requestedAtElapsedRealtimeMs = -1L),
            REQUEST.copy(requestedAtElapsedRealtimeMs = 10_501L),
        )) {
            assertFalse(assess(camera = camera(10_100L), request = request).canStartOrResumeDetection)
        }
    }

    @Test
    fun cameraProfileEpochAndFreshnessGuardsRemainRequired() {
        for (observation in listOf(
            camera(10_100L).copy(profileId = "other-profile"),
            camera(10_100L).copy(epoch = WalkRuntimeEpoch("other-fixture", 1L)),
            camera(10_100L).copy(reason = CameraFrameQualityReason.MISSING_OBSERVATION),
            camera(7_000L),
        )) {
            assertFalse(assess(camera = observation).canStartOrResumeDetection)
        }
    }

    @Test
    fun explicitRestartAfterTimeoutRequiresNewObservation() {
        val retry = REQUEST.copy(requestId = 2L, requestedAtElapsedRealtimeMs = 10_300L)
        assertTrue(assess(camera = camera(10_100L)).canStartOrResumeDetection)
        assertFalse(assess(camera = camera(10_100L), request = retry).canStartOrResumeDetection)
        assertTrue(assess(camera = camera(10_400L), request = retry).canStartOrResumeDetection)
    }

    @Test
    fun sameRequestContinuesIntoActiveRuntimeWithoutManualConfirmation() {
        assertTrue(assess(
            camera = camera(10_100L), phase = PhoneMountingAssessmentPhase.ACTIVE,
        ).canStartOrResumeDetection)
    }

    @Test
    fun runtimeFaultNeedsExplicitNewCheckAndPostRequestEvidence() {
        val fault = assess(
            camera = camera(10_100L, EnvironmentEvidenceStatus.FAIL),
            phase = PhoneMountingAssessmentPhase.ACTIVE,
        )
        val withoutNewRequest = assess(
            camera = camera(10_600L), nowMs = 10_800L,
            phase = PhoneMountingAssessmentPhase.ACTIVE, state = fault.nextState,
        )
        assertFalse(withoutNewRequest.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.POST_FAULT_CHECK_REQUEST_REQUIRED, withoutNewRequest.reason)
        val retry = REQUEST.copy(requestId = 2L, requestedAtElapsedRealtimeMs = 10_700L)
        assertFalse(assess(
            camera = camera(10_600L), request = retry, nowMs = 10_800L,
            phase = PhoneMountingAssessmentPhase.ACTIVE, state = fault.nextState,
        ).canStartOrResumeDetection)
        assertTrue(assess(
            camera = camera(10_750L), request = retry, nowMs = 10_800L,
            phase = PhoneMountingAssessmentPhase.ACTIVE, state = fault.nextState,
        ).canStartOrResumeDetection)
    }

    @Test
    fun explicitKnownProhibitedMethodIsNotOverridden() {
        val result = PhoneMountingPolicy.assess(
            phase = PhoneMountingAssessmentPhase.PREFLIGHT,
            currentEpoch = EPOCH, nowElapsedRealtimeMs = 10_500L,
            userConfirmation = PhoneMountingUserConfirmation(EPOCH, 10_000L, PhoneMountingMethod.HANDHELD, false),
            cameraFrameQuality = camera(10_100L),
            previousState = PhoneMountingPolicy.initialState(EPOCH, PROFILE),
            checkRequest = REQUEST,
        )
        assertFalse(result.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.PROHIBITED_MOUNTING_METHOD, result.reason)
    }

    @Test
    fun legacyPathStillRequiresItsOwnUserConfirmation() {
        val result = assess(camera = camera(10_100L), request = null)
        assertFalse(result.canStartOrResumeDetection)
        assertEquals(PhoneMountingReason.USER_CONFIRMATION_MISSING, result.reason)
    }

    private fun assess(
        camera: CameraFrameQualityAssessment?,
        request: PhoneMountingCheckRequest? = REQUEST,
        nowMs: Long = 10_500L,
        phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.PREFLIGHT,
        state: PhoneMountingRuntimeState = PhoneMountingPolicy.initialState(EPOCH, PROFILE),
    ) = PhoneMountingPolicy.assess(
        phase = phase, currentEpoch = EPOCH, nowElapsedRealtimeMs = nowMs,
        userConfirmation = null, cameraFrameQuality = camera, previousState = state,
        checkRequest = request,
    )

    private fun camera(
        observedAt: Long,
        status: EnvironmentEvidenceStatus = EnvironmentEvidenceStatus.PASS,
    ) = CameraFrameQualityAssessment(
        epoch = EPOCH, observedAtElapsedRealtimeMs = observedAt,
        profileId = "synthetic-camera-profile", maximumEvidenceAgeMs = 2_000L,
        status = status,
        reason = when (status) {
            EnvironmentEvidenceStatus.PASS -> CameraFrameQualityReason.PASSED
            EnvironmentEvidenceStatus.FAIL -> CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE
            EnvironmentEvidenceStatus.UNKNOWN -> CameraFrameQualityReason.MISSING_OBSERVATION
        },
    )

    private companion object {
        val EPOCH = WalkRuntimeEpoch("synthetic-walk", 1L)
        val REQUEST = PhoneMountingCheckRequest(EPOCH, 1L, 10_000L)
        val PROFILE = ApprovedPhoneMountingProfile(
            "synthetic-mount-profile", "synthetic-camera-profile", 2_000L, 2,
        )
    }
}
