package kr.co.hanium.dreamup.walksafe.inference

import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
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
        fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
        val main = source("MainActivity")
        val fallback = source("runtime/vision/primary/PrimaryCameraFallbackDetectorWorker")
        val arcore = source("runtime/vision/primary/PrimaryArDetectorWorker")
        val bridge = source("runtime/vision/primary/AndroidPrimaryArSubmissionBridge")
        val invocation = source("runtime/vision/primary/PrimaryDetectorInvocationWorker")
        val execution = source("runtime/vision/primary/PrimaryDetectorExecutionOwner")
        val composition = source("runtime/vision/composition/VisionPrimaryComposition")
        assertTrue(MainActivityRefactorSource.member(main, "analyzeCameraFallbackFrame")
            .contains("primaryCameraFallbackDetectorWorker.analyze("))
        assertTrue(MainActivityRefactorSource.member(main, "scheduleDetectionIfDue")
            .contains("primaryArSubmissionBridge.schedule("))
        assertTrue(main.contains("frameRouteEvidence, { primaryArDetectorWorker }, object : PrimaryArSubmissionAuthority"))
        assertTrue(bridge.contains("val targetWorker = worker()"))
        assertTrue(bridge.contains("targetWorker.submit(queuedWork)"))
        for (factory in listOf("createPrimaryCameraFallbackDetectorWorker", "createPrimaryArDetectorWorker")) {
            assertTrue(MainActivityRefactorSource.member(main, factory)
                .contains("primaryDetectorOwner, primaryDetectorInvocationWorker"))
        }
        assertTrue(main.contains("private val primaryDetectorInvocationWorker by visionPrimaryComposition.invocation"))
        assertTrue(composition.contains("PrimaryDetectorInvocationWorker(detector.value, elapsedRealtimeMs, wallTimeMs)"))
        assertTrue(fallback.contains("val quarterTurns = frameCapture.imageQuarterTurns"))
        assertTrue(fallback.contains("timestampNs / NANOS_PER_MILLISECOND, quarterTurns, quality"))
        assertTrue(arcore.contains("work.timestampMs, work.imageQuarterTurns, work.input.qualityObservation"))
        for (worker in listOf(fallback, arcore)) assertTrue(worker.contains("invocationWorker.invoke(prepared,"))
        assertEquals(2, Regex("detectorOwner\\.detectOriented\\(").findAll(invocation).count())
        assertTrue(invocation.contains("detectorOwner.detectOriented(ownedFrame, request.timestampMs, request.quarterTurns)"))
        assertTrue(MainActivityRefactorSource.member(execution, "detectOriented")
            .contains("detector.detectOriented(cameraFrame, timestampMs, quarterTurns, partial)"))
        for (runtime in listOf(main, bridge, fallback, arcore, invocation, execution)) {
            assertFalse("Walking paths must not call the unrotated detector API",
                Regex("\\.detect\\(").containsMatchIn(runtime))
        }
        // The unrotated post-login execution check does not publish boxes into walking guidance.
        val probes = source("device/postlogin/AndroidPostLoginDeviceProbes")
        assertEquals(1, Regex("detector\\(\\)\\.detect\\(").findAll(probes).count())
        val preflight = MainActivityRefactorSource.member(probes, "startCamera")
        assertTrue(preflight.contains("detector().detect(frame, timestampNs / NANOS_PER_MILLISECOND)"))
        assertTrue(preflight.contains("DeviceCheckDetectorExecutionPolicy.passes(result)"))
    }
}
