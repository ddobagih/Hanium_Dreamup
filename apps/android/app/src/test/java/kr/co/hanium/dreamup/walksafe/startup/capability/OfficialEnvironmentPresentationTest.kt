package kr.co.hanium.dreamup.walksafe.startup.capability

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.environment.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual evidence store/normalization and formatter; view delivery is a controlled port. */
class OfficialEnvironmentPresentationTest {
    @Test fun constructorAndUnboundViewDoNotReadEvidenceOrRuntime() {
        val f = Fixture()
        assertEquals(0, f.reads)
        f.viewReady = false
        f.display.updateOfficialEnvironmentUi()
        assertEquals(0, f.reads)
        assertTrue(f.events.isEmpty())
    }
    @Test fun currentRuntimeFailureUsesItsActualReasonAndNeverOldPreflightAccuracy() {
        val f = Fixture(runtime = true)
        val pass = OfficialEnvironmentPolicy.assessGpsQuality(f.epoch, 1_000L,
            GpsQualityObservation(f.epoch, 1_000L, true, 1.0), f.profile)
        f.store.acceptGpsPreflightSample(pass, 1.0, 1_000L)
        f.runtimeGps(1_000L, 1.0)
        f.runtimeGps(2_000L, 90.0)
        assertEquals(EnvironmentEvidenceStatus.PASS, f.store.gpsEvidence?.status)
        f.runtimeGps(5_000L, 90.0)
        assertEquals(EnvironmentEvidenceStatus.FAIL, f.store.gpsEvidence?.status)
        assertEquals("GPS_QUALITY_PASSED", f.store.gpsDiagnosticDetail)
        val assessment = f.assessment()
        for (measurements in listOf(false, true)) {
            val message = f.display.officialEnvironmentMeasurementDetail(assessment, measurements)
            assertTrue(message, message.contains("위치: 위치 오차가 커서 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertFalse(message, message.contains("1.0m"))
            assertFalse(message, message.contains("전체 점검이 만료"))
        }
    }
    @Test fun staleWrongEpochAndWrongProfileEvidenceCannotExplainCurrentRuntimeFailure() {
        val f = Fixture(runtime = true)
        f.runtimeGps(1_000L, 90.0)
        val old = requireNotNull(f.store.gpsEvidence)
        for (evidence in listOf(old.copy(epoch = WalkRuntimeEpoch("other", 0)),
            old.copy(measurementProfileId = "other-profile"), old.copy(observedAtElapsedRealtimeMs = 0L))) {
            // The real preflight store accepts the supplied policy evidence; the formatter must normalize it.
            f.store.clearGpsEvidence()
            f.store.acceptGpsPreflightSample(evidence, 90.0, 20_000L)
            f.now = 20_000L
            val message = f.display.officialEnvironmentMeasurementDetail(f.assessment(), true)
            assertTrue(message, message.contains("신뢰할 수 있는 최신 위치를 기다리고 있습니다."))
            assertFalse(message, message.contains("위치 오차가 커서"))
            assertFalse(message, message.contains("전체 점검이 만료"))
        }
    }
    @Test fun correctiveSpeechSelectsOnlyTheActualBlockingFactor() {
        val f = Fixture()
        f.store.markGpsPreflightFailure("LOCATION_SERVICE_DISABLED")
        f.store.markCameraFailure(CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name)
        val assessment = f.assessment()
        val gps = f.display.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.GPS_QUALITY))
        assertTrue(gps.contains("위치 서비스를 켠 뒤")); assertFalse(gps.contains("카메라:"))
        val camera = f.display.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
        assertTrue(camera.contains("더 밝은 곳으로 이동하세요.")); assertFalse(camera.contains("위치:"))
        f.store.markCameraFailure("UNKNOWN")
        val unknown = f.display.officialEnvironmentFailureSpeech(assessment, setOf(OfficialEnvironmentFactor.CAMERA_QUALITY))
        assertTrue(unknown.contains("카메라를 다시 점검해 주세요")); assertFalse(unknown.contains("가려졌"))
        val passed = assessment.copy(factorStatuses = assessment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS })
        assertEquals("", f.display.officialEnvironmentFailureSpeech(passed))
    }
    @Test fun sameMessageAvoidsStatusRewriteButAlwaysUpdatesCurrentButtonState() {
        val f = Fixture(); f.display.updateOfficialEnvironmentUi()
        assertEquals(listOf("message", "enabled:true", "button"), f.events)
        assertEquals("환경 확인", f.buttonText)
        f.events.clear(); f.foreground = false
        f.display.updateOfficialEnvironmentUi()
        assertEquals(listOf("enabled:false", "button"), f.events)
    }

    @Test fun terminalRetryLimitKeepsMountCauseActionAndCameraLimitsAcrossCameraContexts() {
        for (pitch in listOf<Double?>(null, 90.0, 0.0)) {
            val f = Fixture()
            val profiles = WalkSafeEnvironmentProfiles.testCandidate
            val failedObservation = CameraFrameQualityObservation(f.epoch, f.now, true, 0.5, 0.0, null, 90.0)
            val failed = CameraFrameQualityPolicy.assess(f.epoch, f.now, failedObservation, profiles.cameraFrameQuality)
            assertEquals(CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE, failed.reason)
            var mounting = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, f.epoch, f.now,
                null, failed, PhoneMountingPolicy.initialState(f.epoch, profiles.phoneMounting))
            assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, mounting.status)
            repeat(profiles.phoneMounting.maximumRuntimeRetryAttempts) {
                mounting = PhoneMountingPolicy.assess(PhoneMountingAssessmentPhase.ACTIVE, f.epoch, f.now,
                    null, failed, mounting.nextState, runtimeRetryRequested = true)
            }
            assertEquals(PhoneMountingStatus.UNUSABLE, mounting.status)
            assertEquals(PhoneMountingReason.RETRY_LIMIT_REACHED, mounting.reason)
            assertEquals(profiles.phoneMounting.maximumRuntimeRetryAttempts, mounting.nextState.failedRuntimeRetryAttempts)
            if (pitch != null) {
                val observation = failedObservation.copy(mountPitchDegrees = pitch,
                    normalizedBrightness = if (pitch == 0.0) 0.0 else 0.5)
                val camera = CameraFrameQualityPolicy.assess(f.epoch, f.now, observation, profiles.cameraFrameQuality)
                assertEquals(EnvironmentEvidenceStatus.FAIL, camera.status)
                val evidence = camera.asMeasuredEnvironmentEvidence()
                f.store.recordCameraDiagnostic(observation, evidence, null)
                f.store.commitCameraEvidence(evidence)
            }
            val environment = f.assessment()
            assertEquals(if (pitch == null) EnvironmentEvidenceStatus.UNKNOWN else EnvironmentEvidenceStatus.FAIL,
                environment.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
            assertTrue(OfficialEnvironmentFactor.CAMERA_QUALITY in environment.blockingFactors)
            val detail = "${mounting.accessibleReasonKo} ${mounting.accessibleActionKo} " +
                "카메라 인식·거리·위험 안내를 제한합니다. 위치 품질이 확인되면 경로 안내는 계속됩니다."
            for (context in listOf(environment, environment.copy(epoch = WalkRuntimeEpoch("retired-camera", 0)),
                environment.copy(factorStatuses = environment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS }))) {
                val message = f.display.phoneMountingFailureSpeech(mounting, context)
                assertEquals(detail, message)
                assertTrue(message.contains(mounting.accessibleReasonKo))
                assertTrue(message.contains(mounting.accessibleActionKo))
                assertFalse(message.contains("카메라: 상태를 확인하지 못했습니다"))
                assertFalse(message.contains("화면에서 장착 교정을 확인하세요."))
            }
        }
    }

    @Test fun nonterminalMountingKeepsCameraActionPriorityAndExplicitCorrectionOnlyWhenRequired() {
        for (pitch in listOf(0.0, 90.0)) {
            val f = Fixture()
            val profiles = WalkSafeEnvironmentProfiles.testCandidate
            val observation = CameraFrameQualityObservation(f.epoch, f.now, true,
                if (pitch == 0.0) 0.0 else 0.5, 0.0, null, pitch)
            val camera = CameraFrameQualityPolicy.assess(f.epoch, f.now, observation, profiles.cameraFrameQuality)
            assertEquals(EnvironmentEvidenceStatus.FAIL, camera.status)
            val evidence = camera.asMeasuredEnvironmentEvidence()
            f.store.recordCameraDiagnostic(observation, evidence, null)
            f.store.commitCameraEvidence(evidence)
            val environment = f.assessment()
            val cameraAction = if (pitch == 0.0) "카메라: 밝기가 기준에 부족합니다. 더 밝은 곳으로 이동하세요."
                else "카메라: 정면 각도가 기준에 맞지 않습니다. 휴대전화 뒷면 카메라가 앞을 향하도록 세우세요."
            for (phase in PhoneMountingAssessmentPhase.entries) {
                val mounting = PhoneMountingPolicy.assess(phase, f.epoch, f.now,
                    PhoneMountingUserConfirmation(f.epoch, f.now, PhoneMountingMethod.CHEST_FORWARD, false),
                    camera, PhoneMountingPolicy.initialState(f.epoch, profiles.phoneMounting))
                assertEquals(PhoneMountingStatus.CORRECTION_REQUIRED, mounting.status)
                val explicit = phase == PhoneMountingAssessmentPhase.ACTIVE && pitch == 90.0
                if (phase == PhoneMountingAssessmentPhase.ACTIVE) assertEquals(explicit, mounting.nextState.requiresExplicitCorrection)
                assertEquals(cameraAction + if (explicit) " 화면에서 장착 교정을 확인하세요." else "",
                    f.display.phoneMountingFailureSpeech(mounting, environment))
                val clearCamera = environment.copy(factorStatuses = environment.factorStatuses.mapValues { EnvironmentEvidenceStatus.PASS })
                assertEquals(mounting.spokenGuidanceKo, f.display.phoneMountingFailureSpeech(mounting, clearCamera))
            }
        }
    }

    private class Fixture(runtime: Boolean = false) {
        var now = 1_000L; var reads = 0; var viewReady = true; var foreground = true
        var message = ""; var buttonText = ""; val events = mutableListOf<String>()
        val profile = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
        val lifecycle = WalkSessionLifecycle({ "environment-display" }, { now }, { now }).apply {
            handle(WalkSessionEvent.EnteredForeground)
            if (runtime) {
                val collector = WalkSessionReadinessCollector(snapshot().epoch, WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL))
                collector.plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(snapshot().epoch, it, WalkSessionReadinessStatus.READY)) }
                handle(WalkSessionEvent.InitialCheckCompleted(collector.build(now)))
                handle(WalkSessionEvent.StartRequested(requireNotNull(snapshot().confirmationToken)))
            }
        }
        val epoch get() = lifecycle.snapshot().epoch
        val store = OfficialEnvironmentEvidenceStore(Any(), RuntimeMeasuredQualityGate(3_000L), RuntimeMeasuredQualityGate())
        val display = OfficialEnvironmentPresentation(object : OfficialEnvironmentDisplayReader {
            override val profile get() = this@Fixture.profile.also { reads++ }
            override val cameraProfile get() = WalkSafeEnvironmentProfiles.testCandidate.cameraFrameQuality
            override val approvedForProduction: Boolean? = true
            override val preflightActive = false
            override val phase = OfficialEnvironmentPreflightPhase.IDLE
            override val lifecycleAvailable get() = true.also { reads++ }
            override val foreground get() = this@Fixture.foreground
            override val bypass = false
            override val cameraCleanupPending = false
            override fun runtimeEpochOrNull() = lifecycle.currentRuntimeEpochOrNull()
            override fun snapshot() = lifecycle.snapshot()
            override fun assessment(epoch: WalkRuntimeEpoch) = this@Fixture.assessment()
            override fun locationFeatureEnabled() = true
            override fun cameraFeaturesEnabled() = true
            override fun onboardingComplete() = true
        }, { store.also { reads++ } }, object : OfficialEnvironmentDisplayView {
            override fun ready() = viewReady
            override fun currentMessage() = message
            override fun showMessage(message: String) { this@Fixture.message = message; events += "message" }
            override fun setConfirmEnabled(enabled: Boolean) { events += "enabled:$enabled" }
            override fun setConfirmTextAndDescription(message: String) { buttonText = message; events += "button" }
        }, "지원 범위", "환경 확인")
        fun assessment() = OfficialEnvironmentPolicy.assess(epoch, now, store.gpsEvidence, store.cameraEvidence, null, profile)
        fun runtimeGps(at: Long, accuracy: Double) {
            now = at
            store.recordGpsObservation(epoch, epoch, at, true, accuracy, { now }, { profile },
                { lifecycle.isRuntimeEpochCurrent(it) }, { epoch }, { false }, { true }, false, { _, _, _ -> })
        }
    }
}
