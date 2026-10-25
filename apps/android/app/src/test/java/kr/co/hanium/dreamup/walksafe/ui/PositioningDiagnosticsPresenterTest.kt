package kr.co.hanium.dreamup.walksafe.ui

import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingObservation
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSource
import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionEstimate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copied domain facts only: no tracker, platform clock, UI, speech or calibration mutation. */
class PositioningDiagnosticsPresenterTest {
    private val presenter = PositioningDiagnosticsPresenter()
    private val owner = UserMotionStateOwner(4L, WalkRuntimeEpoch("walk", 2L), "boot", "process")

    @Test
    fun registrationDoesNotClaimMeasurementOrCurrentPermission() {
        val text = motionMessage(health = health(), activityPermissionGranted = false)
        assertTrue(text.contains("등록됐지만 실제 측정은 아직 확인되지 않았습니다."))
        assertTrue(text.contains("현재 활동 인식 권한이 없습니다."))
        assertFalse(text.contains("실제 측정 이력이 있습니다."))
    }

    @Test
    fun measurementMustBelongToTheAcceptedRegistrationAndOriginalReceiptRemainsUnknown() {
        val sample = AcceptedSensorSample(7, 1_000L, null)
        val current = health(sample = sample)
        val text = motionMessage(health = current)
        assertTrue(text.contains("실제 측정 이력이 있습니다. 원 수신 시각은 확인되지 않았습니다."))
        assertEquals(sample, current.sources.single().lastAcceptedSample)
        assertFalse(motionMessage(health = health(sample = sample.copy(producerRegistrationGeneration = 6)))
            .contains("실제 측정 이력이 있습니다."))
        assertFalse(motionMessage(health = health(sample = sample, acceptingSamples = false))
            .contains("실제 측정 이력이 있습니다."))
    }

    @Test
    fun oldOwnerCannotPublishMovementOrMeasurementsButCleanupStillExplainsTheWait() {
        val state = state().copy(movement = UserMovementState.MOVING)
        val text = presenter.motionEvidenceStatusMessage(
            state, UserMotionEstimate(), health(sample = AcceptedSensorSample(7, 1_000L, 1_100L)),
            health(cleanupPending = true), true, owner.copy(actorGeneration = 5L),
        )
        assertTrue(text.contains("현재 보행 범위의 움직임 근거를 다시 확인하고 있습니다."))
        assertTrue(text.contains("이전 보행의 측정은 현재 움직임 판단에 사용하지 않습니다."))
        assertTrue(text.contains("이전 등록 자원의 정리가 끝나지 않았습니다."))
        assertFalse(text.contains("이동 근거가 있습니다."))
        assertFalse(text.contains("실제 측정 이력이 있습니다."))
    }

    @Test
    fun conflictingGpsAndStepEvidenceIsExplainedOnceAndPhoneFacingDoesNotEstablishTravelDirection() {
        val reason = UserMotionStateReason.STEP_WITH_STATIONARY_GNSS
        val state = state().copy(
            reason = reason, reasonCodes = setOf(reason),
            support = UserMotionSupport(conflicts = listOf(UserMotionEvidenceConflict(reason, emptyList()))),
        )
        val heading = HeadingObservation(80.0, 4.0, 1_000L)
        val motion = UserMotionEstimate(phoneHeading = heading)
        val text = presenter.motionEvidenceStatusMessage(state, motion, null, null, true, owner)
        assertEquals(1, text.lineSequence().count { it.contains("근거가 서로 맞지 않습니다.") })
        assertTrue(text.contains("휴대전화 방향만으로 이동 방향을 확정하지 않습니다."))
        assertEquals(heading, motion.phoneHeading)
        assertEquals(1_000L, heading.elapsedRealtimeMs)
    }

    @Test
    fun calibrationWaitRejectionAndEmptyHistoryKeepDifferentMeanings() {
        val profile = PedestrianMotionProfile().currentSnapshot()
        val waiting = diagnostic(WalkingCalibrationReason.WAITING_FOR_STEP_WATERMARK)
        val waitText = presenter.walkingCalibrationDiagnosticMessage(profile, waiting, null)
        assertTrue(waitText.contains("보정이 아직 완료되지 않아 기본 보폭"))
        assertTrue(waitText.contains("보정 대기:"))
        val rejected = diagnostic(WalkingCalibrationReason.UNSUITABLE_FIX_ACCURACY, WalkingCalibrationOutcome.REJECTED)
        val rejectionText = presenter.walkingCalibrationDiagnosticMessage(profile, rejected, null)
        assertTrue(rejectionText.contains("위치 정보의 신뢰가 부족"))
        assertFalse(rejectionText.contains("보정 대기:"))
        val emptyHistory = rejected.copy(decisionCounts = emptyMap())
        assertTrue(presenter.walkingCalibrationDiagnosticMessage(profile, emptyHistory, null)
            .contains("보정에 쓸 걸음과 위치 정보를 기다립니다."))
        assertEquals(rejected.latestDecision, emptyHistory.latestDecision)
    }

