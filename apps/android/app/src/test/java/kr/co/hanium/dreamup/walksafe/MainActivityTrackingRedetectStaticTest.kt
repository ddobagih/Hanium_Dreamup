package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityTrackingRedetectStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    @Test fun liveTrackingResultRequestsAnEarlyDetectionThroughThePacingPolicyOnly() {
        val pipeline = source("runtime/vision/frame/FramePipelineController")
        val frame = MainActivityRefactorSource.member(pipeline, "synthesize")
        assertTrue(main.contains("override fun onDrawFrame(gl: GL10?) = framePipelineController.draw()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(frame,
            "synthesis.trackSource(", "if (tracking?.redetectRequested == true)",
            "runtime.requestEarlyDetection(clock.elapsedRealtimeMs())"))
        assertTrue(main.contains("override fun requestEarlyDetection(atElapsedRealtimeMs: Long) { primaryDetectorOwner.requestEarlyWork(atElapsedRealtimeMs) }"))
        val owner = source("runtime/vision/primary/PrimaryDetectorExecutionOwner")
        val early = MainActivityRefactorSource.member(owner, "requestEarlyWork")
        assertTrue(early.contains("pacing.requestEarlyStart(nowMs)"))
        assertEquals(1, Regex("requestEarlyStart\\(").findAll(owner).count())
        assertTrue(source("runtime/vision/frame/FrameSynthesisController").contains("redetect=\${it.redetectReasons.joinToString()}"))
        val between = frame.substringAfter("if (tracking?.redetectRequested == true)")
            .substringBefore("runtime.recordTrackingLoad(")
        assertFalse(between.contains("tryStart"))
        assertFalse(between.contains("detectionInFlight"))
    }
}
