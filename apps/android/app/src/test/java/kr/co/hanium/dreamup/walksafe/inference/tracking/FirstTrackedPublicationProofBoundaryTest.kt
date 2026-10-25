package kr.co.hanium.dreamup.walksafe.inference.tracking

import kr.co.hanium.dreamup.walksafe.depth.*
import org.junit.Assert.*
import org.junit.Test

/** Negative inputs to the public producer -> observation -> actual pipeline source-proof path. */
class FirstTrackedPublicationProofBoundaryTest {
    @Test fun invalidAffineQualityOrFeatureCountsCannotAdmitALargeFirstBackfill() {
        val invalidRows: List<(VisualTrackingObservation) -> VisualTrackingObservation> = listOf(
            { it.copy(transformNorm = requireNotNull(it.transformNorm).copy(tx = .39f)) },
            { it.copy(trackingQuality = .64f) },
            { it.copy(originalFeatureCount = 5, survivingFeatureCount = 5) },
        )
        for (invalidate in invalidRows) {
            val scene = Scene()
            val measured = scene.tracked(1_000L, 1_500L)
            assertAccepted(measured, .4f)
            // Deliberately invalid packet metadata; do not invent a positive image measurement.
            val invalid = measured.copy(observations = measured.observations.map(invalidate))
            assertTrue(scene.publish(invalid).isEmpty())
            val track = scene.identities.activeTracks().single()
            assertEquals(1, track.ageFrames)
            assertEquals(1_000L, track.lastSeenAtMs)
            assertFalse(track.stable)
            assertEquals(1, track.missedFrames)
        }
    }

    @Test fun lostExpiredForeignAndWrongCpuCapturesCannotStartSourceGeometry() {
        val lostScene = Scene(lostAtMs = 1_250L)
        val lost = lostScene.tracked(1_000L, 1_500L)
        val lostRow = lost.observations.single()
        assertEquals(VisualTrackingStatus.LOST, lostRow.status)
        assertEquals(VisualTrackingFailure.FORWARD_BACKWARD_MISMATCH, lostRow.failure)
        assertNull(lostRow.geometry)
        assertNull(lostRow.materialPoint)
        assertTrue(lostScene.publish(lost).isEmpty())

        val scene = Scene()
        val measured = scene.tracked(1_000L, 1_500L)
        assertAccepted(measured, .4f)
        assertNull(CurrentTrackedFrameObservation.fromTrackingResult(measured, listOf(detection), true,
            measured.detectorSourceKey.capturedAtElapsedRealtimeMs + CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS + 1L))
        val foreign = measured.copy(observations = measured.observations.map { row ->
            row.copy(trackedTargetKey = row.trackedTargetKey.copy(epoch = 8L))
        })
        assertNull(CurrentTrackedFrameObservation.fromTrackingResult(foreign, listOf(detection), true,
            measured.trackedTargetKey.capturedAtElapsedRealtimeMs))
        val wrongCpu = snapshot(measured.trackedTargetKey).copy(
            cameraImageTimestampNs = measured.trackedTargetKey.cameraTimestampNs + 1L)
        assertTrue(scene.publish(measured, wrongCpu).isEmpty())
        assertTrue(scene.identities.activeTracks().isEmpty())
    }

    @Test fun aLateSourceProofCannotReplaceMoreRecentCurrentGeometry() {
        // Two source estimates intentionally disagree on the intervening current image.
        // Each packet comes through the measured-flow seam; neither supplies an object ID.
        val scene = Scene(lateSourceScenario = true)
        val before = scene.tracked(1_000L, 1_150L)
        assertAccepted(before, -.05f)
        val first = scene.publish(before).single()
        val previous = scene.identities.activeTracks().single()
        assertEquals(first.trackId, previous.trackId)
        assertEquals(1_150L, previous.lastSeenAtMs)
        val previousGeometry = requireNotNull(previous.latestGeometry)
        assertEquals(.10f, previousGeometry.bboxNorm.x, .001f)

        val late = scene.tracked(1_100L, 1_600L)
        assertAccepted(late, .4f)
        assertEquals(.55f, late.observations.single().geometry!!.bboxNorm.x, .001f)
        assertTrue(scene.publish(late).isEmpty())
        val track = scene.identities.activeTracks().single()
        // The existing unambiguous source assignment is retained, but source1100 is older than
        // the measured current1150. Its source->1600 affine cannot justify current1150->1600.
        assertEquals(first.trackId, track.trackId)
        assertEquals(2, track.ageFrames)
        assertEquals(1_100L, track.lastDetectionAtMs)
        assertEquals(previousGeometry, track.latestGeometry)
        assertEquals(1_150L, track.lastSeenAtMs)
        assertEquals(1, track.missedFrames)
        assertFalse(track.stable)
    }

