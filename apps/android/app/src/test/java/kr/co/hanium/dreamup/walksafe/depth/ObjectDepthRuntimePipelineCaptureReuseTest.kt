package kr.co.hanium.dreamup.walksafe.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S11-13: the legacy entry may replay one completed detector region only for the depth capture it
 * was detected on. A newer depth image under that old region could read background.
 */
class ObjectDepthRuntimePipelineCaptureReuseTest {
    private val person = listOf(DetectionCandidate("person", 0.9f, RectNorm(0.35f, 0.20f, 0.30f, 0.55f)))

    @Test
    fun reusedDetectorRegionCannotSampleANewerDepthCapture() {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker)

        val first = pipeline.processPrealignedForTest(capture(1_000_000_000L, 1_200), 1L, 1_000L, person, detectionSequenceId = 10L).single()
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, first.source)

        val newerDepthUnderOldRegion = pipeline.processPrealignedForTest(
            capture(1_100_000_000L, 1_500), 2L, 1_100L, person, detectionSequenceId = 10L,
        )
        assertTrue(newerDepthUnderOldRegion.isEmpty())
        assertEquals(listOf(1.2f), tracker.activeTracks().single().distanceHistory.map { it.distanceM })

        // Replaying the detector's own capture keeps the documented no-new-confirmation behavior.
        val replay = pipeline.processPrealignedForTest(capture(1_000_000_000L, 1_200), 3L, 1_200L, person, detectionSequenceId = 10L).single()
        assertEquals(1, replay.trackAgeFrames)

        // A new completed detection supplies current geometry for the newer capture.
        val next = pipeline.processPrealignedForTest(capture(1_100_000_000L, 1_500), 4L, 1_300L, person, detectionSequenceId = 11L).single()
        assertEquals(2, next.trackAgeFrames)
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, next.source)
        assertEquals(1.5f, next.riskDistanceM!!, 0.01f)
    }

    private fun capture(timestampNs: Long, mm: Int) = DepthFrameSnapshot(
        frameTimestampNs = timestampNs,
        rawDepth = DepthImage16(40, 40, IntArray(40 * 40) { mm }),
        rawConfidence = ConfidenceImage8(40, 40, ByteArray(40 * 40) { 255.toByte() }),
        fullDepth = DepthImage16(40, 40, IntArray(40 * 40) { mm }),
        rawDepthTimestampNs = timestampNs,
        rawConfidenceTimestampNs = timestampNs,
        fullDepthTimestampNs = timestampNs,
        cameraImageTimestampNs = timestampNs,
    )
}
