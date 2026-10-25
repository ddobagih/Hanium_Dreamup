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
                "safetyThresholdProfile: ApprovedWalkRuntimeSafetyThresholdProfile? =\n        WalkRuntimeSafetyCoordinator.productionThresholdProfile",
            ),
        )
        assertTrue(MainActivityRefactorSource.property(source, "walkRuntimeSafetyCoordinator").contains("thresholdProfile = safetyThresholdProfile"))
        assertTrue(MainActivityRefactorSource.property(source, "walkRuntimeSafetyCoordinator").contains("onSafeStopWithCompletion = ::onWalkRuntimeSafetyStop"))
        val stopWiring = MainActivityRefactorSource.composition(source, "walkStopDelivery")
        assertTrue(stopWiring.contains("performWalkSessionSafetyStopCleanup(reason, persist, checkpoints, camera, location)"))
        // The coordinator callback uses the same lifecycle safe-stop core as
        // enterWalkSessionSafetyStopAndCancelOutputs, with checkpoints so a retry resumes stages.
        val stopCallback = functionBlock("private fun onWalkRuntimeSafetyStop(")
        assertTrue(stopCallback.contains("synchronousComplete = cleanup.run("))
        assertTrue(stopCallback.contains("checkpoints = checkpoints"))
        assertTrue(
            functionBlock("private fun enterWalkSessionSafetyStopAndCancelOutputs(")
                .contains("performWalkSessionSafetyStopCleanup(reason, persistInterruptionMarker, null, null)"),
        )
        assertTrue(
            functionBlock("private fun performWalkSessionSafetyStopCleanup(")
                .contains("walk.transition(WalkSessionEvent.SafetyStopRequested)"),
        )
        val cleanupWiring = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
        assertTrue(cleanupWiring.contains("override fun transition(event: WalkSessionEvent) = transitionWalkSession(event)"))
        assertNull(WalkRuntimeSafetyCoordinator.productionThresholdProfile)
        val activation = functionBlock("private fun activateWalkSessionRuntime(")
        assertTrue(activation.contains(
            "admission.beginSafetyEpoch(runtimeEpoch).disposition ==",
        ))
        assertTrue(activation.contains("WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED"))
        val activationWiring = MainActivityRefactorSource.composition(source, "walkRuntimeActivationController")
        assertTrue(activationWiring.contains("override fun beginSafetyEpoch(epoch: WalkRuntimeEpoch) = beginWalkRuntimeSafetyEpoch(epoch)"))
        assertTrue(MainActivityRefactorSource.member(source, "beginWalkRuntimeSafetyEpoch").contains("walkRuntimeSafetyCoordinator.beginEpoch(epoch)"))
        assertTrue(activationWiring.contains("observeWalkRuntimeResourceSafety(epoch)"))
        assertTrue(activation.contains("if (!services.observeResourceSafety(runtimeEpoch)) return false"))
        assertFalse(activation.contains(
            "!admission.beginSafetyEpoch(runtimeEpoch).safetyOutputsAllowed",
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
        assertTrue(metricGate.contains("admission.runtimeEpochOrNull() ?: return false"))
        assertTrue(metricGate.contains("admission.evaluateSafety(nowMs)"))
        assertTrue(metricGate.contains("!admission.runtimeEpochCurrent(epoch)"))
        val feedbackWiring = MainActivityRefactorSource.composition(source, "riskFeedbackComponents.delivery")
        assertTrue(feedbackWiring.contains("override fun runtimeEpochOrNull() = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(feedbackWiring.contains("override fun evaluateSafety(nowMs: Long) = walkRuntimeSafetyCoordinator.evaluate(nowMs)"))
        assertTrue(feedbackWiring.contains("override fun runtimeEpochCurrent(epoch: WalkRuntimeEpoch) = walkSessionLifecycle.isRuntimeEpochCurrent(epoch)"))
        assertTrue(metricGate.contains("decision.epoch != epoch || !decision.safetyOutputsAllowed"))
    }

    @Test
    fun resourceAndDetectorTimingObservationsUseTheCentralCoordinator() {
        val resource = functionBlock("private fun observeWalkRuntimeResourceSafety(")
        assertTrue(resource.contains("resources ?: facts.resourceSnapshot()"))
        val resourceWiring = MainActivityRefactorSource.composition(source, "startupResourceSafetyAdmission")
        assertTrue(resourceWiring.contains("override fun resourceSnapshot() = walkSessionResourceProbe.snapshot()"))
        assertTrue(resourceWiring.contains("coordinator = { walkRuntimeSafetyCoordinator }"))
        assertTrue(resource.contains("currentResources.toRuntimeSafetyObservation("))
        listOf("batteryNotLow", "privateStorageAboveSystemLow", "thermalBelowCritical").forEach {
            assertFalse("unknown $it cannot become healthy evidence", resource.contains("currentResources.$it == false"))
        }
        val resourceEvidence = resource.replace(Regex("\\s+"), "")
        assertTrue(resourceEvidence.contains("valcheckedAt=currentResources.checkedAtElapsedRealtimeMs"))
        assertTrue(resourceEvidence.contains("checkedAt.size!=WalkSessionResourceQuery.entries.size"))
        assertTrue(resourceEvidence.contains("checkedAt.values.any{it<0L||it>receivedAtMs}"))
        assertTrue(resourceEvidence.contains("valobservedAtMs=checkedAt.values.minOrNull()?:returnfalse"))
        assertTrue(resourceEvidence.contains("epoch=runtimeEpoch,observedAtElapsedRealtimeMs=observedAtMs"))
        assertFalse(resourceEvidence.contains("?:receivedAtMs"))
        assertTrue(resource.contains("coordinator().observe("))
        assertFalse(resource.contains("if (!coordinator().configured) return true"))
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
        val timingWiring = MainActivityRefactorSource.property(source, "primaryWorkerTimingEffects")
        assertTrue(timingWiring.contains("observedAtElapsedRealtimeMs: Long, inferenceLatencyMs: Long) = observeWalkRuntimeDetectorTiming("))
        assertTrue(timingWiring.contains("epoch, capturedAtElapsedRealtimeMs, observedAtElapsedRealtimeMs, inferenceLatencyMs)"))
        listOf("Ar" to "PrimaryArDetectorWorker", "CameraFallback" to "PrimaryCameraFallbackDetectorWorker").forEach { (part, type) ->
            val factory = MainActivityRefactorSource.factory(source, "createPrimary${part}DetectorWorker")
            assertTrue(factory.contains(type))
            assertTrue(factory.contains("detectorRuntimeSupervisor, primaryWorkerTimingEffects"))
            val worker = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/$type.kt").readText()
            assertTrue(worker.contains("if (!timing.observe("))
        }
    }

    @Test
    fun activeWalkDetectorFailureNeverInstallsLegacyLive() {
        val failure = functionBlock("private fun handleDetectorRuntimeFailure(")
        assertTrue(failure.contains("supervisor.onFailure(loadedModelKey, legacyAvailable, true, detectorRuntimeFailureKind(error),"))
        assertTrue(failure.contains("DetectorRuntimeFailureAction.SAFE_STOP"))
        assertTrue(failure.contains("failureEffects.observeSafetyFailure(epoch, elapsedRealtimeMs())"))
        val wiring = MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
        assertTrue(wiring.contains("riskTrusted = false"))
        assertTrue(wiring.contains("!decision.safetyOutputsAllowed && decision.epoch == epoch"))
        assertFalse(failure.contains("TfliteAndroidFrameDetector.createWithStatus"))
        assertFalse(failure.contains("primaryModelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY"))
    }

    @Test
    fun notConfiguredCoordinatorRetainsTheExistingDirectDetectorSafeStop() {
        val disable = functionBlock("private fun disableDetectorAfterRuntimeFailure(")

        assertTrue(disable.contains("safetyHandled: Boolean = false"))
        assertTrue(disable.contains("if (!safetyHandled) failureEffects.enterSafetyStop()"))
        val wiring = MainActivityRefactorSource.composition(source, "primaryDetectorLifecycleController")
        assertTrue(wiring.contains("override fun enterSafetyStop() = enterWalkSessionSafetyStopAndCancelOutputs(\"detector_runtime_failed\")"))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