    private class Scene(lostAtMs: Long? = null, lateSourceScenario: Boolean = false) {
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)
        private val visual = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L },
            MeasuredFlow(lostAtMs, lateSourceScenario))
        private var offeredAt = 950L

        fun tracked(sourceAt: Long, targetAt: Long): VisualTrackingResult {
            while (offeredAt < targetAt) {
                offeredAt += 50L
                assertTrue(visual.offerFrame(frame(offeredAt)).accepted)
            }
            return visual.trackFrom(key(sourceAt), listOf(detection), key(targetAt)).also {
                assertNull(it.batchFailure)
            }
        }

        fun publish(result: VisualTrackingResult,
            depth: DepthFrameSnapshot = snapshot(result.trackedTargetKey)): List<TrackedObjectDepth> {
            val target = result.trackedTargetKey
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
                listOf(detection), true, target.capturedAtElapsedRealtimeMs))
            return pipeline.processTrackedObservation(depth, observation, mapper,
                target.frameId, target.capturedAtElapsedRealtimeMs)
        }
    }

    private class MeasuredFlow(val lostAtMs: Long?, val lateSourceScenario: Boolean) : VisualMotionEstimator {
        override val supportsMaterialPointTracking: Boolean get() = true
        override fun seed(frame: GrayTrackingFrame, detection: DetectionCandidate,
            budget: TrackingWorkBudget): List<VisualMotionFeature> = (0 until 40).map { index ->
            val box = detection.bboxNorm
            VisualMotionFeature(((box.x + box.width * (.1f + (index % 8) * .8f / 7f)) * frame.width).toInt(),
                ((box.y + box.height * (.1f + (index / 8) * .8f / 4f)) * frame.height).toInt())
        }

        override fun advance(source: GrayTrackingFrame, previous: GrayTrackingFrame, target: GrayTrackingFrame,
            detection: DetectionCandidate, features: List<VisualMotionFeature>, originalFeatureCount: Int,
            budget: TrackingWorkBudget, materialPoint: VisualMotionPoint?): VisualMotionEstimate {
            val sourceAt = source.key.frameId / 1_000_000L
            val before = previous.key.frameId / 1_000_000L
            val after = target.key.frameId / 1_000_000L
            val speed = if (lateSourceScenario && sourceAt == 1_000L) -1f / 3f else .8f
            val dx = speed * (after - sourceAt) / 1_000f
            val edgeDx = speed * (after - before) / 1_000f
            return VisualMotionEstimate(features.map { it.copy(currentX = it.sourceX + dx * target.width) },
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
        fun assertAccepted(result: VisualTrackingResult, dx: Float) {
            val row = result.observations.single()
            assertEquals(VisualTrackingStatus.TRACKED, row.status)
            assertNull(row.failure)
            assertEquals(.95f, row.trackingQuality, .001f)
            assertEquals(39, row.originalFeatureCount)
            assertEquals(39, row.survivingFeatureCount)
            assertEquals(result.detectorSourceKey, row.detectorSourceKey)
            assertEquals(result.trackedTargetKey, row.trackedTargetKey)
            assertEquals(dx, requireNotNull(row.transformNorm).tx, .001f)
            assertEquals(detection.bboxNorm.x + dx, requireNotNull(row.geometry).bboxNorm.x, .001f)
        }
    }
}
