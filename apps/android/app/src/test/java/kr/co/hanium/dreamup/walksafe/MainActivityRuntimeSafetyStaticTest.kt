package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
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
        assertTrue(
            functionBlock("private fun onWalkRuntimeSafetyStop(")
                .contains("enterWalkSessionSafetyStopAndCancelOutputs("),
        )
        assertTrue(
            functionBlock("private fun activateWalkSessionRuntime(")
                .contains(
                    "!walkRuntimeSafetyCoordinator.beginEpoch(runtimeEpoch).safetyOutputsAllowed",
                ),
        )
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

        val timing = functionBlock("private fun observeWalkRuntimeDetectorTiming(")
        assertTrue(timing.contains("frameCapturedAtElapsedRealtimeMs"))
        assertTrue(timing.contains("inferenceLatencyMs"))
        assertTrue(timing.contains("walkRuntimeSafetyCoordinator.observe("))
        assertFalse(timing.contains("if (!walkRuntimeSafetyCoordinator.configured) return true"))
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
