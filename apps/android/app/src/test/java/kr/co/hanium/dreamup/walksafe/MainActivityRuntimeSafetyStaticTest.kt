package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyCoordinator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRuntimeSafetyStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun productionProfileIsInjectedAndCallbackUsesExistingLifecycleSafeStop() {
        assertTrue(
            source.contains(
                "thresholdProfile = WalkRuntimeSafetyCoordinator.productionThresholdProfile",
            ),
        )
        // The coordinator callback uses the same lifecycle safe-stop core as
        // enterWalkSessionSafetyStopAndCancelOutputs, with checkpoints so a retry resumes stages.
        val stopCallback = functionBlock("private fun onWalkRuntimeSafetyStop(")
        assertTrue(stopCallback.contains("performWalkSessionSafetyStopCleanup("))
        assertTrue(stopCallback.contains("checkpoints = checkpoints"))
        assertTrue(
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
                .contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"),
        )
        assertTrue(
            functionBlock("private fun performWalkSessionSafetyStopCleanup(")
                .contains("transitionWalkSession(WalkSessionEvent.SafetyStopRequested)"),
        )
        assertNull(WalkRuntimeSafetyCoordinator.productionThresholdProfile)
        val activation = functionBlock("private fun activateWalkSessionRuntime(")
        assertTrue(activation.contains(
            "walkRuntimeSafetyCoordinator.beginEpoch(runtimeEpoch).disposition ==",
        ))
        assertTrue(activation.contains("WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED"))
        assertTrue(activation.contains("if (!observeWalkRuntimeResourceSafety(runtimeEpoch)) return"))
        assertFalse(activation.contains(
            "!walkRuntimeSafetyCoordinator.beginEpoch(runtimeEpoch).safetyOutputsAllowed",
        ))

        val delivery = functionBlock("private fun isFeedbackActionStillDeliverable(")
        assertTrue(delivery.contains("action.reason == \"fresh_metric_depth_alert\""))
        assertTrue(delivery.contains("action.reason == \"fresh_metric_path_guidance\""))
        assertTrue(delivery.contains("action.reason == \"fresh_qualitative_depth_caution\""))
        val metricGateStart = delivery.indexOf("if (requiresRuntimeSafetyPermission) {")
        val remainingDeliveryStart = delivery.indexOf("if (action.trackId.startsWith(")
        assertTrue(metricGateStart >= 0)
        assertTrue(remainingDeliveryStart >= 0)
        assertTrue(remainingDeliveryStart > metricGateStart)
        val metricGate = delivery.substring(metricGateStart, remainingDeliveryStart)
        assertTrue(metricGate.contains(
            "walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return false",
        ))
        assertTrue(metricGate.contains("walkRuntimeSafetyCoordinator.evaluate(nowMs)"))
        assertTrue(metricGate.contains("!walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(metricGate.contains("decision.epoch != epoch || !decision.safetyOutputsAllowed"))
    }

    @Test
    fun resourceAndDetectorTimingObservationsUseTheCentralCoordinator() {
        val resource = functionBlock("private fun observeWalkRuntimeResourceSafety(")
        assertTrue(resource.contains("walkSessionResourceProbe.snapshot()"))
        assertTrue(resource.contains("batteryCritical = resources.batteryNotLow == false"))
        assertTrue(resource.contains("storageCritical = resources.privateStorageAboveSystemLow == false"))
        assertTrue(resource.contains("thermalCritical = resources.thermalBelowCritical == false"))
        assertTrue(resource.contains("walkRuntimeSafetyCoordinator.observe("))
        assertFalse(resource.contains("if (!walkRuntimeSafetyCoordinator.configured) return true"))
        assertTrue(resource.contains("receivedAtElapsedRealtimeMs = receivedAtMs"))
        assertTrue(resource.contains("decision.epoch == runtimeEpoch"))
        assertTrue(resource.contains("decision.disposition != WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED"))
        assertTrue(resource.contains("decision.disposition != WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION"))
        assertFalse(resource.contains(".safetyOutputsAllowed"))

        val timing = functionBlock("private fun observeWalkRuntimeDetectorTiming(")
        assertTrue(timing.contains("frameCapturedAtElapsedRealtimeMs"))
        assertTrue(timing.contains("inferenceLatencyMs"))
        assertTrue(timing.contains("walkRuntimeSafetyCoordinator.observe("))
        assertFalse(timing.contains("if (!walkRuntimeSafetyCoordinator.configured) return true"))
        assertTrue(timing.contains("receivedAtElapsedRealtimeMs = receivedAtMs"))
        assertTrue(timing.contains("walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(timing.contains("decision.epoch == epoch"))
        assertTrue(timing.contains("decision.disposition != WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED"))
        assertTrue(timing.contains("decision.disposition != WalkRuntimeSafetyDisposition.IGNORED_OBSERVATION"))
        assertFalse(timing.contains(".safetyOutputsAllowed"))
        assertTrue(source.contains("!observeWalkRuntimeDetectorTiming("))
    }

    @Test
    fun activeWalkDetectorFailureNeverInstallsLegacyLive() {
        val failure = functionBlock("private fun handleDetectorRuntimeFailure(")
        assertTrue(failure.contains("activeWalk = true"))
        assertTrue(failure.contains("DetectorRuntimeFailureAction.SAFE_STOP"))
        assertTrue(failure.contains("riskTrusted = false"))
        assertTrue(failure.contains("safetyDecision.epoch == expectedWalkEpoch"))
        assertFalse(failure.contains("TfliteAndroidFrameDetector.createWithStatus"))
        assertFalse(failure.contains("primaryModelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY"))
    }

    @Test
    fun notConfiguredCoordinatorRetainsTheExistingDirectDetectorSafeStop() {
        val disable = functionBlock("private fun disableDetectorAfterRuntimeFailure(")

        assertTrue(disable.contains("safetyStopHandledByCoordinator: Boolean = false"))
        assertTrue(disable.contains("if (!safetyStopHandledByCoordinator)"))
        assertTrue(
            disable.contains(
                "enterWalkSessionSafetyStopAndCancelOutputs(\"detector_runtime_failed\")",
            ),
        )
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val next = source.indexOf("\n    private fun ", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }
}