    @Test
    fun restoredCalibratedProfileIsNotRelabeledDuringWarmupAndOriginalStepSourceExplainsRejection() {
        val profile = PedestrianMotionProfile().currentSnapshot().copy(isCalibrated = true)
        val rejected = diagnostic(WalkingCalibrationReason.STEP_NOT_INDIVIDUAL, WalkingCalibrationOutcome.REJECTED)
        val detector = StepEvent(1, 1_000L, StepEventSource.STEP_DETECTOR, StepEventConfidence.LOW)
        val detectorText = presenter.walkingCalibrationDiagnosticMessage(profile, rejected, detector)
        assertTrue(detectorText.contains("개인 보폭 보정값을 사용하고 있습니다."))
        assertTrue(detectorText.contains("신뢰가 낮아"))
        assertFalse(detectorText.contains("기본 보폭"))
        val counter = detector.copy(deltaSteps = 4, source = StepEventSource.STEP_COUNTER,
            confidence = StepEventConfidence.MEDIUM)
        val counterText = presenter.walkingCalibrationDiagnosticMessage(profile, rejected, counter)
        assertTrue(counterText.contains("개별 걸음 시각이 확인되지 않아"))
        assertFalse(counterText.contains("전용 기기"))
        assertFalse(counterText.contains("신뢰가 낮아"))
    }

    @Test
    fun compassPresentationKeepsRearAxisFallbackAndUnavailableCauseSeparate() {
        assertEquals("위치 측정과 함께 나침반 방향을 준비합니다.", presenter.routeCompassStatusMessage(null))
        val result = RouteCompassHeadingResult(
            RouteFacingObservation(90.0, null, 1_000L, RouteFacingSource.GRAVITY_MAGNETIC),
            RouteCompassHeadingReason.VALID,
        )
        assertEquals("나침반: 휴대폰 후면은 동쪽(90도)을 향하고 있습니다. 보조 나침반 사용 중.",
            presenter.routeCompassStatusMessage(result))
        assertTrue(presenter.routeCompassStatusMessage(result.copy(observation = null,
            reason = RouteCompassHeadingReason.SENSOR_STALLED)).contains("센서 값이 들어오지 않아 방향 안내를 멈췄습니다."))
        assertEquals(1_000L, result.observation!!.observedAtElapsedRealtimeMs)
    }

    @Test
    fun debugMotionChannelsRemainIndependentAndDoNotReplaceMissingMeasurements() {
        val state = state()
        val motion = UserMotionEstimate(phoneHeading = HeadingObservation(80.0, 4.0, 1_000L))
        val text = presenter.userMotionStatusText(state, motion)
        assertTrue(text.contains("userSpeed=unknownm/s travelCourse=unknowndeg phoneHeading=80.0deg"))
        assertTrue(text.contains("userState=UNKNOWN locomotion=UNKNOWN deviceRotation=UNKNOWN"))
        assertEquals(1_000L, state.evaluatedAtElapsedRealtimeMs)
    }

    @Test
    fun confidenceTextConsumesTheCurrentDecisionWithoutRecoveringOrRenewingIt() {
        val coordinator = PositioningCoordinator()
        val snapshot = coordinator.evaluate(1_000L)
        val before = snapshot.confidence
        val text = presenter.positionConfidenceDiagnosticLine(snapshot)
        assertTrue(text.contains("event=CONFIDENCE quality=UNAVAILABLE"))
        assertTrue(text.contains("paused=true"))
        assertTrue(text.contains("reported_accuracy_m=null filtered_sigma_m=null"))
        assertEquals(before, snapshot.confidence)
        assertEquals(before, coordinator.evaluate(1_000L).confidence)
    }

    private fun state() = UserMotionStateSnapshot(
        generation = 3L, movement = UserMovementState.UNKNOWN, locomotion = UserLocomotionState.UNKNOWN,
        deviceRotation = UserDeviceRotationState.UNKNOWN, reason = UserMotionStateReason.NO_EVIDENCE,
        stateSinceElapsedRealtimeMs = null, evaluatedAtElapsedRealtimeMs = 1_000L,
        expiresAtElapsedRealtimeMs = null, motionObservedAtElapsedRealtimeMs = null,
        speedObservedAtElapsedRealtimeMs = null, stepObservedAtElapsedRealtimeMs = null,
        stationaryCandidateSinceElapsedRealtimeMs = null, zeroVelocityUpdateAllowed = false, owner = owner,
    )

    private fun health(
        sample: AcceptedSensorSample? = null,
        acceptingSamples: Boolean = true,
        cleanupPending: Boolean = false,
    ) = SensorTrackerHealthSnapshot(
        7, SensorTrackerLifecycle.ACTIVE, null, cleanupPending,
        listOf(SensorSourceHealth(TrackedSensorSource.STEP_DETECTOR, true,
            SensorRegistrationOutcome.REGISTERED, acceptingSamples, sample)),
    )

    private fun motionMessage(health: SensorTrackerHealthSnapshot, activityPermissionGranted: Boolean = true) =
        presenter.motionEvidenceStatusMessage(state(), UserMotionEstimate(), health, null,
            activityPermissionGranted, owner)

    private fun diagnostic(reason: WalkingCalibrationReason, outcome: WalkingCalibrationOutcome = WalkingCalibrationOutcome.WAITING): WalkingCalibrationDiagnosticSnapshot {
        val decision = WalkingCalibrationDecision(WalkingCalibrationStage.STRAIGHT_SEGMENT, outcome, reason)
        return WalkingCalibrationDiagnosticSnapshot(decision, mapOf(decision to 1L))
    }
}
