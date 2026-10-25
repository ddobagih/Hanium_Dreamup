package kr.co.hanium.dreamup.walksafe.depth

import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Public accepted 9ab tracker -> observation -> actual pipeline. Feature correspondences use the
 * existing deterministic estimator seam; neither point IDs nor correct object IDs are injected.
 * The overlapping detector rectangles are synthetic inputs, not real-video semantic ground truth.
 */
class PointBeforeAssociationPipelineRegressionTest {
    @Test fun uniqueMeasuredPointsBeforeMatchingPreserveIdsDespiteOverlappingBoxCosts() {
        for (reverse in listOf(false, true)) {
            val scene = Scene()
            val before = scene.first()
            val after = scene.next(reverse = reverse)
            val previousIds = pointAssignments(before)
            assertEquals(2, previousIds.size)
            assertEquals(previousIds.keys, pointAssignments(after).keys)
            // Accepted producer already preserves these exact measured points. The regression is
            // the consumer's later matching, not source row ordering or invented feature identity.
            assertEquals(previousIds, pointAssignments(after))
            assertTrue(after.outputs.all { it.trackAgeFrames == 2 })
        }
    }

    @Test fun thirdSourceKeepsDetectorConfirmationAndEachIndependentPointHistory() {
        val scene = Scene()
        val before = scene.first()
        scene.next()
        val third = scene.capture(1_100, 1_200, reverse = false)
        assertEquals(pointAssignments(before), pointAssignments(third))
        assertTrue(third.outputs.all { it.trackAgeFrames == 3 })
        assertTrue(scene.identities.activeTracks().all { it.stable })
        assertNotSame(third.result.observations[0].materialPoint!!.id,
            third.result.observations[1].materialPoint!!.id)
    }

    @Test fun absentOrLowQualityPointsCannotOverrideTheExistingGeometryVeto() {
        for (lowQuality in listOf(false, true)) {
            val scene = Scene()
            val oldIds = scene.first().outputs.map { it.trackId }.toSet()
            val after = scene.next { result -> result.copy(observations = result.observations.map {
                if (lowQuality) it.copy(trackingQuality = .64f) else it.copy(materialPoint = null)
            }) }
            assertEquals(2, after.outputs.size)
            assertTrue(after.outputs.none { it.trackId in oldIds })
        }
    }

    @Test fun newlyMeasuredPointsWithoutACommonFrameStartNewObjectIds() {
        val scene = Scene()
        val before = scene.first()
        // The old point was measured at 1100; new source 1200 has no common measured old target.
        val after = scene.capture(1_200, 1_300)
        assertTrue(after.result.observations.all { it.materialPoint != null })
        assertTrue(after.result.observations.none { row ->
            before.result.observations.any { it.materialPoint!!.id === row.materialPoint!!.id }
        })
        assertTrue(after.outputs.none { output -> before.outputs.any { it.trackId == output.trackId } })
    }

    @Test fun duplicatedOrSharedPointPayloadCannotSelectAnOldObjectId() {
        for (duplicateId in listOf(false, true)) {
            val scene = Scene()
            val oldIds = scene.first().outputs.map { it.trackId }.toSet()
            val after = scene.next { result ->
                val first = result.observations.first().materialPoint!!
                result.copy(observations = result.observations.mapIndexed { index, row ->
                    row.copy(materialPoint = if (duplicateId && index == 1) row.materialPoint!!.copy(id = first.id)
                        else if (!duplicateId) row.materialPoint!!.copy(imagePointNorm = Point2(.35f, .5f))
                        else row.materialPoint)
                })
            }
            assertTrue(after.outputs.none { it.trackId in oldIds })
        }
    }

    @Test fun anUntrackedSameClassPeerPreventsPointBasedPartialMatching() {
        val scene = Scene()
        val before = scene.first()
        val after = scene.next { result -> result.copy(observations = result.observations.mapIndexed { index, row ->
            if (index == 1) row.copy(status = VisualTrackingStatus.LOST, geometry = null, materialPoint = null,
                failure = VisualTrackingFailure.OBJECT_BUDGET_EXCEEDED) else row
        }) }
        assertEquals(1, after.outputs.size)
        assertTrue(after.outputs.none { output -> before.outputs.any { it.trackId == output.trackId } })
    }

