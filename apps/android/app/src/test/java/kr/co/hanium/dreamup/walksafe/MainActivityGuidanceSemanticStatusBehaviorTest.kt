package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingMethod
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingStatus
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianMotionProfileSnapshot
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingCalibrationSample
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main pure representation with controlled measured facts. SOURCE verifies the two
 * production callers. TextView delivery, callbacks, OS observation and TalkBack are not JVM facts. */
class MainActivityGuidanceSemanticStatusBehaviorTest {
    @Test fun immediateFailuresRetainTheActionNeededToRecover() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("immediate-failure", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null, false)
        field(main, "officialEnvironmentGpsDiagnosticDetail", "LOCATION_SERVICE_DISABLED")
        assertTrue(detail(main, assessment, false).contains("위치 서비스를 켠 뒤"))
        field(main, "officialEnvironmentGpsDiagnosticDetail", "LOCATION_PERMISSION_MISSING")
        assertTrue(detail(main, assessment, false).contains("위치 권한을 허용"))
        for (reason in listOf("CAMERA_RELEASE_TIMED_OUT", "PREVIOUS_CAMERA_RELEASE_TIMED_OUT")) {
            field(main, "officialEnvironmentCameraDiagnosticReason", reason)
            assertTrue(detail(main, assessment, false).contains("앱을 다시 시작한 뒤"))
        }
    }

    @Test fun theSameMeasuredReasonWithDifferentNumbersHasOneSemanticMessage() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("semantic-status", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null, false)
        field(main, "officialEnvironmentGpsDiagnosticDetail", "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE")
        for (reason in listOf(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE,
            CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE)) {
            field(main, "officialEnvironmentCameraDiagnosticReason", reason.name)
            field(main, "officialEnvironmentGpsDiagnosticAccuracyMeters", 20.0)
            field(main, "officialEnvironmentCameraDiagnosticObservation",
                CameraFrameQualityObservation(epoch, 1_000L, true, .1, .7, 20.0, 1.0))
            val firstSemantic = detail(main, assessment, false)
            val firstMeasurement = detail(main, assessment, true)
            field(main, "officialEnvironmentGpsDiagnosticAccuracyMeters", 21.0)
            field(main, "officialEnvironmentCameraDiagnosticObservation",
                CameraFrameQualityObservation(epoch, 1_000L, true, .2, .8, 21.0, 2.0))
            assertEquals("Changing measured numbers must not change the live-region meaning ($reason)",
                firstSemantic, detail(main, assessment, false))
            assertNotEquals(firstMeasurement, detail(main, assessment, true))
            assertFalse(firstSemantic.contains("20.0"))
        }
    }

    @Test fun changedMeasuredReasonsKeepTheirExistingUserActions() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("semantic-reason", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null, false)
        field(main, "officialEnvironmentCameraDiagnosticReason", CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name)
        val brightness = detail(main, assessment, false)
        field(main, "officialEnvironmentCameraDiagnosticReason", CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name)
        val occlusion = detail(main, assessment, false)
        assertNotEquals(brightness, occlusion)
        assertTrue(brightness.contains("더 밝은 곳으로 이동하세요"))
        assertTrue(occlusion.contains("앞을 가리는 물건이 있는지 확인해 주세요"))
        field(main, "officialEnvironmentCameraDiagnosticReason", "CAMERA_BIND_FAILED")
        assertTrue(detail(main, assessment, false).contains("카메라를 다시 점검해 주세요"))
    }

    @Test fun automaticFailureSpeechSelectsOnlyTheAffectedFactorAndItsAction() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("failure-actions", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null)
        val cameraOnly = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)
        val gpsOnly = setOf(OfficialEnvironmentFactor.GPS_QUALITY)
        val cameraActions = listOf(
            CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name to "더 밝은 곳으로 이동하세요.",
            CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name to "앞을 가리는 물건이 있는지 확인해 주세요.",
            CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name to "휴대전화 뒷면 카메라가 앞을 향하도록 세우세요.",
            "CAMERA_PERMISSION_MISSING" to "설정에서 카메라 권한을 허용해 주세요.",
        )
        for ((reason, action) in cameraActions) {
            field(main, "officialEnvironmentCameraDiagnosticReason", reason)
            val message = main.officialEnvironmentFailureSpeech(assessment, cameraOnly)
            assertTrue(message, message.contains(action))
            assertFalse(message.contains("위치:"))
            assertFalse(message.contains("장착을 교정하세요"))
            assertFalse(message.contains("흔들림"))
            for ((other, otherAction) in cameraActions) if (other != reason) {
                assertFalse(message, message.contains(otherAction))
            }
        }
        for ((reason, action) in listOf(
            "LOCATION_PERMISSION_MISSING" to "설정에서 위치 권한을 허용해 주세요.",
            "LOCATION_SERVICE_DISABLED" to "위치 서비스를 켠 뒤 다시 시도해 주세요.",
            "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE" to "위치: 위치 오차가 커서 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요.",
            "TRUSTED_FIX_UNAVAILABLE" to "현재 위치를 확인하지 못했습니다. 새 위치를 기다려 주세요.",
        )) {
            field(main, "officialEnvironmentGpsDiagnosticDetail", reason)
            val message = main.officialEnvironmentFailureSpeech(assessment, gpsOnly)
            assertTrue(message, message.contains(action))
            assertFalse(message.contains("카메라:"))
            assertFalse(message.contains("확인하고 있습니다"))
            assertFalse(message.contains("자동으로"))
            assertFalse(message.contains("모의 위치"))
            assertFalse(message.contains("이동하세요"))
            assertFalse(message.contains("멈춰"))
            assertFalse(message.contains("실내"))
        }
    }

    @Test fun borderlineCameraAnglesKeepTheSpecificPostureActionWithoutInventingOtherFaults() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("camera-angle-boundary", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null)
        val cameraOnly = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)
        field(main, "officialEnvironmentCameraDiagnosticReason", CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name)
        val expected = main.officialEnvironmentFailureSpeech(assessment, cameraOnly)
        assertTrue(expected.contains("휴대전화 뒷면 카메라가 앞을 향하도록 세우세요."))
        for (reason in listOf(CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY,
            CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING)) {
            field(main, "officialEnvironmentCameraDiagnosticReason", reason.name)
            assertEquals(expected, main.officialEnvironmentFailureSpeech(assessment, cameraOnly))
            assertFalse(expected.contains("위치:"))
            assertFalse(expected.contains("밝기"))
            assertFalse(expected.contains("가리는 물건"))
        }
    }

    @Test fun unknownFailuresNeverInventDarknessOcclusionOrIndoorDetectionAndPassingFactorsStaySilent() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("unknown-actions", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null)
        field(main, "officialEnvironmentCameraDiagnosticReason", "UNKNOWN_CAMERA_REASON")
        field(main, "officialEnvironmentGpsDiagnosticDetail", "UNKNOWN_GPS_REASON")
        val message = main.officialEnvironmentFailureSpeech(assessment)
        assertTrue(message.contains("카메라를 다시 점검해 주세요."))
        assertTrue(message.contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
        for (invented in listOf("더 밝은", "가려졌", "각도가 기준", "실내입니다", "전체 점검이 만료")) {
            assertFalse(message, message.contains(invented))
        }
        val passed = assessment.copy(factorStatuses = assessment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS })
        field(main, "officialEnvironmentCameraPreflightActive", true)
        field(main, "officialEnvironmentCameraDiagnosticReason", "PASSED")
        field(main, "officialEnvironmentGpsDiagnosticDetail", "GPS_QUALITY_PASSED")
        assertEquals("", main.officialEnvironmentFailureSpeech(passed))
    }

    @Test fun cameraStartFailureIsVisibleToImmediateSpeechWithoutChangingTheUnknownEvidence() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("camera-start-action", 1L)
        val profiles = WalkSafeEnvironmentProfiles.testCandidate
        val observation = CameraFrameQualityObservation(epoch, 1_000L, null, null, null, null, null)
        val evidence = kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy.assess(
            epoch, 1_000L, observation, profiles.cameraFrameQuality).asMeasuredEnvironmentEvidence()
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, evidence.status)
        assertEquals(CameraFrameQualityReason.FRAME_STATE_UNKNOWN.name, evidence.detail)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, evidence, null, profiles.officialEnvironment)
        for ((failure, action) in listOf(
            "CAMERA_PERMISSION_MISSING" to "설정에서 카메라 권한을 허용해 주세요.",
            "MOUNTING_SENSOR_UNAVAILABLE" to "중력 센서를 확인한 뒤 다시 점검하세요.",
        )) {
            // Real Main diagnostic commit followed immediately by the same speech selector as the UI continuation.
            main.recordOfficialEnvironmentCameraDiagnostic(observation, evidence, failure)
            val message = main.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
            assertTrue(message, message.contains(action))
            assertFalse(message.contains("상태를 확인하지 못했습니다"))
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, evidence.status)
            assertEquals(CameraFrameQualityReason.FRAME_STATE_UNKNOWN.name, evidence.detail)
        }
        // Ordinary observations replace the SDK diagnostic; the override never becomes sticky state.
        main.recordOfficialEnvironmentCameraDiagnostic(observation, evidence)
        val unknown = main.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
        assertTrue(unknown.contains("카메라를 다시 점검해 주세요."))
        assertFalse(unknown.contains("권한"))
        assertFalse(unknown.contains("중력"))
    }

    @Test fun temporaryBrightnessRestrictionKeepsItsSpecificActionAndAutomaticRecovery() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("camera-correction-action", 1L)
        val profiles = WalkSafeEnvironmentProfiles.testCandidate
        val observation = CameraFrameQualityObservation(epoch, 1_001L, true, 0.0, 0.0, null, 0.0)
        val camera = CameraFrameQualityPolicy.assess(epoch, 1_001L, observation, profiles.cameraFrameQuality)
        assertEquals(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE, camera.reason)
        val evidence = camera.asMeasuredEnvironmentEvidence()
        main.recordOfficialEnvironmentCameraDiagnostic(observation, evidence)
        val environment = OfficialEnvironmentPolicy.assess(epoch, 1_001L, null, evidence, null, profiles.officialEnvironment)
        val confirmation = PhoneMountingUserConfirmation(epoch, 1_000L, PhoneMountingMethod.CHEST_FORWARD, false)
        fun mounting(phase: PhoneMountingAssessmentPhase, approved: Boolean = true) = PhoneMountingPolicy.assess(
            phase, epoch, 1_001L, confirmation, camera,
            PhoneMountingPolicy.initialState(epoch, profiles.phoneMounting.takeIf { approved }))
        fun speech(assessment: PhoneMountingAssessment, observed: OfficialEnvironmentAssessment = environment) =
            invoke(main, "phoneMountingFailureSpeech", arrayOf(PhoneMountingAssessment::class.java,
                OfficialEnvironmentAssessment::class.java), assessment, observed)
        val active = mounting(PhoneMountingAssessmentPhase.ACTIVE)
        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, active.status)
        val message = speech(active)
        assertFalse(active.nextState.requiresExplicitCorrection)
        assertEquals("카메라: 밝기가 기준에 부족합니다. 더 밝은 곳으로 이동하세요.", message)
        assertFalse(message.contains("장착 교정을 확인"))
        assertFalse(message.contains("렌즈 가림"))
        val preflight = mounting(PhoneMountingAssessmentPhase.PREFLIGHT)
        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, preflight.status)
        assertFalse(speech(preflight).contains("장착 교정을 확인"))
        val unusable = mounting(PhoneMountingAssessmentPhase.ACTIVE, approved = false)
        assertEquals(PhoneMountingStatus.UNUSABLE, unusable.status)
        assertFalse(speech(unusable).contains("장착 교정을 확인"))
        // If no current camera reason is available, preserve the policy's required spoken action.
        val noCameraFailure = environment.copy(factorStatuses = environment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS })
        assertEquals(active.spokenGuidanceKo, speech(active, noCameraFailure))
        assertTrue(speech(active, noCameraFailure).contains("자동으로 회복"))
        assertFalse(speech(active, noCameraFailure).contains("장착 교정을 확인"))
    }

    @Test fun physicalMountAngleFailureKeepsSpecificActionAndRequiredExplicitCorrection() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("physical-mount-correction", 1L)
        val profiles = WalkSafeEnvironmentProfiles.testCandidate
        val observation = CameraFrameQualityObservation(epoch, 1_001L, true, 0.5, 0.0, null, 90.0)
        val camera = CameraFrameQualityPolicy.assess(epoch, 1_001L, observation, profiles.cameraFrameQuality)
        assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE, camera.reason)
        val evidence = camera.asMeasuredEnvironmentEvidence()
        main.recordOfficialEnvironmentCameraDiagnostic(observation, evidence)
        val environment = OfficialEnvironmentPolicy.assess(epoch, 1_001L, null, evidence, null, profiles.officialEnvironment)
        val assessment = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, epoch, 1_001L,
            PhoneMountingUserConfirmation(epoch, 1_000L, PhoneMountingMethod.CHEST_FORWARD, false), camera,
            PhoneMountingPolicy.initialState(epoch, profiles.phoneMounting))
        assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, assessment.status)
        assertTrue(assessment.nextState.requiresExplicitCorrection)
        fun speech(observed: OfficialEnvironmentAssessment) = invoke(main, "phoneMountingFailureSpeech",
            arrayOf(PhoneMountingAssessment::class.java, OfficialEnvironmentAssessment::class.java), assessment, observed)
        val message = speech(environment)
        assertTrue(message, message.contains("각도"))
        assertTrue(message.endsWith("화면에서 장착 교정을 확인하세요."))
        val noCameraFailure = environment.copy(factorStatuses = environment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS })
        assertEquals(assessment.spokenGuidanceKo, speech(noCameraFailure))
        assertTrue(speech(noCameraFailure).contains("화면에서 장착 교정을 확인하세요."))
    }

    @Test fun calibrationProfileAuthorityStaysSemanticDuringRestoredWarmup() {
        val main = MainActivity()
        val original = PositioningCoordinator()
        assertTrue(profileMessage(main, original.currentProfile()).contains("기본 보폭"))
        repeat(3) { original.calibrateProfile(WalkingCalibrationSample(16.0, 20, 16_000L, true)) }
        val restored = PositioningCoordinator()
        assertTrue(restored.restoreProfile(requireNotNull(original.snapshotProfileForPersistence())))
        val before = profileMessage(main, restored.currentProfile())
        restored.calibrateProfile(WalkingCalibrationSample(16.0, 20, 16_000L, true))
        assertTrue(restored.currentProfile().isCalibrated)
        assertEquals(before, profileMessage(main, restored.currentProfile()))
        assertTrue(before.contains("개인 보폭 보정값"))
    }

    @Test fun bothExistingCallersUseTheSameConditionalWriterAndKeepCallbackOwners() {
        val source = source()
        assertTrue(MainActivityRefactorSource.member(source, "renderMainUi").contains("nativeWorkflowController.renderMainUi()"))
        val render = MainActivityRefactorSource.function(source, "private fun renderMainUi()")
        assertTrue(render.contains("pages.renderFrame(NativeScreenPresenter.FrameFacts("))
        assertTrue(MainActivityRefactorSource.composition(source, "nativeWorkflowController")
            .contains("this@MainActivity.nativePagePresentationController.renderFrame(frame)"))
        val pageSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePagePresentationController.kt").readText()
        val page = MainActivityRefactorSource.member(pageSource, "renderFrame")
        assertTrue(page.contains("phases.renderGuidanceStatus(presentation, queries.walkingCalibrationStatusMessage())"))
        assertTrue(MainActivityRefactorSource.composition(source, "nativePagePresentationController")
            .contains("updateNativeGuidanceStatus(presentation, measurements)"))
        assertTrue(MainActivityRefactorSource.member(source, "refreshWalkingCalibrationDiagnostics")
            .contains("frameMotionEvidence.refreshWalkingCalibrationDiagnostics()"))
        val callback = MainActivityRefactorSource.function(source, "private fun refreshWalkingCalibrationDiagnostics()")
        val callbackBinding = MainActivityRefactorSource.composition(source, "frameMotionEvidence")
        assertTrue(callback.contains("display.updateCurrentGuidance(message + \"\\n\" + currentMotionEvidenceStatusMessage())"))
        assertTrue(callbackBinding.contains("updateNativeGuidanceStatus(currentNativeGuidancePresentation(), message)"))
        for (guard in listOf("positioning().walkingCalibrationScopeIsCurrent(scope)", "display.guidancePageCurrent()",
            "currentGateway.generation == gateway.generation && currentGateway.session === gateway.session",
            "(source.lifecycleSnapshot()) == lifecycle", "positioning().coordinator.currentProfile() == profile",
            "positioning().calibrationSegments.diagnosticSnapshot() == diagnostic")) assertTrue(guard, callback.contains(guard))
        val writer = MainActivityRefactorSource.member(source, "updateNativeGuidanceStatus")
        assertTrue(writer.contains("destinationSearchPresenter.renderGuidanceStatus(nativeGuidanceStatusMessage(presentation, false)"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        assertTrue(MainActivityRefactorSource.member(presenter, "renderGuidanceStatus").contains("requireNotNull(target).renderGuidanceStatus("))
        assertTrue(source.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        val conditionalWriter = MainActivityRefactorSource.member(panel, "renderGuidanceStatus")
        assertTrue(conditionalWriter.indexOf("nativeGuidanceStatusText.text?.toString() != message") <
            conditionalWriter.indexOf("nativeGuidanceStatusText.text = message"))
        assertFalse(writer.contains("currentMotionEvidenceStatusMessage()"))
        assertFalse(writer.contains("walkingCalibrationProfileMessage(positioningCoordinator.currentProfile())"))
        assertEquals(1, Regex("nativeGuidanceStatusText\\.text\\s*=").findAll(panel).count())
        assertEquals(0, Regex("nativeGuidanceStatusText\\.text\\s*=").findAll(source).count())
        assertFalse(conditionalWriter.contains("announceForAccessibility"))

    }

    @Test fun sensorMeasurementsAreHiddenWhileExplicitStatusAndSafetyReasonsRemainAvailable() {
        val source = source()
        val panel = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationGuidancePanel.kt").readText()
        val view = MainActivityRefactorSource.property(panel, "nativeGuidanceMeasurementText")
        assertTrue(view.contains("visibility = View.GONE"))
        assertTrue(view.contains("View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(view.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(panel.contains("nativeGuidanceStatusText,\n        nativeGuidanceMeasurementText,"))
        assertTrue(source.contains("destinationSearchPresenter.bindPanel(destinationPanel)"))
        val writer = MainActivityRefactorSource.member(source, "updateNativeGuidanceStatus")
        assertTrue(writer.contains("officialEnvironmentMeasurementDetail(currentOfficialEnvironmentAssessment())"))
        assertTrue(writer.contains("calibrationMessage)"))
        assertTrue(writer.contains("nativeUiPublication.isPublished(NativeUiPublication.Target.NATIVE_GUIDANCE_MEASUREMENT_TEXT)"))
        val presenter = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText()
        val measurement = MainActivityRefactorSource.member(presenter, "renderGuidanceMeasurements")
        assertTrue(measurement.contains("requireNotNull(target).renderGuidanceMeasurements("))
        assertTrue(measurement.contains("listOf(environmentDetail.trim(), calibrationMessage)"))
        val conditionalMeasurement = MainActivityRefactorSource.member(panel, "renderGuidanceMeasurements")
        assertTrue(conditionalMeasurement.contains("nativeGuidanceMeasurementText.text = message"))
        val full = MainActivityRefactorSource.member(source, "nativeGuidanceStatusMessage")
        assertTrue(full.contains("includeMeasurements: Boolean = true"))
        assertTrue(full.contains("currentOwnedWalkRuntimeSafetyStopStatus()?.userFacingSummary()"))
        assertTrue(full.contains("NativeGuidanceTextPresentation.guidanceStatus("))
        val textSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeGuidanceTextPresentation.kt").readText()
        val text = MainActivityRefactorSource.member(textSource, "guidanceStatus")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(text,
            "currentSafetyStopSummary()?.let", "developmentBypassEnabled() ->", "!includeMeasurements -> null"))
        assertTrue(text.contains("presentation.routeMessageKo"))
        assertTrue(text.contains("presentation.cameraMessageKo"))
        val preparation = MainActivityRefactorSource.member(source, "nativeDestinationPreparationMessage")
        assertTrue(preparation.contains("OfficialEnvironmentPreflightPhase.TIMED_OUT"))
        assertTrue(preparation.contains("OfficialEnvironmentPreflightPhase.FAILED"))
        assertTrue(preparation.contains("현재 상태를 다시 확인하고 있습니다. 조건이 회복되면 안내를 자동으로 이어갑니다."))
        assertFalse(preparation.contains("다시 시도를 누르거나 안내 취소를 선택하세요"))

    }

    @Test fun activeGpsFailureDoesNotClaimExpiredPreflightInSemanticStatus() {
        assertRuntimeGpsFailureDescription(measurements = false)
    }

    @Test fun activeGpsFailureDoesNotReusePassingPreflightAccuracyInExplicitDetails() {
        assertRuntimeGpsFailureDescription(measurements = true)
    }

    @Test fun runtimeTrustedFixUnavailableDoesNotBlameMockLocation() {
        val f = RuntimeGpsFixture()
        f.now += 1L
        // onLocationAvailability(false) supplies these facts without any mock-location evidence.
        val candidate = OfficialEnvironmentPolicy.assessGpsQuality(f.epoch, f.now,
            GpsQualityObservation(f.epoch, f.now, false, null), f.profile)
        val unavailable = f.gate.observe(candidate, f.now)
        assertEquals(EnvironmentEvidenceStatus.FAIL, unavailable.status)
        assertEquals("TRUSTED_FIX_UNAVAILABLE", unavailable.detail)
        val preflight = MainActivity()
        field(preflight, "officialEnvironmentGpsDiagnosticDetail", "TRUSTED_FIX_UNAVAILABLE")
        for (measurements in listOf(false, true)) {
            val preflightMessage = detail(preflight, f.assessment(unavailable), measurements)
            assertFalse(preflightMessage.contains("모의 위치 설정을 끄고"))
            assertTrue(preflightMessage.contains("현재 위치를 확인하지 못했습니다. 새 위치를 기다려 주세요."))
            val message = f.render(unavailable, f.assessment(unavailable), measurements)
            assertFalse("Runtime location unavailability does not identify mock settings as the cause: $message",
                message.contains("모의 위치"))
            assertTrue(message.contains("현재 위치를 확인하지 못했습니다."))
            assertTrue(message.contains("새 위치를 기다려 주세요."))
        }
        assertEquals(unavailable, f.gate.current(f.now))
    }

    @Test fun runtimeGpsDescriptionRejectsEvidenceFromAnotherEpochProfileOrTime() {
        val f = RuntimeGpsFixture()
        val unavailable = listOf(
            f.failed.copy(epoch = WalkRuntimeEpoch("other-walk", 1L)),
            f.failed.copy(measurementProfileId = "other-profile"),
            f.failed.copy(factor = OfficialEnvironmentFactor.CAMERA_QUALITY),
            f.failed.copy(observedAtElapsedRealtimeMs = f.now + 1L),
            f.failed.copy(observedAtElapsedRealtimeMs = f.now - 1L, maximumEvidenceAgeMs = 0L),
            f.failed.copy(status = EnvironmentEvidenceStatus.UNKNOWN, detail = "INVALID_ACCURACY"),
        )
        for (evidence in unavailable) for (measurements in listOf(false, true)) {
            val assessment = f.assessment(evidence)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN,
                assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
            val message = f.render(evidence, assessment, measurements)
            assertTrue(message, message.contains(if (measurements) "신뢰할 수 있는 최신 위치를 기다리고 있습니다."
                else "현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertFalse(message.contains("전체 점검이 만료"))
            assertFalse(message.contains("위치 오차가 커서"))
            assertFalse(message.contains("3.0m"))
        }
    }

    @Test fun runtimeGpsDescriptionDoesNotReuseAnAssessmentAfterItsOwnerChanges() {
        val f = RuntimeGpsFixture()
        val oldAssessment = f.assessment()
        val cases = listOf(
            f.failed.copy(observedAtElapsedRealtimeMs = f.now + 1L) to oldAssessment,
            f.failed to oldAssessment.copy(epoch = WalkRuntimeEpoch("old-assessment", 1L)),
            f.failed to oldAssessment.copy(profileId = "old-profile"),
        )
        for ((evidence, assessment) in cases) {
            val message = f.render(evidence, assessment, measurements = true)
            assertTrue(message, message.contains("신뢰할 수 있는 최신 위치를 기다리고 있습니다."))
            assertFalse(message.contains("전체 점검이 만료"))
            assertFalse(message.contains("위치 오차가 커서"))
        }
    }

    @Test fun runtimeEvidenceThatAgesOutWaitsForAFreshFixWithoutClaimingWholeCheckExpiry() {
        val f = RuntimeGpsFixture()
        f.now += f.profile.maximumMeasuredEvidenceAgeMs + 1L
        val assessment = f.assessment()
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN,
            assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
        for (measurements in listOf(false, true)) {
            val message = f.render(assessment = assessment, measurements = measurements)
            assertTrue(message.contains(if (measurements) "신뢰할 수 있는 최신 위치를 기다리고 있습니다."
                else "현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertFalse(message.contains("전체 점검이 만료"))
            assertFalse(message.contains("3.0m"))
        }
    }

    @Test fun recoveredRuntimeGpsRemovesOnlyTheGpsFailureAndKeepsOtherDetails() {
        val f = RuntimeGpsFixture()
        f.now += 1L
        val pass = f.gate.observe(f.sample(3.0), f.now)
        field(f.main, "officialEnvironmentCameraDiagnosticReason", CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name)
        field(f.main, "officialEnvironmentCameraDiagnosticObservation",
            CameraFrameQualityObservation(f.epoch, f.now, true, .1, 0.0, 0.0, 0.0))
        for (measurements in listOf(false, true)) {
            val message = f.render(pass, f.assessment(pass), measurements)
            assertFalse(message.contains("위치:"))
            assertFalse(message.contains("전체 점검이 만료"))
            assertTrue(message.contains("더 밝은 곳으로 이동하세요."))
            assertEquals(measurements, message.contains("현재 밝기 0.1"))
        }
    }

    @Test fun preflightPassAndImmediateActionsKeepTheirOriginalDescriptions() {
        val main = MainActivity()
        val epoch = WalkRuntimeEpoch("gps-preflight-description", 1L)
        val assessment = OfficialEnvironmentPolicy.assess(epoch, 1_000L, null, null, null, null)
        field(main, "officialEnvironmentGpsDiagnosticDetail", "GPS_QUALITY_PASSED")
        field(main, "officialEnvironmentCameraPreflightActive", true)
        assertTrue(detail(main, assessment, false).contains("정확도 기준을 통과했고 계속 갱신 중입니다."))
        field(main, "officialEnvironmentCameraPreflightActive", false)
        assertTrue(detail(main, assessment, true).contains("마지막 측정은 통과했지만 전체 점검이 만료"))
        for ((reason, action) in listOf(
            "LOCATION_PERMISSION_MISSING" to "위치 권한을 허용해 주세요.",
            "LOCATION_SERVICE_DISABLED" to "위치 서비스를 켠 뒤 다시 시도해 주세요.",
            "LOCATION_REQUEST_FAILED" to "위치 서비스를 확인한 뒤 다시 시도해 주세요.",
        )) {
            field(main, "officialEnvironmentGpsDiagnosticDetail", reason)
            for (measurements in listOf(false, true)) assertTrue(detail(main, assessment, measurements).contains(action))
        }
    }

    /** Actual policy + an explicit one-period representation input + lifecycle + Main formatter. */
    private fun assertRuntimeGpsFailureDescription(measurements: Boolean) {
        val f = RuntimeGpsFixture()
        val assessment = f.assessment()
        assertEquals(EnvironmentEvidenceStatus.FAIL, assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
        val message = f.render(assessment = assessment, measurements = measurements)
        assertFalse("A current runtime GPS failure is not an expired whole preflight: $message",
            message.contains("전체 점검이 만료"))
        assertTrue("The actual GPS accuracy failure must remain actionable: $message",
            message.contains("위치 오차가 커서"))
        assertFalse("The old passing accuracy is not a current measurement", message.contains("3.0m"))
        assertEquals(f.failed, f.gate.current(f.now))
        assertTrue(f.lifecycle.isRuntimeEpochCurrent(f.epoch))
    }

    private inner class RuntimeGpsFixture {
        val main = MainActivity()
        var now = 1_000L
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "runtime-gps-description" },
            clock = { now }, elapsedRealtimeClock = { now })
        val epoch: WalkRuntimeEpoch
        val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG))
            .officialEnvironment
        // Supply an already confirmed failure; this fixture tests its meaning and lifetime,
        // while Main's three-period GPS continuation window has separate integration coverage.
        val gate = RuntimeMeasuredQualityGate(maximumTransientFailureMs = 3_000L,
            gpsFailureConfirmationPeriods = 1)
        val failed: MeasuredEnvironmentEvidence
        init {
            field(main, "runtimeGpsQualityGate", gate)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch,
                WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
            field(main, "walkSessionLifecycle", lifecycle)
            // Preflight passed earlier; the runtime writer intentionally does not update these fields.
            field(main, "officialEnvironmentGpsDiagnosticDetail", "GPS_QUALITY_PASSED")
            field(main, "officialEnvironmentGpsDiagnosticAccuracyMeters", 3.0)
            val passed = sample(3.0)
            assertEquals(EnvironmentEvidenceStatus.PASS, gate.observe(passed, now).status)
            now = 1_100L
            assertEquals(passed, gate.observe(sample(30.0), now))
            now = 4_099L
            assertEquals(passed, gate.observe(sample(30.0), now))
            now = 4_100L
            failed = gate.observe(sample(30.0), now)
            assertEquals(EnvironmentEvidenceStatus.FAIL, failed.status)
        }
        fun sample(accuracy: Double) = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now,
            GpsQualityObservation(epoch, now, true, accuracy), profile)
        fun assessment(evidence: MeasuredEnvironmentEvidence = failed) =
            OfficialEnvironmentPolicy.assess(epoch, now, evidence, null, null, profile)
        fun render(evidence: MeasuredEnvironmentEvidence = failed,
            assessment: OfficialEnvironmentAssessment = assessment(evidence), measurements: Boolean = false): String {
            field(main, "officialEnvironmentGpsEvidence", evidence)
            return detail(main, assessment, measurements)
        }
    }

    private fun detail(main: MainActivity, assessment: OfficialEnvironmentAssessment, measurements: Boolean) =
        invoke(main, "officialEnvironmentMeasurementDetail", arrayOf(OfficialEnvironmentAssessment::class.java,
            java.lang.Boolean.TYPE), assessment, measurements)
    private fun profileMessage(main: MainActivity, profile: PedestrianMotionProfileSnapshot) =
        invoke(main, "walkingCalibrationProfileMessage", arrayOf(PedestrianMotionProfileSnapshot::class.java), profile)
    private fun invoke(main: MainActivity, name: String, parameters: Array<Class<*>>, vararg values: Any?): String =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(String::class.java, parameters))
            .bindTo(main).invokeWithArguments(values.toList()) as String
    private fun field(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.set(main, value)
    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun block(source: String, signature: String): String = source.substringAfter(signature)
        .substringBefore("\n    private ")
}
