package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualFrameKey
import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPointId
import org.junit.Assert.*
import org.junit.Test

/** Explicit synthetic physical points, sampled by the actual current-frame depth pipeline. */
class AxialTtcSpatialContradictionRegressionTest {
    @Test fun decreasingAxialDepthCannotClaimCollisionWhenTheMeasuredPointIsAlreadyMovingAway() {
        val seen = sequence(lateralSpeedMps = 2f, finalLateralPositionM = 1.1f)
        val output = seen.output
        val velocity = requireNotNull(output.motionEstimate.relativeVelocityInAnchorMps)
        val sample = seen.track.denseMotionHistory().last()
        val position = requireNotNull(sample.objectPositionInAnchor)
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(2f, output.riskDistanceM!!, .001f)
        assertEquals(Trend.APPROACHING, output.trend)
        assertEquals(1f, output.approachSpeedMps!!, .001f)
        assertEquals(2f, velocity.x, .001f)
        assertEquals(1f, velocity.z, .001f)
        assertEquals(ObjectMovementDirection.RIGHT, output.motionEstimate.direction)
        assertTrue(output.motionEstimate.relativeClosingSpeedMps!! < -.05f)
        assertTrue("The closest approach of this measured relative trajectory is already in the past",
            -position.dot(velocity) / velocity.dot(velocity) < 0f)
        assertNull("Axial distance/slope cannot overrule the measured non-closing 3D trajectory",
            output.timeToCollisionMs)
    }

    @Test fun aMeasuredDirectApproachPreservesTheNormalTwoSecondEstimate() {
        val seen = sequence(lateralSpeedMps = 0f, finalLateralPositionM = 0f)
        val output = seen.output
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(1f, output.motionEstimate.relativeClosingSpeedMps!!, .001f)
        assertEquals(ObjectMovementDirection.TOWARD_USER, output.motionEstimate.direction)
        assertEquals(1f, output.approachSpeedMps!!, .001f)
        assertTrue(requireNotNull(output.timeToCollisionMs) in 1_999L..2_001L)
    }

    private fun sequence(lateralSpeedMps: Float, finalLateralPositionM: Float): Seen {
        val tracker = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = tracker, rawDepthMotionOnly = true)
        val pointId = VisualMaterialPointId()
        var output: TrackedObjectDepth? = null
        for (index in 0..3) {
            val at = 1_000L + index * 200L
            val key = VisualFrameKey(7L, at * 1_000_000L, 10_000_000_000_000L + at * 1_000_000L,
                at + 50_000L, 3L)
            val millimeters = 2_600 - index * 200
            val z = millimeters / 1_000f
            val x = finalLateralPositionM - lateralSpeedMps * (3 - index) * .2f
            val point = Point2(.5f + .7f * x / z, .5f)
            val observation = knownMaterialPointObservation(key, key, detection, pointId, point)
            val snapshot = DepthFrameSnapshot(key.frameId,
                DepthImage16(100, 100, IntArray(10_000) { millimeters }),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
                rawDepthTimestampNs = key.cameraTimestampNs,
                rawConfidenceTimestampNs = key.cameraTimestampNs,
                cameraImageTimestampNs = key.cameraTimestampNs,
                cameraPoseEvidence = CameraPoseEvidence(42L, at, 0f, 0f, 0f, 0f, 0f, -1f,
                    CameraImageProjection(100, 100, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
            output = pipeline.processTrackedObservation(snapshot, observation, mapper,
                key.frameId, key.capturedAtElapsedRealtimeMs).single()
        }
        return Seen(requireNotNull(output), tracker.activeTracks().single())
    }

    private data class Seen(val output: TrackedObjectDepth, val track: TrackState)

    private companion object {
        val detection = DetectionCandidate("car", .95f, RectNorm(0f, 0f, 1f, 1f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))
    }
}
