package kr.co.hanium.dreamup.walksafe.depth

import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.ResourceExhaustedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-18: operational depth failures keep distinct, bounded reasons instead of a bare null. */
class DepthAcquisitionFailureCountersTest {
    @Test
    fun arCoreFailuresMapToDistinctReasonsIncludingDeadlineBeforeIllegalState() {
        assertEquals(DepthAcquisitionFailureReason.NOT_YET_AVAILABLE, depthAcquisitionFailureReason(NotYetAvailableException()))
        assertEquals(DepthAcquisitionFailureReason.NOT_TRACKING, depthAcquisitionFailureReason(NotTrackingException()))
        // DeadlineExceededException is an IllegalStateException; an expired frame is not a generic state error.
        assertEquals(DepthAcquisitionFailureReason.DEADLINE_EXCEEDED, depthAcquisitionFailureReason(DeadlineExceededException()))
        assertEquals(DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, depthAcquisitionFailureReason(ResourceExhaustedException()))
        assertEquals(DepthAcquisitionFailureReason.ILLEGAL_STATE, depthAcquisitionFailureReason(IllegalStateException()))
    }

    @Test
    fun countersSeparateStageAndReasonAndStayBounded() {
        val counters = DepthAcquisitionFailureCounters()
        assertEquals("none", counters.summaryText())
        repeat(3) { counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE) }
        counters.record(DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED)
        counters.record(DepthAcquisitionStage.RAW_PAIR, DepthAcquisitionFailureReason.PAIR_MISMATCH)

        assertEquals(3, counters.count(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE))
        assertEquals(0, counters.count(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED))
        assertEquals(1, counters.count(DepthAcquisitionStage.FULL_DEPTH, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED))
        assertEquals(
            "raw:NOT_YET_AVAILABLE=3 raw_pair:PAIR_MISMATCH=1 full:RESOURCE_EXHAUSTED=1",
            counters.summaryText(),
        )
        // Every possible key is an enum pair: the summary can never exceed this fixed set.
        DepthAcquisitionStage.entries.forEach { stage ->
            DepthAcquisitionFailureReason.entries.forEach { reason -> counters.record(stage, reason) }
        }
        assertEquals(
            DepthAcquisitionStage.entries.size * DepthAcquisitionFailureReason.entries.size,
            counters.summaryText().split(" ").size,
        )
    }

    @Test
    fun cameraImageFailuresAreCountedPerConsumerAndTheFieldLogRecordIsBounded() {
        // S11-18 (originals/11.txt:177-182): the shared camera image no longer sums four consumers.
        val counters = DepthAcquisitionFailureCounters()
        repeat(2) { counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE, CameraImageConsumer.DEPTH) }
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.RESOURCE_EXHAUSTED, CameraImageConsumer.VISUAL_TRACKING)
        // The detector's acquisition stays the plain camera key.
        counters.record(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE)
        assertEquals(1, counters.count(DepthAcquisitionStage.CAMERA_IMAGE, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE))
        assertEquals(2, counters.cameraCount(CameraImageConsumer.DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE))
        assertEquals(
            "camera:NOT_YET_AVAILABLE=1 camera.depth:NOT_YET_AVAILABLE=2 camera.visual_tracking:RESOURCE_EXHAUSTED=1",
            counters.summaryText(),
        )
        // Camera failures never decide the depth gate, labeled or not.
        assertNull(counters.consumeMostSevereSinceLastCheck())
        val first = requireNotNull(counters.fieldLogSummaryIfDue(1_000L))
        assertEquals("camera:NOT_YET_AVAILABLE=1+camera.depth:NOT_YET_AVAILABLE=2+camera.visual_tracking:RESOURCE_EXHAUSTED=1", first)
        assertTrue(Regex("[A-Za-z0-9_.:=+\\-]{0,160}").matches(first))
        counters.record(DepthAcquisitionStage.RAW_DEPTH, DepthAcquisitionFailureReason.NOT_YET_AVAILABLE)
        // At most once per interval, and never an unchanged repeat.
        assertNull(counters.fieldLogSummaryIfDue(1_000L + DepthAcquisitionFailureCounters.FIELD_LOG_INTERVAL_MS - 1L))
        assertNotNull(counters.fieldLogSummaryIfDue(1_000L + DepthAcquisitionFailureCounters.FIELD_LOG_INTERVAL_MS))
        assertNull(counters.fieldLogSummaryIfDue(1_000L + 3 * DepthAcquisitionFailureCounters.FIELD_LOG_INTERVAL_MS))
    }

    @Test
    fun rawPairMismatchIsReportedOnceAndMissingConfidenceIsNot() {
        var mismatches = 0
        val raw = Closable()
        val confidence = Closable()
        assertNull(acquireMatchingRawDepthPair({ raw }, { confidence }, onPairMismatch = { mismatches += 1 }) { _, _ -> false })
        assertEquals(1, mismatches)
        assertEquals(1, raw.closed)
        assertEquals(1, confidence.closed)

        val lonelyRaw = Closable()
        assertNull(acquireMatchingRawDepthPair({ lonelyRaw }, { null }, onPairMismatch = { mismatches += 1 }) { _, _ -> true })
        assertEquals(1, mismatches)
        assertEquals(1, lonelyRaw.closed)
    }

    private class Closable : AutoCloseable {
        var closed = 0
        override fun close() {
            closed += 1
        }
    }
}
