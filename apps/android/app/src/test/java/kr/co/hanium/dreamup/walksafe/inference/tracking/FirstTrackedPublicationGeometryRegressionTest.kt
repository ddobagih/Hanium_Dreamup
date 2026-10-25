package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

/**
 * The existing deterministic Flow seam, through the public producer and real depth consumer.
 * This first publication has no earlier detector confirmation or current geometry in the pipeline.
 * Synthetic feature measurements do not establish native/video semantic ground truth.
 */
class FirstTrackedPublicationGeometryRegressionTest {
    @Test fun firstTrackedPublicationKeepsValidBackfilledCurrentGeometry() {
        val flow = Flow(null)
        val frames = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)
        val source = key(1_000L)
        val target = key(1_500L)
        val detections = listOf(detection)
        for (at in 1_000L..1_500L step 50L) {
            val admission = frames.offerFrame(frame(at))
            assertTrue(admission.accepted)
            assertNull(admission.failure)
        }
        assertTrue(identities.activeTracks().isEmpty())
        val result = frames.trackFrom(source, detections, target)
        assertNull(result.batchFailure)
        assertEquals(source, result.detectorSourceKey)
        assertEquals(target, result.trackedTargetKey)
        val row = result.observations.single()
        assertEquals(0, row.sourceIndex)
        assertEquals(VisualTrackingStatus.TRACKED, row.status)
        assertNull(row.failure)
        assertEquals(source, row.detectorSourceKey)
        assertEquals(target, row.trackedTargetKey)
        assertEquals(.95f, row.trackingQuality, .001f)
        assertTrue(row.trackingQuality >= .65f)
        assertEquals(39, row.originalFeatureCount)
        assertEquals(39, row.survivingFeatureCount)
        val transform = requireNotNull(row.transformNorm)
        assertTrue(transform.translationOnly)
        assertEquals(.40f, transform.tx, .001f)
        assertEquals(0f, transform.ty, .001f)
        val geometry = requireNotNull(row.geometry)
        assertEquals(detection.className, geometry.className)
        assertEquals(detection.detectionConfidence, geometry.detectionConfidence, .001f)
        assertEquals(detection.bboxNorm.x + transform.tx, geometry.bboxNorm.x, .001f)
        assertEquals(.55f, geometry.bboxNorm.x, .001f)
        assertEquals(detection.bboxNorm.y, geometry.bboxNorm.y, .001f)
        assertEquals(detection.bboxNorm.width, geometry.bboxNorm.width, .001f)
        assertEquals(detection.bboxNorm.height, geometry.bboxNorm.height, .001f)
        assertEquals(detection.polygonNorm.map(transform::map), geometry.polygonNorm)
        assertEquals(target, requireNotNull(row.materialPoint).targetKey)
        assertTrue(row.materialPoint!!.imagePointNorm.x in .55f.. .75f)
        assertEquals((1_000L..1_450L step 50L).map { it to it + 50L }, flow.edges)
        assertEquals(10, result.metrics.processedEdges)
        assertEquals(500L, target.capturedAtElapsedRealtimeMs - source.capturedAtElapsedRealtimeMs)

        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
            detections, true, target.capturedAtElapsedRealtimeMs))
        assertEquals(source, observation.sourceKey)
        assertEquals(target, observation.targetKey)
        assertEquals(detections, observation.sourceDetections)
        assertEquals(listOf(row), observation.trackedObservations)
        val depth = snapshot(target)
        assertTrue(observation.isFreshAt(target.capturedAtElapsedRealtimeMs))
        assertTrue(observation.matchesDepthSnapshot(depth))
        val outputs = pipeline.processTrackedObservation(depth, observation, mapper,
            target.frameId, target.capturedAtElapsedRealtimeMs)
        // One completed detection can expose measured current depth without becoming stable.
        // Keep this assertion after the actual producer/admission/affine/capture checks.
        assertEquals("First publication must retain the ten accepted 50ms image edges", 1, outputs.size)
        val output = outputs.single()
        assertEquals(0, output.sourceDetectionIndex)
        assertEquals(.55f, output.bboxNorm.x, .001f)
        assertEquals(1, output.trackAgeFrames)
        assertEquals(DepthAvailability.MEASURED, output.depthAvailability)
        assertEquals(3f, output.riskDistanceM!!, .001f)
        val track = identities.activeTracks().single()
        assertEquals(output.trackId, track.trackId)
        assertEquals(1, track.ageFrames)
        assertFalse(track.stable)
        assertEquals(0, track.missedFrames)
        assertEquals(1_000L, track.lastDetectionAtMs)
        assertEquals(1_500L, track.lastSeenAtMs)
    }

    // Unchanged seed/edge equations from TrackedPublishCadenceRegressionTest.Flow.
    private class Flow(val lostAtMs: Long?) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        val edges = mutableListOf<Pair<Long, Long>>()

        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate,
            budget: TrackingWorkBudget): List<VisualMotionFeature> = (0 until 40).map { index ->
            val box = detection.bboxNorm
            VisualMotionFeature(((box.x + box.width * (.1f + (index % 8) * .8f / 7f)) * frame.width).toInt(),
                ((box.y + box.height * (.1f + (index / 8) * .8f / 4f)) * frame.height).toInt())
        }

        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val before = previous.key.frameId / 1_000_000L
            val after = target.key.frameId / 1_000_000L
            edges += before to after
            val dx = .8f * (after - source.key.frameId / 1_000_000L) / 1_000f
            val edgeDx = .8f * (after - before) / 1_000f
            return VisualMotionEstimate(
                features.map { it.copy(currentX = it.sourceX + dx * target.width) },
                VisualAffineTransform(tx = dx), .95f, 0f,
                failure = VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH.takeIf { after == lostAtMs },
                materialPoint = materialPoint?.copy(x = materialPoint.x + edgeDx * target.width))
        }
    }

    private companion object {
        val detection = DetectionCandidate("car", .95f, RectNorm(.15f, .40f, .20f, .20f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))
        fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L,
            10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L)
        fun frame(at: Long) = GrayTrackingFrame.copyOf(key(at), 100, 100, ByteArray(10_000))
        fun snapshot(target: VisualFrameKey) = DepthFrameSnapshot(target.frameId,
            DepthImage16(100, 100, IntArray(10_000) { 3_000 }),
            ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
            rawDepthTimestampNs = target.cameraTimestampNs,
            rawConfidenceTimestampNs = target.cameraTimestampNs,
            cameraImageTimestampNs = target.cameraTimestampNs,
            cameraPoseEvidence = CameraPoseEvidence(42L, target.frameId / 1_000_000L,
                0f, 0f, 0f, 0f, 0f, -1f,
                CameraImageProjection(100, 100, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
    }
}
