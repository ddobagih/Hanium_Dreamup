package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-12/S11-15/S11-23: production callers of [kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline]
 * pass a frame-bound mapper, choose Raw-only motion explicitly and replace the pipeline on a
 * visual epoch/geometry boundary instead of relying on constructor or test defaults.
 */
class MainActivityObjectDepthPipelineWiringStaticTest {
    private fun read(vararg candidates: String): String = candidates.asSequence()
        .flatMap { sequenceOf(File(it), File("app/$it")) }
        .first { it.isFile }.readText()

    private val main by lazy { read("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt") }
    private val synthesis by lazy { read("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/FrameSynthesisController.kt") }
    private val cameraTest by lazy { read("src/debug/java/kr/co/hanium/dreamup/walksafe/CameraTestActivity.kt") }
    private val coordinator by lazy {
        read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileFrameCoordinator.kt")
    }

    /** Balanced constructor argument text, so nested calls such as ObjectTracker(...) stay inside. */
    private fun calls(source: String, marker: String): List<String> =
        source.split(marker).drop(1).map { rest ->
            var depth = 1
            val end = rest.indices.first { index ->
                when (rest[index]) {
                    '(' -> depth += 1
                    ')' -> depth -= 1
                }
                depth == 0
            }
            rest.substring(0, end)
        }

    @Test
    fun everyRuntimeAndDiagnosticPipelineSetsRawOnlyMotionExplicitly() {
        val production = calls(synthesis, "ObjectDepthRuntimePipeline(")
        val diagnostic = calls(cameraTest, "ObjectDepthRuntimePipeline(")
        assertTrue(production.size >= 4)
        assertEquals(1, diagnostic.size)
        (production + diagnostic).forEach { arguments ->
            assertTrue(arguments, arguments.contains("rawDepthMotionOnly = true"))
        }
    }

    @Test
    fun productionDepthCallsNeverFallBackToTheIdentityMapper() {
        val legacyCalls = calls(synthesis, "objectDepthPipeline.process(")
        assertEquals(1, legacyCalls.size)
        assertTrue(legacyCalls.single().contains("mapper = requireNotNull(evidence.depthMapper)"))
        val currentCalls = calls(synthesis, "currentObjectDepthPipeline.processTrackedObservation(")
        assertEquals(1, currentCalls.size)
        assertTrue(currentCalls.single().contains("mapper = requireNotNull(evidence.depthMapper)"))
        assertTrue(currentCalls.single().contains("mapperFrameId = evidence.depthMapper.frameId"))
        // The legacy depth callback is entered only when the matched capture owns a mapper.
        val gate = coordinator.substringAfter("val depthProcessingAttempted = ")
            .substringBefore("processDepth(requireNotNull(matchedEvidence), depthDetections)")
        assertTrue(gate.contains("matchedEvidence?.depthMapper != null"))
    }

    @Test
    fun visualEpochOrGeometryResetReplacesPipelineAndTracker() {
        assertTrue(main.contains("frameSynthesisOwner.advanceGeometry()"))
        assertTrue(main.contains("frameSynthesisOwner.resetCurrentCaptureHistory("))
        val reset = MainActivityRefactorSource.member(synthesis, "resetCurrentCaptureHistory")
        assertTrue(MainActivityRefactorSource.member(synthesis, "advanceGeometry").contains("visualGeometryVersion += 1L"))
        assertTrue(reset.contains("currentObjectDepthPipeline = ObjectDepthRuntimePipeline("))
        assertTrue(reset.contains("tracker = ObjectTracker(trackIdPrefix = \"visual-\$visualGeometryVersion-track-\")"))
        val admission = synthesis.substringAfter("val admission = visualFrameTracker.offerFrame(gray)")
            .substringBefore("lastVisualFrameKey = gray.key")
        assertTrue(admission.contains("if (admission.reset) {"))
        assertTrue(admission.substringAfter("if (admission.reset) {")
            .contains("currentObjectDepthPipeline = ObjectDepthRuntimePipeline("))
        assertTrue(admission.contains("tracker = ObjectTracker(trackIdPrefix = \"visual-\$geometryVersion-\${gray.key.frameId}-track-\")"))
    }
}
