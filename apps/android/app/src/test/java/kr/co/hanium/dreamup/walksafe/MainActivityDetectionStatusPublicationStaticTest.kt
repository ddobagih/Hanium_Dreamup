package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityDetectionStatusPublicationStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val invocation = source("runtime/vision/primary/PrimaryDetectorInvocationWorker")
    private val ar = source("runtime/vision/primary/PrimaryArDetectorWorker")
    private val fallback = source("runtime/vision/primary/PrimaryCameraFallbackDetectorWorker")

    @Test fun walkTimeDetectorPathsStartUnavailableAndMarkExceptionsAsFailed() {
        assertTrue(main.contains("createPrimaryArDetectorWorker()"))
        assertTrue(main.contains("primaryCameraFallbackDetectorWorker.analyze("))
        for (worker in listOf(ar, fallback)) {
            assertTrue(worker.contains("invocationWorker.prepare(PrimaryInvocationRequest("))
            assertTrue(worker.contains("invocationWorker.invoke(prepared,"))
            assertFalse(worker.contains("AndroidDetectionResult.empty()"))
        }
        assertTrue(invocation.contains("internal var result = AndroidDetectionResult.unavailable()"))
        assertTrue(invocation.contains("catch (failure: RuntimeException)"))
        assertTrue(invocation.contains("AndroidDetectionResult.failed()"))
    }

    @Test fun onlyACompletedInferenceCountsAsSuccessOrTimingEvidence() {
        val output = invocation.indexOf("invocation.detectorOutputExpired = onNativeOutput(")
        val gate = invocation.indexOf("if (it.inferenceCompleted) invocation.detectorSucceeded = true")
        assertTrue(output >= 0 && gate > output)
        for (worker in listOf(ar, fallback)) {
            assertTrue(worker.contains("onNativeOutput = { output ->"))
            assertTrue(worker.contains("supervisor.onDetectorOutput(DetectorWorkClaim("))
            assertTrue(worker.contains("output.result,"))
            assertTrue(worker.contains("if (observed.detectorSucceeded) {"))
        }
    }

    @Test fun failedOrUnavailableCallIsNeitherPublishedNorEvaluatedAsAClearFrame() {
        val arGate = ar.indexOf("if (!observed.result.inferenceCompleted) return")
        assertTrue(arGate >= 0 && arGate < ar.indexOf("val published = publisher.publish("))
        val cameraGate = fallback.indexOf("if (!observed.result.inferenceCompleted) return")
        assertTrue(cameraGate >= 0 && cameraGate < fallback.indexOf("advisoryPolicy.evaluate("))
    }
}
