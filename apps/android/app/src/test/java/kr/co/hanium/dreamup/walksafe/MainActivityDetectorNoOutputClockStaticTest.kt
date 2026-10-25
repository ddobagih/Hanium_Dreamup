package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainActivityDetectorNoOutputClockStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path.kt").readText()
    private val main = source("MainActivity")
    @Test fun supervisorOwnershipIsTheCurrentRunAndPacingTicket() {
        val composition = source("runtime/vision/composition/VisionPrimaryComposition")
        assertTrue(main.contains("visionPrimaryComposition.createSupervisor(::isCurrentFrameGeneration)"))
        assertTrue(composition.contains("ownsCurrentWork = { claim ->"))
        assertTrue(composition.contains("admission(claim.detectorGeneration, epoch)"))
        assertTrue(composition.contains("detector.value.ownsWork(claim.ticket)"))
        assertTrue(source("runtime/vision/primary/PrimaryDetectorExecutionOwner").contains("pacing.ownsSlot(ticket)"))
    }

    @Test fun bothPathsStopOnAnExpiredNoOutputClockAfterTheFlightAndBeforeTheCompletionGuard() {
        val invocation = source("runtime/vision/primary/PrimaryDetectorInvocationWorker")
        val output = invocation.indexOf("invocation.detectorOutputExpired = onNativeOutput(")
        val done = invocation.indexOf("detectorOwner.completeNativeFlight(flight)")
        assertTrue(output >= 0 && done > output)
        assertTrue(invocation.substring(output, done).contains("finally"))
        for (name in listOf("PrimaryArDetectorWorker", "PrimaryCameraFallbackDetectorWorker")) {
            val worker = source("runtime/vision/primary/$name")
            assertTrue(ReportStaticSourceInspector.appearsInOrder(worker,
                "invocationWorker.invoke(prepared,", "supervisor.onDetectorOutput(",
                "val observed = prepared.observe()", "if (observed.detectorOutputExpired)",
                "timing.failure(DetectorValidOutputExpired(),", "if (!observed.result.inferenceCompleted) return"))
        }
        assertTrue(main.contains("override fun failure(error: RuntimeException, generation: Int, epoch: WalkRuntimeEpoch)"))
        assertTrue(main.contains("handleDetectorRuntimeFailure(error, generation, epoch)"))
    }
}
