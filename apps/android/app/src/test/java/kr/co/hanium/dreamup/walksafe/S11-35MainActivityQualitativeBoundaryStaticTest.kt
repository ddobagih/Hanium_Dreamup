package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class S11_35MainActivityQualitativeBoundaryStaticTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun shortMetricFrameGapDispatchesOnlyCurrentQualitativeOutputsBeforeMetricReturn() {
        val publication = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FramePublicationController.kt").readText()
        val publish = MainActivityRefactorSource.member(publication, "publish")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(publish,
            "if (!metricOutputAllowed(scope.arGeneration))", "publishQualitative(input, frameTimestampNs)",
            "return", "recording.buildCaptureEntry("))
        val gap = MainActivityRefactorSource.member(publication, "publishQualitative")
        assertTrue(gap.contains("resolveConflictingStateFeedback(input.frame.guidance.outputs, now)"))
        assertTrue(gap.contains(".filter { it.isCurrentQualitativeCaution(now) }"))
        assertTrue(gap.contains("visualOutputAllowed(scope.lease, frameTimestampNs(), scope.tracking)"))
        assertTrue(gap.contains("risk.deviceGate(null, null, scope.arGeneration, outputs.firstOrNull(), visualReady)"))
        assertTrue(gap.contains("input.frame.copy(guidance = input.frame.guidance.copy(outputs = outputs)), !visualReady"))
        assertTrue(gap.contains("risk::qualitativeAlertsAllowed, input.receipt, null"))
        assertTrue(gap.contains("}, emptyList(), null)"))
        assertTrue(gap.indexOf("tactile.dispatchFeedback(") < gap.indexOf("presentation().showGap("))
        val wiring = MainActivityRefactorSource.composition(source, "framePipelineController")
        assertTrue(wiring.contains("FramePublicationController(frameStateLock, runtime::ownsFrame, runtime::metricOutputAllowsWork,"))
        assertTrue(wiring.contains("runtime::visualOutputAllowsWork, tactile, risk, recording, report"))
    }

    @Test
    fun qualitativeDeliveryRechecksCurrentArCoreVisualLeaseNotCameraFallback() {
        val deliveryAnchor = "val currentDeliveryGateAllowsAlerts = if (action.reason == \"fresh_qualitative_depth_caution\") {"
        val owner = MainActivityRefactorSource.function(source, "private fun isFeedbackActionStillDeliverable(")
        assertTrue(owner.contains(deliveryAnchor))
        val delivery = owner.substringAfter(deliveryAnchor)
        assertTrue(delivery.contains("admission.qualitativeAllowsWork(nowMs)"))
        assertTrue(delivery.contains("current.deviceGateAllowsAlerts"))
        val arCoreGate = MainActivityRefactorSource.member(source, "currentArCoreQualitativeFeedbackAllowsWork")
        assertTrue(MainActivityRefactorSource.factory(source, "createRiskFeedbackComponents").contains("currentArCoreQualitativeFeedbackAllowsWork(nowMs)"))
        assertTrue(arCoreGate.contains("allowsRuntimeDepthGapProcessing("))
        assertTrue(arCoreGate.contains("tracking = runtimeVisualDepthFrameTracking"))
        assertTrue(arCoreGate.contains("isActivityForeground"))
        assertTrue(arCoreGate.contains("hasCameraPermission()"))
        assertTrue(arCoreGate.contains("arSessionOwner.observe().purpose == ArSessionPurpose.RUNTIME"))
        assertTrue(arCoreGate.contains("!cameraFallbackRequested && !cameraFallbackRunning"))
    }
}
