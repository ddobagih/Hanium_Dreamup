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
        val frameAnchor = "val preparedTactileFrame = ProductionPreparedTactileFrameDispatch(currentFrame)"
        val gapAnchor = "if (!currentRuntimeMetricOutputAllowsWork(arLease.generation)) {"
        assertTrue(source.contains(frameAnchor))
        val frameBoundary = source.substringAfter(frameAnchor)
        assertTrue(frameBoundary.contains(gapAnchor))
        val gapBranch = frameBoundary.substringAfter(gapAnchor)
            .substringBefore("val captureLogEntry = buildCaptureLogEntry(")
        assertTrue(gapBranch.contains("tactileFrame.guidance.outputs,\n                nowElapsedRealtimeMs = qualitativeNowMs,"))
        assertTrue(gapBranch.contains(".filter { it.isCurrentQualitativeCaution(qualitativeNowMs) }"))
        assertTrue(gapBranch.contains("currentRuntimeVisualDepthProcessingAllowsWork("))
        assertTrue(gapBranch.contains("qualitativeRuntimeReady = qualitativeRuntimeReady"))
        assertTrue(gapBranch.contains("frame = tactileFrame.copy(guidance = tactileFrame.guidance.copy(outputs = qualitativeOutputs))"))
        assertTrue(gapBranch.contains("stale = !qualitativeRuntimeReady"))
        assertTrue(gapBranch.contains("independentFreshOutputs = emptyList()"))
        assertTrue(gapBranch.contains("warningContext = null"))
        assertTrue(gapBranch.indexOf("dispatchTactileFrameFeedback(") < gapBranch.lastIndexOf("return"))
    }

    @Test
    fun qualitativeDeliveryRechecksCurrentArCoreVisualLeaseNotCameraFallback() {
        val deliveryAnchor = "val currentDeliveryGateAllowsAlerts = if (action.reason == \"fresh_qualitative_depth_caution\") {"
        assertTrue(source.contains(deliveryAnchor))
        val delivery = source.substringAfter(deliveryAnchor).substringBefore("private fun currentFeedbackDeviceGateAllowsAlerts(")
        assertTrue(delivery.contains("currentArCoreQualitativeFeedbackAllowsWork(nowMs)"))
        assertTrue(delivery.contains("current.deviceGateAllowsAlerts"))
        val arCoreGate = delivery.substringAfter("private fun currentArCoreQualitativeFeedbackAllowsWork(nowMs: Long): Boolean")
        assertTrue(arCoreGate.contains("allowsRuntimeDepthGapProcessing("))
        assertTrue(arCoreGate.contains("tracking = runtimeVisualDepthFrameTracking"))
        assertTrue(arCoreGate.contains("isActivityForeground"))
        assertTrue(arCoreGate.contains("hasCameraPermission()"))
        assertTrue(arCoreGate.contains("arSessionPurpose == ArSessionPurpose.RUNTIME"))
        assertTrue(arCoreGate.contains("!cameraFallbackRequested && !cameraFallbackRunning"))
    }
}