    @Test fun aMissedTrackCannotBeRecoveredOnlyByItsOldMeasuredPointId() {
        val scene = Scene()
        val before = scene.first()
        scene.identities.predictOnly()
        val after = scene.next()
        assertEquals(pointAssignments(before).keys, pointAssignments(after).keys)
        assertTrue(after.outputs.none { output -> before.outputs.any { it.trackId == output.trackId } })
    }

    @Test fun sameTargetWithAnotherSourceRemainsTheExistingCallerBoundary() {
        val scene = Scene()
        scene.first()
        val before = scene.ledger()
        val input = scene.input(1_050, 1_100)
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(input.result,
            input.detections, true, input.target.capturedAtElapsedRealtimeMs))
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(input.target), observation, MAPPER,
            input.target.frameId, input.target.capturedAtElapsedRealtimeMs).isEmpty())
        assertEquals(before, scene.ledger())
    }

    @Test fun aForeignPointKeyIsRejectedBeforeAnyAssociationOrDepthMutation() {
        val scene = Scene()
        scene.first()
        val before = scene.ledger()
        val input = scene.input(1_050, 1_150)
        val foreign = input.result.copy(observations = input.result.observations.map { row ->
            row.copy(materialPoint = row.materialPoint!!.copy(targetKey = row.trackedTargetKey.copy(epoch = 8L)))
        })
        assertNull(CurrentTrackedFrameObservation.fromTrackingResult(foreign, input.detections, true,
            input.target.capturedAtElapsedRealtimeMs))
        assertEquals(before, scene.ledger())
    }

    @Test fun staleReceiptAndOldSourceCannotRenewTheAcceptedAssignments() {
        val scene = Scene()
        scene.first()
        scene.next()
        val before = scene.ledger()
        val stale = scene.input(1_100, 1_200)
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(stale.result,
            stale.detections, true, stale.target.capturedAtElapsedRealtimeMs))
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(stale.target), observation, MAPPER,
            stale.target.frameId, stale.target.capturedAtElapsedRealtimeMs + CurrentTrackedFrameObservation.MAX_SOURCE_AGE_MS + 1L).isEmpty())
        assertEquals(before, scene.ledger())
        val old = scene.input(1_000, 1_250)
        val oldObservation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(old.result,
            old.detections, true, old.target.capturedAtElapsedRealtimeMs))
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(old.target), oldObservation, MAPPER,
            old.target.frameId, old.target.capturedAtElapsedRealtimeMs).isEmpty())
        assertEquals(before, scene.ledger())
    }

    @Test fun anotherEpochCannotEnterThePreviousPipelineOrReuseItsLedger() {
        val scene = Scene()
        scene.first()
        val before = scene.ledger()
        val source = key(1_200).copy(epoch = 8L)
        val target = key(1_250).copy(epoch = 8L)
        assertTrue(scene.visual.offerFrame(frame(source)).reset)
        assertTrue(scene.visual.offerFrame(frame(target)).accepted)
        val detections = boxes(1_200)
        val result = scene.visual.trackFrom(source, detections, target)
        val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result, detections,
            true, target.capturedAtElapsedRealtimeMs))
        assertTrue(scene.pipeline.processTrackedObservation(snapshot(target), observation, MAPPER,
            target.frameId, target.capturedAtElapsedRealtimeMs).isEmpty())
        assertEquals(before, scene.ledger())
    }

    private data class Input(val target: VisualFrameKey, val detections: List<DetectionCandidate>,
        val result: VisualTrackingResult)
    private data class Seen(val result: VisualTrackingResult, val outputs: List<TrackedObjectDepth>)
    private class Scene {
        val visual = InterFrameDetectionTracker(VisualTrackingConfig(), { 0L }, MeasuredFlow())
        val identities = ObjectTracker()
        val pipeline = ObjectDepthRuntimePipeline(tracker = identities, rawDepthMotionOnly = true)
        private var offeredAt = 950L
        fun first() = capture(1_000, 1_100, reverse = false)
        fun next(reverse: Boolean = true, transform: (VisualTrackingResult) -> VisualTrackingResult = { it }) =
            capture(1_050, 1_150, reverse, transform)
        fun input(sourceAt: Long, targetAt: Long, reverse: Boolean = true): Input {
            while (offeredAt < targetAt) {
                offeredAt += 50L
                assertTrue(visual.offerFrame(frame(key(offeredAt))).accepted)
            }
            val detections = boxes(sourceAt).let { if (reverse) it.reversed() else it }
            return Input(key(targetAt), detections, visual.trackFrom(key(sourceAt), detections, key(targetAt)))
        }
        fun capture(sourceAt: Long, targetAt: Long, reverse: Boolean = true,
            transform: (VisualTrackingResult) -> VisualTrackingResult = { it }): Seen {
            val input = input(sourceAt, targetAt, reverse)
            val result = transform(input.result)
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result,
                input.detections, true, input.target.capturedAtElapsedRealtimeMs))
            return Seen(result, pipeline.processTrackedObservation(snapshot(input.target), observation,
                MAPPER, input.target.frameId, input.target.capturedAtElapsedRealtimeMs))
        }
        fun ledger() = identities.activeTracks().associate { track ->
            track.trackId to Triple(track.ageFrames, track.lastSeenAtMs, track.distanceHistory.toList())
        }
    }

    private class MeasuredFlow : VisualMotionEstimator {
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
            val dx = .1f * (target.key.frameId - source.key.frameId) / 1_000_000_000f
            val edgeDx = .1f * (target.key.frameId - previous.key.frameId) / 1_000_000_000f
            return VisualMotionEstimate(features.map { it.copy(currentX = it.sourceX + dx * target.width) },
                VisualAffineTransform(tx = dx), .95f, 0f,
                materialPoint = materialPoint?.let { it.copy(x = it.x + edgeDx * target.width) })
        }
    }

    private companion object {
        fun key(at: Long) = VisualFrameKey(7L, at * 1_000_000L,
            10_000_000_000_000L + at * 1_000_000L, at + 50_000L, 3L)
        fun frame(key: VisualFrameKey) = GrayTrackingFrame.copyOf(key, 100, 100, ByteArray(10_000))
        fun boxes(at: Long) = listOf(.10f, .28f).map { x ->
            DetectionCandidate("person", .95f, RectNorm(x + .1f * (at - 1_000L) / 1_000f, .35f, .30f, .30f))
        }
        fun pointAssignments(seen: Seen) = seen.result.observations.associate { row ->
            row.materialPoint!!.id to seen.outputs.single { it.sourceDetectionIndex == row.sourceIndex }.trackId
        }
        fun snapshot(key: VisualFrameKey) = DepthFrameSnapshot(key.frameId,
            DepthImage16(100, 100, IntArray(10_000) { 3_000 }),
            ConfidenceImage8(100, 100, ByteArray(10_000) { 255.toByte() }), null,
            rawDepthTimestampNs = key.cameraTimestampNs, rawConfidenceTimestampNs = key.cameraTimestampNs,
            cameraImageTimestampNs = key.cameraTimestampNs,
            cameraPoseEvidence = CameraPoseEvidence(42L, key.frameId / 1_000_000L, 0f, 0f, 0f,
                0f, 0f, -1f, imageProjection = CameraImageProjection(1_000, 1_000, 700f, 700f,
                    500f, 500f, 1f, 0f, 0f, 0f, 1f, 0f)))
        val MAPPER = LetterboxCoordinateMapper(ModelInputTransform(1_000, 1_000, 1_000, 1_000,
            1f, 0f, 0f), ImageSize(100, 100))
    }
}
