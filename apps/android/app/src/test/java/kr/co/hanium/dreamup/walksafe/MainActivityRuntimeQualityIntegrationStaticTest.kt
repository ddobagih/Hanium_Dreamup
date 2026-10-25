package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityRuntimeQualityIntegrationStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val evidence = source("device/environment/OfficialEnvironmentEvidenceStore")
    private val runtime = source("device/environment/OfficialEnvironmentRuntimeController")
    private val camera = source("device/environment/OfficialEnvironmentCameraObserver")
    private fun body(text: String, name: String) = MainActivityRefactorSource.member(text, name)
    @Test fun transientGatesUseRealEvidenceAndResetAtSessionBoundaries() {
        assertTrue(main.contains("officialEnvironmentRuntimeController.activate()"))
        assertTrue(body(runtime, "activate").contains("evidence.prepareGpsForRuntime("))
        assertTrue(body(runtime, "activate").contains("evidence.seedRuntimeCameraHistory("))
        val handoff = body(evidence, "prepareGpsForRuntime")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(handoff, "if (continuousSourcePresent())",
            "continuousGpsEvidence(epoch(), nowMs)", "return", "gpsQualityGate.reset()", "gpsQualityGate.observe(it, nowMs)"))
        val invalidation = body(source("device/environment/OfficialEnvironmentController"), "invalidate")
        listOf("effects.clearContinuousGpsSourceAndRecovery()", "evidence.gpsQualityGate.reset()",
            "evidence.resetRuntimeRawCameraAssessment()").forEach { assertTrue(invalidation.contains(it)) }
        val seed = body(evidence, "seedRuntimeCameraHistory")
        listOf("resetRuntimeRawCameraAssessment()", "cameraQualityGate.observe(it, nowMs)",
            "observeRuntimeRawCameraAssessment(it, nowMs)").forEach { assertTrue(seed.contains(it)) }
        val reset = body(evidence, "resetRuntimeRawCameraAssessment")
        listOf("synchronized(observationLock)", "cameraQualityGate.reset()", "latestRuntimeRawCameraAssessment = null")
            .forEach { assertTrue(reset.contains(it)) }
        val adapter = body(main, "recordOfficialEnvironmentGpsObservation")
        assertTrue(adapter.contains("observedAtElapsedRealtimeMs")); assertTrue(adapter.contains("SystemClock::elapsedRealtime"))
        assertTrue(main.contains("officialEnvironmentEvidenceStore.recordGpsObservation("))
        val gps = body(evidence, "recordGpsObservation")
        listOf("if (currentEpoch != epoch) return", "GpsQualityObservation(epoch, observedAtMs,",
            "active || continuousEpoch() == epoch", "gpsQualityGate.observe(rawEvidence, measuredAtMs)")
            .forEach { assertTrue(it, gps.contains(it)) }
        assertFalse(gps.contains("observedAtMs = measuredAtMs"))
    }
    @Test fun cameraGraceIsAppliedBeforeMountingFaultCanRevokeOutputs() {
        assertTrue(main.contains("officialEnvironmentCameraObserver.observe("))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(camera,
            "evidence.observeRuntimeRawCameraAssessment(assessment, now)", "evidence.cameraQualityGate.current(now)",
            "mountingFault = assessment.status", "mounting.commitCameraAssessment("))
        val raw = body(evidence, "observeRuntimeRawCameraAssessment")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(raw, "cameraQualityGate.observe(candidate, nowMs)",
            "cameraQualityGate.latestRawObservation()", "latestRuntimeRawCameraAssessment = when", "latest == candidate -> assessment"))
        assertTrue(camera.contains("it.observedAtElapsedRealtimeMs == admitted.observedAtElapsedRealtimeMs"))
        assertTrue(camera.contains("mounting.applyRuntimeCameraAssessment(retry, assessment, sequence, observationGeneration, mountingFault)"))
        assertTrue(camera.contains("CameraFrameQualityPolicy.assess(epoch, nowMs(), observation"))
    }
    @Test fun qualityDeadlineAlsoExpiresWhenTheNextSensorCallbackNeverArrives() {
        val current = body(evidence, "currentAssessment")
        assertTrue(current.contains("gpsQualityGate.current(nowMs)")); assertTrue(current.contains("cameraQualityGate.current(nowMs)"))
        assertTrue(current.contains("?.let(updateMountingAssessment)"))
        val watchdog = body(runtime, "scheduleWatchdog")
        listOf("evidence.gpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs()",
            "evidence.cameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs()", "qualityFailureDelayMs", "profile.runtimeRetryIntervalMs")
            .forEach { assertTrue(it, watchdog.contains(it)) }
        assertTrue(runtime.contains("raw.factor !in decision.unavailableFactors"))
        assertTrue(body(runtime, "applyCurrentAssessment").contains("effects.reassessMountingWithoutExplicitRetry()"))
        assertTrue(main.contains("applyCurrentPhoneMountingRuntimeAssessment(runtimeRetryRequested = false)"))
    }
    @Test fun independentFeaturesUseSeparateGatesAndReportsRequireBoth() {
        val navigation = body(main, "navigationEnvironmentOutputsAllowed")
        val cameraGate = body(main, "cameraEnvironmentOutputsAllowed")
        assertTrue(navigation.contains("decision()?.navigationOutputsAllowed == true")); assertFalse(navigation.contains("phoneMountingOutputsAllowed"))
        assertTrue(cameraGate.contains("decision()?.cameraOutputsAllowed == true")); assertTrue(cameraGate.contains("phoneMountingOutputsAllowed"))
        assertTrue(body(main, "walkSafetyOutputsAllowed").contains("navigationEnvironmentOutputsAllowed() && cameraEnvironmentOutputsAllowed()"))
        assertTrue(source("voice/output/SpeechOutputController").contains("reader.navigationOwnerIsCurrent(owner)"))
        val speechAdmission = main.substringAfter("override fun navigationOwnerIsCurrent(").substringBefore("override fun homeFeatureContextAvailable")
        assertTrue(speechAdmission.contains("navigationEnvironmentOutputsAllowed()"))
        assertTrue(body(source("runtime/metric/RuntimeMetricSessionController"), "currentRuntimeMetricOutputAllowsWork").contains("scope.cameraOutputsAllowed"))
        assertTrue(main.contains("override val cameraOutputsAllowed get() = cameraEnvironmentOutputsAllowed()"))
        assertTrue(source("report/runtime/ReportCandidateController").contains("!authority.walkSafetyOutputsAllowed()"))
        assertTrue(body(evidence, "currentAssessment").contains("enabledMeasuredFactors = enabled"))
        assertFalse(body(evidence, "currentAssessment").contains("featureAdjusted"))
    }
    @Test fun runtimeGuardReceivesMountingAvailabilityWithoutChangingTheCameraMeasurement() {
        val current = body(evidence, "currentAssessment")
        val availability = current.substringAfter("val (runtimeUnavailable, recoverableUnavailable) =")
        listOf("synchronized(observationLock)", "facts.isRuntimeEpochCurrent(epoch)", "facts.mountingCameraRestricted(epoch)",
            "setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)", "runtimeUnavailableMeasuredFactors = runtimeUnavailable")
            .forEach { assertTrue(it, availability.contains(it)) }
        assertFalse(availability.contains("factorStatuses =")); assertFalse(availability.contains("EnvironmentEvidenceStatus.FAIL"))
        assertTrue(camera.contains("reassess || retry && applied"))
    }
    @Test fun cameraHandoffAndMountingFaultLeaveTheIndependentRouteIntact() {
        val mounting = source("session/mounting/PhoneMountingSessionController")
        val handoff = body(mounting, "beginRuntimeCameraHandoff")
        assertFalse(handoff.contains("officialEnvironmentOutputsAllowed = false"))
        assertTrue(handoff.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(handoff.contains("resetRuntimeRawCameraAssessment()"))
        val apply = body(mounting, "applyPhoneMountingRuntimeAssessment")
        listOf("cancelActiveRouteRequest()", "cancelDestinationSearch()", "enterWalkSessionSafetyStopAndCancelOutputs(")
            .forEach { assertFalse(apply.contains(it)) }
        assertTrue(apply.contains("revokeReportOutputLease()"))
        assertTrue(body(mounting, "revokeReportOutputLease").contains("effects.revokeReportOutputs()"))
        assertTrue(main.contains("override fun revokeReportOutputs()"))
        val decision = body(runtime, "applyDecision")
        assertTrue(decision.contains("decision.unavailableFactors -"))
        assertTrue(decision.contains("lastAction != decision.action"))
        assertFalse(decision.contains("decision.consecutiveDegradations == 1"))
        assertTrue(decision.contains("effects.enterSafetyStop("))
    }
}
