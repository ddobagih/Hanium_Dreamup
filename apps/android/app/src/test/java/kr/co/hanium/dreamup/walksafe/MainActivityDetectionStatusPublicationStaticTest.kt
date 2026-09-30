package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S10-1 (originals/10.txt:30): a failed or unavailable detector call is not an empty scene. It must
 * not be published over the last completed result, evaluated as a clear frame, or counted as success.
 */
class MainActivityDetectionStatusPublicationStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val fallback = source.substringAfter("private fun analyzeCameraFallbackFrame(")
        .substringBefore("private fun recordCameraFallbackFrameAnalyzedOnce(")
    private val arcore = source.substringAfter("private fun scheduleDetectionIfDue(")
        .substringBefore("private fun encodeDebugFrameJpeg(")

    @Test fun walkTimeDetectorPathsStartUnavailableAndMarkExceptionsAsFailed() {
        for (path in listOf(fallback, arcore)) {
            assertFalse(path.contains("AndroidDetectionResult.empty()"))
            assertTrue(path.contains("var result = AndroidDetectionResult.unavailable()"))
            assertTrue(path.contains("AndroidDetectionResult.failed()"))
        }
    }

    @Test fun onlyACompletedInferenceCountsAsSuccessOrTimingEvidence() {
        for (path in listOf(fallback, arcore)) {
            val gate = path.indexOf("if (it.inferenceCompleted) {")
            assertTrue(gate >= 0)
            // S14-36: the supervisor receives the result itself and counts only a completed, fresh,
            // current-ticket inference (DetectorRuntimeSupervisorTest.onlyACompletedInferenceIsAValidOutput).
            val supervisor = path.indexOf("detectorRuntimeSupervisor.onDetectorOutput(")
            assertTrue(supervisor in 0 until gate)
            assertTrue(path.substring(supervisor, gate).contains("result = it,"))
            assertTrue(gate < path.indexOf("detectorSucceeded = true"))
        }
    }

    @Test fun failedOrUnavailableCallIsNeitherPublishedNorEvaluatedAsAClearFrame() {
        val arcoreGuard = arcore.indexOf("if (!result.inferenceCompleted) return@execute")
        assertTrue(arcoreGuard >= 0)
        assertTrue(arcoreGuard < arcore.indexOf("val published = this@MainActivity.publishDetectionSnapshot("))
        val fallbackGuard = fallback.indexOf("if (!result.inferenceCompleted) return")
        assertTrue(fallbackGuard >= 0)
        assertTrue(fallbackGuard < fallback.indexOf("nonMetricAdvisoryPolicy.evaluate("))
    }
}
