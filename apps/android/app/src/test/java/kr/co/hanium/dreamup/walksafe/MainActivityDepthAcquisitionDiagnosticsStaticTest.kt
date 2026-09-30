package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-18: the runtime status keeps the provider's bounded depth-acquisition failure reasons. */
class MainActivityDepthAcquisitionDiagnosticsStaticTest {
    private val source: String by lazy {
        sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
    }

    @Test
    fun bothRuntimeStatusBranchesShowOperationalAcquisitionFailureReasons() {
        val frame = source.substringAfter("override fun onDrawFrame(gl: GL10?)")
            .substringBefore("private fun handleRuntimeMetricPreflightFrame(")
        assertTrue(frame.contains("val provider = arLease.provider"))
        val status = frame.substringAfter("if (!shouldUpdateUi) return")
        assertTrue(status.contains("val depthAcquisitionStatus = \"depthAcquisitionFailures=\${provider.runtimeAcquisitionDiagnostics()}\""))
        assertEquals(2, status.split("\${latestVisualTrackingStatus}\\n\$depthAcquisitionStatus\\n").size - 1)
    }
    @Test
    fun cameraConsumersAreLabeledAndTrackingLossStillShowsAndLogsTheCounts() {
        // S11-18: depth, visual tracking and unknown-object acquisitions are labeled; the detector
        // keeps the one unlabeled call (its compiled call shape is pinned by another test).
        assertEquals(1, Regex("provider\\.acquireCameraImageOrNull\\(frame\\)").findAll(source).count())
        assertTrue(source.contains("frame, kr.co.hanium.dreamup.walksafe.depth.CameraImageConsumer.VISUAL_TRACKING,"))
        assertTrue(source.contains("frame, kr.co.hanium.dreamup.walksafe.depth.CameraImageConsumer.UNKNOWN_OBJECT,"))
        val frame = source.substringAfter("override fun onDrawFrame(gl: GL10?)")
            .substringBefore("private fun handleRuntimeMetricPreflightFrame(")
        val lost = frame.substringAfter("if (!currentRuntimeVisualDepthProcessingAllowsWork(arLease, frame.timestamp, currentFrameTracking)) {")
        assertTrue(lost.indexOf("recordDepthAcquisitionFailuresIfDue(provider, elapsedRealtimeMs)") in 0 until
            lost.indexOf("if (cameraScreenVisible"))
        assertTrue(lost.substringBefore("runOnUiThread {").contains("\\ndepthAcquisitionFailures=\${provider.runtimeAcquisitionDiagnostics()}"))
        val status = frame.substringAfter("if (!shouldUpdateUi) return")
        assertTrue(status.contains("recordDepthAcquisitionFailuresIfDue(provider, elapsedRealtimeMs)"))
        val helper = source.substringAfter("private fun recordDepthAcquisitionFailuresIfDue(")
            .substringBefore("private fun captureVisualTrackingFrame(")
        assertTrue(helper.contains("provider.runtimeAcquisitionFieldLogSummaryIfDue(nowElapsedRealtimeMs) ?: return"))
        assertTrue(helper.contains("fieldSessionLog.recordEvent(\"depth_acquisition_failures\", mapOf(\"state\" to summary))"))
    }
}
