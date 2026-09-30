package kr.co.hanium.dreamup.walksafe.depth

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S14-25-F1: the depth gate's fatal signal is local to one frame's own depth acquisition. A
 * camera-image failure of a later consumer of the same provider (detector, tactile, calibration)
 * is counted but never stops the next valid depth frame; depth-stage fatal failures still do.
 */
class DepthAcquisitionFrameLocalFailureTest {
    @Test
    fun aDetectorStageExhaustionDoesNotStopTheNextValidDepthFrame() {
        val counters = DepthAcquisitionFailureCounters()
        // Frame N: depth acquired and consumed right after acquireDepthSnapshot.
        counters.beginDepthAcquisition()
        assertNull(counters.consumeMostSevereSinceLastCheck())
        // Later in frame N the detector worker fails to acquire a camera image (images held).
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED)
        // Frame N+1: its own depth acquisition succeeds with valid samples.
        counters.beginDepthAcquisition()
        val frameFailure = counters.consumeMostSevereSinceLastCheck()
        assertNull(frameFailure)
        val assessment = assess(valid = true, failure = frameFailure)
        assertTrue(assessment.outputAllowed)
        assertFalse(assessment.shouldHandleLoss)
        assertNull(assessment.fatalAcquisitionFailure)
        // The failure stays visible in the bounded diagnostics.
        assertEquals(1, counters.count(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED))
    }

    @Test
    fun theTimestampCameraImageInsideTheDepthAcquisitionKeepsTheTransientBudget() {
        val counters = DepthAcquisitionFailureCounters()
        counters.beginDepthAcquisition()
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.ILLEGAL_STATE)
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED)
        assertNull(counters.consumeMostSevereSinceLastCheck())
        // With invalid samples only the existing stale timeout can stop it.
        val fresh = assess(valid = false, failure = null, nowMs = 1_033L)
        assertFalse(fresh.shouldHandleLoss)
        val stale = assess(valid = false, failure = null, nowMs = 3_000L)
        assertTrue(stale.shouldHandleLoss)
        assertNull(stale.fatalAcquisitionFailure)
    }

    @Test
    fun depthStageFatalFailuresOfThisFrameStillStopAtOnce() {
        for (stage in listOf(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionStage.RAW_CONFIDENCE, DepthAcquisitionStage.FULL_DEPTH)) {
            val counters = DepthAcquisitionFailureCounters()
            counters.beginDepthAcquisition()
            counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE)
            counters.record(stage, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED)
            val failure = counters.consumeMostSevereSinceLastCheck()
            assertEquals(stage.name, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, failure)
            val assessment = assess(valid = true, failure = failure)
            assertFalse(stage.name, assessment.outputAllowed)
            assertTrue(stage.name, assessment.shouldHandleLoss)
            assertEquals(stage.name, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, assessment.fatalAcquisitionFailure)
        }
    }

    @Test
    fun aDepthStageFailureRecordedBeforeThisAcquisitionIsNotCarriedIntoIt() {
        val counters = DepthAcquisitionFailureCounters()
        counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.ILLEGAL_STATE)
        counters.beginDepthAcquisition()
        assertNull(counters.consumeMostSevereSinceLastCheck())
        assertEquals(1, counters.count(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.ILLEGAL_STATE))
        assertEquals(setOf(DepthAcquisitionStage.CAMERA_IMAGE),
            DepthAcquisitionStage.entries.filterNot { it.decidesDepthGate }.toSet())
    }

    @Test
    fun theProviderStartsTheWindowAtTheTopOfEveryDepthSnapshot() {
        val source = sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider.kt"),
        ).first { it.isFile }.readText()
        val snapshot = source.substringAfter("fun acquireDepthSnapshot(").substringBefore("private fun captureCameraPose(")
        val closed = snapshot.indexOf("if (closed) return DepthFrameSnapshot(0L, null, null, null)")
        val begin = snapshot.indexOf("runtimeAcquisitionFailures.beginDepthAcquisition()")
        val bundle = snapshot.indexOf("acquireDepthBundle(")
        assertTrue(closed in 0 until begin)
        assertTrue(begin < bundle)
        assertEquals(1, Regex("""runtimeAcquisitionFailures\.beginDepthAcquisition\(\)""").findAll(source).count())
    }

    private fun assess(valid: Boolean, failure: DepthAcquisitionFailureReason?, nowMs: Long = 1_033L) =
        assessRuntimeDepthFrame(
            frameTimestampNanos = 2_000L,
            previousFrameTimestampNanos = 1_000L,
            observedAtElapsedRealtimeMs = nowMs,
            lastValidFrameAtElapsedRealtimeMs = 1_000L,
            samplesAreValid = valid,
            staleTimeoutMs = 2_000L,
            acquisitionFailure = failure,
        )
}
