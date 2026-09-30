package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/** S10-1 (originals/10.txt:30) and S10-14 (originals/10.txt:167). */
class DetectorResultStatusContractTest {
    private fun ownedFrame(): OwnedYuvFrame = OwnedYuvFrame.copyOf(
        4, 4, ByteBuffer.allocate(16), ByteBuffer.allocate(4), ByteBuffer.allocate(4), 4, 1, 2, 1,
    )

    @Test fun noopDetectorIsUnavailableAndNeverACompletedEmptyInference() {
        val noop = NoopAndroidFrameDetector()
        val results = (0..3).map { turns -> noop.detectOriented(ownedFrame(), 0L, turns) } +
            noop.detect(ownedFrame(), 0L)
        for (result in results) {
            assertEquals(AndroidDetectionStatus.UNAVAILABLE, result.status)
            assertFalse(result.inferenceCompleted)
            assertNotEquals(AndroidDetectionResult.empty(), result)
        }
    }

    @Test fun emptySceneUnavailableDetectorAndFailedCallAreThreeDistinctStates() {
        val emptyScene = AndroidDetectionResult.empty()
        val unavailable = AndroidDetectionResult.unavailable()
        val failed = AndroidDetectionResult.failed()
        assertEquals(AndroidDetectionStatus.SUCCESS, emptyScene.status)
        assertTrue(emptyScene.inferenceCompleted)
        assertEquals(AndroidDetectionStatus.UNAVAILABLE, unavailable.status)
        assertEquals(AndroidDetectionStatus.FAILED, failed.status)
        assertEquals(3, setOf(emptyScene, unavailable, failed).size)
        assertTrue(listOf(emptyScene, unavailable, failed).all { it.detections.isEmpty() })
    }

    @Test fun onlyACompletedInferenceCanCarryDetectionsOrPartialOutput() {
        val box = DetectionCandidate("person", .9f, RectNorm(.1f, .1f, .2f, .2f))
        for (status in listOf(AndroidDetectionStatus.UNAVAILABLE, AndroidDetectionStatus.FAILED)) {
            assertThrows(IllegalArgumentException::class.java) {
                AndroidDetectionResult(listOf(box), status = status)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AndroidDetectionResult(emptyList(), partial = true, status = status)
            }
        }
        assertEquals(1, AndroidDetectionResult(listOf(box)).detections.size)
    }

    @Test fun everyDetectorImplementsRotationExplicitlyWithoutAnUnrotatedDefault() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/inference/AndroidDetectorContracts.kt").readText()
        val contract = source.substringAfter("interface AndroidFrameDetector {").substringBefore("\n}\n")
        assertEquals(2, Regex("fun detectOriented\\(").findAll(contract).count())
        assertFalse("detectOriented must not default to detect()", contract.contains("= detect("))
        val noop = source.substringAfter("class NoopAndroidFrameDetector : AndroidFrameDetector {")
            .substringBefore("\n}\n")
        assertEquals(2, Regex("override fun detectOriented\\(").findAll(noop).count())
        val tflite = File("src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt").readText()
        assertEquals(2, Regex("override fun detectOriented\\(").findAll(tflite).count())
    }

    @Test fun walkTimeCameraPathsUseOrientedDetectionAndTheUnrotatedCallStaysInThePreflight() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val fallback = source.substringAfter("private fun analyzeCameraFallbackFrame(")
            .substringBefore("private fun recordCameraFallbackFrameAnalyzedOnce(")
        val arcore = source.substringAfter("private fun scheduleDetectionIfDue(")
            .substringBefore("private fun encodeDebugFrameJpeg(")
        assertTrue(fallback.contains("frameDetector.detectOriented("))
        assertTrue(arcore.contains("frameDetector.detectOriented("))
        assertFalse(fallback.contains("frameDetector.detect("))
        assertFalse(arcore.contains("frameDetector.detect("))
        // The only unrotated call is the post-login execution check, whose boxes are not guidance.
        assertEquals(1, Regex("frameDetector\\.detect\\(").findAll(source).count())
        val preflight = source.substringAfter("val detectorSucceeded = try {")
            .substringBefore("// Count successful camera/model invocations")
        assertTrue(preflight.contains("frameDetector.detect("))
        assertTrue(preflight.contains("DeviceCheckDetectorExecutionPolicy.passes(result)"))
    }
}
