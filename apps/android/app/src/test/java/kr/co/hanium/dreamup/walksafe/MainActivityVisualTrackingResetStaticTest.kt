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
        val reset = MainActivityRefactorSource.member(source, "resetVisualTrackingState")
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt").readText()
        MainActivityRefactorSource.composition(source, "frameSynthesisOwner")
        val discard = reset.indexOf("riskFeedbackComponents.delivery.discardUnstartedFeedbackForTrackPrefix(\"visual-\$visualGeometryVersion-\")")
        val advance = reset.indexOf("frameSynthesisOwner.advanceGeometry()")
        val pipeline = reset.indexOf("frameSynthesisOwner.resetCurrentCaptureHistory(")
        assertTrue(discard in 0 until advance)
        assertTrue(advance < pipeline)
        assertTrue(MainActivityRefactorSource.member(owner, "advanceGeometry").contains("visualGeometryVersion += 1L"))
        assertTrue(MainActivityRefactorSource.member(owner, "resetCurrentCaptureHistory").contains("currentObjectDepthPipeline = ObjectDepthRuntimePipeline("))
        // The discarded prefix is exactly the one the generation's tracker used.
        assertTrue(MainActivityRefactorSource.member(owner, "resetCurrentCaptureHistory").contains("trackIdPrefix = \"visual-\$visualGeometryVersion-track-\""))
    }

    @Test
    fun admissionResetDiscardsThePreviousTrackersFeedbackBeforeReplacingIt() {
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt").readText()
        val capture = MainActivityRefactorSource.member(owner, "captureVisualTrackingFrame")
        MainActivityRefactorSource.composition(source, "frameSynthesisOwner")
        val binding = MainActivityRefactorSource.property(source, "frameSynthesisHolder")
        assertTrue(binding.contains("override fun retireUnstartedFeedback(trackPrefix: String)"))
        assertTrue(binding.contains("discardUnstartedFeedbackForTrackPrefix(trackPrefix)"))
        val admission = capture.substringAfter("if (admission.reset) {")
        val discard = admission.indexOf("authority.retireUnstartedFeedback(\"visual-\$geometryVersion-\")")
        val replace = admission.indexOf("currentObjectDepthPipeline = ObjectDepthRuntimePipeline(")
        assertTrue(discard in 0 until replace)
        assertTrue(admission.contains("trackIdPrefix = \"visual-\$geometryVersion-\${gray.key.frameId}-track-\""))
    }
}
