package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-4 (originals/10.txt:60-72) and S14-36 (14.txt:91): an unavailable call or an old capture reaches
 * the supervisor too, so a walk with no fresh completed output stops at the freshness bound; the
 * supervisor itself checks the current detector run and pacing ticket.
 */
class MainActivityDetectorNoOutputClockStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val fallback = source.substringAfter("private fun analyzeCameraFallbackFrame(")
        .substringBefore("private fun recordCameraFallbackFrameAnalyzedOnce(")
    private val arcore = source.substringAfter("private fun scheduleDetectionIfDue(")
        .substringBefore("private fun encodeDebugFrameJpeg(")

    @Test
    fun supervisorOwnershipIsTheCurrentRunAndPacingTicket() {
        val field = source.substringAfter("private val detectorRuntimeSupervisor = DetectorRuntimeSupervisor(")
            .substringBefore("private val captureLog")
        assertTrue(field.contains("ownsCurrentWork = { claim ->"))
        assertTrue(field.contains("isCurrentFrameGeneration(claim.detectorGeneration, walkEpoch)"))
        assertTrue(field.contains("adaptiveInferencePacing.ownsSlot(claim.ticket)"))
    }

    @Test
    fun bothPathsStopOnAnExpiredNoOutputClockAfterTheFlightAndBeforeTheCompletionGuard() {
        for ((path, guard) in listOf(
            fallback to "if (!result.inferenceCompleted) return",
            arcore to "if (!result.inferenceCompleted) return@execute",
        )) {
            assertTrue(path.contains("var detectorOutputExpired = false"))
            val output = path.indexOf("detectorOutputExpired = detectorRuntimeSupervisor.onDetectorOutput(")
            val flightDone = path.indexOf("completeNativeDetectorFlight(flight)")
            val expired = path.indexOf("if (detectorOutputExpired) {")
            val stop = path.indexOf("kr.co.hanium.dreamup.walksafe.inference.DetectorValidOutputExpired(),")
            assertTrue(output in 0 until flightDone)
            // The detector is closed only after this call's native flight has completed.
            assertTrue(flightDone < expired && expired < stop && stop < path.indexOf(guard))
            assertTrue(path.substring(expired, stop).contains("handleDetectorRuntimeFailure("))
        }
    }
}
