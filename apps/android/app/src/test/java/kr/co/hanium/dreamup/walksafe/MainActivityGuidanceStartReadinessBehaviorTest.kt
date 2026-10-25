package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main sample acceptance/stability bodies. CameraX, fused location and View rendering are
 * SDK boundaries checked by production wiring below, not executed as Android behavior here. */
class MainActivityGuidanceStartReadinessBehaviorTest {
    private val epoch = WalkSessionLifecycle(sessionIdFactory = { "start-readiness" }).snapshot().epoch
    private val gpsProfile = ApprovedOfficialEnvironmentProfile("gps", "camera", 15.0, 10_000L, 2)
    private val cameraProfile = ApprovedCameraFrameQualityProfile("camera", 0.2, 0.5, 15.0,
        -30.0, 30.0, 10_000L)

    @Test fun freshCachedGpsKeepsItsOriginalTimeAndStillPassesTheActualQualityPolicy() {
        val main = MainActivity()
        assertEquals(10_000L, main.officialEnvironmentGpsPreflightMaximumAgeMs(gpsProfile))
        val evidence = requireNotNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 20_000L,
            sample(observedAt = 10_000L), gpsProfile))
        assertEquals(EnvironmentEvidenceStatus.PASS, evidence.status)
        assertEquals(10_000L, evidence.observedAtElapsedRealtimeMs)
        assertEquals(epoch, evidence.epoch)
        assertEquals(gpsProfile.profileId, evidence.measurementProfileId)
        assertEquals("GPS_QUALITY_PASSED", evidence.detail)
    }

    @Test fun gpsCacheUsesTheSmallerDeviceAndProfileLimitIncludingItsExactBoundary() {
        val main = MainActivity()
        val longProfile = gpsProfile.copy(maximumMeasuredEvidenceAgeMs = 60_000L)
        assertEquals(30_000L, main.officialEnvironmentGpsPreflightMaximumAgeMs(longProfile))
        assertNotNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 40_000L,
            sample(10_000L), longProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 40_001L,
            sample(10_000L), longProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 20_001L,
            sample(10_000L), gpsProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 9_999L,
            sample(10_000L), gpsProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, -1L, sample(0L), gpsProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, 1L, sample(-1L), gpsProfile))
        assertNull(main.assessOfficialEnvironmentGpsPreflightSample(epoch, Long.MAX_VALUE,
            sample(0L), gpsProfile))
    }

    @Test fun cachedGpsCannotBypassMockCoordinatesAccuracyOrApprovedProfile() {
        val main = MainActivity()
        val cases = listOf(sample().copy(mock = true), sample().copy(latitude = Double.NaN),
            sample().copy(longitude = 181.0), sample().copy(horizontalAccuracyMeters = null),
            sample().copy(horizontalAccuracyMeters = 15.1))
        for (sample in cases) {
            assertNotEquals(EnvironmentEvidenceStatus.PASS,
                main.assessOfficialEnvironmentGpsPreflightSample(epoch, 20_000L, sample, gpsProfile)?.status)
        }
        assertEquals(0L, main.officialEnvironmentGpsPreflightMaximumAgeMs(null))
        assertNotEquals(EnvironmentEvidenceStatus.PASS,
            main.assessOfficialEnvironmentGpsPreflightSample(epoch, 10_000L, sample(), null)?.status)
    }

    @Test fun initialCameraSamplesNeedThreeActualPassesBeforeNormalMonitoringCadence() {
        val main = MainActivity()
        assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_000L)))
        assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_100L)))
        assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.ACCEPT, main.observePreflightCameraStability(camera(1_200L)))
        assertEquals(500L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS,
            main.observePreflightCameraStability(camera(1_700L, brightness = 0.1)))
        assertEquals(500L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.ACCEPT,
            main.observePreflightCameraStability(camera(2_200L, brightness = 0.1)))
        assertEquals("A later failure must not accelerate the failure cadence", 500L,
            main.cameraPreflightAnalysisIntervalMs())
    }

    @Test fun anInitialCameraFailureResetsPassCountAndANewAttemptResetsTheFastAcquisition() {
        val main = MainActivity()
        main.observePreflightCameraStability(camera(1_000L))
        assertEquals(CameraPreflightStabilityDecision.ACCEPT,
            main.observePreflightCameraStability(camera(1_100L, occlusion = 0.8)))
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_200L)))
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_300L)))
        assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        main.observePreflightCameraStability(camera(1_400L))
        assertEquals(500L, main.cameraPreflightAnalysisIntervalMs())
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup()).findVirtual(
            MainActivity::class.java, "resetPrewalkCameraEvidenceForNewAttempt", MethodType.methodType(Void.TYPE))
            .invokeWithArguments(main)
        assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_500L)))
    }

    @Test fun fastCameraAcquisitionRetainsBrightnessOcclusionAngleAndUnknownChecks() {
        val main = MainActivity()
        for (assessment in listOf(camera(1_000L, brightness = 0.1), camera(1_100L, occlusion = 0.8),
            camera(1_200L, pitch = 50.0), camera(1_300L, pitch = null))) {
            assertNotEquals(EnvironmentEvidenceStatus.PASS, assessment.status)
            main.observePreflightCameraStability(assessment)
            assertEquals(100L, main.cameraPreflightAnalysisIntervalMs())
        }
        assertEquals(CameraPreflightStabilityDecision.STABILIZING, main.observePreflightCameraStability(camera(1_400L)))
    }

    @Test fun productionCallbacksKeepOriginalTimestampRequestOwnershipAndThreeActualFrames() {
        val source = source()
        val controller = production("device/environment/OfficialEnvironmentPreflightController.kt")
        val sdk = production("device/environment/AndroidOfficialEnvironmentPreflightSources.kt")
        val evidence = production("device/environment/OfficialEnvironmentEvidenceStore.kt")
        val request = MainActivityRefactorSource.member(controller, "requestGps")
        assertTrue(MainActivityRefactorSource.member(source, "requestOfficialEnvironmentGpsPreflight")
            .contains("officialEnvironmentPreflightController.requestGps(epoch, clearExistingEvidence)"))
        assertTrue(request.contains("facts.profile() == requestedProfile"))
        assertTrue(request.contains("gateway == requestedOwner && gateway.usable"))
        assertTrue(request.contains("facts.locationPermission() && facts.locationServiceEnabled()"))
        assertTrue(request.contains("if (requestCurrent()) observeGpsSample(epoch, sample, requestedProfile)"))
        val owner = controller.substringAfter("internal class OfficialEnvironmentGpsOwner")
        assertTrue(owner.contains("generation == other.generation && sessionIdentity === other.sessionIdentity"))
        val binding = MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController")
        assertTrue(binding.contains("OfficialEnvironmentGpsOwner(it.generation, it.session"))
        assertTrue(binding.contains("{ officialEnvironmentCameraSource }, { officialEnvironmentGpsSource }"))
        assertTrue(sdk.contains("setMaxUpdateAgeMillis(options.maximumAgeMs)"))
        assertTrue(sdk.contains("maximumAgeMs = minOf(DeviceCheckLocationFixPolicy.MAX_AGE_MS, profile?.maximumMeasuredEvidenceAgeMs ?: 0L)"))
        assertTrue(sdk.contains("location.elapsedRealtimeNanos, location.latitude, location.longitude"))
        assertTrue(sdk.contains("OfficialEnvironmentGpsSample(observedAtElapsedRealtimeNs / 1_000_000L,"))
        assertTrue(sdk.contains("if (current()) onSample(it.observation())"))
        val observe = MainActivityRefactorSource.member(controller, "observeGpsSample")
        assertTrue(observe.indexOf("evidence.assessGpsPreflightSample(") >= 0)
        assertTrue(observe.indexOf("evidence.assessGpsPreflightSample(") < observe.indexOf("evidence.acceptGpsPreflightSample("))
        assertTrue(MainActivityRefactorSource.member(evidence, "assessGpsPreflightSample")
            .contains("val observedAtMs = sample.observedAtElapsedRealtimeMs"))
        assertTrue(MainActivityRefactorSource.member(evidence, "acceptGpsPreflightSample").contains("gpsEvidence = selected"))
        val camera = MainActivityRefactorSource.member(controller, "startCamera")
        assertTrue(camera.contains("at - cameraLastAnalysisMs < cameraAnalysisIntervalMs()"))
        val analyzer = sdk.substringAfter("override fun start(epoch: WalkRuntimeEpoch, generation: Long")
            .substringBefore("override fun stopMountingSensorIfInitialized")
        assertTrue(analyzer.contains("finally { image.close() }"))
        assertTrue(analyzer.indexOf("!acceptAnalysisAt(now)") >= 0)
        assertTrue(analyzer.indexOf("!acceptAnalysisAt(now)") < analyzer.indexOf("OfficialEnvironmentCameraObservationFactory.build("))
        assertTrue(MainActivityRefactorSource.member(source, "observePreflightCameraStability")
            .contains("officialEnvironmentPreflightController.observeCameraStability(assessment)"))
        assertTrue(binding.contains("CameraPreflightStabilityGate(OFFICIAL_ENVIRONMENT_REQUIRED_CONSECUTIVE_CAMERA_PASSES"))
        assertTrue(source.contains("OFFICIAL_ENVIRONMENT_REQUIRED_CONSECUTIVE_CAMERA_PASSES = 3"))
        assertTrue(source.contains("OFFICIAL_ENVIRONMENT_CAMERA_PREFLIGHT_INTERVAL_MS = 500L"))
    }

    @Test fun guidancePreparationShowsDestinationStatusAndEndActionWithoutMeasurementOrProgressSpeech() {
        val source = source()
        val visibility = MainActivityRefactorSource.member(production("ui/NativeScreenPresenter.kt"), "applyVoiceFirstPresentation")
        assertTrue(MainActivityRefactorSource.member(source, "applyNativeVoiceFirstPresentation")
            .contains("nativeScreenPresenter.applyVoiceFirstPresentation("))
        assertTrue(visibility.contains("val guidanceVisible = pageName == \"GUIDANCE\""))
        val shown = visibility.substringAfter("val shown = guidanceVisible && (").substringBefore("val visibility")
        assertTrue(shown.replace(Regex("\\s+"), "") == "it===destination.nativeGuidanceDestinationText||it===destination.nativeGuidanceStatusText)")
        assertTrue(shown.contains("it === destination.nativeGuidanceDestinationText"))
        assertTrue(shown.contains("it === destination.nativeGuidanceStatusText"))
        assertFalse(shown.contains("nativeGuidanceMeasurementText"))
        assertTrue(visibility.contains("val visibility = if (shown) View.VISIBLE else View.GONE"))
        assertTrue(visibility.contains("val importance = if (shown) View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(visibility.contains("else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS"))
        assertTrue(visibility.contains("if (it.visibility != visibility) it.visibility = visibility"))
        assertTrue(visibility.contains("if (it.importantForAccessibility != importance) it.importantForAccessibility = importance"))
        assertTrue(visibility.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(visibility.substringBefore(").forEach").contains("nativeGuidanceMeasurementText"))
        val button = MainActivityRefactorSource.property(production("ui/DestinationGuidancePanel.kt"), "nativeGuidanceCancelButton")
        assertTrue(button.contains("label = \"안내 종료\""))
        assertTrue(button.contains("onClick = actions.onGuidanceCancel"))
        assertTrue(MainActivityRefactorSource.member(source, "buildContentView")
            .contains("onGuidanceCancel = ::cancelNativeGuidanceAndReturnHome"))
        val request = MainActivityRefactorSource.function(source, "private fun requestRoute(")
        assertFalse(request.contains("speakStatusExplanation(detail)"))
        assertFalse(request.contains("speakInteraction(\"현재 위치와 경로 연결을 기다립니다."))
        assertTrue(request.contains("presentation.speak(navigationFailure.routeUserMessage)"))
        assertTrue(MainActivityRefactorSource.property(source, "routePresentation")
            .contains("override fun speak(message: String) { speakInteraction(message) }"))
        assertTrue(MainActivityRefactorSource.factory(source, "createNavigationSessions")
            .contains("presentation = { routePresentation }"))
        val status = MainActivityRefactorSource.member(source, "updateNativeGuidanceStatus")
        assertTrue(status.contains("nativeGuidanceStatusMessage(presentation, false)"))
    }

    private fun production(relative: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/" + relative).readText()

    private fun sample(observedAt: Long = 10_000L) = OfficialEnvironmentGpsSample(observedAt,
        37.0, 127.0, 5.0, false)

    private fun camera(at: Long, brightness: Double = 0.5, occlusion: Double = 0.1,
        pitch: Double? = 0.0): CameraFrameQualityAssessment = CameraFrameQualityPolicy.assess(epoch, at,
        CameraFrameQualityObservation(epoch, at, true, brightness, occlusion, null, pitch), cameraProfile)

    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
}
