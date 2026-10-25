package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic flow seam through the actual frame producer, admission and depth consumer. */
class TrackedPublishCadenceRegressionTest {
    @Test fun consumerCadenceCannotEraseAValidBackfilledTrack() {
        val frequent = Fixture().run(publishIntervalMs = 100L)
        assertEquals(1, frequent.outputs.size)
        assertEquals(frequent.initialTrackId, frequent.outputs.single().trackId)
        assertAcceptedFlow(frequent)

        val delayed = Fixture().run(publishIntervalMs = 500L)
        assertAcceptedFlow(delayed)
        assertEquals("The same ten accepted 50ms image edges must survive a 500ms publication gap",
            1, delayed.outputs.size)
        val output = delayed.outputs.single()
        assertEquals(delayed.initialTrackId, output.trackId)
        assertEquals(frequent.outputs.single().bboxNorm, output.bboxNorm)
        assertEquals(.65f, output.centerNorm.x, .001f)
        assertEquals(3, delayed.track.ageFrames)
        assertTrue(delayed.track.stable)
    }

    @Test fun frequentPublicationPreservesTheSameIdentityWithoutAddingDetectorConfirmations() {
        val seen = Fixture().run(publishIntervalMs = 100L)
        assertAcceptedFlow(seen)
        assertEquals(seen.initialTrackId, seen.outputs.single().trackId)
        assertEquals(3, seen.track.ageFrames)
        assertTrue(seen.track.stable)
        assertEquals(2_300L, seen.track.lastSeenAtMs)
        assertEquals(1_800L, seen.track.lastDetectionAtMs)
        assertEquals(3f, seen.outputs.single().riskDistanceM!!, .001f)
    }

    @Test fun aLongPublicationGapDoesNotAuthorizeAnUnmeasuredBoxJump() {
        val fixture = Fixture()
        val initialId = fixture.warm()
        val unsupported = MaskPolygonExtractor().extract(detection.copy(bboxNorm = RectNorm(.55f, .40f, .20f, .20f)))
        assertTrue(fixture.identities.applyTrackedObservations(listOf(initialId to unsupported), 2_300L).isEmpty())
        assertFalse(fixture.identities.activeTracks().single().stable)
        assertEquals(1, fixture.identities.activeTracks().single().missedFrames)
    }

    @Test fun aLostImageEdgeCannotBeRecoveredByALaterPublication() {
        val seen = Fixture(lostAtMs = 2_100L).run(publishIntervalMs = 500L)
        assertEquals(VisualTrackingStatus.LOST, seen.row.status)
        assertEquals(VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH, seen.row.failure)
        assertNull(seen.row.geometry)
        assertNull(seen.row.materialPoint)
        assertTrue(seen.outputs.isEmpty())
        assertEquals(1, seen.track.missedFrames)
    }

    private fun assertAcceptedFlow(seen: Seen) {
        assertEquals(VisualTrackingStatus.TRACKED, seen.row.status)
        assertNull(seen.row.failure)
        assertEquals(key(1_800L), seen.row.detectorSourceKey)
        assertEquals(key(2_300L), seen.row.trackedTargetKey)
        assertEquals(.40f, seen.row.transformNorm!!.tx, .001f)
        assertEquals(.55f, seen.row.geometry!!.bboxNorm.x, .001f)
        assertTrue(seen.row.trackingQuality >= .65f)
        assertEquals(key(2_300L), seen.row.materialPoint!!.targetKey)
        assertTrue(seen.row.materialPoint!!.imagePointNorm.x in 0.55f..0.75f)
        assertEquals(10, seen.edges.size)
        assertTrue(seen.edges.all { (before, after) -> after - before == 50L })
        assertEquals(500L, seen.row.trackedTargetKey.capturedAtElapsedRealtimeMs -
            seen.row.detectorSourceKey.capturedAtElapsedRealtimeMs)
    }

    private class Fixture(lostAtMs: Long? = null) {
        val flow = Flow(lostAtMs)
        val frames = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, flow)
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)

        fun warm(): String {
            var output: TrackedObjectDepth? = null
            for (at in 1_000L..1_800L step 50L) {
                assertTrue(frames.offerFrame(frame(at)).accepted)
                if (at in listOf(1_000L, 1_400L, 1_800L)) {
                    output = publish(frames.trackFrom(key(at), listOf(detection), key(at))).single()
                }
            }
            val track = identities.activeTracks().single()
            assertEquals(3, track.ageFrames)
            assertTrue(track.stable)
            assertEquals(DepthAvailability.MEASURED, output!!.depthAvailability)
            return output.trackId
        }

        fun run(publishIntervalMs: Long): Seen {
            val initialId = warm()
            var result: VisualTrackingResult? = null
            var outputs = emptyList<TrackedObjectDepth>()
            for (at in 1_850L..2_300L step 50L) {
                assertTrue(frames.offerFrame(frame(at)).accepted)
                if ((at - 1_800L) % publishIntervalMs == 0L) {
                    result = frames.trackFrom(key(1_800L), listOf(detection), key(at))
                    outputs = publish(result)
                }
            }
            return Seen(initialId, result!!.observations.single(), outputs,
                identities.activeTracks().single(), flow.edges.toList())
        }

        private fun publish(result: VisualTrackingResult): List<TrackedObjectDepth> {
            val target = result.trackedTargetKey
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
                listOf(detection), true, target.capturedAtElapsedRealtimeMs))
            val snapshot = DepthFrameSnapshot(target.frameId,
                DepthImage16(100, 100, IntArray(10_000) { 3_000 }),
                ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
                rawDepthTimestampNs = target.cameraTimestampNs,
                rawConfidenceTimestampNs = target.cameraTimestampNs,
                cameraImageTimestampNs = target.cameraTimestampNs,
                cameraPoseEvidence = CameraPoseEvidence(42L, target.frameId / 1_000_000L,
                    0f, 0f, 0f, 0f, 0f, -1f,
                    CameraImageProjection(100, 100, 70f, 70f, 50f, 50f, 1f, 0f, 0f, 0f, 1f, 0f)))
            return pipeline.processTrackedObservation(snapshot, observation, mapper,
                target.frameId, target.capturedAtElapsedRealtimeMs)
        }
    }

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

    private data class Seen(val initialTrackId: String, val row: VisualTrackingObservation,
        val outputs: List<TrackedObjectDepth>, val track: TrackState, val edges: List<Pair<Long, Long>>)

    private companion object {
        val detection = DetectionCandidate("car", .95f, RectNorm(.15f, .40f, .20f, .20f))
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(100, 100, 100, 100, 1f, 0f, 0f), ImageSize(100, 100))
        fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L,
            10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L)
        fun frame(at: Long) = GrayTrackingFrame.copyOf(key(at), 100, 100, ByteArray(10_000))
    }
}
