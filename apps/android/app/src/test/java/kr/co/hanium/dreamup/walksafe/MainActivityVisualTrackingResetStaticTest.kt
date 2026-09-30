package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-15 (originals/11.txt:153-157): a visual tracking generation change replaces the pipeline,
 * tracker, motion history and the generation's unstarted feedback together.
 */
class MainActivityVisualTrackingResetStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun resetDiscardsTheEndingGenerationsFeedbackBeforeAdvancingIt() {
        val reset = source.substringAfter("private fun resetVisualTrackingState() = synchronized(frameStateLock) {")
            .substringBefore("private fun hasFreshVisualSource(")
        val discard = reset.indexOf("feedbackPolicy.discardUnstartedFeedbackForTrackPrefix(\"visual-\$visualGeometryVersion-\")")
        val advance = reset.indexOf("visualGeometryVersion += 1L")
        val pipeline = reset.indexOf("currentObjectDepthPipeline = ObjectDepthRuntimePipeline(")
        assertTrue(discard in 0 until advance)
        assertTrue(advance < pipeline)
        // The discarded prefix is exactly the one the generation's tracker used.
        assertTrue(reset.contains("trackIdPrefix = \"visual-\$visualGeometryVersion-track-\""))
    }

    @Test
    fun admissionResetDiscardsThePreviousTrackersFeedbackBeforeReplacingIt() {
        val capture = source.substringAfter("private fun captureVisualTrackingFrame(")
            .substringBefore("private fun startFieldSessionHeartbeat(")
        val admission = capture.substringAfter("if (admission.reset) {")
        val discard = admission.indexOf("feedbackPolicy.discardUnstartedFeedbackForTrackPrefix(\"visual-\$geometryVersion-\")")
        val replace = admission.indexOf("currentObjectDepthPipeline = ObjectDepthRuntimePipeline(")
        assertTrue(discard in 0 until replace)
        assertTrue(admission.contains("trackIdPrefix = \"visual-\$geometryVersion-\${gray.key.frameId}-track-\""))
    }
}
