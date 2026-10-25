package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityDetectorFailureKindStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    private val lifecycle = source("runtime/vision/primary/PrimaryDetectorLifecycleController")
    private val ar = source("runtime/vision/primary/PrimaryArDetectorWorker")
    private val fallback = source("runtime/vision/primary/PrimaryCameraFallbackDetectorWorker")

    @Test fun runtimeFailureIsClassifiedAndTimedAndCompletedInferenceRecordsItsTime() {
        val failure = MainActivityRefactorSource.member(lifecycle, "runtimeFailure")
        assertTrue(main.contains("primaryDetectorLifecycleController.runtimeFailure(error, expectedDetectorGeneration, expectedWalkEpoch)"))
        assertTrue(failure.contains("detectorRuntimeFailureKind(error)"))
        assertTrue(failure.contains("elapsedRealtimeMs(), run"))
        val invocation = source("runtime/vision/primary/PrimaryDetectorInvocationWorker")
        val returned = ReportStaticSourceInspector.blockAfter(
            MainActivityRefactorSource.member(invocation, "invoke"), "result.also",
        )
        assertEquals(code("""
            {
                val returnedAt = elapsedRealtimeMs()
                latestNativeTiming = PrimaryNativeTimingSnapshot(
                    request.pacingTicket, request.epoch, request.frameGeneration,
                    request.capturedAtElapsedRealtimeMs, invocation.detectorStartedAtElapsedRealtimeMs,
                    returnedAt, it.status, it.timing.copy(
                        completedModels = java.util.Collections.unmodifiableList(ArrayList(it.timing.completedModels)),
                        skippedModels = java.util.Collections.unmodifiableList(ArrayList(it.timing.skippedModels)),
                    ),
                )
                invocation.detectorOutputExpired = onNativeOutput(PrimaryNativeOutput(it, returnedAt))
                if (it.inferenceCompleted) invocation.detectorSucceeded = true
            }
        """), code(returned))
        val callbacks = listOf(
            ar to """{ output ->
                supervisor.onDetectorOutput(DetectorWorkClaim(work.generation, work.epoch, work.ticket),
                    output.result, work.capturedAtElapsedRealtimeMs, output.completedAtElapsedRealtimeMs)
                    .action == DetectorRuntimeFailureAction.SAFE_STOP
            }""",
            fallback to """{ output ->
                supervisor.onDetectorOutput(DetectorWorkClaim(detectorGeneration, expectedEpoch, ticket),
                    output.result, now, output.completedAtElapsedRealtimeMs)
                    .action == DetectorRuntimeFailureAction.SAFE_STOP
            }""",
        )
        for ((worker, expectedCallback) in callbacks) {
            assertEquals(1, Regex("supervisor\\.onDetectorOutput\\(").findAll(code(worker)).count())
            assertEquals(code(expectedCallback), code(ReportStaticSourceInspector.blockAfter(worker, "onNativeOutput =")))
        }
    }

    @Test fun failureInARunWithoutItsOwnSuccessDisarmsTheOldFreshnessClockBeforeTheSupervisorDecides() {
        val failure = MainActivityRefactorSource.member(lifecycle, "runtimeFailure")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(failure,
            "if (!currentness.frameIsCurrent(generation, epoch)) return", "val run = generation to epoch",
            "supervisor.onFailure(", "elapsedRealtimeMs(), run"))
        assertTrue(ar.contains("DetectorWorkClaim(work.generation, work.epoch, work.ticket)"))
        assertTrue(fallback.contains("DetectorWorkClaim(detectorGeneration, expectedEpoch, ticket)"))
        assertFalse(lifecycle.contains("supervisor.onSuccess("))
        assertFalse(main.contains("detectorRuntimeSupervisor.onSuccess("))
    }

    private fun code(value: String): String = ReportStaticSourceInspector.maskNonCode(value).filterNot(Char::isWhitespace)
}
