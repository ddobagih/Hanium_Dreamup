package kr.co.hanium.dreamup.walksafe.depth

import java.util.Random
import kr.co.hanium.dreamup.walksafe.inference.tracking.*
import org.junit.Assert.*
import org.junit.Test

class ObjectTrackerSourceTimeAssociationTest {
    @Test fun movingSeparatedPairKeepsBothIdsAcrossFourHundredMillisecondCaptures() {
        val result = movingScene(count = 2, intervalMs = 400L)
        assertEquals(listOf(1, 1), result.ids.map { it.size })
        assertEquals(94, result.currentOutputs)
        assertEquals(62, result.stableOutputs)
        assertEquals(62, result.warningCandidates)
    }

    @Test fun singleObjectAndFasterDetectorRemainContinuous() {
        val single = movingScene(count = 1, intervalMs = 400L)
        assertEquals(listOf(1), single.ids.map { it.size })
        assertEquals(31, single.stableOutputs)
        val faster = movingScene(count = 2, intervalMs = 100L)
        assertEquals(listOf(1, 1), faster.ids.map { it.size })
        assertEquals(86, faster.stableOutputs)
    }

    @Test fun delayedCaptureUsesItsRecordedPositionsEvenAfterBothObjectsMovedFarther() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.02f, .32f), 1_000L)
        repeat(6) { step ->
            val shift = (step + 1) * .05f
            assertEquals(2, tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to shape(.02f + index * .30f + shift)
            }, 1_050L + step * 50L).size)
        }
        // The new capture at 1250 arrives after the current frame at 1300. Its positions
        // were recorded at 1250; neither the old detector boxes nor 1300 boxes represent it.
        val delayed = tracker.updateSourceWithAssignments(shapes(.27f, .57f), 1_250L,
            shapes(.37f, .67f), 1_350L)
        assertEquals(first.map { it.track.trackId }, delayed.map { it.track.trackId })
        assertTrue(delayed.all { it.track.lastSeenAtMs == 1_300L && it.track.ageFrames == 2 })
    }

    @Test fun sourceTimeConflictCannotBeOverriddenByClearCurrentBoxes() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.20f, .60f), 1_000L)
        tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
            assignment.track.trackId to shape(if (index == 0) .36f else .44f)
        }, 1_100L)
        // At the capture both source boxes coincide with both old tracks. A later flow result
        // that happens to separate again must not manufacture a unique semantic association.
        val next = tracker.updateSourceWithAssignments(shapes(.40f, .40f), 1_100L,
            shapes(.20f, .60f), 1_150L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun clearSourceMatchCannotOverrideCurrentFlowPointingAtTheOtherIdentity() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.10f, .30f), 1_000L)
        tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
            assignment.track.trackId to shape(.10f + index * .20f)
        }, 1_100L)
        val next = tracker.updateSourceWithAssignments(shapes(.10f, .30f), 1_100L,
            shapes(.30f, .10f), 1_150L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun captureBetweenPublishedFramesCannotUseCurrentOnlyToSwapIdentities() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.10f, .30f), 1_000L)
        for (at in listOf(1_050L, 1_100L)) {
            tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to shape(.10f + index * .20f)
            }, at)
        }
        val next = tracker.updateSourceWithAssignments(shapes(.10f, .30f), 1_090L,
            shapes(.30f, .10f), 1_150L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun betweenFrameCaptureKeepsSeparatedMovingIdsWithoutRewindingCurrentGeometry() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.10f, .40f), 1_000L)
        repeat(2) { step ->
            tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
                assignment.track.trackId to shape(.10f + index * .30f + (step + 1) * .04f)
            }, 1_050L + step * 50L)
        }
        val next = tracker.updateSourceWithAssignments(shapes(.172f, .472f), 1_090L,
            shapes(.22f, .52f), 1_150L)
        assertEquals(first.map { it.track.trackId }, next.map { it.track.trackId })
        assertTrue(next.all { it.track.lastSeenAtMs == 1_100L && it.track.ageFrames == 2 })
        assertEquals(.18f, next.first().track.latestGeometry!!.bboxNorm.x, .001f)
    }

    @Test fun pipelineBetweenFrameConflictResetsStableIdsWhileConsistentFlowKeepsThem() {
        fun key(at: Long) = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
        fun detections(vararg xs: Float) = xs.map { x ->
            DetectionCandidate("person", .95f, RectNorm(x, .35f, .13f, .30f))
        }
        val source = detections(.10f, .30f)
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(192, 192, 192, 192, 1f, 0f, 0f), ImageSize(192, 192))
        for (conflict in listOf(false, true)) {
            val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
            var previousIds = emptySet<String>()
            for ((sourceAt, targetAt) in listOf(1_000L to 1_020L, 1_040L to 1_050L,
                1_070L to 1_080L, 1_070L to 1_100L, 1_090L to 1_150L)) {
                val current = if (conflict && targetAt == 1_150L) detections(.30f, .10f) else source
                val result = VisualTrackingResult(key(sourceAt), key(targetAt), current.mapIndexed { index, geometry ->
                    VisualTrackingObservation(index, key(sourceAt), key(targetAt), VisualTrackingStatus.TRACKED,
                        geometry, .99f, null, Point2(geometry.bboxNorm.x - source[index].bboxNorm.x, 0f), 8, 8, 0f, false)
                }, VisualTrackingMetrics(0L, 1, 0L, false, 5, 0))
                val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(result, source, true, targetAt))
                val snapshot = DepthFrameSnapshot(key(targetAt).frameId,
                    DepthImage16(192, 192, IntArray(192 * 192) { 1_000 }),
                    ConfidenceImage8(192, 192, ByteArray(192 * 192) { 255.toByte() }), null,
                    rawDepthTimestampNs = key(targetAt).frameId, cameraImageTimestampNs = key(targetAt).frameId)
                val output = pipeline.processTrackedObservation(snapshot, observation, mapper, key(targetAt).frameId, targetAt)
                assertEquals(2, output.size)
                if (targetAt == 1_100L) {
                    previousIds = output.map { it.trackId }.toSet()
                    assertTrue(output.all { it.trackAgeFrames == 3 })
                } else if (targetAt == 1_150L && conflict) {
                    assertTrue(output.none { it.trackId in previousIds })
                    assertTrue(output.all { it.trackAgeFrames == 1 })
                } else if (targetAt == 1_150L) {
                    assertEquals(previousIds, output.map { it.trackId }.toSet())
                    assertTrue(output.all { it.trackAgeFrames == 4 })
                }
            }
        }
    }

    @Test fun currentOverlapKeepsTheTwoClearSourceIdentitiesAmbiguous() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.10f, .26f), 1_000L)
        tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
            assignment.track.trackId to shape(.10f + index * .16f)
        }, 1_100L)
        val next = tracker.updateSourceWithAssignments(shapes(.10f, .26f), 1_100L,
            shapes(.18f, .18f), 1_150L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 && !it.track.stable })
    }

    @Test fun realOcclusionClearsSourceTimeCorrespondenceBeforeRediscovery() {
        val tracker = ObjectTracker()
        val first = tracker.updateSourceWithAssignments(shapes(.05f, .35f), 1_000L)
        tracker.applyTrackedObservations(first.mapIndexed { index, assignment ->
            assignment.track.trackId to shape(.10f + index * .30f)
        }, 1_050L)
        tracker.applyTrackedObservations(emptyList(), 1_100L)
        val next = tracker.updateSourceWithAssignments(shapes(.10f, .40f), 1_050L,
            shapes(.15f, .45f), 1_150L)
        assertTrue(next.none { candidate -> first.any { it.track.trackId == candidate.track.trackId } })
        assertTrue(next.all { it.track.ageFrames == 1 })
    }

    private data class SceneResult(val ids: List<Set<String>>, val currentOutputs: Int,
        val stableOutputs: Int, val warningCandidates: Int)

    private fun movingScene(count: Int, intervalMs: Long): SceneResult {
        val pixels = ByteArray(192 * 192).also { Random(5729L).nextBytes(it) }
        fun shift(at: Long): Int {
            val phase = ((at - 1_000L) / 50L).toInt() % 16
            return (if (phase <= 8) phase else 16 - phase) * 7
        }
        fun key(at: Long) = VisualFrameKey(1, at * 1_000_000L, at * 1_000_000L, at, 1)
        val visual = InterFrameDetectionTracker(VisualTrackingConfig(
            backend = VisualTrackingBackend.PATCH_DIAGNOSTIC, maxFeaturesPerObject = 8), clockNanos = { 0L })
        val pipeline = ObjectDepthRuntimePipeline(rawDepthMotionOnly = true)
        val mapper = LetterboxCoordinateMapper(ModelInputTransform(192, 192, 192, 192, 1f, 0f, 0f), ImageSize(192, 192))
        val ids = List(count) { mutableSetOf<String>() }
        var current = 0
        var stable = 0
        var warnings = 0
        for (at in 1_000L..3_350L step 50L) {
            val shift = shift(at)
            val image = ByteArray(pixels.size) { pixel ->
                val x = pixel % 192
                if (x >= shift) pixels[pixel - shift] else 0
            }
            assertTrue(visual.offerFrame(GrayTrackingFrame.copyOf(key(at), 192, 192, image)).accepted)
            if (at == 1_000L) continue
            val sourceAt = 1_000L + (at - 1_050L) / intervalMs * intervalMs
            val detections = List(count) { index -> DetectionCandidate("person", .95f,
                RectNorm(.05f + index * .30f + shift(sourceAt) / 192f, .35f, .13f, .30f)) }
            val tracked = visual.trackFrom(key(sourceAt), detections, key(at))
            assertTrue(tracked.observations.all { it.status == VisualTrackingStatus.TRACKED })
            val observation = requireNotNull(CurrentTrackedFrameObservation.fromTrackingResult(tracked, detections, true, at))
            val snapshot = DepthFrameSnapshot(key(at).frameId,
                DepthImage16(192, 192, IntArray(192 * 192) { 1_000 }),
                ConfidenceImage8(192, 192, ByteArray(192 * 192) { 255.toByte() }), null,
                rawDepthTimestampNs = key(at).frameId, cameraImageTimestampNs = key(at).frameId)
            val output = pipeline.processTrackedObservation(snapshot, observation, mapper, key(at).frameId, at)
                .sortedBy { it.bboxNorm.x }
            assertEquals(count, output.size)
            output.forEachIndexed { index, item -> ids[index] += item.trackId }
            current += output.size
            stable += output.count { it.trackAgeFrames >= 3 }
            warnings += output.count { it.userFacing.message != null && it.trackAgeFrames >= 3 && it.trackStableMs >= 700L }
        }
        return SceneResult(ids, current, stable, warnings)
    }

    private fun shapes(vararg xs: Float) = xs.mapIndexed { index, x -> IndexedObjectGeometry(index, shape(x)) }
    private fun shape(x: Float) = MaskPolygonExtractor().extract(
        DetectionCandidate("person", .95f, RectNorm(x, .35f, .13f, .30f)))
}
