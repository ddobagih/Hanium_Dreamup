package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingMetrics
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingObservation
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingResult
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualTrackingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11-10: detector-box jitter is not evidence that the same material point moved. */
class MotionRepresentativeProvenanceRegressionTest {
    @Test
    fun legacyBoxCenterChangesCannotInventMaterialPointMotion() {
        val outputs = sequence(tracked = false, changeCenter = true)
        assertMeasuredStableRange(outputs)
        assertEquals(ObjectMovementDirection.UNKNOWN, outputs.last().motionEstimate.direction)
        assertNull(outputs.last().motionEstimate.objectVelocityInAnchorMps)
    }

    @Test
    fun newDetectorBoxesCannotInventMaterialPointMotionThroughCurrentTracking() {
        val outputs = sequence(tracked = true, changeCenter = true)
        assertMeasuredStableRange(outputs)
        assertEquals(ObjectMovementDirection.UNKNOWN, outputs.last().motionEstimate.direction)
        assertNull(outputs.last().motionEstimate.objectVelocityInAnchorMps)
    }

    @Test
    fun independentRawProximityRemainsAvailableWithoutMaterialPointEvidence() {
        for (tracked in listOf(false, true)) {
            assertMeasuredStableRange(sequence(tracked, changeCenter = false))
        }
    }

    private fun assertMeasuredStableRange(outputs: List<TrackedObjectDepth>) {
        assertEquals(8, outputs.size)
        assertEquals(1, outputs.map { it.trackId }.distinct().size)
        val latest = outputs.last()
        assertEquals(DepthSource.ARCORE_RAW_DEPTH, latest.source)
        assertEquals(3f, requireNotNull(latest.zDistanceM), 1e-5f)
        assertEquals(3f, requireNotNull(latest.riskDistanceM), 1e-5f)
        assertTrue(latest.confidence.finalScore >= .55f)
        assertEquals(Trend.STABLE, latest.trend)
        assertNull(latest.timeToCollisionMs)
        // This counterexample concerns the spatial vector, not an axial approach/TTC warning.
        assertEquals(ObjectMotion.UNKNOWN, latest.objectMotion)
    }

    private fun sequence(tracked: Boolean, changeCenter: Boolean): List<TrackedObjectDepth> {
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        return (0 until 8).map { index ->
            val source = key(1_000L + index * 250L)
            val target = if (tracked) key(source.frameId / 1_000_000L + 40L) else source
            val center = .45f + if (changeCenter) index * .025f else 0f
            val candidate = DetectionCandidate("car", .9f, RectNorm(center - .1f, .35f, .2f, .3f))
            val snapshot = snapshot(target)
            if (tracked) {
                // Each completed detector batch may jitter while the accepted source-to-target
                // flow itself is zero. No actual feature position/identity is manufactured here.
                val row = VisualTrackingObservation(0, source, target, VisualTrackingStatus.TRACKED,
                    candidate, .95f, null, Point2(0f, 0f), 8, 8, 0f, false)
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(
                    VisualTrackingResult(source, target, listOf(row), VisualTrackingMetrics(0L, 1, 0L, false, 2, 0)),
                    listOf(candidate), true, target.capturedAtElapsedRealtimeMs,
                ))
                pipeline.processTrackedObservation(snapshot, observation, mapper, target.frameId,
                    target.capturedAtElapsedRealtimeMs).single()
            } else {
                pipeline.process(snapshot, target.frameId, target.frameId / 1_000_000L,
                    listOf(candidate), mapper = mapper, mapperFrameId = target.frameId,
                    capturedAtElapsedRealtimeMs = target.capturedAtElapsedRealtimeMs).single()
            }
        }
    }

    private fun key(atMs: Long) = VisualFrameKey(7L, atMs * 1_000_000L,
        10_000_000_000_000L + atMs * 1_000_000L, atMs + 50_000L, 3L)

    private fun snapshot(target: VisualFrameKey) = DepthFrameSnapshot(
        frameTimestampNs = target.frameId,
        rawDepth = DepthImage16(100, 100, IntArray(10_000) { 3_000 }),
        rawConfidence = ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }),
        fullDepth = null,
        rawDepthTimestampNs = target.cameraTimestampNs,
        rawConfidenceTimestampNs = target.cameraTimestampNs,
        cameraImageTimestampNs = target.cameraTimestampNs,
        cameraPoseEvidence = CameraPoseEvidence(42L, target.frameId / 1_000_000L,
            0f, 0f, 0f, 0f, 0f, -1f,
            imageProjection = CameraImageProjection(1_000, 1_000, 700f, 700f, 500f, 500f,
                1f, 0f, 0f, 0f, 1f, 0f)),
    )

    private val mapper = LetterboxCoordinateMapper(
        ModelInputTransform(1_000, 1_000, 1_000, 1_000, 1f, 0f, 0f), ImageSize(100, 100),
    )
}
