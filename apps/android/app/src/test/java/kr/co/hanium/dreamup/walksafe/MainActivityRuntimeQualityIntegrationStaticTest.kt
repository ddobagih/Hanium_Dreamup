package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRuntimeQualityIntegrationStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun transientGatesUseRealEvidenceAndResetAtSessionBoundaries() {
        val activation = function("activateOfficialEnvironmentRuntime")
        val invalidation = function("invalidateOfficialEnvironmentEvidence")
        listOf("runtimeGpsQualityGate", "runtimeCameraQualityGate").forEach { gate ->
            assertTrue(activation.contains("$gate.reset()"))
            assertTrue(activation.contains("$gate.observe(it, nowMs)"))
            assertTrue(invalidation.contains("$gate.reset()"))
        }
        val gps = function("recordOfficialEnvironmentGpsObservation")
        assertTrue(gps.contains("runtimeGpsQualityGate.observe(rawEvidence, nowMs)"))
        assertTrue(gps.contains("walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertFalse(gps.contains("observedAtElapsedRealtimeMs = nowMs"))
    }

    @Test
    fun cameraGraceIsAppliedBeforeMountingFaultCanRevokeOutputs() {
        val camera = function("observeOfficialEnvironmentCameraFrame")
        val selection = camera.indexOf("runtimeCameraQualityGate.observe(rawEvidence,")
        val fault = camera.indexOf("mountingFault = cameraAssessment.status")
        val revocation = camera.indexOf("phoneMountingOutputsAllowed = false")
        assertTrue(selection >= 0 && fault > selection && revocation > fault)
        assertTrue(camera.contains("latestRuntimeRawCameraAssessment = cameraAssessment"))
        assertTrue(camera.contains("it.observedAtElapsedRealtimeMs == evidence.observedAtElapsedRealtimeMs"))
        assertTrue(camera.contains("cameraFrameQualityOverride = cameraAssessment"))
        assertTrue(camera.contains("nowElapsedRealtimeMs = SystemClock.elapsedRealtime()"))
    }

    @Test
    fun qualityDeadlineAlsoExpiresWhenTheNextSensorCallbackNeverArrives() {
        val current = function("currentOfficialEnvironmentAssessment")
        val watchdog = function("scheduleOfficialEnvironmentRuntimeWatchdog")
        assertTrue(current.contains("runtimeGpsQualityGate.current(nowElapsedRealtimeMs)"))
        assertTrue(current.contains("runtimeCameraQualityGate.current(nowElapsedRealtimeMs)"))
        assertTrue(current.contains("latestPhoneMountingCameraAssessment = it"))
        assertTrue(watchdog.contains("runtimeGpsQualityGate.pendingFailureDeadlineElapsedRealtimeMs()"))
        assertTrue(watchdog.contains("runtimeCameraQualityGate.pendingFailureDeadlineElapsedRealtimeMs()"))
        assertTrue(watchdog.contains("qualityFailureDelayMs"))
        assertTrue(watchdog.contains("profile.runtimeRetryIntervalMs"))
        assertTrue(watchdog.contains("evidence.factor !in unavailableFactors"))
        assertTrue(function("applyCurrentOfficialEnvironmentRuntimeAssessment")
            .contains("applyCurrentPhoneMountingRuntimeAssessment(runtimeRetryRequested = false)"))
    }

    @Test
    fun independentFeaturesUseSeparateGatesAndReportsRequireBoth() {
        val navigation = function("navigationEnvironmentOutputsAllowed")
        val camera = function("cameraEnvironmentOutputsAllowed")
        val report = function("walkSafetyOutputsAllowed")
        assertTrue(navigation.contains("decision()?.navigationOutputsAllowed == true"))
        assertFalse(navigation.contains("phoneMountingOutputsAllowed"))
        assertTrue(camera.contains("decision()?.cameraOutputsAllowed == true"))
        assertTrue(camera.contains("phoneMountingOutputsAllowed"))
        assertTrue(report.contains("navigationEnvironmentOutputsAllowed() && cameraEnvironmentOutputsAllowed()"))
        assertTrue(function("dispatchNavigationSpeech").contains("navigationEnvironmentOutputsAllowed()"))
        assertTrue(function("currentRuntimeMetricOutputAllowsWork").contains("cameraEnvironmentOutputsAllowed()"))
        assertTrue(function("processReportCandidate").contains("!walkSafetyOutputsAllowed()"))
        val assessment = function("currentOfficialEnvironmentAssessment")
        assertTrue(assessment.contains("enabledMeasuredFactors = enabledMeasuredFactors,"))
        assertFalse(assessment.contains("featureAdjusted"))
    }

    @Test
    fun runtimeGuardReceivesMountingAvailabilityWithoutChangingTheCameraMeasurement() {
        val assessment = function("currentOfficialEnvironmentAssessment")
        val runtimeAvailability = assessment.substringAfter("val runtimeUnavailableMeasuredFactors =")
        assertTrue(runtimeAvailability.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(runtimeAvailability.contains("walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(runtimeAvailability.contains("phoneMountingRuntimeState?.epoch == epoch && !phoneMountingOutputsAllowed"))
        assertTrue(runtimeAvailability.contains("setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)"))
        assertTrue(runtimeAvailability.contains("runtimeUnavailableMeasuredFactors = runtimeUnavailableMeasuredFactors,"))
        assertFalse(runtimeAvailability.contains("factorStatuses ="))
        assertFalse(runtimeAvailability.contains("EnvironmentEvidenceStatus.FAIL"))
        val camera = function("observeOfficialEnvironmentCameraFrame")
        assertTrue(camera.contains("shouldReassessRuntime || runtimeRetryRequested && assessment != null"))
    }

    @Test
    fun cameraHandoffAndMountingFaultLeaveTheIndependentRouteIntact() {
        val handoff = function("beginRuntimeCameraHandoff")
        assertFalse(handoff.contains("officialEnvironmentOutputsAllowed = false"))
        assertTrue(handoff.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(handoff.contains("runtimeCameraQualityGate.reset()"))
        val mounting = function("applyPhoneMountingRuntimeAssessment")
        assertFalse(mounting.contains("cancelActiveRouteRequest()"))
        assertFalse(mounting.contains("cancelDestinationSearch()"))
        assertFalse(mounting.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(mounting.contains("invalidatePhoneMountingDetectionOutputs(epoch)"))
        val decision = function("applyOfficialEnvironmentRuntimeDecision")
        assertTrue(decision.contains("decision.unavailableFactors -"))
        assertTrue(decision.contains("lastOfficialEnvironmentRuntimeAction != decision.action"))
        assertFalse(decision.contains("decision.consecutiveDegradations == 1"))
        assertTrue(decision.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
    }

    private fun function(name: String): String = source
        .substringAfter("private fun $name(", missingDelimiterValue = "")
        .substringBefore("\n    private fun ")
        .also { check(it.isNotEmpty()) { "Missing function $name" } }
}
